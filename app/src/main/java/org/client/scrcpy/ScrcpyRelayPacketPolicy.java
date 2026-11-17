package org.client.scrcpy;

/**
 * Defensive wire boundary for the future scrcpy-to-server media relay.
 *
 * The original local scrcpy stream uses a 4-byte big-endian payload length
 * followed by a packet whose first byte is the MediaPacket.Type.
 * In the read-only internet variant, DO NOT forward the original bidirectional
 * ADB socket. Relay only validated encoded VIDEO payloads; no CONTROL,
 * COMMAND, ADB shell, port forwarding or arbitrary TCP messages.
 *
 * This class is a codec/policy building block, not a running media relay.
 */
public final class ScrcpyRelayPacketPolicy {
    private static final int VIDEO_TYPE = 1;
    private static final int HEADER_BYTES = 10;
    private static final int MAX_VIDEO_PACKET = 4 * 1024 * 1024;

    private ScrcpyRelayPacketPolicy() { }

    /** Validate the payload AFTER the scrcpy 4-byte length prefix. */
    public static boolean allowedVideoPayload(byte[] packet) {
        if (packet == null || packet.length < HEADER_BYTES
                || packet.length > MAX_VIDEO_PACKET) return false;
        if ((packet[0] & 0xff) != VIDEO_TYPE) return false;
        int flag = packet[1] & 0xff;
        // Video: regular frame, IDR key frame, SPS/PPS configuration or END.
        if (flag != 0 && flag != 1 && flag != 2 && flag != 4) return false;
        // Never forward an empty FRAME/KEYFRAME/CONFIG; ending is allowed
        // without codec data.
        return flag == 4 || packet.length > HEADER_BYTES;
    }

    /** Validate a 16-byte scrcpy resolution handshake for the media decoder. */
    public static boolean validResolutionHandshake(byte[] frame) {
        if (frame == null || frame.length != 16) return false;
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            values[i] = ((frame[i * 4] & 0xff) << 24)
                    | ((frame[i * 4 + 1] & 0xff) << 16)
                    | ((frame[i * 4 + 2] & 0xff) << 8)
                    | (frame[i * 4 + 3] & 0xff);
            if (values[i] < 16 || values[i] > 8192) return false;
        }
        return values[2] <= values[0] * 2L
                && values[3] <= values[1] * 2L;
    }
}
