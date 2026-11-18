package org.client.scrcpy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Display only the latest authenticated JPEG; no old-frame backlog. */
public final class JpegFrameRenderer {
    private final ImageView imageView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile String session = "";

    public JpegFrameRenderer(ImageView imageView) {
        this.imageView = imageView;
    }

    public void startSession(String sessionId) {
        session = sessionId;
        generation.incrementAndGet();
        decoder.getQueue().clear();
        ui.post(() -> imageView.setImageDrawable(null));
    }

    public void onPacket(String sessionId, byte[] packet) {
        if (!sessionId.equals(session) || !ScrcpyRelayPacketPolicy.allowedJpegPayload(packet))
            return;
        final int version = generation.get();
        final byte[] jpeg = Arrays.copyOfRange(packet, 10, packet.length);
        decoder.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (opts.outWidth < 16 || opts.outHeight < 16
                    || opts.outWidth > 2048 || opts.outHeight > 2048
                    || (long)opts.outWidth * opts.outHeight > 4000000L) return;
            opts.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bitmap == null) return;
            ui.post(() -> {
                if (version == generation.get() && sessionId.equals(session)) {
                    imageView.setImageBitmap(bitmap);
                } else bitmap.recycle();
            });
        });
    }

    public void stopSession() {
        session = "";
        generation.incrementAndGet();
        decoder.getQueue().clear();
        ui.post(() -> imageView.setImageDrawable(null));
    }

    public void close() {
        stopSession();
        decoder.shutdownNow();
    }
}
