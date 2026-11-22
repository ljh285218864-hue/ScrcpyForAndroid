package org.client.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AccessibilityStatePolicyTest {
    @Test public void runningServiceIsHealthy() {
        assertEquals(AccessibilityStatePolicy.State.RUNNING,
                AccessibilityStatePolicy.evaluate(true, true));
    }

    @Test public void systemEnabledButProcessUnboundDoesNotMeanRevoked() {
        assertEquals(AccessibilityStatePolicy.State.ENABLED_NOT_CONNECTED,
                AccessibilityStatePolicy.evaluate(true, false));
    }

    @Test public void userDisabledStateIsNotAutomaticallyReEnabled() {
        assertEquals(AccessibilityStatePolicy.State.DISABLED,
                AccessibilityStatePolicy.evaluate(false, false));
    }

    @Test public void failedSystemQueryIsUnknownNotUserRevocation() {
        assertEquals(AccessibilityStatePolicy.State.UNKNOWN,
                AccessibilityStatePolicy.evaluate(null, false));
    }

    @Test public void boundServiceWinsDuringTransientSettingsTransition() {
        assertEquals(AccessibilityStatePolicy.State.RUNNING,
                AccessibilityStatePolicy.evaluate(null, true));
    }
}
