package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

/** Opt-in, revocable consent tied to one approved pair, default OFF.
 * Requires a visible accessibility standby overlay before auto-acceptance.
 */
public final class LongTermSharePolicy {
    private static final String PREF = "ss_long_term_share_v1";
    private LongTermSharePolicy() {}
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    public static boolean enabled(Context ctx) {
        if (!NoVpnApi.isRegistered(ctx)) return false;
        String source = NoVpnApi.deviceId(ctx), peer = NoVpnApi.peerId(ctx),
                group = NoVpnApi.groupId(ctx);
        if (source.isEmpty() || peer.isEmpty() || group.isEmpty()) return false;
        SharedPreferences p = prefs(ctx);
        return p.getBoolean("enabled", false)
                && source.equals(p.getString("source", ""))
                && peer.equals(p.getString("peer", ""))
                && group.equals(p.getString("group", ""));
    }
    /** Invoke ONLY following affirmative consent in the local owner's UI. */
    public static boolean enableByOwner(Context ctx) {
        if (!NoVpnApi.isRegistered(ctx) || NoVpnApi.peerId(ctx).isEmpty()
                || !AccessibilityShotService.ready()
                || !AccessibilityShotService.showLongTermReadyOverlay()) return false;
        boolean ok = prefs(ctx).edit().putBoolean("enabled", true)
                .putString("source", NoVpnApi.deviceId(ctx))
                .putString("peer", NoVpnApi.peerId(ctx))
                .putString("group", NoVpnApi.groupId(ctx)).commit();
        if (!ok) AccessibilityShotService.hideLongTermReadyOverlay();
        return ok;
    }
    public static void disable(Context ctx) {
        prefs(ctx).edit().clear().commit();
        AccessibilityShotService.hideLongTermReadyOverlay();
    }
    /** Called by Android after it binds the user's accessibility service. */
    public static void showStandbyWhenAllowed(Context ctx) {
        if (enabled(ctx) && !AccessibilityJpegStreamer.sharingActive())
            AccessibilityShotService.showLongTermReadyOverlay();
        else if (!enabled(ctx))
            AccessibilityShotService.hideLongTermReadyOverlay();
    }
    /** Fails closed unless local, user-visible grant is currently active. */
    public static boolean mayAutoApprove(Context ctx) {
        return enabled(ctx) && NoVpnBackgroundSync.isEnabled(ctx)
                && AccessibilityShotService.ready()
                && AccessibilityShotService.isLongTermReadyOverlayVisible();
    }
}
