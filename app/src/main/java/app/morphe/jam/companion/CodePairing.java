package app.morphe.jam.companion;

import android.content.Context;
import android.net.nsd.*;
import android.os.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** LAN and Wi-Fi Aware bootstrap. Successful PAKE delivers the full invitation. */
public final class CodePairing implements AutoCloseable {
    private static final String TYPE="_morphepair._tcp.";
    private final NsdManager nsd;
    private final ServerSocket server;
    private final Invitation invite;
    private final String code=CodeExchange.generate();
    private final long expires=System.currentTimeMillis()+10*60*1000L;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private NsdManager.RegistrationListener registration;
    private AwareCodePairing aware;
    private volatile boolean closed;
    public CodePairing(Context context,Invitation invitation)throws Exception{
        invite=invitation;nsd=context.getSystemService(NsdManager.class);server=new ServerSocket(0);
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("Jam-pair-"+invite.jamId.substring(0,8));info.setServiceType(TYPE);info.setPort(server.getLocalPort());info.setAttribute("jam",invite.jamId);info.setAttribute("v","1");
        aware=AwareCodePairing.host(context,invite,code,server.getLocalPort());
        registration=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo i){}public void onRegistrationFailed(NsdServiceInfo i,int e){android.util.Log.w("MorpheJam","LAN code advertising failed: "+e);}public void onServiceUnregistered(NsdServiceInfo i){}public void onUnregistrationFailed(NsdServiceInfo i,int e){}};
        try{if(nsd!=null)nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);}catch(Exception e){android.util.Log.w("MorpheJam","LAN code advertising unavailable",e);}
        workers.execute(()->{int attempts=0,inWindow=0;long window=System.currentTimeMillis();
            while(!closed){try{Socket socket=server.accept();long now=System.currentTimeMillis();if(now-window>60000){window=now;inWindow=0;}
                if(!valid()||attempts>=32||inWindow>=8||sockets.size()>=2){socket.close();continue;}
                attempts++;inWindow++;sockets.add(socket);
                workers.execute(()->{try(Socket connection=socket){CodeExchange.give(connection,invite,code);}catch(Exception ignored){}finally{sockets.remove(socket);}});
            }catch(Exception e){if(!closed)close();}}
        });
    }
    public boolean valid(){return !closed&&invite.valid()&&System.currentTimeMillis()<expires;}
    public String display(){return code.substring(0,4)+"-"+code.substring(4);}
    public long expires(){return expires;}
    @Override public void close(){closed=true;if(aware!=null)aware.close();aware=null;try{server.close();}catch(Exception ignored){}for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}sockets.clear();try{if(registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}registration=null;workers.shutdownNow();}

    public static String find(Context context,String entered)throws Exception{
        String code=CodeExchange.normalize(entered);NsdManager nsd=context.getSystemService(NsdManager.class);
        Handler handler=new Handler(Looper.getMainLooper());ExecutorService worker=Executors.newFixedThreadPool(2);
        CompletableFuture<String> result=new CompletableFuture<>();AwareCodePairing aware=AwareCodePairing.find(context,code,result);Set<String> seen=ConcurrentHashMap.newKeySet();Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        ArrayDeque<NsdServiceInfo> queue=new ArrayDeque<>();boolean[] resolving={false};Runnable[] resolve={null};
        resolve[0]=()->{if(result.isDone()||resolving[0]||queue.isEmpty())return;resolving[0]=true;
            try{nsd.resolveService(queue.remove(),new NsdManager.ResolveListener(){
                public void onResolveFailed(NsdServiceInfo i,int e){handler.post(()->{resolving[0]=false;resolve[0].run();});}
                public void onServiceResolved(NsdServiceInfo i){handler.post(()->{resolving[0]=false;resolve[0].run();if(result.isDone())return;
                    byte[] id=i.getAttributes().get("jam");if(id==null)return;String jam=new String(id,StandardCharsets.UTF_8);
                    try{if(!UUID.fromString(jam).toString().equals(jam))return;}catch(Exception e){return;}
                    worker.execute(()->{if(result.isDone())return;try(Socket socket=LanConnection.connect(i,4000)){sockets.add(socket);String invitation=CodeExchange.take(socket,jam,code);result.complete(invitation);}catch(Exception ignored){}finally{sockets.removeIf(Socket::isClosed);}});
                });}
            });}catch(Exception e){resolving[0]=false;handler.postDelayed(resolve[0],500);}
        };
        NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}public void onServiceLost(NsdServiceInfo i){}
            public void onStartDiscoveryFailed(String t,int e){android.util.Log.w("MorpheJam","LAN code discovery failed: "+e);}
            public void onStopDiscoveryFailed(String t,int e){}
            public void onServiceFound(NsdServiceInfo i){handler.post(()->{if(!result.isDone()&&seen.size()<8&&seen.add(i.getServiceName())){queue.add(i);resolve[0].run();}});}
        };
        try{if(nsd!=null)nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}catch(Exception e){android.util.Log.w("MorpheJam","LAN code discovery unavailable",e);}
        try{return result.get(30,TimeUnit.SECONDS);}
        catch(TimeoutException e){throw new IllegalArgumentException("Code not found or expired. Keep both devices nearby, or scan the host QR invitation.");}
        finally{result.cancel(false);aware.close();try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}worker.shutdownNow();}
    }
}
