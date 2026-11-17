package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class RelayVideoFormatTest {
    private byte[] header(int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        int[] data = {a, b, c, d};
        for (int i = 0; i < 4; i++) {
            bytes[i*4] = (byte)(data[i] >>> 24);
            bytes[i*4+1] = (byte)(data[i] >>> 16);
            bytes[i*4+2] = (byte)(data[i] >>> 8);
            bytes[i*4+3] = (byte)data[i];
        }
        return bytes;
    }

    @Test public void parsesRealisticScrcpyDimensions() {
        assertArrayEquals(new int[]{1080, 2400, 720, 1600},
                RelayVideoFormat.dimensions(header(1080, 2400, 720, 1600)));
        assertArrayEquals(new int[]{2400, 1080, 1600, 720},
                RelayVideoFormat.dimensions(header(2400, 1080, 1600, 720)));
    }

    @Test public void refusesInvalidDimensionHeaders() {
        for (byte[] bytes : new byte[][]{
                null, new byte[0], new byte[13],
                header(1080, 2400, 0, 720),
                header(1080, 2400, 9000, 1600),
                header(120, 120, 720, 900),
                header(-1, 720, 720, 720),
        }) {
            try {
                RelayVideoFormat.dimensions(bytes);
                fail("Malformed resolution should not pass");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void safeH264SpsPpsExtraction() {
        byte[] sps = {0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, (byte) 0xff};
        byte[] pps = {0, 0, 0, 1, 0x68, (byte)0xce, 6, (byte)0xe2};
        byte[] config = new byte[sps.length + pps.length];
        System.arraycopy(sps, 0, config, 0, sps.length);
        System.arraycopy(pps, 0, config, sps.length, pps.length);
        byte[][] sets = RelayVideoFormat.h264ParameterSets(config);
        assertArrayEquals(sps, sets[0]);
        assertArrayEquals(pps, sets[1]);
    }

    @Test public void neverLoopsForeverOnTruncatedH264Configuration() {
        for (byte[] invalid : new byte[][]{
                {}, {0, 0, 0, 1, 0x67, 0, 0, 0, 0, 0, 0, 1},
                new byte[40],
                new byte[129 * 1024]}) {
            try {
                RelayVideoFormat.h264ParameterSets(invalid);
                fail("Missing SPS or PPS must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
