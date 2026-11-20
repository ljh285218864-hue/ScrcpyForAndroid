package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

/** Opt-in, revocable consent tied to one approved pair, default OFF.
 * The owner's permission is visible in app settings; media sessions must
 * display exactly one active sharing indicator with an immediate STOP action.
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
                || !AccessibilityShotService.ready()) return false;
        return prefs(ctx).edit().putBoolean("enabled", true)
                .putString("source", NoVpnApi.deviceId(ctx))
                .putString("peer", NoVpnApi.peerId(ctx))
                .putString("group", NoVpnApi.groupId(ctx)).commit();
    }
    public static void disable(Context ctx) {
        prefs(ctx).edit().clear().commit();
    }
    /** Only the still-paired, explicitly owner-enabled grant may approve.
     * Its corresponding JPEG source must show its live STOP indicator before
     * the first video_setup/frame is sent, or capture must fail closed.
     */
    public static boolean mayAutoApprove(Context ctx) {
        return enabled(ctx) && NoVpnBackgroundSync.isEnabled(ctx)
                && AccessibilityShotService.ready();
    }
}
