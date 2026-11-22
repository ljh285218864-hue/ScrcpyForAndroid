package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JpegCapturePacerTest {
    @Test public void enforcesMinimumRequestToRequestIntervalRatherThanCallbackTime() {
        JpegCapturePacer pacer = new JpegCapturePacer();
        assertEquals(0, pacer.delayUntilAllowed(1000));
        pacer.recordRequest(1000);
        pacer.recordSuccess();
        assertEquals(385, pacer.delayUntilAllowed(1000));
        assertEquals(265, pacer.delayUntilAllowed(1120));
        assertEquals(0, pacer.delayUntilAllowed(1385));
    }

    @Test public void androidRateLimitRaisesPacingInsteadOfOneSecondBlindRetry() {
        JpegCapturePacer pacer = new JpegCapturePacer();
        pacer.recordRequest(2000);
        pacer.recordRateLimited();
        assertEquals(450, pacer.gapMs());
        assertEquals(1, pacer.rateFailures());
        assertEquals(250, pacer.delayUntilAllowed(2200));
        assertEquals(0, pacer.delayUntilAllowed(2450));
    }

    @Test public void repeatedThrottleIsBoundedAndGradualSuccessReducesGap() {
        JpegCapturePacer pacer = new JpegCapturePacer();
        for (int i = 0; i < 100; i++) pacer.recordRateLimited();
        assertEquals(JpegCapturePacer.MAX_GAP_MS, pacer.gapMs());
        for (int i = 0; i < 18; i++) pacer.recordSuccess();
        assertEquals(JpegCapturePacer.MAX_GAP_MS - 15, pacer.gapMs());
        for (int i = 0; i < 1500; i++) pacer.recordSuccess();
        assertEquals(JpegCapturePacer.BASE_GAP_MS, pacer.gapMs());
    }

    @Test public void pacingSurvivesAdjacentWatchSessionsThroughSharedInstance() {
        JpegCapturePacer pacer = new JpegCapturePacer();
        pacer.recordRequest(10000);
        pacer.recordSuccess();
        // Same pacer instance is retained when the prior watch ends.
        assertEquals(285, pacer.delayUntilAllowed(10100));
    }

    @Test public void noNegativeWaitEvenWhenSystemClockMovesForward() {
        JpegCapturePacer pacer = new JpegCapturePacer();
        pacer.recordRequest(0);
        assertEquals(0, pacer.delayUntilAllowed(999999));
    }
}
