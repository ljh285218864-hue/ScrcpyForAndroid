package org.client.scrcpy;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class ScrcpyRelayWireTest {
    private static final String SESSION = "abcdef0123456789abcdef0123456789";

    private static byte[] video(int type) {
        byte[] packet = new byte[26];
        packet[0] = (byte) type;
        packet[1] = 1;
        return packet;
    }

    @Test public void sharedGoldenVectorMatchesPythonByteForByte() {
        byte[] packet = new byte[14];
        packet[0] = 1; packet[1] = 1;
        packet[10] = 1; packet[11] = 2; packet[12] = 3; packet[13] = 4;
        byte[] result = ScrcpyRelayWire.encode(SESSION, packet);
        StringBuilder hex = new StringBuilder();
        for (byte value : result)
            hex.append(String.format(java.util.Locale.US, "%02x", value & 255));
        assertEquals("abcdef0123456789abcdef0123456789"
                + "0000000e0101000000000000000001020304", hex.toString());
        assertArrayEquals(packet, ScrcpyRelayWire.decode(result).videoPacket);
    }

    @Test public void binaryFrameRoundTripsWithPythonRelayFormat() {
        byte[] p = video(1);
        byte[] frame = ScrcpyRelayWire.encode(SESSION, p);
        assertEquals(20 + p.length, frame.length);
        assertEquals(p.length, (frame[16] & 255) << 24
                | (frame[17] & 255) << 16
                | (frame[18] & 255) << 8 | (frame[19] & 255));
        ScrcpyRelayWire.Frame decoded = ScrcpyRelayWire.decode(frame);
        assertEquals(SESSION, decoded.sessionId);
        assertArrayEquals(p, decoded.videoPacket);
    }

    @Test public void cannotRelayCommandControlAudioOrMalformedFrames() {
        for (int type : new int[]{0, 2, 3, 255}) {
            try {
                ScrcpyRelayWire.encode(SESSION, video(type));
                fail("non-video type should not pass");
            } catch (IllegalArgumentException expected) { }
        }
        for (String invalid : new String[]{"", "ABCDEF0123456789ABCDEF0123456789",
                "hello", "abcdef0123456789abcdef01234567zz"}) {
            assertFalse(ScrcpyRelayWire.validSessionId(invalid));
        }
        byte[] frame = ScrcpyRelayWire.encode(SESSION, video(1));
        byte[] shorter = Arrays.copyOf(frame, frame.length - 1);
        try {
            ScrcpyRelayWire.decode(shorter);
            fail("truncated frame should not pass");
        } catch (IllegalArgumentException expected) { }
        frame[20] = 3;
        try {
            ScrcpyRelayWire.decode(frame);
            fail("ADB command type should not pass");
        } catch (IllegalArgumentException expected) { }
    }
}
