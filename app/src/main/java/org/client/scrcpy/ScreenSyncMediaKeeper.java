package org.client.scrcpy;

/**
 * Owns the authenticated transport for as long as this app process survives.
 * Does not start a background service, revive a force-stopped app, or resume
 * capture after reboot. Explicitly approved source streams retain their
 * user-visible STOP notification.
 */
public final class ScreenSyncMediaKeeper {
    private static NoVpnMediaSocket transport;
    private ScreenSyncMediaKeeper() { }

    public static synchronized NoVpnMediaSocket current() {
        return transport;
    }

    public static synchronized void retain(NoVpnMediaSocket socket) {
        if (transport != null && transport != socket) {
            throw new IllegalStateException("Only one ScreenSync media transport allowed");
        }
        transport = socket;
    }

    public static synchronized void forget(NoVpnMediaSocket socket) {
        if (transport == socket) transport = null;
    }

    public static synchronized void stop() {
        if (transport == null) return;
        NoVpnMediaSocket old = transport;
        transport = null;
        AccessibilityJpegStreamer.stopActiveWithoutSending();
        old.close();
    }
}
