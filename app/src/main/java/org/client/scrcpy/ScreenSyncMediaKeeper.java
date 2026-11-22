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
    private static final WatchSessionController watchController =
            new WatchSessionController();
    private static NoVpnMediaSocket approvedSourceTransport;
    private static WatchSessionController.CaptureBridge approvedSourceBridge;
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

    /** Starts only an authenticated, currently approved request for the paired viewer. */
    static synchronized boolean startPersistentlyApprovedViewing(
            Context context, NoVpnMediaSocket media) {
        if (media == null || !media.hasApprovedSourceSession()
                || !LongTermSharePolicy.mayAutoApprove(context)) return false;
        if (approvedSourceBridge != null && !AccessibilityJpegStreamer.sharingActive()) {
            watchController.exitWatch(approvedSourceBridge);
            approvedSourceBridge = null;
            approvedSourceTransport = null;
        }
        if (approvedSourceBridge != null || AccessibilityJpegStreamer.sharingActive())
            return false;
        final Context app = context.getApplicationContext();
        final String peer = NoVpnApi.peerId(app);
        WatchSessionController.CaptureBridge bridge =
                new WatchSessionController.CaptureBridge() {
                    private AccessibilityJpegStreamer source;
                    @Override public boolean hasSystemCaptureAccess() {
                        return AccessibilityShotService.ready();
                    }
                    @Override public boolean startCaptureWithVisibleStopControl() {
                        // start() posts the live stop notification before any
                        // video setup or screenshot; failure blocks capture.
                        source = new AccessibilityJpegStreamer(app, media, reason ->
                                ScreenSyncDiagnostics.record(app,
                                        "PERSISTENT_SHARE_STOP", reason));
                        return source.start();
                    }
                    @Override public boolean isWatchingStateAndStopControlAvailable() {
                        return AccessibilityJpegStreamer.hasVisibleStopControl();
                    }
                    @Override public void stopCaptureForCurrentSession() {
                        if (source != null) source.stop();
                    }
                    @Override public void hideWatchingStateAndStopControl() {
                        // AccessibilityJpegStreamer.stop() owns notification cleanup.
                    }
                };
        WatchSessionController.StartResult result = watchController.requestWatch(
                app, peer, bridge);
        ScreenSyncDiagnostics.record(app, "LONG_TERM_WATCH_START", result.name());
        if (result != WatchSessionController.StartResult.STARTED) return false;
        approvedSourceTransport = media;
        approvedSourceBridge = bridge;
        return true;
    }

    /** Ends capture for this viewer session, but not the persistent grant/WSS. */
    static synchronized void stopPersistentlyApprovedViewing(NoVpnMediaSocket media) {
        if (approvedSourceTransport != media || approvedSourceBridge == null) return;
        watchController.exitWatch(approvedSourceBridge);
        approvedSourceTransport = null;
        approvedSourceBridge = null;
    }

    public static synchronized void stop() {
        if (transport == null) return;
        NoVpnMediaSocket old = transport;
        transport = null;
        stopPersistentlyApprovedViewing(old);
        AccessibilityJpegStreamer.stopActiveWithoutSending();
        old.close();
    }
}
