package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ExecUtil;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.List;
import java.util.Locale;

/**
 * Android Studio-compatible system QR pairing.
 *
 * The TARGET phone scans the QR through Android Settings and advertises a
 * random-named _adb-tls-pairing._tcp service. The VIEWER must discover the
 * exact service and actively run adb pair with the QR password.
 *
 * App-level NSD is not reliable on every OEM device. Also poll the native ADB
 * server's own mDNS browser, without requiring IP/port entry. Every session
 * has a hard UI timeout even after an endpoint is discovered.
 */
public final class SystemAdbQrPairingSession {
    private static final String TAG = "SystemAdbQrPair";
    private static final long OVERALL_TIMEOUT_MS = 65_000L;
    private static final long NATIVE_DISCOVERY_INTERVAL_MS = 4_000L;
    private static final String PAIR_TYPE = "_adb-tls-pairing._tcp";

    public interface Listener {
        void onWaiting();
        void onPairing();
        void onSuccess(String verifiedHost, int connectPort);
        void onError(String error);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LanDeviceDiscovery discovery;
    private Runnable deadline;
    private int requestId;
    private boolean running;
    private boolean pairingStarted;
    private boolean nativeProbeRunning;
    private long nextNativeProbe;

    public SystemAdbQrPairingSession(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void stop() {
        requestId++;
        running = false;
        pairingStarted = false;
        nativeProbeRunning = false;
        if (deadline != null) {
            main.removeCallbacks(deadline);
            deadline = null;
        }
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    public void start(String serviceName, String password,
                      PairedDeviceStore.Peer trustedPeer, Listener listener) {
        stop();
        if (trustedPeer == null || listener == null
                || serviceName == null || password == null) return;

        running = true;
        final int id = requestId;
        nextNativeProbe = 0L;
        listener.onWaiting();
        deadline = () -> failIfCurrent(id, listener,
                pairingStarted
                        ? "已经找到系统配对服务，但 ADB 配对没有在限定时间内完成。请返回无线调试重新扫描，或改用自动查找配对码方式。"
                        : "扫码后未发现 Android 系统配对服务。请确认两台手机连同一 Wi-Fi，关闭访客网络或设备隔离；也可以使用自动查找，仅输入系统6位码。");
        main.postDelayed(deadline, OVERALL_TIMEOUT_MS);

        discovery = new LanDeviceDiscovery(appContext, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> devices) {
                // The Android NSD callback contains only wake-enabled devices.
                // poll() reads allDiscovered(), including pairing-only entries.
            }

            @Override public void onError(String message) {
                Log.w(TAG, "NSD discovery: " + message);
                // Native adb mdns discovery is the independent fallback.
            }
        });
        discovery.start();
        poll(id, serviceName, password, trustedPeer, listener);
    }

    private void poll(int id, String serviceName, String password,
                      PairedDeviceStore.Peer trustedPeer, Listener listener) {
        if (!running || id != requestId || pairingStarted) return;

        if (discovery != null) {
            for (LanDeviceDiscovery.Device entry : discovery.allDiscovered()) {
                if (serviceName.equals(entry.pairingServiceName)
                        && entry.pairingPort > 0
                        && PairQrCode.isPrivateIpv4(entry.address)) {
                    beginPairing(id, entry, password, trustedPeer, listener);
                    return;
                }
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (!nativeProbeRunning && now >= nextNativeProbe) {
            nativeProbeRunning = true;
            nextNativeProbe = now + NATIVE_DISCOVERY_INTERVAL_MS;
            ThreadUtils.execute(() -> {
                LanDeviceDiscovery.Device nativeMatch = null;
                try {
                    ExecUtil.AdbCommandResult result =
                            AdbHelper.adbMdnsServices(appContext);
                    nativeMatch = findMatchingAdbMdnsService(
                            result.combinedOutput(), serviceName);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Native adb mdns scan failed", error);
                }
                final LanDeviceDiscovery.Device candidate = nativeMatch;
                main.post(() -> {
                    if (!running || id != requestId) return;
                    nativeProbeRunning = false;
                    if (!pairingStarted && candidate != null) {
                        beginPairing(id, candidate, password, trustedPeer, listener);
                    }
                });
            });
        }
        main.postDelayed(() -> poll(id, serviceName, password, trustedPeer, listener), 750);
    }

    /**
     * adb mdns services outputs three whitespace-separated columns:
     *   studio-RANDOM _adb-tls-pairing._tcp 192.168.1.15:42741
     * Only the exact unpredictable QR service name may trigger pairing.
     * Arbitrary IPs or public addresses from mDNS output are rejected.
     */
    static LanDeviceDiscovery.Device findMatchingAdbMdnsService(
            String output, String expectedName) {
        if (output == null || expectedName == null || expectedName.isEmpty()) return null;
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !expectedName.equals(parts[0])
                    || !PAIR_TYPE.equals(parts[1])) continue;
            String endpoint = parts[2];
            int colon = endpoint.lastIndexOf(':');
            if (colon < 1 || colon == endpoint.length() - 1) continue;
            String ip = endpoint.substring(0, colon);
            if (!PairQrCode.isPrivateIpv4(ip)) continue;
            try {
                int port = Integer.parseInt(endpoint.substring(colon + 1));
                if (port > 0 && port <= 65535) {
                    return new LanDeviceDiscovery.Device(
                            expectedName, ip, 0, 0, port, expectedName);
                }
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private void beginPairing(int id, LanDeviceDiscovery.Device device,
                              String password, PairedDeviceStore.Peer trustedPeer,
                              Listener listener) {
        if (!running || id != requestId || pairingStarted) return;
        pairingStarted = true;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        listener.onPairing();
        ThreadUtils.execute(() -> {
            try {
                pairDiscovered(id, device, password, trustedPeer, listener);
            } catch (RuntimeException error) {
                Log.e(TAG, "ADB pair task crashed", error);
                main.post(() -> failIfCurrent(id, listener,
                        "ADB 配对过程发生异常。请重新生成二维码扫码；如果仍失败，请改用自动发现配对服务的6位码方式。"));
            }
        });
    }

    private void pairDiscovered(int id, LanDeviceDiscovery.Device device,
                                String password, PairedDeviceStore.Peer trustedPeer,
                                Listener listener) {
        // Never trust a *new* IP solely because it published a QR service.
        // The paired app's original secret must authenticate an IP change.
        if (!trustedPeer.host.equals(device.address)) {
            String error = WakeLanClient.request(
                    device.address + ":" + WakeLanService.PORT, trustedPeer.secret);
            if (error != null) {
                main.post(() -> failIfCurrent(id, listener,
                        "系统扫码服务已出现，但设备地址变化且没有通过原 App 的身份验证。请在被观看手机开启接收服务后重新扫码。"));
                return;
            }
        }

        ExecUtil.AdbCommandResult result =
                AdbHelper.adbPair(appContext, device.address, device.pairingPort, password);
        boolean success = result.exitCode == 0
                && result.combinedOutput().toLowerCase(Locale.ROOT)
                .contains("successfully paired");

        if (!success) {
            String output = result.combinedOutput().replace(password, "******").trim();
            if (output.length() > 180) output = output.substring(0, 180);
            String error = result.timedOut
                    ? "已找到系统配对服务，但 ADB 握手超时。请保持两台手机在同一 Wi-Fi 并重新扫码。"
                    : result.exitCode == -1 && output.isEmpty()
                    ? "系统配对进程没有返回结果，请重新打开无线调试并扫码。"
                    : "已找到系统配对服务，但 ADB 配对失败（退出码 "
                        + result.exitCode + "）。" + (output.isEmpty() ? "" : "\\n" + output);
            final String failure = error;
            main.post(() -> failIfCurrent(id, listener, failure));
            return;
        }

        main.post(() -> {
            if (!running || id != requestId) return;
            stop();
            listener.onSuccess(device.address, device.adbPort);
        });
    }

    private void failIfCurrent(int id, Listener listener, String message) {
        if (!running || id != requestId) return;
        stop();
        listener.onError(message);
    }
}
