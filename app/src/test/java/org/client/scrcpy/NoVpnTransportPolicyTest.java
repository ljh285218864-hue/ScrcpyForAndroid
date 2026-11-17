package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

/** Protect the no-system-VPN build contract from accidental regression. */
public final class NoVpnTransportPolicyTest {
    @Test public void neverStartsOrAdvertisesSystemVpn() {
        assertFalse(NoVpnTransportPolicy.usesAndroidVpn());
        assertFalse(NoVpnTransportPolicy.isInternetVideoTransportReady());
    }

    @Test public void experimentalBuildDoesNotClaimRemoteViewing() {
        String message = NoVpnTransportPolicy.pendingMessage();
        assertTrue(message.contains("不启用系统 VPN"));
        assertTrue(message.contains("尚未"));
    }
}
