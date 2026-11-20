package org.client.scrcpy;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * BEST-EFFORT process-scoped, authenticated HTTPS heartbeat.
 *
 * Unlike a WebSocket foreground service, this cannot guarantee continuous
 * connectivity under Doze/OEM restrictions. When Android freezes or kills
 * our process, the periodic WorkManager/boot recovery path is the fallback.
 *
 * Never polls watch requests, starts capture/ADB, or auto-approves viewing.
 */
final class NoVpnPresenceLoop {
    private static final String TAG = "ScreenSyncPresence";
    private static final Object GUARD = new Object();
    private static ScheduledThreadPoolExecutor executor;
    private static ScheduledFuture<?> scheduled;
    private static Context application;
    private static boolean checking;
    private static boolean immediateAfterCheck;
    private static int consecutiveFailures;
    private static long lastCheckElapsedMs;

    private NoVpnPresenceLoop() {}

    static void start(Context context) {
        Context app = context.getApplicationContext();
        if (!NoVpnBackgroundSync.isEnabled(app) || !NoVpnApi.isRegistered(app))
            return;
        synchronized (GUARD) {
            if (executor != null) return;
            application = app;
            consecutiveFailures = 0;
            lastCheckElapsedMs = 0;
            immediateAfterCheck = false;
            checking = false;
            ThreadFactory factory = job -> {
                Thread thread = new Thread(job, "screensync-presence-heartbeat");
                thread.setDaemon(true);
                return thread;
            };
            executor = new ScheduledThreadPoolExecutor(1, factory);
            executor.setRemoveOnCancelPolicy(true);
            scheduleLocked(0);
        }
    }

    static void stop() {
        synchronized (GUARD) {
            ScheduledThreadPoolExecutor old = executor;
            executor = null;
            application = null;
            checking = false;
            immediateAfterCheck = false;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
            if (old != null) old.shutdownNow();
        }
    }

    static void recheckSoon() {
        synchronized (GUARD) {
            if (executor == null) return;
            long elapsed = SystemClock.elapsedRealtime();
            if (checking) {
                immediateAfterCheck = true;
                return;
            }
            if (lastCheckElapsedMs > 0 && elapsed - lastCheckElapsedMs
                    < NoVpnPresencePolicy.MIN_RECOVERY_GAP_MS)
                return;
            if (scheduled != null) scheduled.cancel(false);
            scheduleLocked(0);
        }
    }

    private static void scheduleLocked(long delayMs) {
        ScheduledThreadPoolExecutor current = executor;
        if (current == null) return;
        scheduled = current.schedule(() -> runOnce(current),
                Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private static void runOnce(ScheduledThreadPoolExecutor owner) {
        Context app;
        synchronized (GUARD) {
            if (executor != owner || checking) return;
            checking = true;
            scheduled = null;
            lastCheckElapsedMs = SystemClock.elapsedRealtime();
            app = application;
        }

        boolean ok = false;
        boolean revoked = false;
        try {
            if (app == null || !NoVpnBackgroundSync.isEnabled(app)
                    || !NoVpnApi.isRegistered(app)) {
                revoked = true;
                return;
            }
            // A token-scoped heartbeat cannot initiate screen acquisition.
            JSONObject response = NoVpnApi.heartbeat(app);
            String expected = NoVpnApi.peerId(app);
            if (!"online".equals(response.optString("state"))
                    || expected.isEmpty()
                    || !expected.equals(response.optString("peer_device_id"))) {
                NoVpnBackgroundSync.record(app, "peer_identity_mismatch", false);
                revoked = true;
            } else {
                NoVpnBackgroundSync.record(app, "last_heartbeat_sent", true);
                ok = true;
            }
        } catch (SecurityException error) {
            NoVpnBackgroundSync.record(app, "identity_mismatch", false);
            revoked = true;
        } catch (IllegalStateException error) {
            if (error.getMessage() != null
                    && error.getMessage().contains("AUTH_REQUIRED")) {
                revoked = true;
                NoVpnBackgroundSync.record(app, "pairing_revoked_by_peer", false);
            } else {
                NoVpnBackgroundSync.record(app, "network_unavailable", false);
            }
        } catch (Exception error) {
            NoVpnBackgroundSync.record(app, "network_unavailable", false);
            Log.d(TAG, "Heartbeat temporarily unavailable");
        } finally {
            if (revoked && app != null) {
                // Stop owner-opted presence only on revoked/invalid identity.
                NoVpnBackgroundSync.setEnabled(app, false);
            }
            synchronized (GUARD) {
                if (executor == owner) {
                    checking = false;
                    if (!NoVpnBackgroundSync.isEnabled(app)) {
                        // setEnabled(false) shuts down this executor.
                        owner.shutdownNow();
                        executor = null;
                        application = null;
                        scheduled = null;
                        immediateAfterCheck = false;
                    } else {
                        consecutiveFailures = ok ? 0
                                : Math.min(10, consecutiveFailures + 1);
                        long delay = NoVpnPresencePolicy.nextDelayMs(consecutiveFailures);
                        if (immediateAfterCheck) {
                            delay = NoVpnPresencePolicy.MIN_RECOVERY_GAP_MS;
                            immediateAfterCheck = false;
                        }
                        scheduleLocked(delay);
                    }
                }
            }
        }
    }
}
