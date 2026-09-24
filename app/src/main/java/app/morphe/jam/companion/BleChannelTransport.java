package app.morphe.jam.companion;

import android.bluetooth.BluetoothSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Length-framed records over an LE L2CAP CoC stream. SecureChannel authenticates the peer. */
final class BleChannelTransport implements ChannelTransport {
  private static final ScheduledExecutorService DEADLINES =
      Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jam-ble-deadlines");
        thread.setDaemon(true);
        return thread;
      });

  private final BluetoothSocket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final Object readLock = new Object();
  private final Object writeLock = new Object();
  private volatile boolean closed;
  private volatile int readTimeout = 15000;

  BleChannelTransport(BluetoothSocket socket) throws IOException {
    this.socket = socket;
    input = new DataInputStream(socket.getInputStream());
    output = new DataOutputStream(socket.getOutputStream());
  }

  /** connect() has no timeout argument; closing the socket aborts it. */
  static void connect(BluetoothSocket socket, int timeoutMillis) throws IOException {
    if (timeoutMillis <= 0) throw new IllegalArgumentException("Connect timeout");
    Deadline deadline = new Deadline(socket, timeoutMillis);
    try {
      socket.connect();
      if (deadline.expired()) throw new SocketTimeoutException("BLE connect timed out");
    } catch (IOException error) {
      if (deadline.expired()) throw new SocketTimeoutException("BLE connect timed out");
      throw error;
    } finally {
      deadline.cancel();
    }
  }

  @Override public byte[] read(int maxBytes) throws IOException {
    if (maxBytes < 1 || maxBytes > SecureChannel.LIMIT) throw new IOException("Frame limit");
    synchronized (readLock) {
      if (closed) throw new IOException("BLE closed");
      Deadline deadline = new Deadline(socket, readTimeout);
      try {
        int size = input.readInt();
        if (size < 1 || size > maxBytes) throw new IOException("Frame size");
        byte[] record = new byte[size];
        input.readFully(record);
        if (deadline.expired()) throw new SocketTimeoutException("BLE read timed out");
        return record;
      } catch (IOException error) {
        if (deadline.expired()) throw new SocketTimeoutException("BLE read timed out");
        throw error;
      } finally {
        deadline.cancel();
      }
    }
  }

  @Override public void write(byte[] record) throws IOException {
    if (record == null || record.length < 1 || record.length > SecureChannel.LIMIT)
      throw new IOException("Frame size");
    synchronized (writeLock) {
      if (closed) throw new IOException("BLE closed");
      Deadline deadline = new Deadline(socket, 15000);
      try {
        output.writeInt(record.length);
        output.write(record);
        output.flush();
        if (deadline.expired()) throw new SocketTimeoutException("BLE write timed out");
      } catch (IOException error) {
        if (deadline.expired()) throw new SocketTimeoutException("BLE write timed out");
        throw error;
      } finally {
        deadline.cancel();
      }
    }
  }

  @Override public void setReadTimeout(int millis) throws IOException {
    if (millis <= 0) throw new IOException("BLE read timeout must be positive");
    readTimeout = millis;
  }

  @Override public boolean isClosed() { return closed || !socket.isConnected(); }

  @Override public void close() throws IOException {
    closed = true;
    socket.close();
  }

  private static final class Deadline {
    private final ScheduledFuture<?> alarm;
    private volatile boolean expired;
    private boolean finished;
    Deadline(BluetoothSocket socket, int millis) {
      alarm = DEADLINES.schedule(() -> {
        synchronized (this) {
          if (finished) return;
          expired = true;
          try { socket.close(); } catch (IOException ignored) {}
        }
      }, millis, TimeUnit.MILLISECONDS);
    }
    boolean expired() { return expired; }
    synchronized void cancel() { finished = true; alarm.cancel(false); }
  }
}
