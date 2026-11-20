package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ExecUtil;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Source-side, explicitly accepted ADB+scrcpy media-only bridge.
 *
 * The only ADB target is the phone's OWN wireless debugging interface.
 * The relay receives validated VIDEO packets; no raw ADB connection,
 * remote shell, touch or control frame can cross the WSS connection.
 *
 * Lifecycle: created in visible Activity following per-session user approval;
 * caller MUST stop on leave, session stop, revoke or network loss. This
 * component never runs an Android foreground service or MediaProjection.
 */
public final class LocalScrcpyStreamer {
    public interface Listener {
        void onState(String state);
        void onStopped(String reason);
    }

    // Dedicated loopback port: never steal the legacy LAN viewer's 7008.
    private static final int FORWARD_PORT = 7018;
    private final Context app;
    private final NoVpnMediaSocket relay;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket videoSocket;
    private volatile String serial;
    private Thread worker;

    public LocalScrcpyStreamer(Context context, NoVpnMediaSocket relay, Listener listener) {
        this.app = context.getApplicationContext();
        this.relay = relay;
        this.listener = listener;
    }

    public synchronized boolean startAfterLocalApproval() {
        if (!running.compareAndSet(false, true)) return false;
        worker = new Thread(this::run, "screensync-local-scrcpy-video");
        worker.start();
        return true;
    }

    private void status(String message) {
        main.post(() -> listener.onState(message));
    }

    private int screenRotation() {
        WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        return manager == null ? -1 : manager.getDefaultDisplay().getRotation();
    }

    private void run() {
        String endReason = "capturing_stopped";
        try {
            status("正在检查本机无线调试授权");
            // Android wireless-debugging connects to this device's own
            // Wi-Fi interface. Never accept an address from a remote peer.
            String ownIp = PairQrCode.localWifiIp(app);
            if (ownIp == null || !PairQrCode.isPrivateIpv4(ownIp))
                throw new IllegalStateException("本机没有可用的 Wi-Fi 无线调试接口");
            ExecUtil.AdbCommandResult mdns = AdbHelper.adbMdnsServices(app);
            int ownPort = SystemAdbQrPairingSession.findAdbConnectPort(
                    mdns.combinedOutput(), ownIp);
            if (!TrustedPairEndpoint.validAdbPort(ownPort))
                throw new IllegalStateException("本机无线调试未开启或未被发现；请由手机持有人开启");
            if (!AdbHelper.isDeviceOnline(app, ownIp, ownPort))
                throw new IllegalStateException("本机 ADB 未授权；请先完成系统无线调试配对");

            serial = ownIp + ":" + ownPort;
            if (!running.get()) return;
            status("正在启动本机 scrcpy 视频编码");

            // Script already used by the LAN version. It invokes app_process
            // locally and forwards the existing in-app scrcpy TCP socket.
            // Audio and input control are disabled for this bridge.
            AdbHelper.writeAssetsJarServer(app);
            Options opts = new Options();
            opts.setIp("127.0.0.1");
            opts.setMaxSize(720);
            opts.setBitRate(1_200_000);
            opts.setFrameRate(20);
            opts.setVideoCodec(Options.CODEC_H264);
            opts.setVideoEncoder(Options.DEFAULT_ENCODER);
            opts.setTunnelForward(true);
            opts.setEnableAudioForward(false);
            if (new SendCommands().SendAdbCommands(
                    app, ownIp, ownPort, FORWARD_PORT, opts, running::get)
                    != SendCommands.CmdStatus.SUCCESS) {
                throw new IllegalStateException("本机 scrcpy 启动失败，检查无线调试");
            }
            if (!running.get()) return;
            Socket socket = null;
            for (int attempt = 0; attempt < 18 && running.get(); attempt++) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(
                            "127.0.0.1", FORWARD_PORT), 700);
                    break;
                } catch (Exception notReady) {
                    if (socket != null) socket.close();
                    socket = null;
                    Thread.sleep(250);
                }
            }
            if (socket == null || !running.get())
                throw new IllegalStateException("本机 scrcpy 视频端口未就绪");
            videoSocket = socket;
            socket.setSoTimeout(8000);
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                ScrcpyLocalFrameReader frames = new ScrcpyLocalFrameReader(in);
                int[] dims = frames.readDimensions();
                int lastRotation = screenRotation();
                boolean sawVideoFrame = false;
                if (!relay.sendVideoSetup(dims, Options.CODEC_H264))
                    throw new IllegalStateException("观看会话已经结束或 WSS 不可用");
                status("本机视频正在向已授权的观看端发送");
                long count = 0;
                while (running.get()) {
                    byte[] packet = frames.readPacket();
                    if (packet == null) continue; // never forward non-video
                    if ((packet[1] & 255) == 2 && sawVideoFrame) {
                        int nextRotation = screenRotation();
                        if (lastRotation >= 0 && nextRotation >= 0
                                && nextRotation != lastRotation) {
                            dims = RelayVideoFormat.forRotation(
                                    dims, lastRotation, nextRotation);
                            if (!relay.sendVideoSetup(dims, Options.CODEC_H264))
                                throw new IllegalStateException("旋转时远端视频会话已结束");
                            lastRotation = nextRotation;
                        }
                    }
                    if (!relay.sendVideoPacket(packet))
                        throw new IllegalStateException("已停止发送：WSS 拥塞或观看会话终止");
                    if (packet[1] == 4) break; // scrcpy END
                    if (packet[1] == 0 || packet[1] == 1)
                        sawVideoFrame = true;
                    count++;
                    if (count % 120 == 0)
                        status("已发送 " + count + " 个只读视频包");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            endReason = "用户已停止";
        } catch (Exception error) {
            endReason = error.getMessage() == null ? "本机视频已断开" : error.getMessage();
        } finally {
            running.set(false);
            Socket sock = videoSocket;
            videoSocket = null;
            if (sock != null) try { sock.close(); } catch (Exception ignored) { }
            // Do not remove a port forward not owned by this stream.
            String target = serial;
            serial = null;
            if (target != null) {
                AdbHelper.adbCmd(app, "-s", target,
                        "forward", "--remove", "tcp:" + FORWARD_PORT);
            }
            final String message = endReason;
            main.post(() -> listener.onStopped(message));
        }
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        Socket socket = videoSocket;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }
}
