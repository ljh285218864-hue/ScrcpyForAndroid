package org.client.scrcpy;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;

/**
 * Report a prior process exit as observed by Android 11+ at the next start.
 * Useful because an OEM hard kill does not invoke Activity/Service onDestroy.
 * No screen data, other app information, credentials or trace details are read.
 */
final class ScreenSyncProcessExitDiagnostics {
    private static final String PREF = "screensync_previous_exit_v1";
    private static final String LAST_EXIT = "last_reported_timestamp";

    private ScreenSyncProcessExitDiagnostics() {}

    static void recordPreviousExit(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        Context app = context.getApplicationContext();
        try {
            ActivityManager activity = (ActivityManager) app.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (activity == null) return;
            List<ApplicationExitInfo> history = activity.getHistoricalProcessExitReasons(
                    app.getPackageName(), 0, 8);
            if (history == null || history.isEmpty()) return;
            SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long previous = prefs.getLong(LAST_EXIT, 0L);
            ApplicationExitInfo newest = null;
            for (ApplicationExitInfo info : history) {
                if (info != null && info.getTimestamp() > previous
                        && (newest == null || info.getTimestamp() > newest.getTimestamp())) {
                    newest = info;
                }
            }
            if (newest == null) return;
            // The reason code is the system's own process-exit diagnosis.
            // Description and traces are intentionally not collected.
            ScreenSyncDiagnostics.record(app, "PREVIOUS_PROCESS_EXIT",
                    "reason=" + newest.getReason()
                            + " status=" + newest.getStatus()
                            + " importance=" + newest.getImportance()
                            + " age_s=" + Math.max(0L,
                                    (System.currentTimeMillis() - newest.getTimestamp()) / 1000L));
            prefs.edit().putLong(LAST_EXIT, newest.getTimestamp()).apply();
        } catch (RuntimeException unavailable) {
            ScreenSyncDiagnostics.record(app, "PREVIOUS_PROCESS_EXIT",
                    "system_history_unavailable");
        }
    }
}
