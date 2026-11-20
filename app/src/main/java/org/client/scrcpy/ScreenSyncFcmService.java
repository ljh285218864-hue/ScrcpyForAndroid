package org.client.scrcpy;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * FCM delivers a data-only wake if Play services and OS policies permit.
 * Never shows an app notification or initiates background capture.
 */
public final class ScreenSyncFcmService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        ScreenSyncFcmBridge.registerToken(getApplicationContext(), token);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        ScreenSyncFcmBridge.handleDataWake(getApplicationContext(), message);
    }
}
