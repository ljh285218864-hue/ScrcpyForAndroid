package org.client.scrcpy;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.wireguard.android.backend.GoBackend;

/**
 * Reconnects ONLY a previously approved Beta4 VPN after network recovery.
 *
 * Does not grant VPN, ADB or screen-capture permissions, enable debugging,
 * automatically start a receiver, or initiate screen viewing. The system may
 * still revoke authorization; in that case the app must explain that
 * reauthorization is required and stop retrying.
 *
 * For reboot/background persistence the user may enable Android's system
 * Always-on VPN setting once. No BOOT_COMPLETED foreground-service launch.
 */
public final class RemoteConnectionRecovery {
    private static final String PREF = "screensync_recovery_v4";
    private static final String ENABLED = "enabled_by_owner";
    private static final String LAST_STATUS = "last_state";
    private static final String LAST_REASON = "last_trigger";
    private static final long DEBOUNCE_MS = 2500L;
    private static final long MIN_RETRY_MS = 30000L;
    private static final Object GUARD = new Object();

    private static Context app;
    private static ConnectivityManager connectivity;
    private static ConnectivityManager.NetworkCallback callback;
    private static Handler handler;
    private static boolean attempting;
    private static long lastAttemptElapsed;
    private static final Runnable RECONNECT = RemoteConnectionRecovery::reconcile;

    private RemoteConnectionRecovery() { }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    public static String lastState(Context context) {
        return preferences(context).getString(LAST_STATUS, "not_enabled");
    }

    /**
     * Only call after the user explicitly enabled this VPN and the system
     * confirmed its consent. An explicit disconnect/revoke disables recovery.
     */
    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled)
                .putString(LAST_STATUS, enabled ? "armed" : "disabled")
                .apply();
        synchronized (GUARD) {
            if (app == null) {
                app = context.getApplicationContext();
                handler = new Handler(Looper.getMainLooper());
            }
        }
        if (enabled) {
            ensureNetworkListener();
        } else {
            synchronized (GUARD) {
                if (handler != null) handler.removeCallbacks(RECONNECT);
            }
        }
    }

    /** Called on Application creation, including system Always-on VPN starts. */
    public static void initialize(Application application) {
        synchronized (GUARD) {
            app = application.getApplicationContext();
            if (handler == null) handler = new Handler(Looper.getMainLooper());
        }
        // The WireGuard library invokes this only when Android starts its
        // authorized, Always-on VpnService. Not a new system authorization.
        GoBackend.setAlwaysOnCallback(() -> requestReconnect("system_always_on"));
        if (isEnabled(application)) ensureNetworkListener();
    }

    private static void ensureNetworkListener() {
        synchronized (GUARD) {
            if (app == null || callback != null) return;
            connectivity = (ConnectivityManager) app.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return;
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    requestReconnect("underlay_available");
                }
                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        requestReconnect("underlay_changed");
                    }
                }
            };
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build();
                connectivity.registerNetworkCallback(request, callback);
            } catch (RuntimeException error) {
                Log.w("ScreenSyncRecovery", "Could not register underlay network callback");
                callback = null;
            }
        }
    }

    public static void requestReconnect(String reason) {
        synchronized (GUARD) {
            if (app == null || handler == null || !isEnabled(app)) return;
            preferences(app).edit().putString(LAST_REASON, reason).apply();
            handler.removeCallbacks(RECONNECT);
            handler.postDelayed(RECONNECT, DEBOUNCE_MS);
        }
    }

    private static void reconcile() {
        final Context context;
        synchronized (GUARD) {
            context = app;
            if (context == null || !isEnabled(context) || attempting) return;
            if (EmbeddedRemoteTunnel.isActive() || EmbeddedRemoteTunnel.isStarting()) {
                preferences(context).edit().putString(LAST_STATUS, "vpn_running").apply();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (lastAttemptElapsed > 0 && now - lastAttemptElapsed < MIN_RETRY_MS) {
                long remaining = MIN_RETRY_MS - (now - lastAttemptElapsed);
                handler.removeCallbacks(RECONNECT);
                handler.postDelayed(RECONNECT, remaining);
                return;
            }
            lastAttemptElapsed = now;
            attempting = true;
        }

        try {
            if (!AutoRemoteProvisioner.isRegistered(context) ||
                    !EmbeddedRemoteTunnel.hasConfig(context)) {
                state(context, "pairing_or_config_missing");
                synchronized (GUARD) { attempting = false; }
                return;
            }
            // Never launch Android VPN permission UI from the background.
            // Only the system/user can grant/re-grant this permission.
            if (VpnService.prepare(context) != null) {
                state(context, "vpn_consent_required");
                synchronized (GUARD) { attempting = false; }
                return;
            }
            state(context, "reconnecting");
            EmbeddedRemoteTunnel.start(context, (connected, message) -> {
                state(context, connected ? "vpn_running" : "reconnect_failed");
                synchronized (GUARD) { attempting = false; }
            });
        } catch (RuntimeException error) {
            state(context, "reconnect_failed");
            synchronized (GUARD) { attempting = false; }
        }
    }

    private static void state(Context context, String value) {
        preferences(context).edit().putString(LAST_STATUS, value).apply();
    }
}
