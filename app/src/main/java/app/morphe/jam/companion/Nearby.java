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
    private final Map<Integer, ConnectivityManager.NetworkCallback> paths = new HashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolves = new ArrayDeque<>();
    private boolean resolving;
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
            if(manager==null||!manager.isAvailable()){listener.status("Aware unavailable");return;}
            manager.attach(new AttachCallback(){
                public void onAttachFailed(){listener.status("Aware attach failed");}
                public void onAttached(WifiAwareSession s){
                    if(closed){s.close();return;} aware=s;
                    DiscoverySessionCallback callback=new DiscoverySessionCallback(){
                        public void onPublishStarted(PublishDiscoverySession s){if(closed){s.close();return;}session=s;listener.status("Aware published");}
                        public void onSubscribeStarted(SubscribeDiscoverySession s){if(closed){s.close();return;}session=s;listener.status("Aware discovering");}
                        public void onSessionConfigFailed(){listener.status("Aware discovery failed");}
                        public void onServiceDiscovered(PeerHandle peer,byte[] info,List<byte[]> filter){
                            if(!closed && session!=null && info!=null && !host && invite.jamId.equals(new String(info,StandardCharsets.UTF_8)))session.sendMessage(peer,1,invite.jamId.getBytes(StandardCharsets.UTF_8));
                        }
                        public void onMessageReceived(PeerHandle peer,byte[] message){
                            if(closed||session==null)return;
                            String value=new String(message,StandardCharsets.UTF_8);
                            if(host && invite.jamId.equals(value)){path(peer);session.sendMessage(peer,2,("OK:"+invite.jamId).getBytes(StandardCharsets.UTF_8));}
                            else if(!host && ("OK:"+invite.jamId).equals(value))path(peer);
                        }
                    };
                    byte[] id=invite.jamId.getBytes(StandardCharsets.UTF_8);
                    if(host)s.publish(new PublishConfig.Builder().setServiceName("morphejam").setServiceSpecificInfo(id).build(),callback,handler);
                    else s.subscribe(new SubscribeConfig.Builder().setServiceName("morphejam").build(),callback,handler);
                }
            },handler);
        } catch(Exception e){listener.status("Aware unavailable: "+e.getClass().getSimpleName());}
    }
    private void path(PeerHandle peer) {
        if(closed||paths.size()>=8||paths.containsKey(peer.hashCode()))return;
        try {
            String psk=SecureChannel.encode(SecureChannel.hmac(invite.secret,"aware-path-v1".getBytes(StandardCharsets.UTF_8)));
            WifiAwareNetworkSpecifier.Builder builder=new WifiAwareNetworkSpecifier.Builder(session,peer).setPskPassphrase(psk);
            if(host)builder.setPort(port);
            ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
                boolean connected;
                public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
                    if(host||connected||closed||!(caps.getTransportInfo() instanceof WifiAwareNetworkInfo))return;
                    WifiAwareNetworkInfo info=(WifiAwareNetworkInfo)caps.getTransportInfo();
                    if(info.getPeerIpv6Addr()==null||info.getPort()==0)return; connected=true;
                    workers.execute(()->{Socket socket=null;try{socket=network.getSocketFactory().createSocket();socket.connect(new InetSocketAddress(info.getPeerIpv6Addr(),info.getPort()),5000);if(closed)socket.close();else listener.connect(socket,"Aware");}catch(Exception e){try{if(socket!=null)socket.close();}catch(Exception ignored){}listener.status("Aware socket failed");}});
                }
                public void onUnavailable(){paths.remove(peer.hashCode());listener.status("Aware path unavailable");}
                public void onLost(Network n){paths.remove(peer.hashCode());try{connectivity.unregisterNetworkCallback(this);}catch(Exception ignored){}listener.status("Aware path lost");}
            };
            paths.put(peer.hashCode(),callback);
            connectivity.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(builder.build()).build(),callback,handler,15000);
        }catch(Exception e){listener.status("Aware path failed: "+e.getClass().getSimpleName());}
    }
    @Override public void close(){closed=true;handler.post(()->{
        if(session!=null)session.close();if(aware!=null)aware.close();
        for(ConnectivityManager.NetworkCallback callback:paths.values())try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
        paths.clear();try{if(registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}
        try{if(discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}
        workers.shutdownNow();
    });}
}
