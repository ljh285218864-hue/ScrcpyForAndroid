package org.client.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AccessibilityRecoveryFallbackPolicyTest {
    @Test public void markOnlyWhenPreviouslyRunningAndOwnerStillApproved() {
        assertTrue(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                true, true, AccessibilityStatePolicy.State.DISABLED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                false, true, AccessibilityStatePolicy.State.DISABLED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                true, false, AccessibilityStatePolicy.State.DISABLED));
    }

    @Test public void dontTreatTemporaryUnbindOrUnknownAsClosedPermission() {
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                true, true, AccessibilityStatePolicy.State.ENABLED_NOT_CONNECTED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                true, true, AccessibilityStatePolicy.State.UNKNOWN));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldMarkLocalRecovery(
                true, true, AccessibilityStatePolicy.State.RUNNING));
    }

    @Test public void showOnlyWhileOwnerActionIsNeeded() {
        assertTrue(AccessibilityRecoveryFallbackPolicy.shouldDisplayLocalRecovery(
                true, true, AccessibilityStatePolicy.State.DISABLED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldDisplayLocalRecovery(
                false, true, AccessibilityStatePolicy.State.DISABLED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldDisplayLocalRecovery(
                true, false, AccessibilityStatePolicy.State.DISABLED));
        assertFalse(AccessibilityRecoveryFallbackPolicy.shouldDisplayLocalRecovery(
                true, true, AccessibilityStatePolicy.State.RUNNING));
    }
}
