package app.morphe.jam.companion;

import org.bouncycastle.crypto.agreement.jpake.*;
import org.json.*;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Short-code authentication uses BC J-PAKE, never a low-entropy PSK handshake. */
public final class CodeExchange {
    private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static String generate(){byte[] random=SecureChannel.random(8);StringBuilder s=new StringBuilder();for(byte value:random)s.append(ALPHABET.charAt((value&255)%32));return s.toString();}
    public static String normalize(String code){String value=code==null?"":code.replace("-","").replace(" ","").toUpperCase(Locale.ROOT);if(!value.matches("[A-HJ-NP-Z2-9]{8}"))throw new IllegalArgumentException("Enter the eight-character Jam code");return value;}
    private static JSONObject read(DataInputStream in)throws Exception{int n=in.readInt();if(n<2||n>16384)throw new IOException("Invalid pairing frame");byte[] data=new byte[n];in.readFully(data);return new JSONObject(new String(data,StandardCharsets.UTF_8));}
    private static void send(DataOutputStream out,JSONObject object)throws Exception{byte[] data=object.toString().getBytes(StandardCharsets.UTF_8);if(data.length>16384)throw new IOException("Pairing frame too large");out.writeInt(data.length);out.write(data);out.flush();}
    private static JSONObject read(ChannelTransport in)throws Exception{return new JSONObject(new String(in.read(16384),StandardCharsets.UTF_8));}
    private static void send(ChannelTransport out,JSONObject object)throws Exception{byte[] data=object.toString().getBytes(StandardCharsets.UTF_8);if(data.length>16384)throw new IOException("Pairing frame too large");out.write(data);}
    private static BigInteger number(String value){if(!value.matches("-?[0-9a-f]{1,768}"))throw new IllegalArgumentException("Invalid proof");return new BigInteger(value,16);}
    private static JSONArray proof(BigInteger[] values){JSONArray a=new JSONArray();for(BigInteger value:values)a.put(value.toString(16));return a;}
    private static BigInteger[] proof(JSONArray a)throws Exception{if(a.length()!=2)throw new IllegalArgumentException("Invalid proof length");return new BigInteger[]{number(a.getString(0)),number(a.getString(1))};}
    public static byte[] agree(Socket socket,boolean host,String jam,String code)throws Exception{
        return agree(new SocketChannelTransport(socket),host,jam,code);
    }
    static byte[] agree(ChannelTransport connection,boolean host,String jam,String code)throws Exception{
        connection.setReadTimeout(12000);
        ChannelTransport in=connection,out=connection;
        String prefix="morphejam-pair/1/"+jam+"/",identity=prefix+(host?"host":"client/"+UUID.randomUUID());
        JPAKEParticipant participant=new JPAKEParticipant(identity,normalize(code).toCharArray());
        JPAKERound1Payload r1=participant.createRound1PayloadToSend();
        send(out,new JSONObject().put("v",1).put("id",identity).put("x1",r1.getGx1().toString(16)).put("x2",r1.getGx2().toString(16)).put("p1",proof(r1.getKnowledgeProofForX1())).put("p2",proof(r1.getKnowledgeProofForX2())));
        JSONObject peer=read(in);String peerId=peer.getString("id");
        if(peer.getInt("v")!=1||!(host?peerId.matches(java.util.regex.Pattern.quote(prefix+"client/")+"[a-f0-9-]{36}"):peerId.equals(prefix+"host")))throw new IOException("Wrong pairing session");
        participant.validateRound1PayloadReceived(new JPAKERound1Payload(peerId,number(peer.getString("x1")),number(peer.getString("x2")),proof(peer.getJSONArray("p1")),proof(peer.getJSONArray("p2"))));
        JPAKERound2Payload r2=participant.createRound2PayloadToSend();send(out,new JSONObject().put("id",identity).put("a",r2.getA().toString(16)).put("p",proof(r2.getKnowledgeProofForX2s())));
        peer=read(in);participant.validateRound2PayloadReceived(new JPAKERound2Payload(peer.getString("id"),number(peer.getString("a")),proof(peer.getJSONArray("p"))));
        BigInteger key=participant.calculateKeyingMaterial();JPAKERound3Payload r3=participant.createRound3PayloadToSend(key);
        send(out,new JSONObject().put("id",identity).put("mac",r3.getMacTag().toString(16)));
        peer=read(in);participant.validateRound3PayloadReceived(new JPAKERound3Payload(peer.getString("id"),number(peer.getString("mac"))),key);
        byte[] prk=SecureChannel.hmac((prefix+"extract").getBytes(StandardCharsets.UTF_8),key.toByteArray());
        try{return SecureChannel.hmac(prk,(prefix+"invitation-key\u0001").getBytes(StandardCharsets.UTF_8));}finally{Arrays.fill(prk,(byte)0);}
    }
    public static void give(Socket socket,Invitation invite,String code)throws Exception{
        try{giveAndKeep(socket,invite,code);}finally{socket.close();}
    }
    /** Delivers the invitation but leaves the authenticated socket ready for Jam. */
    static void giveAndKeep(Socket socket,Invitation invite,String code)throws Exception{
        giveAndKeep(new SocketChannelTransport(socket),invite,code);
    }
    static void giveAndKeep(ChannelTransport socket,Invitation invite,String code)throws Exception{
        byte[] key=agree(socket,true,invite.jamId,code);
        try{
            SecureChannel secure=new SecureChannel(socket,true,invite.jamId,key,null);
            try{secure.send(new JSONObject().put("invite",invite.uri()).toString());}finally{secure.discardKeys();}
        }finally{Arrays.fill(key,(byte)0);}
    }
    public static String take(Socket socket,String jam,String code)throws Exception{
        try{return takeAndKeep(socket,jam,code);}finally{socket.close();}
    }
    /** Receives the invitation but leaves the authenticated socket ready for Jam. */
    static String takeAndKeep(Socket socket,String jam,String code)throws Exception{
        return takeAndKeep(new SocketChannelTransport(socket),jam,code);
    }
    static String takeAndKeep(ChannelTransport socket,String jam,String code)throws Exception{
        byte[] key=agree(socket,false,jam,code);
        try{
            SecureChannel secure=new SecureChannel(socket,false,jam,key,UUID.randomUUID().toString());
            try{
                String value=new JSONObject(secure.receive()).getString("invite");Invitation invitation=new Invitation(value);try{if(!invitation.jamId.equals(jam))throw new IOException("Invitation session changed");return value;}finally{invitation.destroy();}
            }finally{secure.discardKeys();}
        }finally{Arrays.fill(key,(byte)0);}
    }
}
