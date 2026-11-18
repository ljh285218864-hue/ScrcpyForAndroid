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
 * MediaProjection or VPN. Notification + STOP action remain visible while
 * sharing. The remote side must get explicit permission for EACH session.
 * Never auto-resumes capture on reboot, network reconnection or process start.
 */
public final class AccessibilityJpegStreamer {
    public interface Listener { void onStopped(String reason); }

    private static final int NOTICE_ID = 8413;
    private static final String CHANNEL_ID = "screensync_active_share";
    private static final long DELAY_MS = 400L;
    private static volatile AccessibilityJpegStreamer current;

    private final Context app;
    private final NoVpnMediaSocket socket;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean busy;
    private int outWidth, outHeight;
    private final Runnable tick = this::capture;

    public AccessibilityJpegStreamer(Context ctx, NoVpnMediaSocket media, Listener cb) {
        app = ctx.getApplicationContext();
        socket = media;
        listener = cb;
    }

    public boolean start() {
        if (!AccessibilityShotService.ready() || running || current != null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        float scale = Math.min(1f, 960f / Math.max(metrics.widthPixels, metrics.heightPixels));
        outWidth = Math.max(16, Math.round(metrics.widthPixels * scale));
        outHeight = Math.max(16, Math.round(metrics.heightPixels * scale));
        if (!socket.sendVideoSetup(new int[] {
                metrics.widthPixels, metrics.heightPixels, outWidth, outHeight}, "jpeg"))
            return false;
        current = this;
        running = true;
        showNotice();
        main.post(tick);
        return true;
    }

    private void capture() {
        if (!running || busy) return;
        busy = true;
        AccessibilityShotService.capture(main::post, new AccessibilityShotService.Callback() {
            @Override public void success(Bitmap screenshot) {
                if (!running) {
                    screenshot.recycle();
                    busy = false;
                    return;
                }
                encoder.execute(() -> {
                    try {
                        if (running) {
                            Bitmap image = Bitmap.createScaledBitmap(
                                    screenshot, outWidth, outHeight, true);
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
                            image.compress(Bitmap.CompressFormat.JPEG, 60, buffer);
                            if (image != screenshot) image.recycle();
                            byte[] data = buffer.toByteArray();
                            if (running && data.length >= 64 && data.length <= 1024 * 1024)
                                socket.sendJpegFrame(data);
                        }
                    } catch (RuntimeException ignored) {
                        // Device screenshots may fail or be blocked for secure content.
                    } finally {
                        screenshot.recycle();
                        main.post(() -> {
                            busy = false;
                            if (running) main.postDelayed(tick, DELAY_MS);
                        });
                    }
                });
            }
            @Override public void failure(int reason) {
                busy = false;
                if (running) main.postDelayed(tick, 1000);
            }
        });
    }

    public void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        encoder.shutdownNow();
        if (current == this) current = null;
        NotificationManager nm = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTICE_ID);
    }

    public static void stopActiveFromNotification() {
        AccessibilityJpegStreamer stream = current;
        if (stream == null) return;
        stream.stop();
        stream.socket.endSourceSession();
        stream.main.post(() -> stream.listener.onStopped("共享已从通知栏停止"));
    }

    private void showNotice() {
        NotificationManager nm = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "ScreenSync 共享状态", NotificationManager.IMPORTANCE_LOW));
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
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "停止共享", stopAction).build());
        nm.notify(NOTICE_ID, notification.build());
    }
}
