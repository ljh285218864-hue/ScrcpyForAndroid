package org.client.scrcpy;

import android.app.Application;

/** Accessibility/JPEG-only product. No ADB daemon, native binary or
 * wireless-debugging startup is present.
 *
 * Restore only owner-enabled, best-effort pairing presence. This does not
 * authorize or start screenshot capture.
 */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ScreenSyncWatchNotice.clear(this);
        // A sudden OEM process kill has no onDestroy callback; the Android
        // exit history is available only after the next process startup.
        ScreenSyncProcessExitDiagnostics.recordPreviousExit(this);
        // Observe the existing OS grant; never re-enable a revoked service.
        AccessibilityServiceHealth.start(this);
        NoVpnBackgroundSync.restore(this);
    }
}
