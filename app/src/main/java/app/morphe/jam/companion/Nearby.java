package app.morphe.jam.companion;

import android.content.*;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.aware.*;
import android.os.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Discovery never advertises the invitation secret. Both transports stay independent. */
public final class Nearby implements AutoCloseable {
    public interface Listener { void connect(Socket socket, String transport); void status(String message); }
    private static final class PendingMessage {
        final PeerHandle peer;
        final byte[] body;
        final int id;
        final int generation;
        int retries;
        PendingMessage(PeerHandle peer, byte[] body, int id, int generation) { this.peer=peer; this.body=body; this.id=id; this.generation=generation; }
    }
    private final Context context;
    private final Invitation invite;
    private final boolean host;
    private final int port;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final NsdManager nsd;
    private final ConnectivityManager connectivity;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private WifiAwareSession aware;
    private DiscoverySession session;
    private WifiAwareManager awareManager;
    private BroadcastReceiver awareState;
    private final Map<Integer, ConnectivityManager.NetworkCallback> paths = new HashMap<>();
    private final Map<Integer, Integer> pathRetries = new HashMap<>();
    private final Map<Integer, PendingMessage> messages = new HashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolves = new ArrayDeque<>();
    private final Runnable retryAware = this::attachAware;
    private boolean resolving;
    private boolean attaching;
    private int nextMessageId=1;
    private volatile int awareGeneration;
    private volatile boolean closed;
    private final String preference;
    public Nearby(Context context, Invitation invite, boolean host, int port, String preference, Listener listener) {
        this.context=context; this.invite=invite; this.host=host; this.port=port; this.listener=listener; this.preference=preference;
        nsd=context.getSystemService(NsdManager.class); connectivity=context.getSystemService(ConnectivityManager.class);
    }
    public void start() { handler.post(() -> { if(closed)return; if (!"Aware".equals(preference)) lan(); if (!"LAN".equals(preference)) aware(); }); }
    private void lan() {
        try {
            if (host) {
                NsdServiceInfo info=new NsdServiceInfo(); info.setServiceName("Jam-"+invite.jamId.substring(0,8));
                info.setServiceType("_morphejam._tcp."); info.setPort(port); info.setAttribute("jam", invite.jamId); info.setAttribute("v","1");
                registration=new NsdManager.RegistrationListener() {
                    public void onServiceRegistered(NsdServiceInfo i){ listener.status("LAN advertised"); }
                    public void onRegistrationFailed(NsdServiceInfo i,int e){ listener.status("LAN registration failed: "+e); }
                    public void onServiceUnregistered(NsdServiceInfo i){}
                    public void onUnregistrationFailed(NsdServiceInfo i,int e){}
                }; nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
            } else {
                discovery=new NsdManager.DiscoveryListener() {
                    public void onDiscoveryStarted(String t){}
                    public void onDiscoveryStopped(String t){}
                    public void onStartDiscoveryFailed(String t,int e){listener.status("LAN discovery failed: "+e);}
                    public void onStopDiscoveryFailed(String t,int e){}
                    public void onServiceLost(NsdServiceInfo i){}
                    public void onServiceFound(NsdServiceInfo i){handler.post(()->{if(!closed && resolves.size()<32){resolves.add(i);resolve();}});}
                }; nsd.discoverServices("_morphejam._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
            }
        } catch(Exception e){listener.status("LAN unavailable: "+e.getClass().getSimpleName());}
    }
    private void resolve() {
        if(closed||resolving||resolves.isEmpty())return;
        resolving=true;
        try{nsd.resolveService(resolves.remove(),new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo i,int e){handler.post(()->{resolving=false;resolve();});}
            public void onServiceResolved(NsdServiceInfo i){handler.post(()->{
                resolving=false;if(closed)return;resolve();
                byte[] id=i.getAttributes().get("jam");
                if(id!=null && invite.jamId.equals(new String(id,StandardCharsets.UTF_8))) {
                    Runnable connect=()->{if(closed)return;workers.execute(()->{Socket s=new Socket();try{s.connect(new InetSocketAddress(i.getHost(),i.getPort()),5000); if(closed)s.close();else listener.connect(s,"LAN");}catch(Exception e){try{s.close();}catch(Exception ignored){} listener.status("LAN connection failed");}});};
                    handler.postDelayed(connect,"Auto".equals(preference)?4000:0);
                }
            });}
        });}catch(Exception e){resolving=false;listener.status("LAN resolution unavailable");handler.post(this::resolve);}
    }
    private void aware() {
        if(Build.VERSION.SDK_INT<29){listener.status("Aware data paths require Android 10; LAN remains available");return;}
        try {
            WifiAwareManager manager=context.getSystemService(WifiAwareManager.class);
            if(manager==null){listener.status("Aware unavailable");return;}
            awareManager=manager;
            observeAwareState();
            attachAware();
        } catch(Exception e){listener.status("Aware unavailable: "+e.getClass().getSimpleName());}
    }
    private void observeAwareState() {
        if(awareState!=null)return;
        awareState=new BroadcastReceiver(){@Override public void onReceive(Context ignored,Intent intent){
            if(!WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED.equals(intent.getAction()))return;
            handler.post(()->{
                if(closed)return;
                closeAware();
                if(awareManager!=null&&awareManager.isAvailable())attachAware();
                else listener.status("Aware unavailable; waiting for Wi-Fi Aware");
            });
        }};
        try {
            IntentFilter filter=new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(awareState,filter,Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(awareState,filter);
        } catch(Exception e){awareState=null;listener.status("Aware state monitoring unavailable");}
    }
    private boolean activeAware(int generation) { return !closed&&awareGeneration==generation; }
    private void attachAware() {
        WifiAwareManager manager=awareManager;
        if(closed||attaching||aware!=null||manager==null)return;
        if(!manager.isAvailable()){listener.status("Aware unavailable; waiting for Wi-Fi Aware");scheduleAwareRetry();return;}
        int generation=awareGeneration;
        attaching=true;
        try {
            manager.attach(new AttachCallback(){
                public void onAttachFailed(){if(!activeAware(generation))return;attaching=false;listener.status("Aware attach failed");scheduleAwareRetry();}
                public void onAttached(WifiAwareSession awareSession){
                    attaching=false;
                    if(!activeAware(generation)){try{awareSession.close();}catch(Exception ignored){}return;} aware=awareSession;
                    DiscoverySessionCallback callback=new DiscoverySessionCallback(){
                        public void onPublishStarted(PublishDiscoverySession discoverySession){if(!activeAware(generation)){try{discoverySession.close();}catch(Exception ignored){}return;}session=discoverySession;listener.status("Aware published");}
                        public void onSubscribeStarted(SubscribeDiscoverySession discoverySession){if(!activeAware(generation)){try{discoverySession.close();}catch(Exception ignored){}return;}session=discoverySession;listener.status("Aware discovering");}
                        public void onSessionConfigFailed(){if(!activeAware(generation))return;listener.status("Aware discovery failed");handler.post(()->{if(activeAware(generation)){closeAware();scheduleAwareRetry();}});}
                        public void onSessionTerminated(){if(!activeAware(generation))return;listener.status("Aware discovery ended");handler.post(()->{if(activeAware(generation)){closeAware();scheduleAwareRetry();}});}
                        public void onServiceDiscovered(PeerHandle peer,byte[] info,List<byte[]> filter){
                            if(activeAware(generation) && session!=null && info!=null && !host && invite.jamId.equals(new String(info,StandardCharsets.UTF_8)))sendAwareMessage(peer,invite.jamId);
                        }
                        public void onMessageReceived(PeerHandle peer,byte[] message){
                            if(!activeAware(generation)||session==null)return;
                            String value=new String(message,StandardCharsets.UTF_8);
                            if(host && invite.jamId.equals(value)){if(path(peer,generation))sendAwareMessage(peer,"OK:"+invite.jamId);}
                            else if(!host && ("OK:"+invite.jamId).equals(value))path(peer,generation);
                        }
                        public void onMessageSendSucceeded(int messageId){if(activeAware(generation))messages.remove(messageId);}
                        public void onMessageSendFailed(int messageId){if(activeAware(generation))retryMessage(messageId);}
                    };
                    byte[] id=invite.jamId.getBytes(StandardCharsets.UTF_8);
                    try {
                        if(host)awareSession.publish(new PublishConfig.Builder().setServiceName("morphejam").setServiceSpecificInfo(id).build(),callback,handler);
                        else awareSession.subscribe(new SubscribeConfig.Builder().setServiceName("morphejam").build(),callback,handler);
                    } catch(Exception e){if(activeAware(generation)){listener.status("Aware discovery failed: "+e.getClass().getSimpleName());closeAware();scheduleAwareRetry();}}
                }
            },handler);
        } catch(Exception e){attaching=false;listener.status("Aware unavailable: "+e.getClass().getSimpleName());scheduleAwareRetry();}
    }
    private void scheduleAwareRetry() {
        handler.removeCallbacks(retryAware);
        if(!closed)handler.postDelayed(retryAware,3000);
    }
    private void sendAwareMessage(PeerHandle peer,String value) {
        int generation=awareGeneration;
        if(!activeAware(generation))return;
        int messageId=nextMessageId++;
        if(nextMessageId<1)nextMessageId=1;
        PendingMessage message=new PendingMessage(peer,value.getBytes(StandardCharsets.UTF_8),messageId,generation);
        messages.put(messageId,message);
        sendAwareMessage(message);
    }
    private void sendAwareMessage(PendingMessage message) {
        if(!activeAware(message.generation)||session==null){messages.remove(message.id);return;}
        try { session.sendMessage(message.peer,message.id,message.body); }
        catch(Exception e){retryMessage(message.id);}
    }
    private void retryMessage(int messageId) {
        PendingMessage message=messages.get(messageId);
        if(message==null||!activeAware(message.generation)){messages.remove(messageId);return;}
        if(message.retries++>=2){messages.remove(messageId);listener.status("Aware discovery message failed");return;}
        handler.postDelayed(()->sendAwareMessage(message),300);
    }
    private boolean path(PeerHandle peer) { return path(peer,awareGeneration); }
    private boolean path(PeerHandle peer,int generation) {
        if(!activeAware(generation)||session==null||paths.size()>=8)return false;
        if(paths.containsKey(peer.hashCode()))return true;
        try {
            String psk=SecureChannel.encode(SecureChannel.hmac(invite.secret,"aware-path-v1".getBytes(StandardCharsets.UTF_8)));
            WifiAwareNetworkSpecifier.Builder builder=new WifiAwareNetworkSpecifier.Builder(session,peer).setPskPassphrase(psk);
            if(host)builder.setPort(port).setTransportProtocol(6);
            ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
                boolean connecting;
                public void onAvailable(Network network){if(!activeAware(generation))return;pathRetries.remove(peer.hashCode());if(host)listener.status("Aware path ready");}
                public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
                    if(host||connecting||!activeAware(generation)||!(caps.getTransportInfo() instanceof WifiAwareNetworkInfo))return;
                    WifiAwareNetworkInfo info=(WifiAwareNetworkInfo)caps.getTransportInfo();
                    if(info.getPeerIpv6Addr()==null||info.getPort()==0)return; connecting=true;
                    workers.execute(()->connectAwareSocket(network,info,peer,generation,this));
                }
                public void onUnavailable(){releasePath(peer.hashCode(),this);if(activeAware(generation)){listener.status("Aware path unavailable");retryPath(peer,generation);}}
                public void onLost(Network n){releasePath(peer.hashCode(),this);if(activeAware(generation)){listener.status("Aware path lost");retryPath(peer,generation);}}
            };
            paths.put(peer.hashCode(),callback);
            connectivity.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(builder.build()).build(),callback,handler,15000);
            return true;
        }catch(Exception e){paths.remove(peer.hashCode());listener.status("Aware path failed: "+e.getClass().getSimpleName());retryPath(peer,generation);return false;}
    }
    private void releasePath(int pathId,ConnectivityManager.NetworkCallback callback) {
        if(paths.get(pathId)==callback)paths.remove(pathId);
        try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
    }
    private void retryPath(PeerHandle peer,int generation) {
        if(!activeAware(generation))return;
        int pathId=peer.hashCode();Integer previous=pathRetries.get(pathId);int attempt=previous==null?1:previous+1;
        if(attempt>3){pathRetries.remove(pathId);listener.status("Aware path retry exhausted");return;}
        pathRetries.put(pathId,attempt);
        handler.postDelayed(()->{
            if(!activeAware(generation)||session==null||!pathRetries.containsKey(pathId))return;
            if(host){if(path(peer,generation))sendAwareMessage(peer,"OK:"+invite.jamId);}
            else sendAwareMessage(peer,invite.jamId);
        },attempt*500L);
    }
    private void connectAwareSocket(Network network,WifiAwareNetworkInfo info,PeerHandle peer,int generation,ConnectivityManager.NetworkCallback callback) {
        for(int attempt=0;attempt<3&&activeAware(generation);attempt++){
            Socket socket=null;
            try {
                socket=network.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(info.getPeerIpv6Addr(),info.getPort()),5000);
                if(!activeAware(generation))socket.close();else {handler.post(()->{if(activeAware(generation))pathRetries.remove(peer.hashCode());});listener.connect(socket,"Aware");}
                return;
            } catch(Exception e){
                try{if(socket!=null)socket.close();}catch(Exception ignored){}
                if(attempt==2){handler.post(()->{if(activeAware(generation)){releasePath(peer.hashCode(),callback);listener.status("Aware socket failed");retryPath(peer,generation);}});return;}
                try{Thread.sleep(300);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}
            }
        }
    }
    private void closeAware() {
        awareGeneration++;
        handler.removeCallbacks(retryAware);
        attaching=false;
        messages.clear();
        pathRetries.clear();
        for(ConnectivityManager.NetworkCallback callback:paths.values())try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
        paths.clear();
        if(session!=null)try{session.close();}catch(Exception ignored){}session=null;
        if(aware!=null)try{aware.close();}catch(Exception ignored){}aware=null;
    }
    @Override public void close(){closed=true;handler.post(()->{
        if(awareState!=null)try{context.unregisterReceiver(awareState);}catch(Exception ignored){}awareState=null;
        closeAware();try{if(registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}
        try{if(discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}
        workers.shutdownNow();
    });}
}
