package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores a user's explicit, long-lived approval of a paired remote viewer.
 *
 * IMPORTANT:
 * - grantAfterExplicitLocalApproval() must ONLY be called after a deliberate
 *   action on the device that may be viewed. Never call it from a remote
 *   message, receiver, boot handler, or background job.
 * - The peer ID must come from an authenticated pairing protocol, not an
 *   untrusted string sent in a watch request.
 * - This class does not grant Android Accessibility or screen-capture rights.
 * - Do not include this preference file in Android Auto Backup or device
 *   transfer backups; a restored install must require fresh local approval.
 */
public final class PersistentWatchConsent {
    private static final String PREFS = "screensync_persistent_watch_consent_v1";
    private static final String KEY_APPROVED = "approved";
    private static final String KEY_PEER_ID = "authenticated_peer_id";
    private static final String KEY_APPROVED_AT = "approved_at_ms";

    private PersistentWatchConsent() {}

    private static SharedPreferences preferences(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Persist consent until the local user revokes it.
     * Returns false if Android did not persist the change.
     */
    public static synchronized boolean grantAfterExplicitLocalApproval(
            Context context, String authenticatedPeerId) {
        String peerId = normalizePeerId(authenticatedPeerId);
        return preferences(context).edit()
                .putString(KEY_PEER_ID, peerId)
                .putLong(KEY_APPROVED_AT, System.currentTimeMillis())
                .putBoolean(KEY_APPROVED, true)
                .commit();
    }

    /**
     * Only an authenticated request from the approved paired peer is eligible
     * to start an authorized and visibly indicated viewing session.
     */
    public static synchronized boolean permitsWatch(
            Context context, String authenticatedPeerId) {
        if (authenticatedPeerId == null || authenticatedPeerId.trim().isEmpty()) {
            return false;
        }
        SharedPreferences p = preferences(context);
        return p.getBoolean(KEY_APPROVED, false)
                && authenticatedPeerId.trim()
                        .equals(p.getString(KEY_PEER_ID, null));
    }

    /** Revoke ongoing permission; the caller must also stop any active stream. */
    public static synchronized boolean revokeFromLocalUserAction(Context context) {
        return preferences(context).edit().clear().commit();
    }

    public static synchronized long approvedAtMillis(Context context) {
        SharedPreferences p = preferences(context);
        return p.getBoolean(KEY_APPROVED, false)
                ? p.getLong(KEY_APPROVED_AT, 0L) : 0L;
    }

    private static String normalizePeerId(String peerId) {
        if (peerId == null || peerId.trim().isEmpty()) {
            throw new IllegalArgumentException("authenticatedPeerId is required");
        }
        return peerId.trim();
    }
}
