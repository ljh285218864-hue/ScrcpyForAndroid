package org.client.scrcpy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
 import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    private WindowManager shareWindowManager;
    private View stopOverlay;

    /** No app notification is needed for the visible, cross-app STOP control.
     * This overlay belongs to the already owner-enabled AccessibilityService.
     */
    static boolean showShareStopOverlay() {
        AccessibilityShotService service = connected;
        if (service == null || Looper.myLooper() != Looper.getMainLooper())
            return false;
        return service.attachStopOverlay();
    }

    static boolean isShareStopOverlayVisible() {
        AccessibilityShotService service = connected;
        return service != null && service.stopOverlay != null;
    }

    static void hideShareStopOverlay() {
        AccessibilityShotService service = connected;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper())
            service.detachStopOverlay();
        else new Handler(Looper.getMainLooper()).post(service::detachStopOverlay);
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private boolean attachStopOverlay() {
        if (stopOverlay != null) return true;
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager == null) return false;

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(7), dp(4), dp(6), dp(4));
        bar.setBackgroundColor(Color.argb(242, 28, 36, 53));
        TextView status = new TextView(this);
        status.setText("ScreenSync 正在共享");
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setPadding(dp(6), 0, dp(8), 0);
        bar.addView(status);
        Button stop = new Button(this);
        stop.setText(LongTermSharePolicy.enabled(this)
                ? "停止并关闭长期授权" : "停止共享");
        stop.setAllCaps(false);
        stop.setTextSize(12);
        stop.setMinWidth(0);
        stop.setMinimumWidth(0);
        stop.setMinHeight(dp(38));
        stop.setPadding(dp(8), 0, dp(8), 0);
        stop.setOnClickListener(v -> AccessibilityJpegStreamer.stopActiveFromOverlay());
        bar.addView(stop);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(6);
        lp.y = dp(70);
        try {
            manager.addView(bar, lp);
            shareWindowManager = manager;
            stopOverlay = bar;
            ScreenSyncDiagnostics.record(this, "SHARE_OVERLAY", "visible_stop_ready");
            return true;
        } catch (RuntimeException failure) {
            ScreenSyncDiagnostics.record(this, "SHARE_OVERLAY",
                    "failed_" + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void detachStopOverlay() {
        View bar = stopOverlay;
        WindowManager manager = shareWindowManager;
        stopOverlay = null;
        shareWindowManager = null;
        if (bar != null && manager != null) {
            try { manager.removeViewImmediate(bar); }
            catch (RuntimeException ignored) { }
        }
    }

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
        detachStopOverlay();
        if (connected == this) connected = null;
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_unbound");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        detachStopOverlay();
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
