package org.client.scrcpy;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Real Android Looper + BitmapFactory smoke test of burst spacing.
 * Does not start or authorize any screen capture or network session.
 */
@RunWith(AndroidJUnit4.class)
public final class JpegFrameRendererTimingTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    private static byte[] picture(int color) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream));
        bitmap.recycle();
        byte[] jpeg = stream.toByteArray();
        byte[] packet = new byte[10 + jpeg.length];
        packet[0] = 2;
        packet[1] = 0;
        System.arraycopy(jpeg, 0, packet, 10, jpeg.length);
        assertTrue(ScrcpyRelayPacketPolicy.allowedJpegPayload(packet));
        return packet;
    }

    private static boolean matchesColor(ImageView view, int expected) {
        Drawable drawable = view.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return false;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return false;
        int color = bitmap.getPixel(40, 40);
        if (expected == Color.RED)
            return Color.red(color) > Color.green(color) + 90;
        return Color.green(color) > Color.red(color) + 90;
    }

    @Test public void burstOfRealJpegsDoesNotFlashBeforeMinimumDisplayInterval()
            throws Exception {
        final AtomicReference<ImageView> view = new AtomicReference<>();
        final AtomicReference<JpegFrameRenderer> renderer = new AtomicReference<>();
        final AtomicLong firstAt = new AtomicLong();
        final CountDownLatch first = new CountDownLatch(1);
        final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();

        inst.runOnMainSync(() -> {
            ImageView iv = new ImageView(inst.getTargetContext());
            JpegFrameRenderer player = new JpegFrameRenderer(iv);
            player.setFirstFrameCallback(() -> {
                firstAt.set(SystemClock.uptimeMillis());
                first.countDown();
            });
            player.startSession(SESSION);
            view.set(iv);
            renderer.set(player);
        });
        try {
            renderer.get().onPacket(SESSION, picture(Color.RED));
            assertTrue("first JPEG never displayed", first.await(4, TimeUnit.SECONDS));
            renderer.get().onPacket(SESSION, picture(Color.GREEN));
            Thread.sleep(110);

            if (SystemClock.uptimeMillis() - firstAt.get()
                    < JpegStreamTuning.MIN_VIEW_DISPLAY_GAP_MS - 40) {
                inst.runOnMainSync(() ->
                        assertTrue("a burst frame flashed too early",
                                matchesColor(view.get(), Color.RED)));
            }
            boolean greenShown = false;
            for (int i = 0; i < 36 && !greenShown; i++) {
                final boolean[] green = {false};
                inst.runOnMainSync(() -> green[0] = matchesColor(view.get(), Color.GREEN));
                greenShown = green[0];
                if (!greenShown) Thread.sleep(50);
            }
            assertTrue("newest burst frame was not displayed", greenShown);
        } finally {
            inst.runOnMainSync(() -> renderer.get().close());
        }
    }
}
