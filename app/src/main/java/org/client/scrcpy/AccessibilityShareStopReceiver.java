package org.client.scrcpy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Stops only this app's current, explicitly approved screenshot-sharing session. */
public final class AccessibilityShareStopReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "org.client.scrcpy.STOP_ACCESSIBILITY_SHARE";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_STOP.equals(intent.getAction()))
            AccessibilityJpegStreamer.stopActiveFromNotification();
    }
}
