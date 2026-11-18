package org.client.scrcpy;

import java.util.Arrays;

/**
 * Framing contract shared with remote/relay_v1/media_relay.py.
 *
 * 16 raw bytes of server-issued session ID + a 4-byte big-endian VIDEO
 * payload length + the original scrcpy video packet (without its original
 * 4-byte length prefix). Never forwards the complete bidirectional ADB
 * connection. A remote frame is NOT permission to record the device screen.
 */
public final class ScrcpyRelayWire {
    public static final int HEADER_LENGTH = 20;
    private static final int MAX_PACKET = 4 * 1024 * 1024;

    private ScrcpyRelayWire() { }

    public static final class Frame {
        public final String sessionId;
        public final byte[] videoPacket;
        private Frame(String sessionId, byte[] packet) {
            this.sessionId = sessionId;
            this.videoPacket = packet;
        }
    }

    public static byte[] encode(String sessionId, byte[] packet) {
        if (!validSessionId(sessionId) ||
                !ScrcpyRelayPacketPolicy.allowedVideoPayload(packet)) {
            throw new IllegalArgumentException("INVALID_VIDEO_PACKET");
        }
        return pack(sessionId, packet);
    }

    public static byte[] encodeJpeg(String sessionId, byte[] jpeg) {
        if (!validSessionId(sessionId) || jpeg == null
                || jpeg.length < 64 || jpeg.length > 1024 * 1024
                || (jpeg[0] & 255) != 0xff || (jpeg[1] & 255) != 0xd8
                || (jpeg[jpeg.length - 2] & 255) != 0xff
                || (jpeg[jpeg.length - 1] & 255) != 0xd9)
            throw new IllegalArgumentException("INVALID_JPEG_PACKET");
        byte[] payload = new byte[10 + jpeg.length];
        payload[0] = 2; // JPEG-only, never ADB or scrcpy CONTROL
        payload[1] = 0;
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < 8; ++i) payload[i + 2] = (byte) (stamp >>> (56 - i * 8));
        System.arraycopy(jpeg, 0, payload, 10, jpeg.length);
        return pack(sessionId, payload);
    }

    private static byte[] pack(String sessionId, byte[] packet) {
        int length = packet.length;
        byte[] frame = new byte[HEADER_LENGTH + length];
        for (int i = 0; i < 16; i++) {
            int offset = i * 2;
            frame[i] = (byte) Integer.parseInt(
                    sessionId.substring(offset, offset + 2), 16);
        }
        frame[16] = (byte) (length >>> 24);
        frame[17] = (byte) (length >>> 16);
        frame[18] = (byte) (length >>> 8);
        frame[19] = (byte) length;
        System.arraycopy(packet, 0, frame, HEADER_LENGTH, length);
        return frame;
    }

    public static Frame decode(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH + 10
                || data.length > HEADER_LENGTH + MAX_PACKET) {
            throw new IllegalArgumentException("INVALID_FRAME_LENGTH");
        }
        int length = (data[16] & 0xff) << 24 | (data[17] & 0xff) << 16
                | (data[18] & 0xff) << 8 | (data[19] & 0xff);
        if (length != data.length - HEADER_LENGTH) {
            throw new IllegalArgumentException("MISMATCHED_VIDEO_LENGTH");
        }
        byte[] packet = Arrays.copyOfRange(data, HEADER_LENGTH, data.length);
        if (!ScrcpyRelayPacketPolicy.allowedMediaPayload(packet)) {
            throw new IllegalArgumentException("UNSAFE_VIDEO_PACKET");
        }
        char[] hex = "0123456789abcdef".toCharArray();
        char[] id = new char[32];
        for (int i = 0; i < 16; i++) {
            int value = data[i] & 0xff;
            id[2 * i] = hex[value >>> 4];
            id[2 * i + 1] = hex[value & 15];
        }
        return new Frame(new String(id), packet);
    }

    public static boolean validSessionId(String id) {
        if (id == null || id.length() != 32) return false;
        for (int i = 0; i < id.length(); i++) {
            char value = id.charAt(i);
            if (!((value >= '0' && value <= '9')
                    || (value >= 'a' && value <= 'f'))) return false;
        }
        return true;
    }
}
