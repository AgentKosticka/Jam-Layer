package app.morphe.jam.companion;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.widget.*;
import app.morphe.jam.ipc.Trust;
import java.util.*;

/** Permission and camera broker. Session and queue controls live inside YouTube Music. */
public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean scanning;
    private boolean finishAfterPermissions;
    private final Runnable refresh=new Runnable(){public void run(){
        if(status!=null){JamService s=JamService.active;
            if(s==null)status.setText("Ready to connect\nStart or join a Jam from YouTube Music's player.");
            else{org.json.JSONObject state=s.state();status.setText(state.optString("role")+" · "+state.optString("transport")+"\n"+state.optString("message"));}}
        handler.postDelayed(this,1500);
    }};
    @Override public void onCreate(Bundle b){super.onCreate(b);
        if("app.morphe.jam.PAIR".equals(getIntent().getAction())){pair();return;}
        if("app.morphe.jam.SCAN".equals(getIntent().getAction())){
            android.content.SharedPreferences p=getSharedPreferences("pair",0);
            String caller=getCallingPackage();
            try{if(caller==null||!caller.equals(p.getString("package","")))throw new SecurityException();
                Trust.capability(p.getString("cap",null),getIntent().getStringExtra("cap"));scanning=true;
            }catch(Exception e){finish();return;}
            if(checkSelfPermission("android.permission.CAMERA")!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.CAMERA"},3);else scan();return;
        }
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(40,40,40,24);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});scroll.requestApplyInsets();
        TextView title=new TextView(this);title.setText("Jam Layer");title.setTextSize(28);body.addView(title);
        TextView description=new TextView(this);description.setText("Nearby connections for YouTube Music.\n\nYour queue, invitations and session controls are in the YouTube Music player. This layer keeps the devices connected.");description.setTextSize(16);description.setPadding(0,24,0,32);body.addView(description);
        status=new TextView(this);body.addView(status);
        button(body,"Open YouTube Music",()->{String pkg=getSharedPreferences("pair",0).getString("package","app.morphe.jam.next.music");Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);if(launch!=null)startActivity(launch);else Toast.makeText(this,"Install the Jam-enabled YouTube Music build",Toast.LENGTH_LONG).show();});
        button(body,"Nearby permissions",this::permissions);
        button(body,"Disconnect",()->{if(JamService.active!=null)JamService.active.end();});
        permissions();handler.post(refresh);
    }
    private void pair(){
        String pkg=getCallingPackage(),cap=getIntent().getStringExtra("cap");
        if(!validPackage(pkg)||cap==null||cap.length()!=64){finish();return;}
        new AlertDialog.Builder(this).setTitle("Connect YouTube Music?").setMessage("Allow "+pkg+" to use Jam Layer for nearby queue sharing. Its signing certificate is not checked.")
            .setNegativeButton("Cancel",(d,w)->finish()).setPositiveButton("Connect",(d,w)->{
                JamService service=JamService.active;
                if(service!=null)service.end();
                getSharedPreferences("pair",0).edit().putString("package",pkg).remove("cert").putString("cap",cap).commit();
                if(service!=null)service.rebindMusic();
                try{startForegroundService(JamService.startIntent(this));}catch(Exception e){Toast.makeText(this,"Could not start Jam Layer",Toast.LENGTH_LONG).show();finish();return;}
                setResult(RESULT_OK);
                finishAfterPermissions=true;if(!permissions())finish();
            }).setOnCancelListener(d->finish()).show();
    }
    private boolean permissions(){
        ArrayList<String> permissions=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33){permissions.add("android.permission.NEARBY_WIFI_DEVICES");permissions.add("android.permission.POST_NOTIFICATIONS");}else permissions.add("android.permission.ACCESS_FINE_LOCATION");
        if(Build.VERSION.SDK_INT>=37)permissions.add("android.permission.ACCESS_LOCAL_NETWORK");
        permissions.removeIf(p->checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED);if(!permissions.isEmpty()){requestPermissions(permissions.toArray(new String[0]),2);return true;}return false;
    }
    private static boolean validPackage(String value){return value!=null&&value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");}
    private void scan(){startActivityForResult(new Intent(this,ScanActivity.class),4);}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] grants){super.onRequestPermissionsResult(code,p,grants);if(code==2&&finishAfterPermissions){finish();return;}if(code==3){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)scan();else finish();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(scanning&&request==4){
        if(result==RESULT_OK&&data!=null){String invite=data.getStringExtra("invite");try{new Invitation(invite);startForegroundService(JamService.startIntent(this));joinWhenReady(invite,20);}catch(Exception e){Toast.makeText(this,"Could not join this Jam",Toast.LENGTH_LONG).show();finish();}}
        else finish();
    }}
    private void joinWhenReady(String invite,int attempts){JamService s=JamService.active;if(s!=null){s.join(invite,"Auto");setResult(RESULT_OK);finish();}else if(attempts>0)handler.postDelayed(()->joinWhenReady(invite,attempts-1),100);else finish();}
    private void button(LinearLayout body,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setOnClickListener(v->action.run());body.addView(b);}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
