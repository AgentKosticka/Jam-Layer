package app.morphe.jam.companion;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** PSK-authenticated, versioned, directional AES-GCM records over either IP transport. */
public final class SecureChannel implements Closeable {
    public static final int LIMIT = 1048576;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] txKey, rxKey;
    private final int txDirection, rxDirection;
    private long sent, received;
    public final String clientId;

    public static byte[] random(int size) { byte[] b = new byte[size]; RANDOM.nextBytes(b); return b; }
    public static String encode(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] decode(String s) { return Base64.getUrlDecoder().decode(s); }
    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data);
    }
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.write(a, 0, a.length);
        return out.toByteArray();
    }
    private static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] read(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > max) throw new IOException("Frame size");
        byte[] data = new byte[size]; in.readFully(data); return data;
    }
    private static void write(DataOutputStream out, byte[] data) throws IOException {
        if (data.length > LIMIT) throw new IOException("Frame size");
        out.writeInt(data.length); out.write(data); out.flush();
    }
    public SecureChannel(Socket socket, boolean host, String jamId, byte[] secret, String identity)
            throws IOException, GeneralSecurityException {
        this.socket = socket;
        socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
        input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream());
        if (secret.length != 32) throw new GeneralSecurityException("Secret length");
        byte[] hostNonce, clientNonce;
        if (host) {
            hostNonce = random(32);
            write(output, concat(utf("MORPHEJAM/1:" + jamId + ":"), hostNonce));
            byte[] hello = read(input, 128);
            if (hello.length != 100) throw new GeneralSecurityException("Client hello");
            clientNonce = Arrays.copyOfRange(hello, 0, 32);
            clientId = new String(hello, 32, 36, StandardCharsets.US_ASCII);
            if (!UUID.fromString(clientId).toString().equals(clientId)) throw new GeneralSecurityException("Identity");
            byte[] transcript = concat(utf("MORPHEJAM/1:" + jamId + ":" + clientId), hostNonce, clientNonce);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(hello, 68, 100), hmac(secret, concat(utf("client"), transcript))))
                throw new GeneralSecurityException("Authentication failed");
            write(output, hmac(secret, concat(utf("host"), transcript)));
        } else {
            clientId = UUID.fromString(identity).toString();
            byte[] challenge = read(input, 128);
            byte[] prefix = utf("MORPHEJAM/1:" + jamId + ":");
            if (challenge.length != prefix.length + 32 || !Arrays.equals(prefix, Arrays.copyOf(challenge, prefix.length)))
                throw new GeneralSecurityException("Protocol or session mismatch");
            hostNonce = Arrays.copyOfRange(challenge, prefix.length, challenge.length);
            clientNonce = random(32);
            byte[] transcript = concat(utf("MORPHEJAM/1:" + jamId + ":" + clientId), hostNonce, clientNonce);
            write(output, concat(clientNonce, utf(clientId), hmac(secret, concat(utf("client"), transcript))));
            if (!MessageDigest.isEqual(read(input, 32), hmac(secret, concat(utf("host"), transcript))))
                throw new GeneralSecurityException("Host authentication failed");
        }
        // HKDF extract then single-block expand with separate direction labels.
        byte[] prk = hmac(concat(hostNonce, clientNonce), secret);
        byte[] clientKey = hmac(prk, concat(utf("morphejam-v1-client:" + jamId + clientId), new byte[]{1}));
        byte[] hostKey = hmac(prk, concat(utf("morphejam-v1-host:" + jamId + clientId), new byte[]{1}));
        txKey = host ? hostKey : clientKey; rxKey = host ? clientKey : hostKey;
        txDirection = host ? 2 : 1; rxDirection = host ? 1 : 2;
        Arrays.fill(prk, (byte)0);
        // Native insertion includes a server callback; allow it to finish before
        // treating a connection as lost. Handshake stays bounded to 15 seconds.
        socket.setSoTimeout(45000);
    }
    private static byte[] crypt(int mode, byte[] key, int direction, long sequence, byte[] data)
            throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(concat(utf("morphejam/1"), nonce));
        return cipher.doFinal(data);
    }
    public void send(String json) throws IOException, GeneralSecurityException {
        byte[] plain = utf(json);
        if (plain.length > LIMIT - 24 || sent == Long.MAX_VALUE) throw new IOException("Frame limit");
        long seq = ++sent;
        write(output, concat(ByteBuffer.allocate(8).putLong(seq).array(), crypt(Cipher.ENCRYPT_MODE, txKey, txDirection, seq, plain)));
    }
    public String receive() throws IOException, GeneralSecurityException {
        byte[] frame = read(input, LIMIT);
        if (frame.length < 24) throw new IOException("Truncated frame");
        long seq = ByteBuffer.wrap(frame).getLong();
        if (seq != received + 1 || seq <= 0) throw new GeneralSecurityException("Replay or sequence gap");
        byte[] plain = crypt(Cipher.DECRYPT_MODE, rxKey, rxDirection, seq, Arrays.copyOfRange(frame, 8, frame.length));
        received = seq; return new String(plain, StandardCharsets.UTF_8);
    }
    @Override public void close() throws IOException {
        Arrays.fill(txKey, (byte)0); Arrays.fill(rxKey, (byte)0); socket.close();
    }
}
