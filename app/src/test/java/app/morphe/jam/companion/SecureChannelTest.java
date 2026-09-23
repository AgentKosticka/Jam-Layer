package app.morphe.jam.companion;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class SecureChannelTest {
    private static final class MemoryTransport implements ChannelTransport {
        private static final byte[] CLOSED=new byte[0];
        final BlockingQueue<byte[]> received=new ArrayBlockingQueue<>(8); MemoryTransport peer;
        volatile boolean closed; volatile int timeout=5000;
        static MemoryTransport[] pair(){MemoryTransport a=new MemoryTransport(),b=new MemoryTransport();a.peer=b;b.peer=a;return new MemoryTransport[]{a,b};}
        public byte[] read(int max)throws IOException{try{byte[] value=received.poll(timeout,TimeUnit.MILLISECONDS);if(value==null)throw new SocketTimeoutException();if(value==CLOSED||closed)throw new EOFException();if(value.length>max)throw new IOException("Frame size");return value;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        public void write(byte[] record)throws IOException{if(closed||peer.closed||!peer.received.offer(record.clone()))throw new IOException("Closed");}
        public void setReadTimeout(int millis){timeout=millis;}
        public boolean isClosed(){return closed;}
        public void close(){closed=true;received.offer(CLOSED);}
    }
    private static final class CapturingSocket extends Socket {
        final java.io.ByteArrayOutputStream captured=new java.io.ByteArrayOutputStream();
        final java.io.OutputStream raw;
        boolean block;
        CapturingSocket(int port)throws Exception{super("127.0.0.1",port);raw=super.getOutputStream();}
        @Override public java.io.OutputStream getOutputStream(){return new java.io.OutputStream(){
            public void write(int value)throws java.io.IOException{captured.write(value);if(!block)raw.write(value);}
            public void write(byte[] b,int off,int len)throws java.io.IOException{captured.write(b,off,len);if(!block)raw.write(b,off,len);}
            public void flush()throws java.io.IOException{raw.flush();}
        };}
    }
    @Test public void replayedRecordRejected()throws Exception{attack(false);}
    @Test public void modifiedCiphertextRejected()throws Exception{attack(true);}
    private void attack(boolean tamper)throws Exception{
        Invitation invite=new Invitation();ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<Boolean> rejected=executor.submit(()->{try(SecureChannel host=new SecureChannel(server.accept(),true,invite.jamId,invite.secret,null)){
                if(!tamper)assertEquals("one",host.receive());
                try{host.receive();return false;}catch(java.security.GeneralSecurityException expected){return true;}
            }});
            try(CapturingSocket socket=new CapturingSocket(server.getLocalPort());SecureChannel client=new SecureChannel(socket,false,invite.jamId,invite.secret,UUID.randomUUID().toString())){
                socket.captured.reset();socket.block=tamper;client.send("one");byte[] frame=socket.captured.toByteArray();if(tamper)frame[frame.length-1]^=1;socket.raw.write(frame);socket.raw.flush();
                assertTrue(rejected.get(5,TimeUnit.SECONDS));
            }
        }finally{executor.shutdownNow();}
    }
    @Test public void encryptedRoundTripAndIndependentSessions()throws Exception{
        Invitation invite=new Invitation();
        try(ServerSocket server=new ServerSocket(0)){
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<String> received=executor.submit(()->{try(SecureChannel host=new SecureChannel(server.accept(),true,invite.jamId,invite.secret,null)){
                    String request=host.receive();host.send("reply:"+request);assertEquals("second",host.receive());return host.clientId;
                }});
                String id=UUID.randomUUID().toString();
                try(SecureChannel client=new SecureChannel(new Socket("127.0.0.1",server.getLocalPort()),false,invite.jamId,invite.secret,id)){
                    client.send("queue.add");assertEquals("reply:queue.add",client.receive());client.send("second");
                }
                assertEquals(id,received.get(5,TimeUnit.SECONDS));
            }finally{executor.shutdownNow();}
        }
    }
    @Test public void encryptedRoundTripOverRecordTransport()throws Exception{
        Invitation invite=new Invitation();MemoryTransport[] pair=MemoryTransport.pair();ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<String> host=executor.submit(()->{try(SecureChannel secure=new SecureChannel(pair[0],true,invite.jamId,invite.secret,null)){assertEquals("nearby",secure.receive());secure.send("connected");return secure.clientId;}});
            String id=UUID.randomUUID().toString();
            try(SecureChannel client=new SecureChannel(pair[1],false,invite.jamId,invite.secret,id)){client.send("nearby");assertEquals("connected",client.receive());}
            assertEquals(id,host.get(5,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
    }
    @Test public void wrongSecretIsRejected()throws Exception{
        Invitation invite=new Invitation();
        try(ServerSocket server=new ServerSocket(0)){
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<Boolean> rejected=executor.submit(()->{try(Socket socket=server.accept()){try{new SecureChannel(socket,true,invite.jamId,invite.secret,null);return false;}catch(java.security.GeneralSecurityException e){return true;}}});
                try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){
                    try{new SecureChannel(socket,false,invite.jamId,SecureChannel.random(32),UUID.randomUUID().toString());fail("Accepted wrong key");}catch(java.io.IOException|java.security.GeneralSecurityException expected){}
                }
                assertTrue(rejected.get(5,TimeUnit.SECONDS));
            }finally{executor.shutdownNow();}
        }
    }
    @Test public void invitationsValidateAndEraseSecrets(){
        Invitation original=new Invitation(),copy=new Invitation(original.uri());
        assertEquals(original.jamId,copy.jamId);assertArrayEquals(original.secret,copy.secret);
        for(String invalid:new String[]{"https://example.com","morphejam://join?v=2",original.uri()+"&v=1",original.uri().replace("secret=","secret=x")}){
            try{new Invitation(invalid);fail("Accepted invalid invitation");}catch(RuntimeException expected){}
        }
        original.destroy();assertArrayEquals(new byte[32],original.secret);
    }
    @Test public void frameSizeBounded()throws Exception{
        Invitation invite=new Invitation();
        try(ServerSocket server=new ServerSocket(0)){
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<?> future=executor.submit(()->{try(SecureChannel host=new SecureChannel(server.accept(),true,invite.jamId,invite.secret,null)){assertEquals("safe",host.receive());}return null;});
                try(SecureChannel client=new SecureChannel(new Socket("127.0.0.1",server.getLocalPort()),false,invite.jamId,invite.secret,UUID.randomUUID().toString())){
                    try{client.send("x".repeat(SecureChannel.LIMIT));fail("Oversize accepted");}catch(java.io.IOException expected){}
                    client.send("safe");
                }future.get(5,TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
        }
    }
}
