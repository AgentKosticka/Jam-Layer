package app.morphe.jam.companion;

import static org.junit.Assert.*;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import org.junit.Test;

public class SocketChannelTransportTest {

  @Test
  public void closeInterruptsBlockedWriter() throws Exception {
    CountDownLatch writing = new CountDownLatch(1),
      closed = new CountDownLatch(1);
    Socket socket = new Socket() {
      public void setTcpNoDelay(boolean enabled) {}

      public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[0]);
      }

      public OutputStream getOutputStream() {
        return new OutputStream() {
          public void write(int b) throws IOException {
            writing.countDown();
            try {
              if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException(
                "Writer not interrupted"
              );
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            throw new IOException("Closed");
          }
        };
      }

      public void close() {
        closed.countDown();
      }
    };
    SocketChannelTransport transport = new SocketChannelTransport(socket);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<?> writer = workers.submit(() -> {
        try {
          transport.write(new byte[] { 1 });
          fail("Closed write succeeded");
        } catch (IOException expected) {}
      });
      assertTrue(writing.await(1, TimeUnit.SECONDS));
      workers
        .submit(() -> {
          transport.close();
          return null;
        })
        .get(500, TimeUnit.MILLISECONDS);
      writer.get(1, TimeUnit.SECONDS);
    } finally {
      closed.countDown();
      workers.shutdownNow();
    }
  }
}
