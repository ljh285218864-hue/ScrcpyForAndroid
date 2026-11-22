package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local-only fallback when a previously active accessibility permission
 * later appears disabled. Marks a need for the source owner's attention,
 * never re-enables a system switch or launches UI from the background.
 *
 * Cannot determine whether the OS/vendor or the user disabled the service.
 */
final class AccessibilityRecoveryFallback {
    private static final String PREF = "ss_accessibility_local_recovery_v1";
    private static final String EVER_RUNNING = "ever_running";
    private static final String NEEDS_LOCAL_ACTION = "needs_local_action";

    private AccessibilityRecoveryFallback() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static synchronized void observe(Context context, AccessibilityStatePolicy.State state) {
        SharedPreferences p = prefs(context);
        if (state == AccessibilityStatePolicy.State.RUNNING) {
            if (!p.getBoolean(EVER_RUNNING, false)
                    || p.getBoolean(NEEDS_LOCAL_ACTION, false)) {
                boolean recovered = p.getBoolean(NEEDS_LOCAL_ACTION, false);
                p.edit().putBoolean(EVER_RUNNING, true)
                        .putBoolean(NEEDS_LOCAL_ACTION, false).apply();
                if (recovered) {
                    ScreenSyncDiagnostics.record(context, "ACCESSIBILITY_FALLBACK",
                            "local_access_restored");
                }
            }
            return;
        }

        if (AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                p.getBoolean(EVER_RUNNING, false),
                LongTermSharePolicy.enabled(context),
                state)
                && !p.getBoolean(NEEDS_LOCAL_ACTION, false)) {
            p.edit().putBoolean(NEEDS_LOCAL_ACTION, true).apply();
            ScreenSyncDiagnostics.record(context, "ACCESSIBILITY_FALLBACK",
                    "owner_attention_required");
        }
    }

    static boolean needsLocalRecovery(Context context,
            AccessibilityStatePolicy.State state) {
        return AccessibilityRecoveryFallbackPolicy.shouldDisplayLocalRecovery(
                prefs(context).getBoolean(NEEDS_LOCAL_ACTION, false),
                LongTermSharePolicy.enabled(context), state);
    }

    /** Clears the reminder when the owner revokes persistent viewing consent. */
    static void clearOnOwnerRevocation(Context context) {
        prefs(context).edit().putBoolean(NEEDS_LOCAL_ACTION, false).apply();
    }
}
