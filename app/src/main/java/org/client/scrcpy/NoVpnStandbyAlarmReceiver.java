package org.client.scrcpy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Single bounded alarm tick. No capture is started by this receiver. */
public final class NoVpnStandbyAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !NoVpnStandbyAlarm.ACTION_WAKE.equals(
                intent.getAction())) return;
        if (!NoVpnBackgroundSync.isEnabled(context)
                || !NoVpnApi.isRegistered(context)) return;
        // Re-arm first so a temporary connection failure does not stop checks.
        NoVpnStandbyAlarm.schedule(context);
        NoVpnBackgroundSync.requestOnce(context, "standby_alarm");
        ScreenSyncMediaKeeper.restoreOptedInSignaling(context);
        ScreenSyncDiagnostics.record(context, "STANDBY_ALARM",
                "signal_recovery_attempted");
    }
}
