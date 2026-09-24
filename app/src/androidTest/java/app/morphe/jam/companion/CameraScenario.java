/*
 * Copyright 2026 AgentKosticka.
 * https://github.com/AgentKosticka/Jam-Layer
 */

package app.morphe.jam.companion;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Exercises the real camera without requiring a second device to display a QR. */
@SuppressWarnings("deprecation")
final class CameraScenario {

  static void run(Instrumentation runner) throws Exception {
    ScanActivity activity = (ScanActivity) runner.startActivitySync(
      new Intent(runner.getTargetContext(), ScanActivity.class).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
      )
    );
    Field cameraField = ScanActivity.class.getDeclaredField("camera");
    cameraField.setAccessible(true);
    Field doneField = ScanActivity.class.getDeclaredField("done");
    doneField.setAccessible(true);
    try {
      Camera camera = waitForCamera(runner, activity, cameraField);
      Camera.Parameters parameters = camera.getParameters();
      if (
        parameters
          .getSupportedFocusModes()
          .contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) &&
        !Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(
          parameters.getFocusMode()
        )
      ) {
        throw new AssertionError("Continuous focus not selected");
      }
      runner.runOnMainSync(() ->
        activity.setRequestedOrientation(
          ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        )
      );
      Thread.sleep(1200);
      runner.runOnMainSync(() ->
        activity.setRequestedOrientation(
          ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        )
      );
      Thread.sleep(1200);
      runner.runOnMainSync(() -> runner.callActivityOnPause(activity));
      if (cameraField.get(activity) != null) throw new AssertionError(
        "Pause retained camera"
      );
      runner.runOnMainSync(() -> runner.callActivityOnResume(activity));
      camera = waitForCamera(runner, activity, cameraField);
      Camera.Size size = camera.getParameters().getPreviewSize();
      int edge = Math.min(size.width, size.height);
      String invite = new Invitation().uri();
      BitMatrix qr = new QRCodeWriter().encode(
        invite,
        BarcodeFormat.QR_CODE,
        edge,
        edge
      );
      byte[] frame = new byte[(size.width * size.height * 3) / 2];
      Arrays.fill(frame, (byte) 255);
      Arrays.fill(frame, size.width * size.height, frame.length, (byte) 128);
      int left = (size.width - edge) / 2,
        top = (size.height - edge) / 2;
      for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) {
        frame[(top + y) * size.width + left + x] = qr.get(x, y)
          ? 0
          : (byte) 255;
      }
      Method decode = ScanActivity.class.getDeclaredMethod(
        "decode",
        byte[].class,
        Camera.class
      );
      decode.setAccessible(true);
      Camera active = camera;
      runner.runOnMainSync(() -> {
        try {
          decode.invoke(activity, frame, active);
        } catch (Exception error) {
          throw new RuntimeException(error);
        }
      });
      if (!doneField.getBoolean(activity) || !activity.isFinishing()) {
        throw new AssertionError("Valid QR did not finish scanner");
      }
      Thread.sleep(500);
      if (cameraField.get(activity) != null) throw new AssertionError(
        "Camera not released"
      );
    } finally {
      runner.runOnMainSync(activity::finish);
    }
  }

  private static Camera waitForCamera(
    Instrumentation runner,
    ScanActivity activity,
    Field field
  ) throws Exception {
    for (int i = 0; i < 50; i++) {
      runner.waitForIdleSync();
      Camera camera = (Camera) field.get(activity);
      if (camera != null) return camera;
      Thread.sleep(100);
    }
    throw new AssertionError("Camera preview did not start");
  }
}
