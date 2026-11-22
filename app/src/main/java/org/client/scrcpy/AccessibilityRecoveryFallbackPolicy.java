package org.client.scrcpy;

/**
 * Distinguishes a once-running permission which needs local attention from
 * an app that has never been granted access.
 *
 * Android does not reveal whether its accessibility toggle was changed by a
 * human or an OEM; this state is never permission to change that toggle.
 */
final class AccessibilityRecoveryFallbackPolicy {
    private AccessibilityRecoveryFallbackPolicy() {}

    static boolean shouldMarkLocalRecovery(boolean previouslyRunning,
            boolean ownerStillApproved, AccessibilityStatePolicy.State state) {
        return previouslyRunning
                && ownerStillApproved
                && state == AccessibilityStatePolicy.State.DISABLED;
    }

    static boolean shouldDisplayLocalRecovery(boolean recoveryPending,
            boolean ownerStillApproved, AccessibilityStatePolicy.State state) {
        return recoveryPending
                && ownerStillApproved
                && state == AccessibilityStatePolicy.State.DISABLED;
    }
}
