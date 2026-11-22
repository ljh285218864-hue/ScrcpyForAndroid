package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * When Android re-binds a previously user-enabled AccessibilityService,
 * re-establish the owner's previously opted-in signaling (not capture).
 *
 * This component cannot/does not initiate an AccessibilityService binding,
 * change secure settings, grant accessibility, or capture a screen.
 */
final class AccessibilityReconnectCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SETTLE_MS = 700L;
    private static Runnable pendingRestore;

    private AccessibilityReconnectCoordinator() {}

    static synchronized void onServiceConnected(Context context) {
        final Context app = context.getApplicationContext();
        cancelPending();
        Runnable work = () -> {
            synchronized (AccessibilityReconnectCoordinator.class) {
                pendingRestore = null;
            }
            try {
                boolean paired = NoVpnApi.isRegistered(app)
                        && !NoVpnApi.peerId(app).isEmpty()
                        && !NoVpnApi.groupId(app).isEmpty();
                boolean eligible = AccessibilityReconnectPolicy.shouldRestoreSignaling(
                        AccessibilityServiceHealth.state(app),
                        NoVpnBackgroundSync.isEnabled(app),
                        paired,
                        LongTermSharePolicy.enabled(app));
                if (!eligible) {
                    ScreenSyncDiagnostics.record(app, "ACCESSIBILITY_REBIND",
                            "signal_skipped_not_eligible");
                    return;
                }
                // These operations are idempotent. No permission change, no
                // screenshot session, no remote session approval is performed.
                NoVpnBackgroundSync.restore(app);
                NoVpnBackgroundSync.requestOnce(app, "accessibility_rebind");
                ScreenSyncDiagnostics.record(app, "ACCESSIBILITY_REBIND",
                        "signal_recovery_requested");
            } catch (RuntimeException error) {
                ScreenSyncDiagnostics.record(app, "ACCESSIBILITY_REBIND",
                        "recovery_deferred=" + error.getClass().getSimpleName());
            }
        };
        pendingRestore = work;
        MAIN.postDelayed(work, SETTLE_MS);
    }

    static synchronized void onServiceDisconnected() {
        cancelPending();
    }

    private static void cancelPending() {
        if (pendingRestore != null) {
            MAIN.removeCallbacks(pendingRestore);
            pendingRestore = null;
        }
    }
}
