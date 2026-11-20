package org.client.scrcpy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
 import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.Executor;

/**
 * User-enabled accessibility screenshot capability.
 * Not a system accessibility tool for disability assistance; retain Android's
 * privacy reminder behavior. Never initiates streaming or peer connections.
 */
public final class AccessibilityShotService extends AccessibilityService {
    public interface Callback {
        void success(Bitmap softwareBitmap);
        void failure(int reason);
    }
    private static volatile AccessibilityShotService connected;
    public static boolean ready() {
        return Build.VERSION.SDK_INT >= 30 && connected != null;
    }

    public static void capture(Executor callbackExecutor, Callback callback) {
        AccessibilityShotService service = connected;
        if (service == null || Build.VERSION.SDK_INT < 30) {
            if (service != null) ScreenSyncDiagnostics.record(service,
                    "CAPTURE_DENIED", "android_api_below_30");
            callbackExecutor.execute(() -> callback.failure(-1));
            return;
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor,
                    new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    // Copy the GPU bitmap to software memory while the buffer is alive.
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (buffer == null) throw new IllegalStateException("no buffer");
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(
                                buffer, result.getColorSpace());
                        if (hardware == null) throw new IllegalStateException("no bitmap");
                        Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        hardware.recycle();
                        if (software == null) throw new IllegalStateException("copy failed");
                        callback.success(software);
                    } catch (RuntimeException error) {
                        ScreenSyncDiagnostics.record(service, "CAPTURE_COPY_FAIL",
                                "error=" + error.getClass().getSimpleName());
                        callback.failure(-2);
                    }
                }
                @Override public void onFailure(int reason) {
                    ScreenSyncDiagnostics.record(service, "CAPTURE_OS_FAIL",
                            "code=" + reason);
                    callback.failure(reason);
                }
            });
        } catch (SecurityException | IllegalStateException error) {
            ScreenSyncDiagnostics.record(service, "CAPTURE_START_FAIL",
                    "error=" + error.getClass().getSimpleName());
            callbackExecutor.execute(() -> callback.failure(-3));
        }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_connected");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) {
        AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        if (connected == this) connected = null;
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_unbound");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
