package org.client.scrcpy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * OPTIONAL user-facing status notification for a locally approved video share.
 *
 * Not a foreground service. This helper never requests notification permission
 * or controls the capture, pairing, authorization or transport lifecycle.
 * A denied permission, muted channel or notify() failure must not stop sharing.
 *
 * The sharing Activity always shows the current session and a Stop control.
 */
final class SharingStatusNotifier {
    private static final String TAG = "ScreenSyncShareStatus";
    private static final String PREFS = "screensync_sharing_notification";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "screensync_optional_sharing_v1";
    private static final int NOTIFICATION_ID = 7321;

    private SharingStatusNotifier() {}

    static boolean isEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) cancel(context);
    }

    static void showIfEnabled(Context context) {
        if (!isEnabled(context)) return;
        Context app = context.getApplicationContext();
        // Android 13+: permission controls only this optional notification.
        if (Build.VERSION.SDK_INT >= 33 &&
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManager manager = (NotificationManager)
                    app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "ScreenSync 共享状态",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("已授权屏幕共享的可选状态提醒");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            Intent openIntent = new Intent(app, RemoteSetupActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(app, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID)
                    : new Notification.Builder(app);
            builder.setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("ScreenSync 正在共享屏幕")
                    .setContentText("已授权设备可观看；打开应用可随时停止")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setOnlyAlertOnce(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException error) {
            // Notification is best-effort; no callbacks into capture/transport.
            Log.w(TAG, "Optional sharing notification unavailable", error);
        }
    }

    static void cancel(Context context) {
        try {
            NotificationManager manager = (NotificationManager)
                    context.getApplicationContext()
                            .getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException error) {
            Log.w(TAG, "Optional notification cleanup unavailable", error);
        }
    }
}
