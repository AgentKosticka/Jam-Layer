package app.morphe.jam.companion;

import java.io.Closeable;
import java.io.IOException;

/** A bounded, ordered record transport for the authenticated Jam protocol. */
interface ChannelTransport extends Closeable {

  byte[] read(int maxBytes) throws IOException;

  void write(byte[] record) throws IOException;

  void setReadTimeout(int millis) throws IOException;

  boolean isClosed();
}
