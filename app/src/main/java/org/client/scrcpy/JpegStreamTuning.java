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
    // When OS-aware screenshot pacing permits a request, schedule on the
    // next main-loop turn rather than waiting an extra 140-265ms per frame.
    public static final long MIN_CAPTURE_IDLE_MS = 8L;
    // Rare Android/OEM takeScreenshot callbacks never arrive. The old 6000ms
    // watchdog created seven-second visible stalls in the Android 15 CI.
    // Keep this comfortably above normal screenshot callbacks (~50-405ms).
    public static final long SCREENSHOT_CALLBACK_TIMEOUT_MS = 1900L;
    public static final long SCREENSHOT_TIMEOUT_RETRY_MS = 240L;
    // A 450ms backpressure sleep added a visible frame-sized freeze even
    // after a queued packet had already drained. Poll readiness cheaply;
    // no screenshots are acquired while the socket is still congested.
    public static final long CONGESTION_RETRY_MS = 110L;
    public static final long MAX_PENDING_BYTES_BEFORE_CAPTURE = 160L * 1024L;
    public static final long MAX_PENDING_BYTES_BEFORE_SEND = 384L * 1024L;
    private JpegStreamTuning() { }

    // Smooth very closely spaced incoming frames rather than flashing through
    // several screenshots at once after a brief network backlog. Frames that
    // arrive normally (~385ms apart) display immediately, without buffering.
    public static final long MIN_VIEW_DISPLAY_GAP_MS = 320L;

    static long nextViewerDisplayAtMs(long nowMs, long previousDisplayAtMs) {
        if (previousDisplayAtMs <= 0L) return nowMs;
        return Math.max(nowMs, previousDisplayAtMs + MIN_VIEW_DISPLAY_GAP_MS);
    }

    /** A relay without this optional negotiated field is the older 4 FPS one. */
    public static int negotiatedFps(int advertised) {
        return advertised >= MAX_SERVER_FPS ? MAX_SERVER_FPS : FALLBACK_SERVER_FPS;
    }

    /** Leave headroom for screenshot and WebSocket scheduling jitter. */
    public static long captureGapMs(int serverFps) {
        return negotiatedFps(serverFps) == MAX_SERVER_FPS
                ? FAST_CAPTURE_GAP_MS : CAPTURE_GAP_MS;
    }

    /** Recover quickly from one isolated Android screenshot failure;
     * repeated failures back off to avoid hammering a disabled service. */
    static long captureFailureRetryMs(int consecutiveFailures) {
        if (consecutiveFailures <= 1) return 170L;
        if (consecutiveFailures <= 3) return 350L;
        return 850L;
    }

    public static int[] targetSize(int width, int height) {
        if (width < 16 || height < 16) throw new IllegalArgumentException("invalid dimensions");
        float scale = Math.min(1f, (float) MAX_LONG_EDGE / Math.max(width, height));
        return new int[] { Math.max(16, Math.round(width * scale)),
                Math.max(16, Math.round(height * scale)) };
    }
}
