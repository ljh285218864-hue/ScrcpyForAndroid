package org.client.scrcpy;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import static org.junit.Assert.*;

public class ScrcpyLocalFrameReaderTest {
    private void appendDimensions(DataOutputStream data) throws IOException {
        for (int n : new int[]{1080, 2400, 720, 1600}) data.writeInt(n);
    }

    private byte[] packet(int type, int flag, int size) {
        byte[] p = new byte[size];
        p[0] = (byte)type;
        p[1] = (byte)flag;
        return p;
    }

    @Test public void forwardsOnlyVideoFromSyntheticLocalScrcpySocket() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        appendDimensions(out);
        for (int type : new int[]{0, 2, 3}) {
            byte[] p = packet(type, 1, 64);
            out.writeInt(p.length);
            out.write(p);
        }
        byte[] config = packet(1, 2, 64), frame = packet(1, 1, 256);
        out.writeInt(config.length);
        out.write(config);
        out.writeInt(frame.length);
        out.write(frame);
        ScrcpyLocalFrameReader reader = new ScrcpyLocalFrameReader(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertArrayEquals(new int[]{1080, 2400, 720, 1600}, reader.readDimensions());
        assertNull(reader.readPacket()); // audio
        assertNull(reader.readPacket()); // control
        assertNull(reader.readPacket()); // shell/command
        assertArrayEquals(config, reader.readPacket());
        assertArrayEquals(frame, reader.readPacket());
    }

    @Test public void refusesTruncationNegativeLengthsAndOversizedFrames() throws Exception {
        for (int length : new int[]{-1, 0, 9, 4 * 1024 * 1024 + 1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            appendDimensions(out);
            out.writeInt(length);
            ScrcpyLocalFrameReader reader = new ScrcpyLocalFrameReader(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            reader.readDimensions();
            try {
                reader.readPacket();
                fail("invalid length was accepted: " + length);
            } catch (IOException expected) { }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        appendDimensions(out);
        out.writeInt(100);
        out.write(new byte[12]);
        ScrcpyLocalFrameReader reader = new ScrcpyLocalFrameReader(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        reader.readDimensions();
        try {
            reader.readPacket();
            fail("truncated media packet accepted");
        } catch (EOFException expected) { }
    }
}
