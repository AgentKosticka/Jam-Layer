package app.morphe.jam.companion;

import android.content.*;
import android.net.*;
import android.net.wifi.aware.*;
import android.os.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class AwareCodePairing implements AutoCloseable {
    private static final String SERVICE="morphepair",REQUEST="PAIR:",READY="READY:";
    private static final class Candidate {
        final PeerHandle peer;
        final String jam;
        Candidate(PeerHandle peer,String jam){this.peer=peer;this.jam=jam;}
    }
    private static final class PendingMessage {
        final PeerHandle peer;
        final byte[] body;
        final int id;
        final int generation;
        int retries;
        PendingMessage(PeerHandle peer,byte[] body,int id,int generation){this.peer=peer;this.body=body;this.id=id;this.generation=generation;}
    }
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Map<Integer,Candidate> candidates=new HashMap<>();
    private final Map<Integer,ConnectivityManager.NetworkCallback> paths=new HashMap<>();
    private final Map<Integer,Integer> retries=new HashMap<>();
    private final Map<Integer,PendingMessage> messages=new HashMap<>();
    private final WifiAwareManager manager;
    private final Runnable retryAware=this::attach;
    private final boolean host;
    private final String code,tag,jam;
    private final int port;
    private final CompletableFuture<String> result;
    private WifiAwareSession aware;
    private DiscoverySession session;
    private BroadcastReceiver awareState;
    private boolean attaching;
    private volatile boolean closed;
    private int generation;
    private int nextMessageId=1;
    private AwareCodePairing(Context context,Invitation invite,String code,int port,CompletableFuture<String> result){
        this.context=context.getApplicationContext();host=invite!=null;this.code=CodeExchange.normalize(code);tag=tag(this.code);jam=host?invite.jamId:null;this.port=port;this.result=result;manager=this.context.getSystemService(WifiAwareManager.class);
    }
    static AwareCodePairing host(Context context,Invitation invite,String code,int port){AwareCodePairing pairing=new AwareCodePairing(context,invite,code,port,null);pairing.start();return pairing;}
    static AwareCodePairing find(Context context,String code,CompletableFuture<String> result){AwareCodePairing pairing=new AwareCodePairing(context,null,code,0,result);pairing.start();return pairing;}
    private static String tag(String code){try{return SecureChannel.encode(MessageDigest.getInstance("SHA-256").digest(("morphejam-pair/1/"+code).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void start(){handler.post(()->{observeAwareState();attach();});}
    private boolean active(int expected){return !closed&&generation==expected;}
    private void attach(){
        if(closed||attaching||aware!=null||Build.VERSION.SDK_INT<29||manager==null)return;
        if(!manager.isAvailable()){retryAttach();return;}
        int expected=generation;attaching=true;
        try{manager.attach(new AttachCallback(){
            @Override public void onAttachFailed(){if(!active(expected))return;attaching=false;retryAttach();}
            @Override public void onAttached(WifiAwareSession next){
                attaching=false;if(!active(expected)){try{next.close();}catch(Exception ignored){}return;}aware=next;
                DiscoverySessionCallback callback=new DiscoverySessionCallback(){
                    @Override public void onPublishStarted(PublishDiscoverySession discovery){if(!active(expected)){try{discovery.close();}catch(Exception ignored){}return;}session=discovery;android.util.Log.i("MorpheJam","Aware code publisher ready");}
                    @Override public void onSubscribeStarted(SubscribeDiscoverySession discovery){if(!active(expected)){try{discovery.close();}catch(Exception ignored){}return;}session=discovery;android.util.Log.i("MorpheJam","Aware code discovery ready");}
                    @Override public void onSessionConfigFailed(){restart(expected);}
                    @Override public void onSessionTerminated(){restart(expected);}
                    @Override public void onServiceDiscovered(PeerHandle peer,byte[] info,List<byte[]> filter){
                        if(host||!active(expected)||session==null)return;String found=parseJam(info,tag);if(found==null)return;
                        candidates.put(peer.hashCode(),new Candidate(peer,found));send(peer,REQUEST+tag);android.util.Log.i("MorpheJam","Aware code host discovered");
                    }
                    @Override public void onMessageReceived(PeerHandle peer,byte[] message){
                        if(!active(expected)||session==null)return;String value=new String(message,StandardCharsets.UTF_8);
                        if(host){if((REQUEST+tag).equals(value)&&path(peer,null,0,expected))send(peer,READY+jam+":"+port);return;}
                        Candidate candidate=candidates.get(peer.hashCode());int remotePort=readyPort(value,candidate);if(candidate!=null&&remotePort>0)path(peer,candidate,remotePort,expected);
                    }
                    @Override public void onMessageSendSucceeded(int messageId){if(active(expected))messages.remove(messageId);}
                    @Override public void onMessageSendFailed(int messageId){if(active(expected))retryMessage(messageId);}
                };
                try{
                    if(host)next.publish(new PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo((tag+":"+jam).getBytes(StandardCharsets.UTF_8)).build(),callback,handler);
                    else next.subscribe(new SubscribeConfig.Builder().setServiceName(SERVICE).build(),callback,handler);
                }catch(Exception e){restart(expected);}
            }
        },handler);}catch(Exception e){attaching=false;retryAttach();}
    }
    private void observeAwareState(){
        if(awareState!=null)return;
        awareState=new BroadcastReceiver(){@Override public void onReceive(Context ignored,Intent intent){
            if(!WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(intent.getAction()))return;
            handler.post(()->{if(closed)return;closeResources();if(manager!=null&&manager.isAvailable())attach();else retryAttach();});
        }};
        try{
            IntentFilter filter=new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(awareState,filter,Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(awareState,filter);
        }catch(Exception ignored){awareState=null;}
    }
    private void retryAttach(){if(!closed){handler.removeCallbacks(retryAware);handler.postDelayed(retryAware,1500);}}
    private void restart(int expected){if(!active(expected))return;closeResources();retryAttach();}
    private static String parseJam(byte[] info,String tag){
        if(info==null)return null;String value=new String(info,StandardCharsets.UTF_8),prefix=tag+":";
        if(!value.startsWith(prefix))return null;String found=value.substring(prefix.length());try{return UUID.fromString(found).toString().equals(found)?found:null;}catch(Exception e){return null;}
    }
    private static int readyPort(String value,Candidate candidate){
        if(candidate==null||!value.startsWith(READY+candidate.jam+":"))return 0;
        try{int valuePort=Integer.parseInt(value.substring((READY+candidate.jam+":").length()));return valuePort>0&&valuePort<=65535?valuePort:0;}catch(Exception e){return 0;}
    }
    private void send(PeerHandle peer,String value){
        int expected=generation;if(!active(expected))return;
        int id=nextMessageId++;if(nextMessageId<1)nextMessageId=1;
        PendingMessage message=new PendingMessage(peer,value.getBytes(StandardCharsets.UTF_8),id,expected);
        messages.put(id,message);send(message);
    }
    private void send(PendingMessage message){
        if(!active(message.generation)||session==null){messages.remove(message.id);return;}
        try{session.sendMessage(message.peer,message.id,message.body);}catch(Exception e){retryMessage(message.id);}
    }
    private void retryMessage(int messageId){
        PendingMessage message=messages.get(messageId);
        if(message==null||!active(message.generation)){messages.remove(messageId);return;}
        if(message.retries++>=2){messages.remove(messageId);return;}
        handler.postDelayed(()->send(message),300);
    }
    private boolean path(PeerHandle peer,Candidate candidate,int remotePort,int expected){
        if(!active(expected)||session==null||paths.size()>=2)return false;int id=peer.hashCode();if(paths.containsKey(id))return true;
        try{
            ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);if(connectivity==null)return false;
            WifiAwareNetworkSpecifier.Builder builder=new WifiAwareNetworkSpecifier.Builder(session,peer);
            if(host)builder.setPort(port).setTransportProtocol(6);
            WifiAwareNetworkSpecifier specifier=builder.build();
            ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
                boolean connecting;
                @Override public void onAvailable(Network network){
                    if(host&&active(expected))retries.remove(peer.hashCode());
                }
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){
                    if(host||connecting||!active(expected)||candidate==null||!(capabilities.getTransportInfo() instanceof WifiAwareNetworkInfo))return;
                    WifiAwareNetworkInfo info=(WifiAwareNetworkInfo)capabilities.getTransportInfo();if(info.getPeerIpv6Addr()==null||remotePort==0)return;
                    connecting=true;retries.remove(peer.hashCode());workers.execute(()->take(network,info,candidate,remotePort,expected,this));
                }
                @Override public void onUnavailable(){lost(peer,candidate,expected,this);}
                @Override public void onLost(Network network){lost(peer,candidate,expected,this);}
            };
            paths.put(id,callback);
            try{
                connectivity.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build(),callback,handler,15000);
                handler.postDelayed(()->releasePath(id,callback),30000);
                return true;
            }
            catch(Exception e){if(paths.get(id)==callback)paths.remove(id);try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}retry(peer,candidate,expected);return false;}
        }catch(Exception e){retry(peer,candidate,expected);return false;}
    }
    private void take(Network network,WifiAwareNetworkInfo info,Candidate candidate,int remotePort,int expected,ConnectivityManager.NetworkCallback callback){
        Socket socket=null;
        try{socket=network.getSocketFactory().createSocket();socket.connect(new InetSocketAddress(info.getPeerIpv6Addr(),remotePort),5000);String invite=CodeExchange.take(socket,candidate.jam,code);socket=null;if(result!=null)result.complete(invite);}
        catch(Exception ignored){try{if(socket!=null)socket.close();}catch(Exception ignoredAgain){}handler.post(()->lost(candidate.peer,candidate,expected,callback));}
    }
    private void lost(PeerHandle peer,Candidate candidate,int expected,ConnectivityManager.NetworkCallback callback){
        releasePath(peer.hashCode(),callback);retry(peer,candidate,expected);
    }
    private void releasePath(int id,ConnectivityManager.NetworkCallback callback){
        if(paths.get(id)!=callback)return;paths.remove(id);
        try{context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);}catch(Exception ignored){}
    }
    private void retry(PeerHandle peer,Candidate candidate,int expected){
        if(!active(expected))return;int id=peer.hashCode(),attempt=retries.getOrDefault(id,0)+1;if(attempt>3)return;retries.put(id,attempt);
        handler.postDelayed(()->{if(!active(expected))return;if(host){if(path(peer,null,0,expected))send(peer,READY+jam+":"+port);}else if(candidate!=null)send(peer,REQUEST+tag);},attempt*500L);
    }
    private void closeResources(){
        generation++;attaching=false;handler.removeCallbacks(retryAware);candidates.clear();retries.clear();messages.clear();
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);for(ConnectivityManager.NetworkCallback callback:paths.values())try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}paths.clear();
        if(session!=null)try{session.close();}catch(Exception ignored){}session=null;
        if(aware!=null)try{aware.close();}catch(Exception ignored){}aware=null;
    }
    @Override public void close(){closed=true;handler.post(()->{if(awareState!=null)try{context.unregisterReceiver(awareState);}catch(Exception ignored){}awareState=null;closeResources();workers.shutdownNow();});}
}
