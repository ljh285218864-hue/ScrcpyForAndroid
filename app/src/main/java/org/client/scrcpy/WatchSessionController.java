package org.client.scrcpy;

import android.content.Context;
import android.util.Log;

/**
 * Separates a long-lived pairing/permission from short-lived viewing sessions.
 *
 * Keep one controller instance in the owner of the capture service, NOT in an
 * Activity. A viewer's Exit action stops only the current stream, never pairing
 * or the saved permission. This controller never starts screen capture at boot.
 */
public final class WatchSessionController {
    private static final String TAG = "WatchSessionController";

    public enum StartResult {
        STARTED,
        ALREADY_WATCHING,
        PERMISSION_REQUIRED,
        SYSTEM_CAPTURE_UNAVAILABLE,
        VISIBLE_STOP_UNAVAILABLE,
        CAPTURE_FAILED
    }

    /**
     * Adapter to the existing capture and visible-stop implementations.
     * The visible control must actually work; never return true artificially.
     */
    public interface CaptureBridge {
        boolean hasSystemCaptureAccess();
        boolean showWatchingStateAndStopControl();
        boolean isWatchingStateAndStopControlAvailable();
        boolean startCaptureForCurrentSession();
        void stopCaptureForCurrentSession();
        void hideWatchingStateAndStopControl();
    }

    private String activePeerId;
    private boolean watching;

    /** A verified and paired device requests a new, locally pre-approved view. */
    public synchronized StartResult requestWatch(
            Context context, String authenticatedPeerId, CaptureBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("capture bridge is required");
        }
        if (watching) {
            return activePeerId != null
                    && activePeerId.equals(authenticatedPeerId)
                    ? StartResult.ALREADY_WATCHING : StartResult.CAPTURE_FAILED;
        }
        if (!PersistentWatchConsent.permitsWatch(context, authenticatedPeerId)) {
            return StartResult.PERMISSION_REQUIRED;
        }
        if (!bridge.hasSystemCaptureAccess()) {
            return StartResult.SYSTEM_CAPTURE_UNAVAILABLE;
        }

        boolean started = false;
        try {
            if (!bridge.showWatchingStateAndStopControl()
                    || !bridge.isWatchingStateAndStopControlAvailable()) {
                return StartResult.VISIBLE_STOP_UNAVAILABLE;
            }
            if (!bridge.startCaptureForCurrentSession()) {
                return StartResult.CAPTURE_FAILED;
            }
            if (!bridge.isWatchingStateAndStopControlAvailable()) {
                return StartResult.VISIBLE_STOP_UNAVAILABLE;
            }
            activePeerId = authenticatedPeerId;
            watching = true;
            started = true;
            return StartResult.STARTED;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start visible viewing session", error);
            return StartResult.CAPTURE_FAILED;
        } finally {
            if (!started) {
                stopAndHide(bridge);
            }
        }
    }

    /**
     * Viewer presses Exit: end media only. Pairing, network wake credentials,
     * and saved approval stay untouched.
     */
    public synchronized void exitWatch(CaptureBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("capture bridge is required");
        }
        if (!watching) return;
        watching = false;
        activePeerId = null;
        stopAndHide(bridge);
    }

    /**
     * Call periodically during active capture. A failed visibility check must
     * end the current stream; it must not silently continue capture.
     */
    public synchronized boolean checkVisibleStopOrEnd(CaptureBridge bridge) {
        if (!watching) return true;
        boolean visible;
        try {
            visible = bridge.isWatchingStateAndStopControlAvailable();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to verify active sharing control", error);
            visible = false;
        }
        if (!visible) exitWatch(bridge);
        return visible;
    }

    /** Local device owner explicitly stops future viewing until re-approved. */
    public synchronized boolean revokeAndStop(
            Context context, CaptureBridge bridge) {
        exitWatch(bridge);
        return PersistentWatchConsent.revokeFromLocalUserAction(context);
    }

    public synchronized boolean isWatching() {
        return watching;
    }

    private static void stopAndHide(CaptureBridge bridge) {
        try {
            bridge.stopCaptureForCurrentSession();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to stop capture", error);
        } finally {
            try {
                bridge.hideWatchingStateAndStopControl();
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to hide stopped sharing control", error);
            }
        }
    }
}
