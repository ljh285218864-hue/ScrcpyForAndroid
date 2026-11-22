package org.client.scrcpy;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Optional inexact 5-minute standby check; never requests exact-alarm access.
 *
 * Android may batch or postpone this alarm during Doze, battery restrictions,
 * or force-stop. It does not guarantee an always-on socket or unattended
 * screen capture. A fire only attempts signaling/presence recovery.
 */
public final class NoVpnStandbyAlarm {
    static final String ACTION_WAKE =
            "org.client.scrcpy.action.STANDBY_SIGNALING_WAKE";
    private static final String TAG = "ScreenSyncAlarm";
    private static final int REQUEST_CODE = 8907;
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;

    private NoVpnStandbyAlarm() {}

    private static PendingIntent pending(Context context, int flags) {
        Intent intent = new Intent(context.getApplicationContext(),
                NoVpnStandbyAlarmReceiver.class).setAction(ACTION_WAKE);
        int immutable = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        return PendingIntent.getBroadcast(context.getApplicationContext(),
                REQUEST_CODE, intent, flags | immutable);
    }

    /** Only enabled by device-owner choice of background communication. */
    static void schedule(Context context) {
        if (!NoVpnBackgroundSync.isEnabled(context)
                || !NoVpnApi.isRegistered(context)) return;
        AlarmManager alarms = (AlarmManager) context.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent target = pending(context, PendingIntent.FLAG_UPDATE_CURRENT);
        long due = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        due, target);
            } else {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, target);
            }
        } catch (SecurityException error) {
            Log.w(TAG, "Standby wake alarm unavailable", error);
        }
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        PendingIntent target = pending(context, PendingIntent.FLAG_NO_CREATE);
        if (target != null && alarms != null) alarms.cancel(target);
        if (target != null) target.cancel();
    }
}
