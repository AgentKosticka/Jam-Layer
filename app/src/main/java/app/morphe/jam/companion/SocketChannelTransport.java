package app.morphe.jam.companion;

import java.io.*;
import java.net.Socket;

/** Keeps length framing at the TCP boundary so other transports share SecureChannel. */
final class SocketChannelTransport implements ChannelTransport {

  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;

  SocketChannelTransport(Socket socket) throws IOException {
    this.socket = socket;
    socket.setTcpNoDelay(true);
    input = new DataInputStream(socket.getInputStream());
    output = new DataOutputStream(socket.getOutputStream());
  }

  @Override
  public byte[] read(int maxBytes) throws IOException {
    int size = input.readInt();
    if (size < 1 || size > maxBytes) throw new IOException("Frame size");
    byte[] value = new byte[size];
    input.readFully(value);
    return value;
  }

  @Override
  public synchronized void write(byte[] record) throws IOException {
    if (record == null || record.length < 1 || record.length > SecureChannel.LIMIT)
      throw new IOException("Frame size");
    output.writeInt(record.length);
    output.write(record);
    output.flush();
  }

  @Override
  public void setReadTimeout(int millis) throws IOException {
    socket.setSoTimeout(millis);
  }

  @Override
  public boolean isClosed() {
    return socket.isClosed();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
