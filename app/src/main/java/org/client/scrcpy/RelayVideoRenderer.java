package org.client.scrcpy;

import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.client.scrcpy.decoder.VideoDecoder;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Viewer-side read-only remote scrcpy decoder. MediaCodec output only:
 * no input injection, ADB transport, audio, or foreground notification.
 * An intentionally bounded queue drops stale P-frames instead of allocating
 * unbounded memory when the decoder or screen is slow.
 */
public final class RelayVideoRenderer {
    public interface Listener {
        void onState(String message);
    }

    private final SurfaceView surfaceView;
    private final Listener listener;
    private final SurfaceHolder.Callback surfaceCallback;
    private volatile boolean closed;
    private volatile long generation;
    private final Object pendingLock = new Object();

    private final class FrameTask implements Runnable {
        final String sourceSession;
        final long version;
        final byte[] data;
        FrameTask(String sid, long gen, byte[] packet) {
            sourceSession = sid;
            version = gen;
            data = packet;
        }
        @Override public void run() {
            if (!closed && version == generation && sourceSession.equals(session))
                process(data);
        }
    }

    private final ThreadPoolExecutor decoderThread =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(8),
                    runnable -> new Thread(runnable, "screensync-remote-decoder"),
                    new ThreadPoolExecutor.AbortPolicy());

    private volatile String session = "";
    private String codec = "h264";
    private int videoW, videoH;
    private volatile boolean visibleSurface;
    private boolean configured;
    private boolean waitingForKeyframe = true;
    private byte[] lastConfiguration;
    private VideoDecoder decoder;
    private long lastReportAt;
    private int frameCount;

    public RelayVideoRenderer(SurfaceView view, Listener listener) {
        this.surfaceView = view;
        this.listener = listener;
        Surface initial = view.getHolder().getSurface();
        visibleSurface = initial != null && initial.isValid();
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (closed) return;
                visibleSurface = true;
                decoderThread.execute(() -> {
                    if (!session.isEmpty() && lastConfiguration != null)
                        configure(lastConfiguration);
                });
            }
            @Override public void surfaceChanged(SurfaceHolder holder,
                                                   int format, int width, int height) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                visibleSurface = false;
                if (!closed) decoderThread.execute(RelayVideoRenderer.this::releaseDecoder);
            }
        };
        view.getHolder().addCallback(surfaceCallback);
    }

    public void startSession(String sid, int[] dims, String videoCodec) {
        if (closed || !ScrcpyRelayWire.validSessionId(sid)
                || dims == null || dims.length != 4
                || dims[2] < 16 || dims[3] < 16
                || dims[2] > 8192 || dims[3] > 8192
                || !("h264".equals(videoCodec) || "h265".equals(videoCodec))) {
            listener.onState("收到无效的远程视频编码设置");
            return;
        }
        synchronized (pendingLock) {
            generation++;
            final long version = generation;
            session = sid;
            decoderThread.getQueue().removeIf(task -> task instanceof FrameTask);
            decoderThread.execute(() -> {
            if (version != generation || closed) return;
            releaseDecoder();
            videoW = dims[2];
            videoH = dims[3];
            codec = videoCodec;
            lastConfiguration = null;
            frameCount = 0;
            waitingForKeyframe = true;
            listener.onState("视频接收准备完成：" + videoW + "x" + videoH);
            });
        }
    }

    public void onPacket(String sid, byte[] packet) {
        if (closed || !sid.equals(session)
                || !ScrcpyRelayPacketPolicy.allowedVideoPayload(packet))
            return;
        synchronized (pendingLock) {
            if (closed || !sid.equals(session)) return;
            // Setup/stop tasks must NEVER be evicted by CONFIG or frames.
            // Removing queued FRAME tasks is safe; removing the setup task
            // caused a persistent black screen on the first CONFIG packet.
            if ((packet[1] & 255) == 2) {
                decoderThread.getQueue().removeIf(task -> task instanceof FrameTask);
            } else if (decoderThread.getQueue().size() >= 6) {
                // Bounded buffering: skip stale frames until next keyframe.
                // Keep decoder configure and close tasks intact.
                waitingForKeyframe = true;
                return;
            }
            try {
                decoderThread.execute(new FrameTask(sid, generation, packet));
            } catch (RejectedExecutionException discarded) {
                waitingForKeyframe = true;
            }
        }
    }

    private void process(byte[] packet) {
        if (session.isEmpty()) return;
        int flag = packet[1] & 255;
        if (flag == 4) {
            listener.onState("远程视频发送结束");
            releaseDecoder();
            return;
        }
        if (flag == 2) {
            if (packet.length - 10 > 128 * 1024) {
                listener.onState("视频配置过大，已拒绝");
                return;
            }
            lastConfiguration = Arrays.copyOfRange(packet, 10, packet.length);
            configure(lastConfiguration);
            return;
        }
        if (!configured || decoder == null || !visibleSurface) return;
        if (flag == 1) waitingForKeyframe = false;
        if (waitingForKeyframe) return;
        try {
            decoder.decodeSample(packet, 10, packet.length - 10,
                    SystemClock.elapsedRealtimeNanos() / 1000, flag == 1 ? 1 : 0);
            frameCount++;
            long now = SystemClock.elapsedRealtime();
            if (now - lastReportAt > 3000) {
                lastReportAt = now;
                listener.onState("正在观看远程实时视频（只读），已解码 " + frameCount + " 帧");
            }
        } catch (RuntimeException error) {
            waitingForKeyframe = true;
            listener.onState("解码器异常，需要重新获得关键帧");
        }
    }

    private void configure(byte[] config) {
        if (!visibleSurface || config == null || session.isEmpty()) return;
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        try {
            ByteBuffer csd0, csd1 = null;
            if ("h264".equals(codec)) {
                byte[][] sets = RelayVideoFormat.h264ParameterSets(config);
                csd0 = ByteBuffer.wrap(sets[0]);
                csd1 = ByteBuffer.wrap(sets[1]);
            } else {
                if (config.length < 12 || config.length > 128 * 1024)
                    throw new IllegalArgumentException("Invalid HEVC config");
                csd0 = ByteBuffer.wrap(config);
            }
            releaseDecoder();
            decoder = new VideoDecoder();
            decoder.start();
            decoder.configure(surface, videoW, videoH, csd0, csd1,
                    "h265".equals(codec) ? "video/hevc" : "video/avc");
            configured = true;
            waitingForKeyframe = true;
            listener.onState("解码器已准备，等待视频关键帧");
        } catch (Exception error) {
            releaseDecoder();
            listener.onState("视频编码配置无效或当前设备不支持解码");
        }
    }

    private void releaseDecoder() {
        configured = false;
        waitingForKeyframe = true;
        VideoDecoder old = decoder;
        decoder = null;
        if (old != null) {
            try { old.stop(); } catch (RuntimeException ignored) { }
        }
    }

    public void stopSession() {
        synchronized (pendingLock) {
            generation++;
            session = "";
            if (closed) return;
            decoderThread.getQueue().removeIf(task -> task instanceof FrameTask);
            decoderThread.execute(() -> {
                releaseDecoder();
                lastConfiguration = null;
            });
        }
    }

    public void close() {
        if (closed) return;
        stopSession();
        closed = true;
        surfaceView.getHolder().removeCallback(surfaceCallback);
        decoderThread.shutdown();
    }
}
