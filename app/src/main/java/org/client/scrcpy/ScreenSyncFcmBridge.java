package org.client.scrcpy;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional FCM normal-priority data wake for approved pairing SIGNALING ONLY.
 * No notification, permission dialog, activity launch or screenshot happens
 * here. This feature is off unless the owner enabled background connectivity.
 */
public final class ScreenSyncFcmBridge {
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static volatile boolean initialized;

    private ScreenSyncFcmBridge() { }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        if (BuildConfig.FCM_APP_ID.isEmpty()
                || BuildConfig.FCM_PROJECT_ID.isEmpty()
                || BuildConfig.FCM_SENDER_ID.isEmpty()
                || BuildConfig.FCM_API_KEY.isEmpty()) return;
        try {
            Context app = context.getApplicationContext();
            if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .build();
                FirebaseApp.initializeApp(app, options);
            }
            initialized = true;
        } catch (RuntimeException error) {
            ScreenSyncDiagnostics.record(context, "FCM_INIT",
                    "failed=" + error.getClass().getSimpleName());
        }
    }

    public static boolean configured() {
        return initialized;
    }

    public static void registerIfOptedIn(Context context) {
        if (!NoVpnBackgroundSync.isEnabled(context)
                || !NoVpnApi.isRegistered(context)
                || NoVpnApi.peerId(context).isEmpty()) return;
        initialize(context);
        if (!initialized) return;
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> registerToken(context, token))
                .addOnFailureListener(error ->
                        ScreenSyncDiagnostics.record(context,
                                "FCM_TOKEN", "unavailable"));
    }

    static void registerToken(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        Context app = context.getApplicationContext();
        if (!NoVpnBackgroundSync.isEnabled(app)
                || !NoVpnApi.isRegistered(app)
                || NoVpnApi.peerId(app).isEmpty()) return;
        NETWORK.execute(() -> {
            if (!NoVpnBackgroundSync.isEnabled(app)) return;
            try {
                NoVpnApi.registerPushToken(app, token);
                ScreenSyncDiagnostics.record(app, "FCM_TOKEN", "registered");
            } catch (Exception failure) {
                ScreenSyncDiagnostics.record(app, "FCM_TOKEN", "register_failed");
            }
        });
    }

    public static void unregisterDevice(Context context) {
        Context app = context.getApplicationContext();
        if (!NoVpnApi.isRegistered(app)) return;
        NETWORK.execute(() -> {
            try {
                NoVpnApi.clearPushToken(app);
                ScreenSyncDiagnostics.record(app, "FCM_TOKEN", "cleared");
            } catch (Exception failure) {
                ScreenSyncDiagnostics.record(app, "FCM_TOKEN", "clear_deferred");
            }
        });
    }

    static void handleDataWake(Context context, RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (!"screensync_wake".equals(data.get("type"))) return;
        Context app = context.getApplicationContext();
        if (!NoVpnBackgroundSync.isEnabled(app)
                || !NoVpnApi.isRegistered(app)
                || !NoVpnApi.groupId(app).equals(data.get("group_id"))
                || !NoVpnApi.peerId(app).equals(data.get("from_device_id"))) {
            ScreenSyncDiagnostics.record(app, "FCM_WAKE", "ignored_unpaired_or_disabled");
            return;
        }
        // Keep this callback short. FCM does not grant permission to record
        // another app, restart an accessibility service or open an Activity.
        ScreenSyncDiagnostics.record(app, "FCM_WAKE", "signaling_reconnect");
        ScreenSyncMediaKeeper.restoreOptedInSignaling(app);
        NoVpnBackgroundSync.requestOnce(app, "fcm_wake");
    }
}
