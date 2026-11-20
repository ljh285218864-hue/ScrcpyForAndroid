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

/**
 * Single incoming watch request alert. No foreground/background resident
 * notification and no capture authorization. The owner must open the app,
 * inspect the request and tap "Allow" before a source session starts.
 */
public final class ScreenSyncWatchNotice {
    private static final String CHANNEL = "screensync_incoming_request";
    private static final int NOTICE_ID = 8416;

    private ScreenSyncWatchNotice() { }

    public static void show(Context context) {
        Context app = context.getApplicationContext();
        // Only one visible notice during an active, owner-approved share.
        // Incoming requests must not create a second notification on top of
        // the ongoing sharing-status / STOP control.
        if (AccessibilityJpegStreamer.sharingActive()) {
            ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "suppressed_during_sharing");
            return;
        }
        // First try the source user's accessible, on-screen review button.
        // This never accepts a request; normal notifications are optional.
        if (AccessibilityShotService.showIncomingWatchOverlay()) {
            ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "owner_review_overlay");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "notification_denied");
            return;
        }
        NotificationManager manager = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "notifications_off");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "ScreenSync 观看申请", NotificationManager.IMPORTANCE_HIGH));
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance()
                    == NotificationManager.IMPORTANCE_NONE) {
                ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "channel_blocked");
                return;
            }
        }
        Intent open = new Intent(app, RemoteSetupActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(app, NOTICE_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("配对手机申请观看")
                .setContentText("点击打开 ScreenSync，确认是否允许本次屏幕共享")
                .setAutoCancel(true).setContentIntent(tap)
                .setOnlyAlertOnce(true);
        manager.notify(NOTICE_ID, builder.build());
        ScreenSyncDiagnostics.record(app, "WATCH_NOTICE", "posted");
    }

    public static void clear(Context context) {
        AccessibilityShotService.hideIncomingWatchOverlay();
        NotificationManager manager = (NotificationManager)
                context.getApplicationContext().getSystemService(
                        Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTICE_ID);
    }
}
