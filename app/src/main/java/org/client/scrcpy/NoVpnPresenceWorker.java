package org.client.scrcpy;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

/**
 * Short, scheduled HTTPS heartbeat and best-effort WSS signaling recovery.
 *
 * Does not capture the screen, approve a request, start a foreground service,
 * open ADB, or post a persistent notification. WorkManager cannot guarantee
 * prompt startup after the user removes the app's recent-task card.
 */
public final class NoVpnPresenceWorker extends Worker {
    public NoVpnPresenceWorker(@NonNull Context context,
                               @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override public Result doWork() {
        Context app = getApplicationContext();
        if (isStopped() || !NoVpnBackgroundSync.isEnabled(app)
                || !NoVpnApi.isRegistered(app)) {
            return Result.success();
        }
        try {
            JSONObject status = NoVpnApi.status(app);
            if (!"paired".equals(status.optString("state"))) {
                NoVpnBackgroundSync.record(app, "waiting_for_approval", false);
                return Result.success();
            }
            if (isStopped()) return Result.retry();
            JSONObject response = NoVpnApi.heartbeat(app);
            if (!"online".equals(response.optString("state"))
                    || !NoVpnApi.peerId(app)
                        .equals(response.optString("peer_device_id"))) {
                NoVpnBackgroundSync.record(app, "peer_identity_mismatch", false);
                return Result.failure();
            }
            NoVpnBackgroundSync.record(app, "last_heartbeat_sent", true);
            // WorkManager can recreate the app process on a later scheduled
            // wake-up. Restore only owner-enabled, already-paired SIGNALING.
            // Screen capture still requires the source's visible STOP control.
            if (!isStopped() && NoVpnBackgroundSync.isEnabled(app)) {
                ScreenSyncMediaKeeper.restoreOptedInSignaling(app);
                ScreenSyncDiagnostics.record(app, "BACKGROUND_WSS",
                        "worker_recovery_attempted");
            }
            return Result.success();
        } catch (SecurityException error) {
            NoVpnBackgroundSync.record(app, "identity_mismatch", false);
            return Result.failure();
        } catch (IllegalStateException error) {
            if ("服务端拒绝：AUTH_REQUIRED".equals(error.getMessage())) {
                // The other device revoked this group. Avoid retry storms;
                // never automatically recreate or reapprove the pairing.
                NoVpnBackgroundSync.setEnabled(app, false);
                NoVpnBackgroundSync.record(app, "pairing_revoked_by_peer", false);
                return Result.success();
            }
            NoVpnBackgroundSync.record(app, "network_unavailable", false);
            return Result.retry();
        } catch (Exception error) {
            // Temporary radio/network errors are retried by WorkManager
            // while it can run. No unbounded process loop or foreground task.
            NoVpnBackgroundSync.record(app, "network_unavailable", false);
            return Result.retry();
        }
    }
}
