package app.morphe.jam.companion;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("deprecation")
public final class ScanActivity
  extends Activity
  implements SurfaceHolder.Callback
{

  private Camera camera;
  private SurfaceView preview;
  private boolean resumed, done, focusing;
  private int cameraId;
  private String focusMode;
  private final MultiFormatReader reader = new MultiFormatReader();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    reader.setHints(
      Collections.singletonMap(
        DecodeHintType.POSSIBLE_FORMATS,
        Collections.singletonList(BarcodeFormat.QR_CODE)
      )
    );
    preview = new SurfaceView(this);
    preview.getHolder().addCallback(this);
    preview.setOnTouchListener((view, event) -> {
      if (event.getAction() == MotionEvent.ACTION_UP) {
        view.performClick();
        focus();
      }
      return true;
    });
    setContentView(preview);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    open();
  }

  public void surfaceCreated(SurfaceHolder holder) {
    open();
  }

  public void surfaceChanged(
    SurfaceHolder holder,
    int format,
    int width,
    int height
  ) {
    if (camera != null) orient();
  }

  public void surfaceDestroyed(SurfaceHolder holder) {
    release();
  }

  private void open() {
    if (
      !resumed ||
      done ||
      camera != null ||
      !preview.getHolder().getSurface().isValid()
    ) return;
    try {
      Camera.CameraInfo info = new Camera.CameraInfo();
      cameraId = 0;
      for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
        Camera.getCameraInfo(i, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
          cameraId = i;
          break;
        }
      }
      camera = Camera.open(cameraId);
      Camera.Parameters parameters = camera.getParameters();
      List<String> modes = parameters.getSupportedFocusModes();
      focusMode = parameters.getFocusMode();
      if (
        modes != null &&
        modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
      ) {
        focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
      } else if (
        modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)
      ) {
        focusMode = Camera.Parameters.FOCUS_MODE_AUTO;
      }
      if (focusMode != null) parameters.setFocusMode(focusMode);
      camera.setParameters(parameters);
      camera.setPreviewDisplay(preview.getHolder());
      orient();
      camera.setPreviewCallback(this::decode);
      camera.startPreview();
      focus();
    } catch (Exception error) {
      Log.e("JamScan", "Could not open camera", error);
      release();
      finish();
    }
  }

  private void orient() {
    Camera.CameraInfo info = new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);
    int degrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
    int orientation =
      info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        ? (360 - ((info.orientation + degrees) % 360)) % 360
        : (info.orientation - degrees + 360) % 360;
    try {
      camera.setDisplayOrientation(orientation);
    } catch (RuntimeException error) {
      Log.w("JamScan", "Could not rotate preview", error);
    }
  }

  private void focus() {
    if (
      camera == null ||
      done ||
      focusing ||
      !(
        Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode) ||
        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(focusMode)
      )
    ) return;
    try {
      focusing = true;
      camera.autoFocus((success, focused) -> {
        if (camera != focused) return;
        focusing = false;
        if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(focusMode)) {
          try {
            focused.cancelAutoFocus();
          } catch (RuntimeException error) {
            Log.d("JamScan", "Camera focus ended", error);
          }
        } else preview.postDelayed(this::focus, 1500);
      });
    } catch (RuntimeException error) {
      focusing = false;
      Log.d("JamScan", "Camera could not focus", error);
    }
  }

  private void decode(byte[] bytes, Camera active) {
    if (done || active != camera) return;
    Camera.Size size = active.getParameters().getPreviewSize();
    try {
      Result result = reader.decodeWithState(
        new BinaryBitmap(
          new HybridBinarizer(
            new PlanarYUVLuminanceSource(
              bytes,
              size.width,
              size.height,
              0,
              0,
              size.width,
              size.height,
              false
            )
          )
        )
      );
      new Invitation(result.getText());
      done = true;
      active.setPreviewCallback(null);
      setResult(RESULT_OK, new Intent().putExtra("invite", result.getText()));
      finish();
    } catch (
      ReaderException
      | IllegalArgumentException
      | NullPointerException expected
    ) {
      // Most preview frames contain no complete, valid Jam invitation.
    } finally {
      reader.reset();
    }
  }

  private void release() {
    Camera active = camera;
    camera = null;
    focusing = false;
    if (active == null) return;
    try {
      active.setPreviewCallback(null);
      active.stopPreview();
    } catch (RuntimeException error) {
      Log.d("JamScan", "Preview already stopped", error);
    } finally {
      active.release();
    }
  }

  @Override
  protected void onPause() {
    resumed = false;
    release();
    super.onPause();
  }
}
