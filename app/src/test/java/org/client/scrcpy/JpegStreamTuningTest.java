package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JpegStreamTuningTest {
    @Test public void scaledPortraitUsesLessCpuAndStaysWithinDecoderLimit() {
        assertArrayEquals(new int[]{518, 1152}, JpegStreamTuning.targetSize(1080, 2400));
        assertArrayEquals(new int[]{1152, 518}, JpegStreamTuning.targetSize(2400, 1080));
    }

    @Test public void smallScreensAreNeverUpscaled() {
        assertArrayEquals(new int[]{360, 800}, JpegStreamTuning.targetSize(360, 800));
    }

    @Test public void oldServerWithoutCapabilityCannotBeOverrun() {
        assertEquals(4, JpegStreamTuning.negotiatedFps(0));
        assertEquals(4, JpegStreamTuning.negotiatedFps(4));
        assertEquals(265L, JpegStreamTuning.captureGapMs(4));
    }

    @Test public void newServerAdvertisesHigherBoundedFrameRate() {
        assertEquals(8, JpegStreamTuning.negotiatedFps(8));
        assertEquals(8, JpegStreamTuning.negotiatedFps(100));
        assertEquals(140L, JpegStreamTuning.captureGapMs(8));
    }

    @Test public void screenshotWatchdogCannotStallViewerForSixSeconds() {
        assertTrue(JpegStreamTuning.SCREENSHOT_CALLBACK_TIMEOUT_MS >= 1500L);
        assertTrue(JpegStreamTuning.SCREENSHOT_CALLBACK_TIMEOUT_MS <= 2500L);
        assertTrue(JpegStreamTuning.SCREENSHOT_TIMEOUT_RETRY_MS > 0);
        assertTrue(JpegStreamTuning.SCREENSHOT_TIMEOUT_RETRY_MS <= 350L);
        assertTrue(JpegStreamTuning.SCREENSHOT_CALLBACK_TIMEOUT_MS
                + JpegStreamTuning.SCREENSHOT_TIMEOUT_RETRY_MS < 3000L);
    }

    @Test public void congestionPollingRecoversWithoutAnExtraHalfSecondFreeze() {
        assertTrue(JpegStreamTuning.CONGESTION_RETRY_MS >= 75L);
        assertTrue(JpegStreamTuning.CONGESTION_RETRY_MS <= 150L);
    }

    @Test public void isolatedScreenshotFailureRetriesQuicklyButRepeatedFailuresBackOff() {
        assertEquals(170L, JpegStreamTuning.captureFailureRetryMs(1));
        assertEquals(350L, JpegStreamTuning.captureFailureRetryMs(2));
        assertEquals(350L, JpegStreamTuning.captureFailureRetryMs(3));
        assertEquals(850L, JpegStreamTuning.captureFailureRetryMs(4));
        assertEquals(850L, JpegStreamTuning.captureFailureRetryMs(1000));
    }

    @Test public void viewerBurstsAreSpacedButNormalFramesStayRealTime() {
        assertEquals(1000L, JpegStreamTuning.nextViewerDisplayAtMs(1000, 0));
        assertEquals(1320L, JpegStreamTuning.nextViewerDisplayAtMs(1050, 1000));
        assertEquals(1385L, JpegStreamTuning.nextViewerDisplayAtMs(1385, 1000));
        assertEquals(1800L, JpegStreamTuning.nextViewerDisplayAtMs(1800, 1000));
        assertTrue(JpegStreamTuning.MIN_VIEW_DISPLAY_GAP_MS >= 250L);
        assertTrue(JpegStreamTuning.MIN_VIEW_DISPLAY_GAP_MS <= 340L);
    }

    @Test public void profileKeepsBoundedQualityAndWebsocketQueue() {
        assertTrue(JpegStreamTuning.JPEG_QUALITY >= 65);
        assertTrue(JpegStreamTuning.JPEG_QUALITY <= 82);
        assertTrue(JpegStreamTuning.MAX_LONG_EDGE <= 2048);
        assertTrue(JpegStreamTuning.CAPTURE_GAP_MS >= 250);
        assertTrue(JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_CAPTURE
                < JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_SEND);
    }
}
