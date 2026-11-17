package org.client.scrcpy;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Bounded parser for the pre-existing local scrcpy TCP video socket.
 * It does not accept remote ADB command messages or arbitrary socket targets.
 */
public final class ScrcpyLocalFrameReader {
    private static final int MAX_PACKET = 4 * 1024 * 1024;
    private final DataInputStream input;

    public ScrcpyLocalFrameReader(DataInputStream input) {
        if (input == null) throw new IllegalArgumentException("Input is null");
        this.input = input;
    }

    public int[] readDimensions() throws IOException {
        byte[] handshake = new byte[16];
        input.readFully(handshake);
        return RelayVideoFormat.dimensions(handshake);
    }

    /** Returns null for non-video packets. Audio / command data is discarded. */
    public byte[] readPacket() throws IOException {
        int size = input.readInt();
        if (size < 10 || size > MAX_PACKET)
            throw new IOException("Invalid local scrcpy packet size");
        byte[] packet = new byte[size];
        input.readFully(packet);
        return ScrcpyRelayPacketPolicy.allowedVideoPayload(packet) ? packet : null;
    }
}
