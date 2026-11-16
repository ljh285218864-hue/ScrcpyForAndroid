package org.client.scrcpy;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class RemoteOverlayNetworkTest {
    @Test public void onlyRfc6598TailnetAddressesAllowed() {
        assertTrue(RemoteOverlayNetwork.isOverlayAddress("100.64.0.1"));
        assertTrue(RemoteOverlayNetwork.isOverlayAddress("100.127.255.254"));
        assertTrue(RemoteOverlayNetwork.isOverlayAddress("100.86.15.20"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress("100.63.255.255"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress("100.128.0.1"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress("100.64.256.1"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress("100.64.1.1x"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress("203.0.113.1"));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress(""));
        assertFalse(RemoteOverlayNetwork.isOverlayAddress(null));
    }

    @Test public void supportedPeersOnlyLanLoopbackOrPrivateOverlay() throws Exception {
        assertTrue(RemoteOverlayNetwork.isAllowedPeerIp(
                InetAddress.getByName("192.168.1.44")));
        assertTrue(RemoteOverlayNetwork.isAllowedPeerIp(
                InetAddress.getByName("100.76.1.4")));
        assertTrue(RemoteOverlayNetwork.isAllowedPeerIp(
                InetAddress.getByName("127.0.0.1")));
        assertFalse(RemoteOverlayNetwork.isAllowedPeerIp(
                InetAddress.getByName("8.8.8.8")));
    }

    @Test public void appPairingRecognizesOverlayAddress() {
        assertTrue(PairQrCode.isPrivateIpv4("100.80.11.3"));
        assertFalse(PairQrCode.isPrivateIpv4("100.180.1.2"));
    }

    @Test public void remoteResponseProofIsMutuallyAuthenticated() throws Exception {
        String key = WakeAuth.randomHex(16);
        String viewerNonce = WakeAuth.randomHex(8);
        String serverNonce = WakeAuth.randomHex(16);
        String proof = WakeAuth.mac(key, serverNonce, viewerNonce);
        assertTrue(WakeAuth.verify(proof,
                WakeAuth.mac(key, serverNonce, viewerNonce)));
        assertFalse(WakeAuth.verify(proof,
                WakeAuth.mac(WakeAuth.randomHex(16), serverNonce, viewerNonce)));
    }
}
