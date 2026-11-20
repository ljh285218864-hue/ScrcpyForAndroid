package org.client.scrcpy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-initiated screenshot sharing, without foreground service, ADB,
 * MediaProjection or VPN. The source's user-enabled accessibility service
 * shows a visible sharing / STOP control across all apps for EACH approved
 * session. Ordinary notifications never control screenshot capture.
 * Never auto-resumes capture on reboot, network reconnection or process start.
 */
public final class AccessibilityJpegStreamer {
    public interface Listener { void onStopped(String reason); }

    // Single, quiet sharing-status notification; never bypass app/channel settings.
    private static final int NOTICE_ID = 8413;
    private static final String CHANNEL_ID = "screensync_active_share";
    private static final long OVERLAY_CHECK_INTERVAL_MS = 1000L;
    private static final long DELAY_MS = JpegStreamTuning.CAPTURE_GAP_MS;
    private static volatile AccessibilityJpegStreamer current;

    private final Context app;
    private final NoVpnMediaSocket socket;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean busy;
    private long lastOverlayCheckMs;
    private volatile boolean noticePosted;
    private int captureSerial;
    private int outWidth, outHeight;
    private int screenshotCount, sendCount, sendFailCount, captureFailCount;
    private int congestionSkips;
    private volatile int compressionQuality = JpegStreamTuning.JPEG_QUALITY;
    private final Runnable tick = this::capture;

    public AccessibilityJpegStreamer(Context ctx, NoVpnMediaSocket media, Listener cb) {
        app = ctx.getApplicationContext();
        socket = media;
        listener = cb;
    }

    /** Device-owner accessibility permission, never ordinary notifications. */
    public static String readinessIssue(Context context) {
        return AccessibilityShotService.ready() ? "" : "accessibility_not_enabled";
    }

    /** Ordinary sharing notification is optional, not a capture prerequisite. */
    public static boolean notificationAllowed(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled())
            return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel != null
                    && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
        }
        return true;
    }

    /** Activity visibility never controls the already-approved stream. */
    static void setSourceUiVisible(boolean visible) {
        if (!visible) stopIfNoVisibleStopControl();
    }

    static void stopIfNoVisibleStopControl() {
        AccessibilityJpegStreamer stream = current;
        if (stream != null && stream.running
                && !AccessibilityShotService.isShareStopOverlayVisible())
            stream.stopDueToMissingVisibleStop();
    }

    public boolean start() {
        String blocked = readinessIssue(app);
        if (!blocked.isEmpty()) {
            ScreenSyncDiagnostics.record(app, "CAPTURE_START", "not_ready=" + blocked);
            return false;
        }
        if (running || current != null) {
            ScreenSyncDiagnostics.record(app, "CAPTURE_START", "already_active");
            return false;
        }
        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        int[] dimensions = JpegStreamTuning.targetSize(
                metrics.widthPixels, metrics.heightPixels);
        outWidth = dimensions[0];
        outHeight = dimensions[1];
        current = this;
        running = true;
        // Always provide a visible, source-side Stop control in ANY app,
        // independent of Android's ordinary notification permission.
        if (!AccessibilityShotService.showShareStopOverlay()) {
            running = false;
            current = null;
            encoder.shutdownNow();
            ScreenSyncDiagnostics.record(app, "CAPTURE_START",
                    "accessibility_overlay_unavailable");
            return false;
        }
        noticePosted = showNoticeIfAllowed(); // optional only
        if (!socket.sendVideoSetup(new int[] {
                metrics.widthPixels, metrics.heightPixels, outWidth, outHeight}, "jpeg")) {
            ScreenSyncDiagnostics.record(app, "CAPTURE_START", "send_video_setup_failed");
            running = false;
            current = null;
            AccessibilityShotService.hideShareStopOverlay();
            NotificationManager manager = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTICE_ID);
            encoder.shutdownNow();
            return false;
        }
        ScreenSyncDiagnostics.record(app, "CAPTURE_START", "success jpeg_width="
                + outWidth + " jpeg_height=" + outHeight + " quality=" + compressionQuality
                + " gap_ms=" + DELAY_MS);
        main.post(tick);
        return true;
    }

    private void capture() {
        if (!running || busy) return;
        // Home / switching apps / disabled notices do not end sharing.
        // If the dedicated cross-app STOP overlay disappears, end sharing.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOverlayCheckMs >= OVERLAY_CHECK_INTERVAL_MS) {
            lastOverlayCheckMs = now;
            if (!AccessibilityShotService.isShareStopOverlayVisible()) {
                stopDueToMissingVisibleStop();
                return;
            }
        }
        // Never acquire screenshots while the old WSS session is disconnected.
        if (!socket.isSourceTransportReady()) {
            main.postDelayed(tick, 350);
            return;
        }
        // Limit queueing before we spend CPU and data on a screenshot that
        // would arrive late. Drop a frame rather than delay subsequent frames.
        if (socket.isSourceBackpressured()) {
            congestionSkips++;
            if (congestionSkips % 3 == 0)
                compressionQuality = Math.max(58, compressionQuality - 2);
            if (congestionSkips == 1 || congestionSkips % 10 == 0)
                ScreenSyncDiagnostics.record(app, "JPEG_BACKPRESSURE",
                        "skipped=" + congestionSkips
                        + " quality=" + compressionQuality);
            main.postDelayed(tick, JpegStreamTuning.CONGESTION_RETRY_MS);
            return;
        }
        busy = true;
        final int serial = ++captureSerial;
        // Some OEMs can leave takeScreenshot without a callback. Recover
        // rather than displaying a permanent black waiting screen.
        final Runnable watchdog = () -> {
            if (!running || !busy || serial != captureSerial) return;
            captureSerial++;
            busy = false;
            ScreenSyncDiagnostics.record(app, "CAPTURE_TIMEOUT",
                    "callback_timeout_ms=6000");
            main.postDelayed(tick, 850);
        };
        main.postDelayed(watchdog, 6000);
        AccessibilityShotService.capture(main::post, new AccessibilityShotService.Callback() {
            @Override public void success(Bitmap screenshot) {
                main.removeCallbacks(watchdog);
                if (!running || serial != captureSerial) {
                    screenshot.recycle();
                    return;
                }
                try {
                encoder.execute(() -> {
                    try {
                        if (running) {
                            Bitmap image = Bitmap.createScaledBitmap(
                                    screenshot, outWidth, outHeight, true);
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream(131072);
                            boolean encoded = image.compress(
                                    Bitmap.CompressFormat.JPEG, compressionQuality, buffer);
                            if (!encoded) throw new IllegalStateException("jpeg_encode_failed");
                            if (image != screenshot) image.recycle();
                            byte[] data = buffer.toByteArray();
                            screenshotCount++;
                            if (running && data.length >= 64 && data.length <= 1024 * 1024) {
                                boolean sent = socket.sendJpegFrame(data);
                                if (sent) {
                                    sendCount++;
                                    // Recover gradually after a slow-network phase.
                                    if (sendCount % 25 == 0 && compressionQuality
                                            < JpegStreamTuning.JPEG_QUALITY)
                                        compressionQuality++;
                                } else {
                                    sendFailCount++;
                                    compressionQuality = Math.max(58, compressionQuality - 3);
                                }
                                if (screenshotCount == 1 || screenshotCount % 20 == 0
                                        || (sendFailCount == 1 && !sent)) {
                                    ScreenSyncDiagnostics.record(app, "JPEG_SEND",
                                            "captured=" + screenshotCount + " sent=" + sendCount
                                            + " failed=" + sendFailCount + " bytes=" + data.length
                                            + " quality=" + compressionQuality);
                                }
                            } else if (screenshotCount == 1) {
                                ScreenSyncDiagnostics.record(app, "JPEG_SEND",
                                        "rejected_size=" + data.length);
                            }
                        }
                    } catch (RuntimeException error) {
                        ScreenSyncDiagnostics.record(app, "JPEG_ENCODE_FAIL",
                                "error=" + error.getClass().getSimpleName());
                    } finally {
                        screenshot.recycle();
                        main.post(() -> {
                            if (serial != captureSerial) return;
                            busy = false;
                            if (running) main.postDelayed(tick, DELAY_MS);
                        });
                    }
                });
                } catch (java.util.concurrent.RejectedExecutionException stopped) {
                    ScreenSyncDiagnostics.record(app, "JPEG_ENCODE_FAIL", "executor_stopped");
                    screenshot.recycle();
                    if (serial == captureSerial) busy = false;
                }
            }
            @Override public void failure(int reason) {
                main.removeCallbacks(watchdog);
                if (!running || serial != captureSerial) return;
                captureFailCount++;
                if (captureFailCount == 1 || captureFailCount % 5 == 0) {
                    ScreenSyncDiagnostics.record(app, "CAPTURE_FAIL",
                            "count=" + captureFailCount + " code=" + reason);
                }
                busy = false;
                if (running) main.postDelayed(tick, 1000);
            }
        });
    }

    public void stop() {
        if (!running) return;
        running = false;
        captureSerial++;
        ScreenSyncDiagnostics.record(app, "CAPTURE_STOP",
                "captured=" + screenshotCount + " sent=" + sendCount
                + " send_failed=" + sendFailCount + " capture_failed=" + captureFailCount
                + " congestion_skips=" + congestionSkips);
        main.removeCallbacks(tick);
        encoder.shutdownNow();
        if (current == this) current = null;
        AccessibilityShotService.hideShareStopOverlay();
        NotificationManager nm = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        noticePosted = false;
        if (nm != null) nm.cancel(NOTICE_ID);
        // Resurface the owner's opt-in state after a session ends.
        main.postDelayed(() -> LongTermSharePolicy.showStandbyWhenAllowed(app), 150);
    }

    /**
     * Finish starting the SAME screen-share session the device owner explicitly
     * approved before their Activity was removed. No new remote request can
     * grant permission here; the WSS source session must already be approved.
     * Android may still stop the process at any time.
     */
    public static void startPreviouslyApprovedWithoutUi(
            Context appContext, NoVpnMediaSocket media) {
        if (sharingActive() || !media.hasApprovedSourceSession()) return;
        // The approved source can start off-screen only after installing
        // its visible accessibility STOP overlay; no notification gate.
        if (!AccessibilityShotService.ready()) {
            media.endSourceSession("accessibility_service_unavailable");
            return;
        }
        AccessibilityJpegStreamer stream = new AccessibilityJpegStreamer(
                appContext, media, reason ->
                    ScreenSyncDiagnostics.record(appContext, "BACKGROUND_SHARE", reason));
        if (stream.start()) {
            ScreenSyncDiagnostics.record(appContext, "BACKGROUND_SHARE",
                    "previously_approved_started_visible_overlay");
        } else {
            ScreenSyncDiagnostics.record(appContext, "BACKGROUND_SHARE",
                    "previously_approved_start_failed");
            media.endSourceSession("background_start_failed");
        }
    }

    /** Re-send video dimensions for the SAME currently approved source.
     * No new capture, no permission changes and no replacement session.
     */
    static boolean resendCurrentSetup(NoVpnMediaSocket transport) {
        AccessibilityJpegStreamer stream = current;
        if (stream == null || !stream.running || stream.socket != transport
                || !AccessibilityShotService.isShareStopOverlayVisible())
            return false;
        DisplayMetrics metrics = stream.app.getResources().getDisplayMetrics();
        return transport.sendVideoSetup(new int[] {
                metrics.widthPixels, metrics.heightPixels,
                stream.outWidth, stream.outHeight}, "jpeg");
    }

    public static boolean sharingActive() {
        AccessibilityJpegStreamer stream = current;
        return stream != null && stream.running;
    }

    /** UI stop or loss of an already-approved network session.
     * Never resume capture when the service/process is restarted. */
    public static void stopActiveWithoutSending() {
        AccessibilityJpegStreamer stream = current;
        if (stream != null) stream.stop();
    }

    public static void stopActiveFromNotification() {
        // A deliberate source-device Stop revokes background auto-approval.
        AccessibilityJpegStreamer stream = current;
        if (stream != null) LongTermSharePolicy.disable(stream.app);
        stopFromSourceDevice("共享已从通知栏停止");
    }

    public static void stopActiveFromOverlay() {
        AccessibilityJpegStreamer stream = current;
        if (stream != null) LongTermSharePolicy.disable(stream.app);
        stopFromSourceDevice("共享已从跨应用悬浮条停止");
    }

    static void stopActiveOnAccessibilityLost() {
        stopFromSourceDevice("无障碍权限已关闭，屏幕共享结束");
    }

    private static void stopFromSourceDevice(String reason) {
        AccessibilityJpegStreamer stream = current;
        if (stream == null) return;
        stream.stop();
        stream.socket.endSourceSession();
        stream.main.post(() -> stream.listener.onStopped(reason));
    }

    private void stopDueToMissingVisibleStop() {
        if (!running) return;
        ScreenSyncDiagnostics.record(app, "CAPTURE_STOP", "no_visible_stop_control");
        stop();
        socket.endSourceSession();
        main.post(() -> listener.onStopped("共享结束：跨应用停止浮条不可用"));
    }

    /** Best-effort ordinary notice, never starts a foreground service. */
    private boolean showNoticeIfAllowed() {
        if (!notificationAllowed(app)) return false;
        try {
            NotificationManager nm = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                            "ScreenSync 共享状态", NotificationManager.IMPORTANCE_LOW);
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
                if (!notificationAllowed(app)) return false;
            }
            Intent intent = new Intent(app, AccessibilityShareStopReceiver.class)
                    .setAction(AccessibilityShareStopReceiver.ACTION_STOP);
            PendingIntent stopAction = PendingIntent.getBroadcast(app, NOTICE_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder notification = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            notification.setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("ScreenSync 正在共享屏幕")
                    .setContentText("屏幕截图正在传输，点击停止可立即结束共享")
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .setDefaults(0)
                    .addAction(new Notification.Action.Builder(
                            android.R.drawable.ic_menu_close_clear_cancel,
                            "停止共享", stopAction).build());
            nm.notify(NOTICE_ID, notification.build());
            return true;
        } catch (RuntimeException failure) {
            ScreenSyncDiagnostics.record(app, "CAPTURE_NOTICE",
                    "optional_notice_failed=" + failure.getClass().getSimpleName());
            return false;
        }
    }

}
