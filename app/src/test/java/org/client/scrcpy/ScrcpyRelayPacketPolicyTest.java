package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScrcpyRelayPacketPolicyTest {
    private static byte[] packet(int type, int flag, int bytes) {
        byte[] data = new byte[bytes];
        data[0] = (byte) type;
        data[1] = (byte) flag;
        return data;
    }

    @Test public void permitsOnlyVideoMediaFrames() {
        for (int flag : new int[] {0, 1, 2, 4}) {
            assertTrue(ScrcpyRelayPacketPolicy.allowedVideoPayload(
                    packet(1, flag, 128)));
        }
        assertTrue(ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(1, 4, 10)));
    }

    @Test public void explicitlyRejectsControlCommandAndAudioBytes() {
        for (int type : new int[] {0, 2, 3, 4, 255}) {
            assertFalse("type=" + type,
                    ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(type, 0, 128)));
        }
        for (int flag : new int[] {3, 5, 255}) {
            assertFalse("flag=" + flag,
                    ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(1, flag, 128)));
        }
    }

    @Test public void rejectsMalformedOrOversizedPayload() {
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(null));
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(new byte[0]));
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(1, 0, 9)));
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(1, 0, 10)));
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(packet(1, 2, 10)));
        assertFalse(ScrcpyRelayPacketPolicy.allowedVideoPayload(
                packet(1, 0, 4 * 1024 * 1024 + 1)));
    }

    private static void putInt(byte[] frame, int pos, int number) {
        frame[pos] = (byte)(number >>> 24);
        frame[pos + 1] = (byte)(number >>> 16);
        frame[pos + 2] = (byte)(number >>> 8);
        frame[pos + 3] = (byte)number;
    }

    @Test public void validatesRemoteDecoderDimensions() {
        byte[] frame = new byte[16];
        putInt(frame, 0, 1080);
        putInt(frame, 4, 2400);
        putInt(frame, 8, 540);
        putInt(frame, 12, 1200);
        assertTrue(ScrcpyRelayPacketPolicy.validResolutionHandshake(frame));
        putInt(frame, 8, 16000);
        assertFalse(ScrcpyRelayPacketPolicy.validResolutionHandshake(frame));
        putInt(frame, 8, 540);
        putInt(frame, 12, 0);
        assertFalse(ScrcpyRelayPacketPolicy.validResolutionHandshake(frame));
        assertFalse(ScrcpyRelayPacketPolicy.validResolutionHandshake(new byte[12]));
    }
}
