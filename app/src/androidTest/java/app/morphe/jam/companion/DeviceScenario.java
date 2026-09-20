package app.morphe.jam.companion;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import app.morphe.jam.ipc.BridgeProtocol;
import app.morphe.jam.ipc.IJamBridge;

/** Installed only in the separate test APK. No diagnostic entry point in shipped app. */
public final class DeviceScenario extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments){args=arguments;start();}
    private void note(String s){Bundle b=new Bundle();b.putString("stream",s+"\n");sendStatus(0,b);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private JSONObject request(JamService s,JSONObject r)throws Exception{
        JSONObject result=s.dispatch(r);note(r.optString("op")+" ok="+result.optBoolean("ok")+" items="+(result.optJSONArray("items")==null?"-":result.getJSONArray("items").length())+" error="+result.optString("error"));
        return result;
    }
    private static JSONObject command(String op)throws Exception{return new JSONObject().put("op",op).put("id",UUID.randomUUID().toString());}
    private static String read(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static void write(Path path,String value)throws Exception{Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Context context=getTargetContext();context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1200);context.startForegroundService(JamService.startIntent(context));
            for(int i=0;i<50&&JamService.active==null;i++)Thread.sleep(100);
            JamService s=JamService.active;check(s!=null,"Service unavailable");String mode=args.getString("transport","LAN");
            if("bridge".equals(args.getString("role"))){
                java.lang.reflect.Field field=JamService.class.getDeclaredField("bridge");field.setAccessible(true);
                for(int i=0;i<50&&field.get(s)==null;i++)Thread.sleep(100);
                IJamBridge bridge=(IJamBridge)field.get(s);check(bridge!=null,"Bridge not bound");
                String cap=context.getSharedPreferences("pair",0).getString("cap","");
                JSONObject hello=BridgeProtocol.advertise(new JSONObject().put("op","HELLO"));
                JSONObject response=BridgeProtocol.validate(new JSONObject(bridge.call(cap,hello.toString())));
                check(response.optBoolean("ok")&&response.has("bridgeProtocol"),"Capability negotiation failed");
                hello.getJSONObject("bridgeProtocol").put("version",2).put("minimumVersion",2);
                check(!new JSONObject(bridge.call(cap,hello.toString())).optBoolean("ok"),"Unsupported version accepted");
                boolean denied=false;
                try{bridge.call("invalid-capability",new JSONObject().put("op","HELLO").toString());}
                catch(SecurityException expected){denied=true;}
                check(denied,"Invalid capability accepted");
                note("PASS bridge: version negotiation, incompatible version rejection, capability rejection");
                finish(Activity.RESULT_OK,result);return;
            }
            if("host".equals(args.getString("role"))){
                s.host(mode);for(int i=0;i<50&&s.invite().isEmpty();i++)Thread.sleep(100);
                check(!s.invite().isEmpty(),"Host not ready");
                write(context.getFilesDir().toPath().resolve("test-invite.txt"),s.invite());
                JSONObject invitation=s.dispatch(new JSONObject().put("op","INVITE"));
                 write(context.getFilesDir().toPath().resolve("test-code.txt"),invitation.getString("code"));
                 // Runner captures these privately to exercise the signed, non-debuggable release APK.
                 result.putString("invite",s.invite());result.putString("code",invitation.getString("code"));
                 note("HOST_READY "+mode);
                finish(Activity.RESULT_OK,result);return;
            }else{
                if("code".equals(args.getString("join"))){String code=args.containsKey("code")?args.getString("code"):read(context.getFilesDir().toPath().resolve("test-code.txt"));check(s.dispatch(new JSONObject().put("op","JOIN").put("invite",code).put("transport",mode)).optBoolean("ok"),"Code join failed");}
                else {String invite=args.containsKey("invite")?args.getString("invite"):read(context.getFilesDir().toPath().resolve("test-invite.txt"));s.join(invite,mode);}
                for(int i=0;i<300&&!Arrays.asList("LAN","Aware").contains(s.state().optString("transport"));i++)Thread.sleep(100);
                note("STATE "+s.state());check(mode.equals(s.state().optString("transport"))||("Auto".equals(mode)&&Arrays.asList("LAN","Aware").contains(s.state().optString("transport"))),"Requested transport not connected");
                if("recovery".equals(args.getString("role"))){
                    JSONObject before=request(s,new JSONObject().put("op","SNAPSHOT"));check(before.optBoolean("ok"),"Initial snapshot failed");
                    // Fault injection is confined to this separate instrumentation APK.
                    java.lang.reflect.Field field=JamService.class.getDeclaredField("channel");field.setAccessible(true);
                    SecureChannel previous=(SecureChannel)field.get(s);previous.close();
                    for(int i=0;i<450;i++){Thread.sleep(100);Object replacement=field.get(s);if(replacement!=null&&replacement!=previous)break;}
                    check(field.get(s)!=null&&field.get(s)!=previous,"Connection did not recover");
                    JSONObject after=request(s,new JSONObject().put("op","SNAPSHOT"));check(after.optBoolean("ok"),"Snapshot after reconnect failed");
                    check(before.getJSONArray("items").toString().equals(after.getJSONArray("items").toString()),"Reconnect changed queue");
                    note("PASS recovery: authenticated reconnection, unchanged native queue");finish(Activity.RESULT_OK,result);return;
                }
                if("interactive".equals(args.getString("role"))){
                    context.startActivity(new Intent().setComponent(new ComponentName("app.morphe.jam.probe.music","com.google.android.apps.youtube.music.activities.MusicActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    note("INTERACTIVE_READY");
                    for(int i=0;i<1800;i++){
                        Path input=context.getFilesDir().toPath().resolve("test-command.json");
                        if(Files.exists(input)){String body=read(input);Files.delete(input);JSONObject response=s.dispatch(new JSONObject(body));write(context.getFilesDir().toPath().resolve("test-response.json"),response.toString());}
                        JSONObject view=s.dispatch(new JSONObject().put("op","VIEW"));write(context.getFilesDir().toPath().resolve("test-view.json"),view.toString());Thread.sleep(2000);}
                    finish(Activity.RESULT_OK,result);return;
                }
                JSONObject initial=request(s,new JSONObject().put("op","SNAPSHOT"));check(initial.optBoolean("ok"),initial.toString());
                JSONArray items=initial.getJSONArray("items");check(items.length()>2,"Need at least three queue items");
                int a=items.length()-2,b=items.length()-1;
                JSONObject move=command("MOVE").put("revision",initial.getString("revision")).put("item",items.getJSONObject(b).getString("id")).put("target",items.getJSONObject(a).getString("id"));
                JSONObject moved=request(s,move);check(moved.optBoolean("ok"),moved.toString());
                check(moved.getJSONArray("items").getJSONObject(a).getString("id").equals(items.getJSONObject(b).getString("id")),"Move did not converge");
                check(request(s,move).toString().equals(moved.toString()),"Duplicate changed result");
                JSONObject stale=request(s,command("REMOVE").put("revision",initial.getString("revision")).put("item",items.getJSONObject(a).getString("id")));
                check(!stale.optBoolean("ok")&&stale.optString("error").contains("changed"),"Stale revision accepted");
                JSONObject restored=request(s,command("MOVE").put("revision",moved.getString("revision")).put("item",items.getJSONObject(b).getString("id")).put("target",items.getJSONObject(a).getString("id")));
                check(restored.optBoolean("ok"),"Restore move failed");
                Set<String> old=new HashSet<>();for(int i=0;i<items.length();i++)old.add(items.getJSONObject(i).getString("id"));
                JSONObject add=command("ADD").put("videoId","9bZkp7q19f0");JSONObject added=request(s,add);check(added.optBoolean("ok")&&added.optBoolean("confirmed"),"Add not confirmed");
                check(request(s,add).toString().equals(added.toString()),"Duplicate add changed result");
                JSONArray after=added.getJSONArray("items");String inserted=null;
                for(int i=0;i<after.length();i++)if(!old.contains(after.getJSONObject(i).getString("id"))&&"9bZkp7q19f0".equals(after.getJSONObject(i).optString("videoId")))inserted=after.getJSONObject(i).getString("id");
                check(inserted!=null,"Added item missing");
                JSONObject removed=request(s,command("REMOVE").put("revision",added.getString("revision")).put("item",inserted));
                check(removed.optBoolean("ok"),"Remove failed");JSONArray end=removed.getJSONArray("items");
                for(int i=0;i<end.length();i++)check(!inserted.equals(end.getJSONObject(i).getString("id")),"Removed item remains");
                check(end.length()==items.length(),"Queue size changed unexpectedly");
                note("PASS "+mode+": snapshot, move, stale rejection, duplicate move/add, confirmed add, remove");
            }
            result.putString("stream","PASS\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream","FAIL "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}
    }
}
