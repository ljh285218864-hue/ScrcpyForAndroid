package org.client.scrcpy;

import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Device-side half of the isolated CI paired-device lifecycle test.
 *
 * Test-only, never shipped in the normal APK. A fake peer on the isolated
 * GitHub runner supplies the other half of the enrollment protocol.
 * No remote viewing consent is granted by this test.
 */
@RunWith(AndroidJUnit4.class)
public final class ScreenSyncPairedLifecycleTest {
    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void save(Context app, String name, String value) throws Exception {
        try (FileOutputStream out = app.openFileOutput(name, Context.MODE_PRIVATE)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void pairedLifecyclePhase() throws Exception {
        Context app = ctx();
        Bundle args = InstrumentationRegistry.getArguments();
        String phase = args.getString("phase", "");
        if ("register".equals(phase)) {
            assertFalse("test emulator must start unpaired", NoVpnApi.isRegistered(app));
            JSONObject registered = NoVpnApi.registerOwner(app);
            assertEquals("ready", registered.getString("state"));
            assertTrue(NoVpnApi.isRegistered(app));
            // Short-lived invite is consumed by the companion test fixture and
            // is never printed in logs or collected as a test artifact.
            save(app, "ci_pair_invite", registered.getString("invite_code"));
        } else if ("approve".equals(phase)) {
            String peerId = args.getString("guestId", "");
            String code = args.getString("verifyCode", "");
            JSONObject pending = NoVpnApi.status(app);
            assertEquals("ready", pending.getString("state"));
            assertEquals(peerId, pending.getString("pending_id"));
            assertEquals(code, pending.getString("pending_verify_code"));
            assertEquals("paired", NoVpnApi.approve(app, peerId).getString("state"));
            assertEquals("paired", NoVpnApi.status(app).getString("state"));
            assertEquals(peerId, NoVpnApi.peerId(app));
            save(app, "ci_expected_peer", peerId);
            app.deleteFile("ci_pair_invite");
            NoVpnBackgroundSync.setEnabled(app, true);
            ScreenSyncMediaKeeper.restoreOptedInSignaling(app);
            assertTrue(NoVpnBackgroundSync.isEnabled(app));
            assertEquals(peerId, NoVpnApi.heartbeat(app).getString("peer_device_id"));
        } else if ("verify".equals(phase)) {
            assertTrue("pairing token must survive process reboot", NoVpnApi.isRegistered(app));
            assertTrue("background opt-in must persist", NoVpnBackgroundSync.isEnabled(app));
            JSONObject actual = NoVpnApi.status(app);
            assertEquals("paired", actual.getString("state"));
            assertEquals(NoVpnApi.peerId(app), actual.getString("peer_device_id"));
            assertEquals("online", NoVpnApi.heartbeat(app).getString("state"));
        } else if ("revoke".equals(phase)) {
            assertEquals("revoked", NoVpnApi.revoke(app).getString("state"));
            LongTermSharePolicy.disable(app);
            NoVpnBackgroundSync.setEnabled(app, false);
        } else {
            fail("Unknown isolated emulator test phase: " + phase);
        }
    }
}
