package org.client.scrcpy;

/**
 * Consent and OS-state gate for an accessibility-service reconnect.
 *
 * Never interprets a disconnected process as a new permission grant and never
 * enables the AccessibilityService or starts screen capture. The sole
 * permitted action is restoring authenticated, opted-in signaling.
 */
final class AccessibilityReconnectPolicy {
    private AccessibilityReconnectPolicy() {}

    static boolean shouldRestoreSignaling(AccessibilityStatePolicy.State state,
            boolean ownerEnabledBackground, boolean stillPaired,
            boolean ownerApprovedLongTermViewing) {
        return state == AccessibilityStatePolicy.State.RUNNING
                && ownerEnabledBackground && stillPaired
                && ownerApprovedLongTermViewing;
    }
}
