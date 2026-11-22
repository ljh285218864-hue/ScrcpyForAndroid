package org.client.scrcpy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restore opted-in BACKGROUND PRESENCE only after reboot or app upgrade.
 *
 * Deliberately does not start screen capture, ADB, video transport,
 * accessibility services, a foreground service or any Activity.
 * Android may defer the WorkManager job due to Doze/OEM battery policy.
 */
public final class NoVpnStandbyRestoreReceiver extends BroadcastReceiver {
    private static final String TAG = "ScreenSyncStandby";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!NoVpnBackgroundSync.isEnabled(context)
                || !NoVpnApi.isRegistered(context)) return;
        try {
            // Scheduler is idempotent. This does not connect to the remote
            // peer or authorize sharing; it only sends a short HTTPS heartbeat.
            NoVpnBackgroundSync.schedule(context);
            NoVpnStandbyAlarm.schedule(context);
            // Best effort: signaling only; never starts a screenshot session.
            ScreenSyncMediaKeeper.restoreOptedInSignaling(context);
            NoVpnBackgroundSync.requestOnce(context,
                    Intent.ACTION_BOOT_COMPLETED.equals(action)
                            ? "boot_recovery" : "app_updated");
        } catch (RuntimeException error) {
            Log.w(TAG, "Standby reschedule was deferred", error);
        }
    }
}
