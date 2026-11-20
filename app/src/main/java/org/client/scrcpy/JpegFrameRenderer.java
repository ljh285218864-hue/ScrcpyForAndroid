package org.client.scrcpy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Render only the latest consented JPEG: one worker, one queued decode and
 * one pending UI frame. Frames arriving faster than the UI can draw are
 * dropped instead of showing an increasingly stale screen.
 */
public final class JpegFrameRenderer {
    private static final class PendingFrame {
        final Bitmap bitmap;
        final int generation;
        final String sessionId;
        PendingFrame(Bitmap bitmap, int generation, String sessionId) {
            this.bitmap = bitmap;
            this.generation = generation;
            this.sessionId = sessionId;
        }
    }

    private final ImageView imageView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<PendingFrame> latest = new AtomicReference<>();
    private final AtomicBoolean drawScheduled = new AtomicBoolean();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile String session = "";
    private int incoming, decoded, dropped;
    private Runnable firstFrameCallback;

    public JpegFrameRenderer(ImageView imageView) {
        this.imageView = imageView;
    }

    /** Invoked on the main thread after the first real displayed frame. */
    public void setFirstFrameCallback(Runnable onFirstFrame) {
        firstFrameCallback = onFirstFrame;
    }

    public boolean hasDisplayedFrame() { return decoded > 0; }

    public void startSession(String sessionId) {
        session = sessionId;
        int version = generation.incrementAndGet();
        incoming = decoded = dropped = 0;
        decoder.getQueue().clear();
        clearPendingBitmap();
        ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                "session_started");
        ui.post(() -> {
            if (version == generation.get()) imageView.setImageDrawable(null);
        });
    }

    public void onPacket(String sessionId, byte[] packet) {
        if (!sessionId.equals(session)
                || !ScrcpyRelayPacketPolicy.allowedJpegPayload(packet)) {
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
        // packet already contains the complete bounded JPEG at offset 10:
        // no Arrays.copyOfRange allocation for every frame.
        try {
            decoder.execute(() -> {
                if (version != generation.get()) return;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(packet, 10, packet.length - 10, opts);
                if (opts.outWidth < 16 || opts.outHeight < 16
                        || opts.outWidth > 2048 || opts.outHeight > 2048
                        || (long) opts.outWidth * opts.outHeight > 4000000L) {
                    ScreenSyncDiagnostics.record(imageView.getContext(),
                            "JPEG_DECODE_FAIL",
                            "invalid_dimensions=" + opts.outWidth + "x" + opts.outHeight);
                    return;
                }
                opts.inJustDecodeBounds = false;
                Bitmap bitmap;
                try {
                    bitmap = BitmapFactory.decodeByteArray(
                            packet, 10, packet.length - 10, opts);
                } catch (RuntimeException | OutOfMemoryError failure) {
                    ScreenSyncDiagnostics.record(imageView.getContext(),
                            "JPEG_DECODE_FAIL",
                            "exception=" + failure.getClass().getSimpleName());
                    return;
                }
                if (bitmap == null) {
                    ScreenSyncDiagnostics.record(imageView.getContext(),
                            "JPEG_DECODE_FAIL", "bitmap_null");
                    return;
                }
                if (version != generation.get() || !sessionId.equals(session)) {
                    bitmap.recycle();
                    return;
                }
                PendingFrame old = latest.getAndSet(
                        new PendingFrame(bitmap, version, sessionId));
                if (old != null) old.bitmap.recycle();
                requestDraw();
            });
        } catch (RejectedExecutionException ignored) {
            // Late network packets cannot crash a closed viewer.
        }
    }

    private void requestDraw() {
        if (drawScheduled.compareAndSet(false, true))
            ui.post(this::drawLatest);
    }

    private void drawLatest() {
        PendingFrame next = latest.getAndSet(null);
        // Release the scheduling flag before checking for a newer frame.
        drawScheduled.set(false);
        if (next != null) {
            if (next.generation == generation.get()
                    && next.sessionId.equals(session)) {
                // The ImageView owns this displayed Bitmap until replaced.
                imageView.setImageBitmap(next.bitmap);
                decoded++;
                if (decoded == 1 && firstFrameCallback != null)
                    firstFrameCallback.run();
                if (decoded == 1 || decoded % 25 == 0)
                    ScreenSyncDiagnostics.record(imageView.getContext(),
                            "JPEG_DISPLAY", "frames=" + decoded);
            } else next.bitmap.recycle();
        }
        if (latest.get() != null) requestDraw();
    }

    private void clearPendingBitmap() {
        PendingFrame old = latest.getAndSet(null);
        if (old != null) old.bitmap.recycle();
    }

    public void stopSession() {
        if (!session.isEmpty())
            ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                    "stopped received=" + incoming + " displayed=" + decoded);
        session = "";
        decoded = 0;
        int version = generation.incrementAndGet();
        decoder.getQueue().clear();
        clearPendingBitmap();
        ui.post(() -> {
            if (version == generation.get()) imageView.setImageDrawable(null);
        });
    }

    public void close() {
        stopSession();
        decoder.shutdownNow();
    }
}
