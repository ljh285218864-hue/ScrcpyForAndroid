package org.client.scrcpy;

/**
 * Rate-limits AccessibilityService.takeScreenshot requests by their START
 * timestamps, including across adjacent, owner-approved watch sessions.
 *
 * Android rejects calls that are too close together (screenshot error 3).
 * Scheduling 140 ms AFTER JPEG encoding made those failures frequent; the
 * old 1000-ms retry after error 3 caused sub-1-fps video despite low network
 * queue size and fast JPEG encoding.
 *
 * Does not bypass any Android limits. Adapts conservatively to OEMs that
 * enforce a longer interval; uses elapsedRealtime supplied by the caller.
 */
final class JpegCapturePacer {
    static final long BASE_GAP_MS = 385L; // AOSP request floor is ~333ms.
    static final long MAX_GAP_MS = 1100L;
    static final long RATE_FAILURE_INCREMENT_MS = 65L;
    static final long RATE_SUCCESS_DECREMENT_MS = 15L;

    private long lastAttemptMs = -1L;
    private long gapMs = BASE_GAP_MS;
    private int successes;
    private int rateFailures;

    synchronized long delayUntilAllowed(long nowMs) {
        if (lastAttemptMs < 0) return 0;
        return Math.max(0L, lastAttemptMs + gapMs - nowMs);
    }

    synchronized void recordRequest(long nowMs) {
        lastAttemptMs = nowMs;
    }

    synchronized void recordSuccess() {
        successes++;
        // Back down gradually, not on every frame: avoid oscillating around
        // an OEM's actual rate limit after a throttled capture.
        if (successes >= 18 && gapMs > BASE_GAP_MS) {
            gapMs = Math.max(BASE_GAP_MS, gapMs - RATE_SUCCESS_DECREMENT_MS);
            successes = 0;
        }
    }

    synchronized void recordRateLimited() {
        rateFailures++;
        successes = 0;
        gapMs = Math.min(MAX_GAP_MS, gapMs + RATE_FAILURE_INCREMENT_MS);
    }

    synchronized long gapMs() {
        return gapMs;
    }

    synchronized int rateFailures() {
        return rateFailures;
    }
}
