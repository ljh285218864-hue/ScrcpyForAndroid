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
    private int incoming, decoded, dropped;

    public JpegFrameRenderer(ImageView imageView) {
        this.imageView = imageView;
    }

    public void startSession(String sessionId) {
        session = sessionId;
        generation.incrementAndGet();
        incoming = decoded = dropped = 0;
        ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                "session_started");
        decoder.getQueue().clear();
        ui.post(() -> imageView.setImageDrawable(null));
    }

    public void onPacket(String sessionId, byte[] packet) {
        if (!sessionId.equals(session) || !ScrcpyRelayPacketPolicy.allowedJpegPayload(packet)) {
            if (++dropped == 1)
                ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_DROP",
                        "wrong_session_or_payload");
            return;
        }
        incoming++;
        if (incoming == 1 || incoming % 25 == 0)
            ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_RX",
                    "frames=" + incoming + " bytes=" + packet.length);
        final int version = generation.get();
        final byte[] jpeg = Arrays.copyOfRange(packet, 10, packet.length);
        try {
        decoder.execute(() -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (opts.outWidth < 16 || opts.outHeight < 16
                    || opts.outWidth > 2048 || opts.outHeight > 2048
                    || (long)opts.outWidth * opts.outHeight > 4000000L) {
                ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_DECODE_FAIL",
                        "invalid_dimensions=" + opts.outWidth + "x" + opts.outHeight);
                return;
            }
            opts.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bitmap == null) {
                ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_DECODE_FAIL",
                        "bitmap_null");
                return;
            }
            ui.post(() -> {
                if (version == generation.get() && sessionId.equals(session)) {
                    imageView.setImageBitmap(bitmap);
                    decoded++;
                    if (decoded == 1 || decoded % 25 == 0)
                        ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_DISPLAY",
                                "frames=" + decoded);
                } else bitmap.recycle();
            });
        });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing the viewer must not crash on a late network frame.
        }
    }

    public void stopSession() {
        if (!session.isEmpty())
            ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                    "stopped received=" + incoming + " displayed=" + decoded);
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
