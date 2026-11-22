package org.client.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AccessibilityReconnectPolicyTest {
    @Test public void restoreSignalingOnlyForRunningOptedInApprovedPair() {
        assertTrue(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.RUNNING, true, true, true));
    }

    @Test public void neverRestoreIfSystemServiceIsUnbound() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.ENABLED_NOT_CONNECTED, true, true, true));
    }

    @Test public void neverRestoreIfOwnerDisabledAccessibility() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.DISABLED, true, true, true));
    }

    @Test public void unknownSystemStateIsNotPermission() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.UNKNOWN, true, true, true));
    }

    @Test public void noRestoreWithoutOwnerBackgroundChoice() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.RUNNING, false, true, true));
    }

    @Test public void noRestoreAfterUnpairing() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.RUNNING, true, false, true));
    }

    @Test public void noRestoreAfterLongTermConsentRevocation() {
        assertFalse(AccessibilityReconnectPolicy.shouldRestoreSignaling(
                AccessibilityStatePolicy.State.RUNNING, true, true, false));
    }
}
