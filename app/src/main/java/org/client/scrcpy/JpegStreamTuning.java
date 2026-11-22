package org.client.scrcpy;

/**
 * Bandwidth-aware JPEG profile. An old relay only allows 4 FPS; do not
 * accelerate capture until a newer authenticated relay advertises its limit.
 * The relay's maximum is a safety ceiling, not a promised rendered FPS.
 */
public final class JpegStreamTuning {
    public static final int MAX_LONG_EDGE = 1152;
    public static final int JPEG_QUALITY = 70;
    public static final int FALLBACK_SERVER_FPS = 4;
    public static final int MAX_SERVER_FPS = 8;
    public static final long CAPTURE_GAP_MS = 265L;
    public static final long FAST_CAPTURE_GAP_MS = 140L;
    public static final long CONGESTION_RETRY_MS = 450L;
    public static final long MAX_PENDING_BYTES_BEFORE_CAPTURE = 160L * 1024L;
    public static final long MAX_PENDING_BYTES_BEFORE_SEND = 384L * 1024L;
    private JpegStreamTuning() { }

    /** A relay without this optional negotiated field is the older 4 FPS one. */
    public static int negotiatedFps(int advertised) {
        return advertised >= MAX_SERVER_FPS ? MAX_SERVER_FPS : FALLBACK_SERVER_FPS;
    }

    /** Leave headroom for screenshot and WebSocket scheduling jitter. */
    public static long captureGapMs(int serverFps) {
        return negotiatedFps(serverFps) == MAX_SERVER_FPS
                ? FAST_CAPTURE_GAP_MS : CAPTURE_GAP_MS;
    }

    public static int[] targetSize(int width, int height) {
        if (width < 16 || height < 16) throw new IllegalArgumentException("invalid dimensions");
        float scale = Math.min(1f, (float) MAX_LONG_EDGE / Math.max(width, height));
        return new int[] { Math.max(16, Math.round(width * scale)),
                Math.max(16, Math.round(height * scale)) };
    }
}
