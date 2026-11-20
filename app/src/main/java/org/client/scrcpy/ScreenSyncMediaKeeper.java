package org.client.scrcpy;

import android.content.Context;
import android.util.Log;

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

    /**
     * Re-establish authenticated WSS SIGNALING when the device owner opted in.
     * Never starts JPEG capture, an accessibility service or a watch session.
     * Android may kill/freeze the process and disconnect this socket.
     */
    public static synchronized void restoreOptedInSignaling(Context context) {
        if (!NoVpnBackgroundSync.isEnabled(context)
                || !NoVpnApi.isRegistered(context)
                || NoVpnApi.peerId(context).isEmpty()
                || !NoVpnMediaSocket.endpointConfigured()) return;
        if (transport != null) {
            transport.reconnectWhenNetworkAvailable();
            return;
        }
        NoVpnMediaSocket candidate = new NoVpnMediaSocket(context, null);
        try {
            candidate.connect();
            transport = candidate;
            ScreenSyncDiagnostics.record(context, "BACKGROUND_WSS",
                    "owner_enabled_connect");
        } catch (Exception error) {
            Log.w("ScreenSyncWss", "Opted-in connection deferred",
                    error);
            candidate.close();
        }
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
