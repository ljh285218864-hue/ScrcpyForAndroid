package org.client.scrcpy;

/**
 * Bounded JPEG capture profile for the Osaka low-resource, 4-fps relay.
 * Keeping at most 4 JPEG frames per second avoids server-side session
 * termination. A small output queue prevents stale frames building up.
 */
public final class JpegStreamTuning {
    public static final int MAX_LONG_EDGE = 1440;
    public static final int JPEG_QUALITY = 74;
    public static final long CAPTURE_GAP_MS = 265L;
    public static final long CONGESTION_RETRY_MS = 650L;
    public static final long MAX_PENDING_BYTES_BEFORE_CAPTURE = 160L * 1024L;
    public static final long MAX_PENDING_BYTES_BEFORE_SEND = 384L * 1024L;
    private JpegStreamTuning() { }

    public static int[] targetSize(int width, int height) {
        if (width < 16 || height < 16) throw new IllegalArgumentException("invalid dimensions");
        float scale = Math.min(1f, (float)MAX_LONG_EDGE / Math.max(width, height));
        return new int[] { Math.max(16, Math.round(width * scale)),
                Math.max(16, Math.round(height * scale)) };
    }
}
