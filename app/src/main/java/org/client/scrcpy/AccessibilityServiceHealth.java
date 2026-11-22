package org.client.scrcpy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Passive status observer for the user's accessibility permission.
 *
 * Does NOT call disableSelf(), write Settings.Secure, start an Activity in the
 * background, or silently enable/re-enable an accessibility service.
 * Android manages binding of services that remain enabled by the user.
 */
public final class AccessibilityServiceHealth {
    private static final String TAG = "ScreenSyncAccessHealth";
    private static final String PREFS = "ss_accessibility_health_v1";
    private static final String LAST_CONNECTED = "connected_at";
    private static final String LAST_DISCONNECTED = "disconnected_at";
    private static ContentObserver settingsObserver;
    private static AccessibilityStatePolicy.State lastLoggedState;

    private AccessibilityServiceHealth() { }

    /**
     * Is THIS application's service enabled in Android settings?
     * Null means the status could not be inspected and must not be treated
     * as a deliberate revocation.
     */
    public static Boolean systemEnabled(Context context) {
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getApplicationContext().getSystemService(
                            Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return null;
            List<AccessibilityServiceInfo> enabled = manager
                    .getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return null;
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null) continue;
                ResolveInfo resolved = info.getResolveInfo();
                ServiceInfo service = resolved == null ? null : resolved.serviceInfo;
                if (service != null
                        && context.getPackageName().equals(service.packageName)
                        && AccessibilityShotService.class.getName().equals(
                                service.name.startsWith(".")
                                        ? service.packageName + service.name
                                        : service.name)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to inspect accessibility service settings", error);
            return null;
        }
    }

    public static AccessibilityStatePolicy.State state(Context context) {
        return AccessibilityStatePolicy.evaluate(systemEnabled(context),
                AccessibilityShotService.ready());
    }

    /** Observes genuine settings changes while the application process lives. */
    public static synchronized void start(Context context) {
        if (settingsObserver != null) return;
        final Context app = context.getApplicationContext();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                checkAndRecord(app, "system_settings_changed");
            }
        };
        try {
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
            settingsObserver = observer;
        } catch (RuntimeException error) {
            app.getContentResolver().unregisterContentObserver(observer);
            Log.w(TAG, "Unable to observe accessibility settings", error);
        }
        checkAndRecord(app, "process_started");
    }

    static void onServiceConnected(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit()
                .putLong(LAST_CONNECTED, System.currentTimeMillis()).apply();
        checkAndRecord(context, "service_connected");
    }

    static void onServiceDisconnected(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).edit()
                .putLong(LAST_DISCONNECTED, System.currentTimeMillis()).apply();
        checkAndRecord(context, "service_disconnected");
    }

    public static long lastConnectedAtMillis(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).getLong(LAST_CONNECTED, 0L);
    }

    public static long lastDisconnectedAtMillis(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).getLong(LAST_DISCONNECTED, 0L);
    }

    /** Log transitions only. No automatic permission toggling or capture. */
    public static synchronized void checkAndRecord(Context context, String trigger) {
        AccessibilityStatePolicy.State next = state(context);
        // Keep the local recovery flag in sync even if the OS state did not
        // change, e.g. after the owner opted in during the same process.
        AccessibilityRecoveryFallback.observe(context, next);
        if (next == lastLoggedState) return;
        lastLoggedState = next;
        ScreenSyncDiagnostics.record(context, "ACCESSIBILITY_HEALTH",
                "state=" + next.name() + " trigger=" + trigger);
        if (next == AccessibilityStatePolicy.State.DISABLED) {
            // The Android system is authoritative: never keep a source stream
            // alive if its screenshot permission has been revoked.
            AccessibilityJpegStreamer.stopActiveOnAccessibilityLost();
        }
    }
}
