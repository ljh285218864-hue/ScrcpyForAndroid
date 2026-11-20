package org.client.scrcpy;

/**
 * Pure, deterministic backoff and presence freshness policy.
 *
 * Android's background scheduling is not real-time, so these values are
 * intentions while our process is runnable, not delivery guarantees.
 */
final class NoVpnPresencePolicy {
    static final long HEALTHY_INTERVAL_MS = 30_000L;
    static final long MAX_RETRY_MS = 300_000L;
    static final long MIN_RECOVERY_GAP_MS = 5_000L;
    static final long SERVER_ONLINE_TTL_MS = 45_000L;

    private NoVpnPresencePolicy() {}

    static long nextDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return HEALTHY_INTERVAL_MS;
        int shift = Math.min(consecutiveFailures - 1, 5);
        return Math.min(MAX_RETRY_MS, 10_000L << shift);
    }

    static boolean recentlyConfirmed(long nowMillis, long lastSuccessMillis) {
        return lastSuccessMillis > 0 && nowMillis >= lastSuccessMillis
                && nowMillis - lastSuccessMillis <= SERVER_ONLINE_TTL_MS;
    }
}
