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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    // HardwareBuffer -> software bitmap is a full-screen memory copy.
    // Keep that costly work off Android's main looper; the caller still
    // receives completion on its requested executor (normally main::post).
    private static final ExecutorService SCREENSHOT_COPY_WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ScreenSync-screenshot-copy");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
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
            service.takeScreenshot(Display.DEFAULT_DISPLAY, SCREENSHOT_COPY_WORKER,
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
                        callbackExecutor.execute(() -> callback.success(software));
                    } catch (RuntimeException error) {
                        ScreenSyncDiagnostics.record(service, "CAPTURE_COPY_FAIL",
                                "error=" + error.getClass().getSimpleName());
                        callbackExecutor.execute(() -> callback.failure(-2));
                    }
                }
                @Override public void onFailure(int reason) {
                    ScreenSyncDiagnostics.record(service, "CAPTURE_OS_FAIL",
                            "code=" + reason);
                    callbackExecutor.execute(() -> callback.failure(reason));
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
        AccessibilityServiceHealth.onServiceConnected(this);
        AccessibilityReconnectCoordinator.onServiceConnected(this);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) {
        // A stale old instance must never stop a newer, re-bound service.
        if (connected == this) {
            connected = null;
            AccessibilityReconnectCoordinator.onServiceDisconnected();
            AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
            AccessibilityServiceHealth.onServiceDisconnected(this);
        }
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_unbound");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_destroyed");
        if (connected == this) {
            connected = null;
            AccessibilityReconnectCoordinator.onServiceDisconnected();
            AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
            AccessibilityServiceHealth.onServiceDisconnected(this);
        }
        super.onDestroy();
    }
}
