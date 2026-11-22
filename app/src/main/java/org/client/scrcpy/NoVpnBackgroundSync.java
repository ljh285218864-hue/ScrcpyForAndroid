package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Best-effort, permission-respecting no-VPN presence reconciliation.
 *
 * This scheduler never starts any foreground service, foreground notification,
 * scrcpy/ADB, remote control, MediaProjection, or screen capture.
 *
 * Android persists periodic WorkManager requests across normal process death
 * and reboot, but Android/OEM Doze and force-stop can defer or prevent runs.
 * The supported minimum periodic interval is 15 minutes, not real-time.
 */
public final class NoVpnBackgroundSync {
    static final String UNIQUE_PERIODIC = "ss_novpn_presence_v1";
    static final String UNIQUE_ONCE = "ss_novpn_recheck_v1";
    private static final String PREF = "screensync_novpn_background_v1";
    private static final String ENABLED = "enabled";
    private static final String STATE = "state";
    private static final String LAST_ATTEMPT = "attempt_at";
    private static final String LAST_SUCCESS = "success_at";
    private static final String LAST_ERROR = "error";
    private static final long MIN_TRIGGER_ELAPSED_MS = 30000L;

    private static final Object GUARD = new Object();
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static long lastTriggerElapsed;

    private NoVpnBackgroundSync() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF,
                Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(ENABLED, false);
    }

    static long lastSuccess(Context context) {
        return prefs(context).getLong(LAST_SUCCESS, 0L);
    }

    static String state(Context context) {
        return prefs(context).getString(STATE, "disabled");
    }

    static void record(Context context, String state, boolean success) {
        long time = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(STATE, state)
                .putLong(LAST_ATTEMPT, time);
        if (success) {
            editor.putLong(LAST_SUCCESS, time).remove(LAST_ERROR);
        } else {
            editor.putString(LAST_ERROR, state);
        }
        editor.apply();
    }

    /**
     * Called at normal app startup. Do not start a long-running service.
     * WorkManager itself restores scheduled work after normal device reboot.
     */
    static void restore(Context context) {
        if (!isEnabled(context) || !NoVpnApi.isRegistered(context)) return;
        schedule(context);
        NoVpnStandbyAlarm.schedule(context);
        ensureNetworkListener(context);
        NoVpnPresenceLoop.start(context);
        ScreenSyncMediaKeeper.restoreOptedInSignaling(context);
    }

    /** Invoked by the device owner via the pairing UI after mutual approval. */
    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled)
                .putString(STATE, enabled ? "scheduled" : "disabled").commit();
        if (enabled && NoVpnApi.isRegistered(context)) {
            schedule(context);
            NoVpnStandbyAlarm.schedule(context);
            ensureNetworkListener(context);
            NoVpnPresenceLoop.start(context);
            ScreenSyncMediaKeeper.restoreOptedInSignaling(context);
            requestOnce(context, "owner_enabled");
        } else {
            WorkManager manager = WorkManager.getInstance(context);
            manager.cancelUniqueWork(UNIQUE_PERIODIC);
            manager.cancelUniqueWork(UNIQUE_ONCE);
            NoVpnStandbyAlarm.cancel(context);
            stopNetworkListener(context);
            NoVpnPresenceLoop.stop();
        }
    }

    private static Constraints online() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    static void schedule(Context context) {
        if (!isEnabled(context) || !NoVpnApi.isRegistered(context)) return;
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                NoVpnPresenceWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(online())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    /**
     * Called while our own process is running and an ordinary internet
     * underlay reappears. Do not hammer the server after rapid Wi-Fi/4G churn.
     */
    static void requestOnce(Context context, String reason) {
        if (!isEnabled(context) || !NoVpnApi.isRegistered(context)) return;
        synchronized (GUARD) {
            long now = SystemClock.elapsedRealtime();
            if (lastTriggerElapsed > 0
                    && now - lastTriggerElapsed < MIN_TRIGGER_ELAPSED_MS) return;
            lastTriggerElapsed = now;
        }
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(
                NoVpnPresenceWorker.class)
                .setConstraints(online())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ss_novpn_" + reason)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE, ExistingWorkPolicy.KEEP, work);
    }

    private static void ensureNetworkListener(Context context) {
        synchronized (GUARD) {
            if (networkCallback != null) return;
            Context application = context.getApplicationContext();
            ConnectivityManager manager = (ConnectivityManager)
                    application.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return;
            ConnectivityManager.NetworkCallback callback =
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(Network network) {
                            NoVpnPresenceLoop.recheckSoon();
                            ScreenSyncMediaKeeper.restoreOptedInSignaling(application);
                            requestOnce(application, "network_return");
                        }
                        @Override public void onCapabilitiesChanged(Network network,
                                NetworkCapabilities caps) {
                            if (caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                NoVpnPresenceLoop.recheckSoon();
                                ScreenSyncMediaKeeper.restoreOptedInSignaling(application);
                                requestOnce(application, "network_changed");
                            }
                        }
                    };
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build();
                manager.registerNetworkCallback(request, callback);
                networkCallback = callback;
            } catch (RuntimeException error) {
                Log.w("ScreenSyncNoVpn", "Network observer unavailable");
            }
        }
    }

    private static void stopNetworkListener(Context context) {
        synchronized (GUARD) {
            if (networkCallback == null) return;
            ConnectivityManager manager = (ConnectivityManager) context
                    .getApplicationContext().getSystemService(
                            Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                try {
                    manager.unregisterNetworkCallback(networkCallback);
                } catch (RuntimeException ignored) { }
            }
            networkCallback = null;
        }
    }
}
