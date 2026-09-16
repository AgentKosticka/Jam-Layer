package app.morphe.jam.companion;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.hardware.Camera;
import android.view.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;private final AtomicBoolean done=new AtomicBoolean();
    @Override public void onCreate(Bundle b){super.onCreate(b);SurfaceView view=new SurfaceView(this);setContentView(view);view.getHolder().addCallback(this);}
    public void surfaceCreated(SurfaceHolder holder){try{camera=Camera.open();camera.setPreviewDisplay(holder);camera.setDisplayOrientation(90);camera.setPreviewCallback((bytes,c)->{
        if(done.get())return;Camera.Size size=c.getParameters().getPreviewSize();
        try{Result result=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(bytes,size.width,size.height,0,0,size.width,size.height,false))));new Invitation(result.getText());if(done.compareAndSet(false,true)){setResult(RESULT_OK,new Intent().putExtra("invite",result.getText()));finish();}}catch(Exception ignored){}
    });camera.startPreview();}catch(Exception e){finish();}}
    public void surfaceChanged(SurfaceHolder h,int format,int width,int height){}
    public void surfaceDestroyed(SurfaceHolder h){release();}
    private void release(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
    @Override protected void onPause(){release();super.onPause();}
}
