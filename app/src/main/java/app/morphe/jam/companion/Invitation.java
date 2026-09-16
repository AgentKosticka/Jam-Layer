package app.morphe.jam.companion;
import java.net.URI;
import java.util.*;

public final class Invitation {
    public final String jamId;
    public final byte[] secret;
    public final long expires;
    public Invitation() { jamId = UUID.randomUUID().toString(); secret = SecureChannel.random(32); expires = System.currentTimeMillis() + 12*60*60*1000L; }
    public Invitation(String value) {
        if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid invite");
        URI uri = URI.create(value.trim());
        if (!"morphejam".equals(uri.getScheme()) || !"join".equals(uri.getHost())) throw new IllegalArgumentException("Not a Jam invite");
        Map<String,String> p = new HashMap<>();
        for (String field : Objects.requireNonNull(uri.getRawQuery()).split("&")) {
            String[] pair = field.split("=",2);
            if (pair.length != 2 || p.put(pair[0], pair[1]) != null) throw new IllegalArgumentException("Invalid invite fields");
        }
        if (!"1".equals(p.get("v"))) throw new IllegalArgumentException("Unsupported protocol");
        jamId = UUID.fromString(p.get("jam")).toString(); secret = SecureChannel.decode(p.get("secret"));
        expires = Long.parseLong(p.get("exp"));
        if (secret.length != 32 || expires <= System.currentTimeMillis() || expires > System.currentTimeMillis()+13*60*60*1000L)
            throw new IllegalArgumentException("Expired or invalid invite");
    }
    public boolean valid() { return expires > System.currentTimeMillis(); }
    public String uri() { return "morphejam://join?v=1&jam="+jamId+"&secret="+SecureChannel.encode(secret)+"&exp="+expires; }
    public void destroy() { Arrays.fill(secret,(byte)0); }
}
