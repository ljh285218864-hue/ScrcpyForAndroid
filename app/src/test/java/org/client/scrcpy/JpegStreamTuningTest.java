package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JpegStreamTuningTest {
    @Test public void portraitGetsSharperWithoutExceedingDecoderLimit() {
        assertArrayEquals(new int[]{540, 1200}, JpegStreamTuning.targetSize(1080, 2400));
        assertArrayEquals(new int[]{1200, 540}, JpegStreamTuning.targetSize(2400, 1080));
    }

    @Test public void smallScreensAreNeverUpscaled() {
        assertArrayEquals(new int[]{360, 800}, JpegStreamTuning.targetSize(360, 800));
    }

    @Test public void profileRespectsFourFpsServerLimit() {
        assertTrue(JpegStreamTuning.JPEG_QUALITY >= 70);
        assertTrue(JpegStreamTuning.JPEG_QUALITY <= 82);
        assertTrue(JpegStreamTuning.MAX_LONG_EDGE <= 2048);
        assertTrue(JpegStreamTuning.CAPTURE_GAP_MS >= 250);
        assertTrue(JpegStreamTuning.MIN_CAPTURE_INTERVAL_MS >= 300);
        assertTrue(JpegStreamTuning.MIN_JPEG_QUALITY >= 45);
        assertTrue(JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_CAPTURE
                < JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_SEND);
    }
}
