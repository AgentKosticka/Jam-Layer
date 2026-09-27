package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.security.*;
import org.junit.Test;

public class TransportFailureTest {

  @Test
  public void classifiesRootAndSuppressedFailures() {
    assertEquals(
      TransportFailure.CONNECT_TIMEOUT,
      TransportFailure.classify(new SocketTimeoutException())
    );
    assertEquals(
      TransportFailure.AUTH_FAILED,
      TransportFailure.classify(new GeneralSecurityException())
    );
    assertEquals(
      TransportFailure.HOST_UNREACHABLE,
      TransportFailure.classify(new NoRouteToHostException())
    );
    IOException composite = new IOException("Budget exhausted");
    composite.addSuppressed(new ConnectException());
    assertEquals(
      TransportFailure.TCP_REFUSED,
      TransportFailure.classify(composite)
    );
  }
}
