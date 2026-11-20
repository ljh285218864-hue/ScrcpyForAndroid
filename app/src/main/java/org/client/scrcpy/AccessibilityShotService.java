package org.client.scrcpy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.content.Intent;
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
    private View longTermReadyOverlay;
    private View incomingWatchOverlay;
    private final Handler incomingHandler = new Handler(Looper.getMainLooper());
    private final Runnable expireIncomingWatch = this::removeIncomingWatchOverlay;

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

    /** A user-visible incoming request action independent of ordinary
     * notifications. This cannot accept a request or begin screenshot capture.
     */
    static boolean showIncomingWatchOverlay() {
        AccessibilityShotService service = connected;
        if (service == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper())
            return service.attachIncomingWatchOverlay();
        new Handler(Looper.getMainLooper()).post(service::attachIncomingWatchOverlay);
        return true;
    }

    static void hideIncomingWatchOverlay() {
        AccessibilityShotService service = connected;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper())
            service.removeIncomingWatchOverlay();
        else new Handler(Looper.getMainLooper()).post(service::removeIncomingWatchOverlay);
    }

    private boolean attachIncomingWatchOverlay() {
        if (stopOverlay != null || AccessibilityJpegStreamer.sharingActive())
            return false;
        if (incomingWatchOverlay != null) {
            incomingHandler.removeCallbacks(expireIncomingWatch);
            incomingHandler.postDelayed(expireIncomingWatch, 90_000);
            return true;
        }
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager == null) return false;
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.argb(245, 27, 45, 73));
        TextView title = new TextView(this);
        title.setText("配对手机申请观看");
        title.setTextColor(Color.WHITE);
        title.setTextSize(12);
        bar.addView(title);
        Button open = new Button(this);
        open.setText("打开确认");
        open.setTextSize(12);
        open.setAllCaps(false);
        open.setMinWidth(0);
        open.setMinimumWidth(0);
        // This is a direct local screen tap by the device owner.
        open.setOnClickListener(v -> {
            removeIncomingWatchOverlay();
            Intent launch = new Intent(this, RemoteSetupActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { startActivity(launch); }
            catch (RuntimeException error) {
                ScreenSyncDiagnostics.record(this, "WATCH_OVERLAY",
                        "open_failed");
            }
        });
        bar.addView(open);
        Button dismiss = new Button(this);
        dismiss.setText("稍后");
        dismiss.setTextSize(12);
        dismiss.setAllCaps(false);
        dismiss.setMinWidth(0);
        dismiss.setMinimumWidth(0);
        dismiss.setOnClickListener(v -> removeIncomingWatchOverlay());
        bar.addView(dismiss);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(130);
        try {
            manager.addView(bar, lp);
            incomingWatchOverlay = bar;
            incomingHandler.postDelayed(expireIncomingWatch, 90_000);
            ScreenSyncDiagnostics.record(this, "WATCH_OVERLAY",
                    "request_visible_review_required");
            return true;
        } catch (RuntimeException error) {
            ScreenSyncDiagnostics.record(this, "WATCH_OVERLAY",
                    "request_overlay_unavailable");
            return false;
        }
    }

    private void removeIncomingWatchOverlay() {
        incomingHandler.removeCallbacks(expireIncomingWatch);
        View previous = incomingWatchOverlay;
        incomingWatchOverlay = null;
        if (previous != null) {
            WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (manager != null) {
                try { manager.removeViewImmediate(previous); }
                catch (RuntimeException ignored) { }
            }
        }
    }

    static boolean showLongTermReadyOverlay() {
        AccessibilityShotService a = connected;
        return a != null && Looper.myLooper() == Looper.getMainLooper()
                && a.attachLongTermReadyOverlay();
    }

    static boolean isLongTermReadyOverlayVisible() {
        return connected != null && connected.longTermReadyOverlay != null;
    }

    static void hideLongTermReadyOverlay() {
        AccessibilityShotService a = connected;
        if (a == null) return;
        if (Looper.myLooper() == Looper.getMainLooper())
            a.detachLongTermReadyOverlay();
        else new Handler(Looper.getMainLooper()).post(a::detachLongTermReadyOverlay);
    }

    private boolean attachLongTermReadyOverlay() {
        if (stopOverlay != null) return false;
        if (longTermReadyOverlay != null) return true;
        WindowManager window = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (window == null) return false;
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(7), dp(4), dp(7), dp(4));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.argb(242, 33, 44, 71));
        TextView message = new TextView(this);
        message.setText("ScreenSync 长期共享已开启");
        message.setTextSize(12);
        message.setTextColor(Color.WHITE);
        bar.addView(message);
        Button stop = new Button(this);
        stop.setText("关闭授权");
        stop.setAllCaps(false);
        stop.setTextSize(12);
        stop.setMinWidth(0);
        stop.setMinimumWidth(0);
        stop.setOnClickListener(v -> LongTermSharePolicy.disable(this));
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
            window.addView(bar, lp);
            longTermReadyOverlay = bar;
            ScreenSyncDiagnostics.record(this, "LONG_TERM_SHARE", "standby_visible");
            return true;
        } catch (RuntimeException error) {
            ScreenSyncDiagnostics.record(this, "LONG_TERM_SHARE", "standby_unavailable");
            return false;
        }
    }

    private void detachLongTermReadyOverlay() {
        View previous = longTermReadyOverlay;
        longTermReadyOverlay = null;
        if (previous == null) return;
        WindowManager w = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (w != null) {
            try { w.removeViewImmediate(previous); }
            catch (RuntimeException ignored) { }
        }
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private boolean attachStopOverlay() {
        removeIncomingWatchOverlay();
        detachLongTermReadyOverlay();
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
        LongTermSharePolicy.showStandbyWhenAllowed(this);
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_connected");
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) {
        AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        detachStopOverlay();
        detachLongTermReadyOverlay();
        removeIncomingWatchOverlay();
        if (connected == this) connected = null;
        ScreenSyncDiagnostics.record(this, "ACCESSIBILITY", "service_unbound");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        detachStopOverlay();
        detachLongTermReadyOverlay();
        removeIncomingWatchOverlay();
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
