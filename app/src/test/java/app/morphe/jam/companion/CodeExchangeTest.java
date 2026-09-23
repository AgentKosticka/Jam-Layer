package app.morphe.jam.companion;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.util.concurrent.*;
public class CodeExchangeTest {
    @Test public void shortCodeReleasesInvitationOnlyAfterMutualProof()throws Exception{
        Invitation invite=new Invitation();String code=CodeExchange.generate();ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<?> host=worker.submit(()->{try(Socket socket=server.accept()){CodeExchange.give(socket,invite,code);}return null;});
            try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){assertEquals(invite.uri(),CodeExchange.take(socket,invite.jamId,code));}
            host.get(10,TimeUnit.SECONDS);
        }finally{worker.shutdownNow();invite.destroy();}
    }
    @Test public void wrongCodeCannotDecryptInvitation()throws Exception{
        Invitation invite=new Invitation();ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<Boolean> host=worker.submit(()->{try(Socket socket=server.accept()){try{CodeExchange.give(socket,invite,"ABCDEFGH");return false;}catch(Exception expected){return true;}}});
            try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){try{CodeExchange.take(socket,invite.jamId,"ABCDEFGJ");fail("Wrong code accepted");}catch(Exception expected){}}
            assertTrue(host.get(10,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();invite.destroy();}
    }
    @Test public void successfulPairingSocketCanBecomeJamChannel()throws Exception{
        Invitation invite=new Invitation();String code=CodeExchange.generate();ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<String> host=worker.submit(()->{try(Socket socket=server.accept()){
                CodeExchange.giveAndKeep(socket,invite,code);
                try(SecureChannel jam=new SecureChannel(socket,true,invite.jamId,invite.secret,null)){
                    assertEquals("joined",jam.receive());jam.send("connected");return jam.clientId;
                }
            }});
            String identity=java.util.UUID.randomUUID().toString();
            try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){
                assertEquals(invite.uri(),CodeExchange.takeAndKeep(socket,invite.jamId,code));
                try(SecureChannel jam=new SecureChannel(socket,false,invite.jamId,invite.secret,identity)){
                    jam.send("joined");assertEquals("connected",jam.receive());
                }
            }
            assertEquals(identity,host.get(10,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();invite.destroy();}
    }
    @Test public void codeAlphabetAndFormatting(){
        for(int i=0;i<100;i++)assertEquals(8,CodeExchange.normalize(CodeExchange.generate()).length());
        assertEquals("ABCDEFGH",CodeExchange.normalize("abcd-efgh"));
        for(String bad:new String[]{"123456","ABCDEFGI","ABCDEFG0","ABCDEFGHABCDEFGH"})try{CodeExchange.normalize(bad);fail("Invalid code accepted");}catch(IllegalArgumentException expected){}
    }
}
