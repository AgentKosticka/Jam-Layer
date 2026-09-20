package app.morphe.jam.companion;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import javax.net.SocketFactory;

public class LanConnectionTest {
    private static final class FakeSocket extends Socket {
        final boolean fail;
        boolean closed;
        SocketAddress target;
        FakeSocket(boolean fail) { this.fail=fail; }
        @Override public void connect(SocketAddress target, int timeout) throws IOException {
            this.target=target;
            if(fail) throw new IOException("Unreachable address");
        }
        @Override public void close() { closed=true; }
    }
    private static final class Factory extends SocketFactory {
        final ArrayDeque<FakeSocket> sockets = new ArrayDeque<>();
        Factory(FakeSocket... sockets) { this.sockets.addAll(Arrays.asList(sockets)); }
        @Override public Socket createSocket() { return sockets.remove(); }
        @Override public Socket createSocket(String h,int p) { throw new AssertionError(); }
        @Override public Socket createSocket(String h,int p,InetAddress a,int l) { throw new AssertionError(); }
        @Override public Socket createSocket(InetAddress h,int p) { throw new AssertionError(); }
        @Override public Socket createSocket(InetAddress h,int p,InetAddress a,int l) { throw new AssertionError(); }
    }
    @Test public void retriesOtherResolvedAddressesUsingSuppliedNetworkFactory() throws Exception {
        FakeSocket first=new FakeSocket(true), second=new FakeSocket(false);
        List<InetAddress> addresses=Arrays.asList(InetAddress.getByName("::1"),InetAddress.getByName("127.0.0.1"));
        assertSame(second,LanConnection.connect(addresses,12345,5000,new Factory(first,second)));
        assertTrue(first.closed);assertFalse(second.closed);
        assertEquals(new InetSocketAddress(addresses.get(1),12345),second.target);
    }
    @Test public void closesEveryFailedAttempt() throws Exception {
        FakeSocket first=new FakeSocket(true),second=new FakeSocket(true);
        try {
            LanConnection.connect(Arrays.asList(InetAddress.getLoopbackAddress(),InetAddress.getLoopbackAddress()),12345,100,new Factory(first,second));
            fail("Expected failure");
        } catch(IOException expected) { assertEquals(2,expected.getSuppressed().length); }
        assertTrue(first.closed);assertTrue(second.closed);
    }
    @Test public void emptyResolutionCannotConnectToLocalhostAccidentally() throws Exception {
        try { LanConnection.connect(Arrays.asList((InetAddress)null),12345,100,new Factory());fail("Expected failure"); }
        catch(IOException expected) { assertEquals(0,expected.getSuppressed().length); }
    }
}
