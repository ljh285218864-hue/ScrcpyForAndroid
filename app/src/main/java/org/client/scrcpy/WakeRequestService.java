package org.client.scrcpy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * INTERNAL, one-shot receiver for a wake REQUEST, not a screen capture service.
 *
 * This is a local integration-test building block. No network endpoint, no
 * exported component, no background polling, no implicit broadcast registration,
 * no ADB/scrcpy invocation and no automatic activation of video sharing.
 *
 * Future peer notifications must ONLY be handed to this component by a trusted
 * and authenticated transport after pairing, revocation and replay protection
 * exist. Until then, the only supported action is LOCAL_TEST.
 */
public final class WakeRequestService extends Service {
    private static final String TAG = "WakeRequestService";
    public static final String ACTION_LOCAL_TEST = "org.client.scrcpy.action.LOCAL_WAKE_TEST";
    private static final String PREFS = "wake_receiver_status_private";
    private static final String LAST_TEST_TIME = "last_test_ms";
    private static final String CHANNEL_ID = "screen_sync_wake_requests";
    private static final int NOTIFICATION_ID = 7231;

    public static boolean requestLocalTest(Context context) {
        // Explicit class intent, internal to our own app. No external intent filter.
        Intent intent = new Intent(context, WakeRequestService.class);
        intent.setAction(ACTION_LOCAL_TEST);
        try {
            context.startService(intent);
            return true;
        } catch (IllegalStateException | SecurityException exception) {
            Log.w(TAG, "Local test service could not start", exception);
            return false;
        }
    }

    public static long lastTestReceivedAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_TEST_TIME, 0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null || !ACTION_LOCAL_TEST.equals(intent.getAction())) {
                Log.w(TAG, "Unsupported or missing wake request action; ignored");
                return START_NOT_STICKY;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(LAST_TEST_TIME, System.currentTimeMillis())
                    .apply();
            Log.i(TAG, "Internal local wake request received (no screen sharing)");
            notifyLocally();
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void notifyLocally() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Notification permission missing: local receipt still recorded");
            return;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "屏幕共享请求", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("屏幕共享请求与唤醒接收测试通知");
            manager.createNotificationChannel(channel);
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(getString(R.string.wake_notification_title))
                .setContentText(getString(R.string.wake_notification_message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
