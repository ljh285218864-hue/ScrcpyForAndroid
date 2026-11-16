package org.client.scrcpy;

import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EmbeddedRemoteTunnelTest {
    private Config configFor(String clientAddress, String allowedIps) throws Exception {
        String privateKey = new KeyPair().getPrivateKey().toBase64();
        String serverKey = new KeyPair().getPublicKey().toBase64();
        String raw = "[Interface]\n"
                + "Address = " + clientAddress + "\n"
                + "PrivateKey = " + privateKey + "\n"
                + "[Peer]\n"
                + "PublicKey = " + serverKey + "\n"
                + "AllowedIPs = " + allowedIps + "\n"
                + "Endpoint = 198.51.100.10:51820\n"
                + "PersistentKeepalive = 25\n";
        return Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void validSingleAppWireGuardRoute() throws Exception {
        EmbeddedRemoteTunnel.validateConfig(configFor("100.80.0.2/32", "100.64.0.0/10"));
    }

    @Test public void rejectsFullDeviceTrafficTunnel() throws Exception {
        try {
            EmbeddedRemoteTunnel.validateConfig(configFor("100.80.0.2/32", "0.0.0.0/0"));
            fail("Default route must not be accepted");
        } catch (IOException expected) { }
    }

    @Test public void rejectsNonScreenSyncOverlayNetwork() throws Exception {
        try {
            EmbeddedRemoteTunnel.validateConfig(configFor("10.0.0.2/32", "100.64.0.0/10"));
            fail("Only the isolated ScreenSync overlay is permitted");
        } catch (IOException expected) { }
    }

    @Test public void rejectsOverlayRoutesOutsideCgnatPrefix() throws Exception {
        try {
            EmbeddedRemoteTunnel.validateConfig(configFor("100.80.0.2/32", "100.64.0.0/9"));
            fail("Broad overlay routes are forbidden");
        } catch (IOException expected) { }
    }
}
