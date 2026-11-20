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
        ScreenSyncFcmBridge.initialize(this);
        NoVpnBackgroundSync.restore(this);
        ScreenSyncFcmBridge.registerIfOptedIn(this);
    }
}
