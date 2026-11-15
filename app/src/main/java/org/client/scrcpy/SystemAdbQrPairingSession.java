package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ExecUtil;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.List;
import java.util.Locale;

/**
 * The VIEWER creates the Android Studio-compatible ADB QR. On the owner's
 * phone, Settings > Wireless debugging > Pair device with QR code starts a
 * system-controlled pairing server. We require the exact random mDNS instance
 * before calling adb pair. QR pairing never bypasses OS approval.
 */
public final class SystemAdbQrPairingSession {
    public interface Listener {
        void onWaiting();
        void onPairing();
        void onSuccess(String verifiedHost, int connectPort);
        void onError(String error);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LanDeviceDiscovery discovery;
    private int requestId;
    private boolean running;
    private long expiresAt;
    private boolean pairingStarted;

    public SystemAdbQrPairingSession(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void stop() {
        requestId++;
        running = false;
        pairingStarted = false;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    public void start(String serviceName, String password,
                      PairedDeviceStore.Peer trustedPeer, Listener listener) {
        stop();
        if (trustedPeer == null || listener == null) return;
        running = true;
        int id = requestId;
        expiresAt = android.os.SystemClock.elapsedRealtime() + 70_000L;
        listener.onWaiting();
        discovery = new LanDeviceDiscovery(appContext, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> ignored) { }
            @Override public void onError(String message) {
                // Keep polling until the deadline; device settings may start
                // the service only after the owner opens the QR scanner.
            }
        });
        discovery.start();
        poll(id, serviceName, password, trustedPeer, listener);
    }

    private void poll(int id, String serviceName, String password,
                      PairedDeviceStore.Peer trustedPeer, Listener listener) {
        if (!running || id != requestId || pairingStarted) return;
        if (android.os.SystemClock.elapsedRealtime() > expiresAt) {
            stop();
            listener.onError("没有发现系统二维码配对服务。请确认 A 手机无线调试已开启，并在系统「使用二维码配对设备」里扫描；若系统没有此选项，请使用配对码方式。");
            return;
        }
        if (discovery != null) {
            for (LanDeviceDiscovery.Device item : discovery.allDiscovered()) {
                if (!serviceName.equals(item.pairingServiceName)
                        || item.pairingPort <= 0
                        || !PairQrCode.isPrivateIpv4(item.address)) {
                    continue;
                }
                pairingStarted = true;
                discovery.stop();
                discovery = null;
                listener.onPairing();
                ThreadUtils.execute(() -> pairDiscovered(
                        id, item, password, trustedPeer, listener));
                return;
            }
        }
        main.postDelayed(() -> poll(id, serviceName, password, trustedPeer, listener), 700);
    }

    private void pairDiscovered(int id, LanDeviceDiscovery.Device device, String password,
                                PairedDeviceStore.Peer trustedPeer, Listener listener) {
        // A discovered service name and address alone do not authenticate a
        // previously paired App peer. Verify the existing shared secret first.
        if (!trustedPeer.host.equals(device.address)) {
            String error = WakeLanClient.request(
                    device.address + ":" + WakeLanService.PORT, trustedPeer.secret);
            if (error != null) {
                main.post(() -> failIfCurrent(id, listener,
                        "发现二维码配对服务，但它不是已通过 App 配对验证的手机。请在 A 手机开启接收服务，再重试。"));
                return;
            }
        }
        ExecUtil.AdbCommandResult result =
                AdbHelper.adbPair(appContext, device.address, device.pairingPort, password);
        boolean success = result.exitCode == 0
                && result.combinedOutput().toLowerCase(Locale.ROOT)
                .contains("successfully paired");
        if (!success) {
            String failure = result.timedOut
                    ? "系统二维码配对超时，请重试或使用6位配对码"
                    : "系统二维码配对失败，错误码 " + result.exitCode
                        + "。请确认 A 手机已完成系统扫码，并尝试配对码备用方式";
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
