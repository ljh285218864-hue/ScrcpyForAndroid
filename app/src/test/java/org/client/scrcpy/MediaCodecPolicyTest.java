package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MediaCodecPolicyTest {
    @Test public void acceptsScreenSyncJpegAndLegacyVideoCodecs() {
        assertTrue(MediaCodecPolicy.isAllowed("jpeg"));
        assertTrue(MediaCodecPolicy.isAllowed("h264"));
        assertTrue(MediaCodecPolicy.isAllowed("h265"));
    }

    @Test public void rejectsControlAudioAndUnknownCodecs() {
        for (String value : new String[]{"", "JPEG", "adb", "audio", "control", "raw"}) {
            assertFalse(MediaCodecPolicy.isAllowed(value));
        }
        assertFalse(MediaCodecPolicy.isAllowed(null));
    }
}
