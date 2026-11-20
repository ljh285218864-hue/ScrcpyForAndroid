package org.client.scrcpy;

/**
 * Bounded, low-latency JPEG profile compatible with deployed 4fps relay.
 * Smaller frames and monotonic spacing avoid relay burst disconnects.
 * Backpressure drops stale captures rather than building a frame backlog.
 */
public final class JpegStreamTuning {
    public static final int MAX_LONG_EDGE = 1200;
    public static final int JPEG_QUALITY = 74;
    public static final int MIN_JPEG_QUALITY = 54;
    // Sequential captures have a post-encode gap, plus an independent
    // monotonic minimum so delayed callbacks cannot produce burst frames.
    public static final long CAPTURE_GAP_MS = 280L;
    public static final long MIN_CAPTURE_INTERVAL_MS = 320L;
    public static final long CONGESTION_RETRY_MS = 450L;
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
