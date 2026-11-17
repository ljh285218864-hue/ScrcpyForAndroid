package org.client.scrcpy;

import java.util.Arrays;

/**
 * Strict parser for scrcpy 16-byte resolution handshake and Annex-B H.264
 * SPS/PPS config. No unbounded loops on network-provided payloads.
 */
public final class RelayVideoFormat {
    private RelayVideoFormat() {}

    public static int[] dimensions(byte[] header) {
        if (!ScrcpyRelayPacketPolicy.validResolutionHandshake(header))
            throw new IllegalArgumentException("Invalid scrcpy video dimensions");
        int[] dims = new int[4];
        for (int i = 0; i < dims.length; i++) {
            int p = 4 * i;
            dims[i] = (header[p] & 255) << 24 | (header[p+1] & 255) << 16
                    | (header[p+2] & 255) << 8 | (header[p+3] & 255);
        }
        return dims;
    }

    /** Each result includes its own Annex-B start code; [0] SPS, [1] PPS. */
    public static byte[][] h264ParameterSets(byte[] data) {
        if (data == null || data.length < 12 || data.length > 128 * 1024)
            throw new IllegalArgumentException("Invalid H264 parameter-set length");
        byte[] sps = null, pps = null;
        for (int i = 0; i < data.length - 4; ) {
            int prefix = startCode(data, i);
            if (prefix == 0) {
                i++;
                continue;
            }
            int nal = i + prefix;
            if (nal >= data.length) break;
            int type = data[nal] & 31;
            int end = nal + 1;
            while (end < data.length && startCode(data, end) == 0) end++;
            if (end <= nal) throw new IllegalArgumentException("Empty H264 NAL");
            if (type == 7 && sps == null) sps = Arrays.copyOfRange(data, i, end);
            if (type == 8 && pps == null) pps = Arrays.copyOfRange(data, i, end);
            i = end;
        }
        if (sps == null || pps == null)
            throw new IllegalArgumentException("Missing H264 SPS or PPS");
        return new byte[][] {sps, pps};
    }

    private static int startCode(byte[] data, int pos) {
        if (pos + 3 >= data.length || data[pos] != 0 || data[pos+1] != 0)
            return 0;
        if (data[pos+2] == 1) return 3;
        return data[pos+2] == 0 && data[pos+3] == 1 ? 4 : 0;
    }
}
