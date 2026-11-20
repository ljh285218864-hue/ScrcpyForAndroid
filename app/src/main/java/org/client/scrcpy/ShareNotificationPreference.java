package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * An app-only preference for the single "正在共享" notification.
 * The system notification permission, app-level block, channel importance,
 * and any future system restrictions ALWAYS take precedence.
 *
 * This preference never requests/grants notification permission and is not
 * used by pairing, WSS authentication, background presence or reconnect.
 */
public final class ShareNotificationPreference {
    private static final String PREF = "screensync_share_notification_v1";
    private static final String KEY_ENABLED = "app_enabled";

    private ShareNotificationPreference() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(
                PREF, Context.MODE_PRIVATE);
    }

    /** App switch defaults ON; system settings are checked separately. */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** App and OS must BOTH permit a visible stop notification. */
    public static boolean mayPost(Context ctx) {
        return isEnabled(ctx) && AccessibilityJpegStreamer.notificationAllowed(ctx);
    }
}
