package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class NoVpnPresencePolicyTest {
    @Test public void healthyHeartbeatEveryThirtySeconds() {
        assertEquals(30_000L, NoVpnPresencePolicy.nextDelayMs(0));
        assertEquals(30_000L, NoVpnPresencePolicy.nextDelayMs(-1));
    }

    @Test public void failuresBackOffAndCapAtFiveMinutes() {
        assertEquals(10_000L, NoVpnPresencePolicy.nextDelayMs(1));
        assertEquals(20_000L, NoVpnPresencePolicy.nextDelayMs(2));
        assertEquals(40_000L, NoVpnPresencePolicy.nextDelayMs(3));
        assertEquals(80_000L, NoVpnPresencePolicy.nextDelayMs(4));
        assertEquals(160_000L, NoVpnPresencePolicy.nextDelayMs(5));
        assertEquals(300_000L, NoVpnPresencePolicy.nextDelayMs(6));
        assertEquals(300_000L, NoVpnPresencePolicy.nextDelayMs(12));
    }

    @Test public void onlineRequiresRecentConfirmedHeartbeat() {
        assertFalse(NoVpnPresencePolicy.recentlyConfirmed(100_000L, 0));
        assertTrue(NoVpnPresencePolicy.recentlyConfirmed(100_000L, 55_000L));
        assertFalse(NoVpnPresencePolicy.recentlyConfirmed(100_000L, 54_999L));
        assertFalse(NoVpnPresencePolicy.recentlyConfirmed(100_000L, 105_000L));
    }
}
