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

/**
 * Latest-frame-only JPEG renderer. Bounds the decode queue AND the UI pending
 * frame; otherwise a blocked main thread can accumulate many old Bitmaps.
 */
public final class JpegFrameRenderer {
    private final ImageView imageView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Object uiFrameGuard = new Object();
    private Bitmap pendingUiBitmap;
    // Only touched on the main thread. The previous displayed bitmap must
    // not accumulate until delayed GC during a long watching session.
    private Bitmap displayedBitmap;
    private static final long RETIRED_FRAME_GRACE_MS = 350L;
    private String pendingUiSession = "";
    private int pendingUiVersion;
    private boolean uiFrameScheduled;
    private volatile String session = "";
    private int incoming, decoded, dropped;
    private Runnable firstFrameCallback;

    public JpegFrameRenderer(ImageView imageView) {
        this.imageView = imageView;
    }

    /** Invoked on main thread only after the first JPEG has actually rendered. */
    public void setFirstFrameCallback(Runnable onFirstFrame) {
        firstFrameCallback = onFirstFrame;
    }

    public boolean hasDisplayedFrame() { return decoded > 0; }

    public void startSession(String sessionId) {
        session = sessionId;
        final int version = generation.incrementAndGet();
        incoming = decoded = dropped = 0;
        ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                "session_started");
        decoder.getQueue().clear();
        clearPendingUiFrame();
        ui.post(() -> {
            if (version == generation.get()) clearDisplayedFrame();
        });
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
                        || (long) opts.outWidth * opts.outHeight > 4000000L) {
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
                synchronized (uiFrameGuard) {
                    if (version != generation.get() || !sessionId.equals(session)) {
                        bitmap.recycle();
                        return;
                    }
                    if (pendingUiBitmap != null) {
                        // The previously decoded frame was never displayed:
                        // replacing it cannot skip any user-visible state.
                        pendingUiBitmap.recycle();
                        dropped++;
                    }
                    pendingUiBitmap = bitmap;
                    pendingUiVersion = version;
                    pendingUiSession = sessionId;
                    if (!uiFrameScheduled) {
                        uiFrameScheduled = true;
                        ui.post(this::displayNewestFrame);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing the viewer must not crash on a late network frame.
        }
    }

    /** Executes at most one pending frame update per main-thread dispatch. */
    private void displayNewestFrame() {
        Bitmap bitmap;
        String sessionId;
        int version;
        synchronized (uiFrameGuard) {
            bitmap = pendingUiBitmap;
            pendingUiBitmap = null;
            sessionId = pendingUiSession;
            version = pendingUiVersion;
            uiFrameScheduled = false;
        }
        if (bitmap == null) return;
        if (version == generation.get() && sessionId.equals(session)) {
            Bitmap previous = displayedBitmap;
            imageView.setImageBitmap(bitmap);
            displayedBitmap = bitmap;
            retireDisplayedFrame(previous);
            decoded++;
            if (decoded == 1 && firstFrameCallback != null)
                firstFrameCallback.run();
            if (decoded == 1 || decoded % 25 == 0)
                ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_DISPLAY",
                        "frames=" + decoded + " decoded_discarded=" + dropped);
        } else {
            bitmap.recycle();
        }
    }

    /** Main-thread only. Give RenderThread time to finish any in-flight draw
     * before releasing the old native bitmap allocation. */
    private void retireDisplayedFrame(Bitmap previous) {
        if (previous == null) return;
        ui.postDelayed(() -> {
            if (previous != displayedBitmap && !previous.isRecycled()) previous.recycle();
        }, RETIRED_FRAME_GRACE_MS);
    }

    private void clearDisplayedFrame() {
        imageView.setImageDrawable(null);
        Bitmap previous = displayedBitmap;
        displayedBitmap = null;
        retireDisplayedFrame(previous);
    }

    private void clearPendingUiFrame() {
        synchronized (uiFrameGuard) {
            if (pendingUiBitmap != null) pendingUiBitmap.recycle();
            pendingUiBitmap = null;
            pendingUiSession = "";
        }
    }

    public void stopSession() {
        if (!session.isEmpty())
            ScreenSyncDiagnostics.record(imageView.getContext(), "JPEG_VIEW",
                    "stopped received=" + incoming + " displayed=" + decoded
                    + " dropped=" + dropped);
        session = "";
        decoded = 0;
        final int version = generation.incrementAndGet();
        decoder.getQueue().clear();
        clearPendingUiFrame();
        ui.post(() -> {
            if (version == generation.get()) clearDisplayedFrame();
        });
    }

    public void close() {
        stopSession();
        decoder.shutdownNow();
    }
}
