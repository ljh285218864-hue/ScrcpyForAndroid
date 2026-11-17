package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

/** No Android framework, VPN or live server required. */
public class TrustedPairEndpointTest {
    @Test public void rejectsInvalidAndLegacyEndpoints() {
        for (String ip : new String[] {
                "100.80.0.2", "100.80.0.3", "192.168.1.30",
                "172.16.0.10", "10.0.0.2", "100.81.0.1", "100.81.0.0",
                "100.81.255.255", "127.0.0.1", "100.81.000.7",
                "100.81.0.7 ", "100.81.0.7:5555", "100.81.0.-2",
                "100.81.0.256", "host.local", "127.1", ""}) {
            assertFalse(ip, TrustedPairEndpoint.isBeta4ClientIp(ip));
        }
        assertFalse(TrustedPairEndpoint.isBeta4ClientIp(null));
    }

    @Test public void acceptsDynamicPairAddressesWithoutGuessing() {
        assertTrue(TrustedPairEndpoint.validPair("100.81.0.2", "100.81.0.3"));
        assertTrue(TrustedPairEndpoint.validPair("100.81.15.20", "100.81.15.21"));
        assertFalse(TrustedPairEndpoint.validPair("100.81.0.2", "100.81.0.2"));
        assertFalse(TrustedPairEndpoint.validPair("100.80.0.2", "100.81.0.3"));
    }

    @Test public void refusesAdbAddressUnlessMutuallyAuthenticatedPeerMatches() {
        assertTrue(TrustedPairEndpoint.authenticatedPeerMatches(
                "100.81.0.7", "100.81.0.7", "100.81.0.7"));
        assertFalse(TrustedPairEndpoint.authenticatedPeerMatches(
                "100.81.0.7", "100.81.0.8", "100.81.0.8"));
        assertFalse(TrustedPairEndpoint.authenticatedPeerMatches(
                "100.81.0.7", "100.81.0.7", "100.81.0.8"));
        assertFalse(TrustedPairEndpoint.authenticatedPeerMatches(
                "100.81.0.7", "100.81.0.7", null));
        assertFalse(TrustedPairEndpoint.authenticatedPeerMatches(
                "100.81.0.7", "192.168.1.7", "100.81.0.7"));
    }

    @Test public void deniesAbsentOrInvalidAdbPort() {
        assertFalse(TrustedPairEndpoint.validAdbPort(0));
        assertFalse(TrustedPairEndpoint.validAdbPort(-1));
        assertFalse(TrustedPairEndpoint.validAdbPort(65536));
        assertTrue(TrustedPairEndpoint.validAdbPort(37099));
    }
}
