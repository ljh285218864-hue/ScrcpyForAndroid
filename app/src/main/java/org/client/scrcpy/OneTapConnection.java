package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Never identify a phone by its IP address, mDNS label or an online ADB port.
 * LAN mode requires an authenticated receiver before ADB. The no-VPN
 * remote media relay never tunnels ADB or guesses remote device IP addresses.
 * This local connector cannot be used as a fallback for registered devices.
 */
public final class OneTapConnection {
    public enum Failure {
        DEVICE_NOT_FOUND,
        WIRELESS_DEBUGGING_NOT_READY,
        PAIRING_NOT_READY,
        IDENTITY_MISMATCH
    }

    public interface Callback {
        void onStage(int stringRes);
        void onConnected(String host, int adbPort);
        void onFailed(Failure reason);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LanDeviceDiscovery discovery;
    private volatile int generation;
    private volatile boolean active;

    public OneTapConnection(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isRunning() {
        return active;
    }

    public void cancel() {
        generation++;
        active = false;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    public void start(PairedDeviceStore.Peer peer, Callback callback) {
        if (callback == null || active) return;
        active = true;
        final int request = ++generation;
        callback.onStage(R.string.quick_stage_saved_connection);
        if (NoVpnApi.isRegistered(context)) {
            // Remote sessions must use the separately authorized video-only
            // server transport. No fallback to raw ADB or old LAN IP.
            fail(request, callback, Failure.PAIRING_NOT_READY);
        } else if (peer != null) {
            ThreadUtils.execute(() -> connectVerifiedLegacyLocal(request, peer, callback));
        } else {
            fail(request, callback, Failure.PAIRING_NOT_READY);
        }
    }

    private void connectVerifiedLegacyLocal(
            int request, PairedDeviceStore.Peer peer, Callback callback) {
        if (!PairQrCode.isPrivateIpv4(peer.host)) {
            fail(request, callback, Failure.DEVICE_NOT_FOUND);
            return;
        }
        // Even a stored address can now belong to a different LAN phone
        // after DHCP renewal. Require fresh, mutual HMAC proof.
        WakeLanClient.RemoteInfo info = WakeLanClient.queryRemoteInfo(
                context, peer.host, peer.secret);
        if (info.ok()) {
            int port = TrustedPairEndpoint.validAdbPort(info.adbPort)
                    ? info.adbPort : peer.adbPort;
            if (TrustedPairEndpoint.validAdbPort(port)
                    && checkOnline(peer.host, port)) {
                complete(request, callback, peer.host, port);
                return;
            }
        }
        main.post(() -> {
            if (isCurrent(request)) scanForVerifiedLanPeer(request, peer, callback);
        });
    }

    private boolean checkOnline(String host, int port) {
        try {
            return AdbHelper.isDeviceOnline(context, host, port);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean isCurrent(int request) {
        return active && generation == request;
    }

    private void scanForVerifiedLanPeer(
            int request, PairedDeviceStore.Peer peer, Callback callback) {
        callback.onStage(R.string.quick_stage_discovering);
        discovery = new LanDeviceDiscovery(context, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> devices) { }
            @Override public void onError(String message) { }
        });
        discovery.start();
        main.postDelayed(() -> {
            if (!isCurrent(request) || discovery == null) return;
            List<LanDeviceDiscovery.Device> candidates =
                    new ArrayList<>(discovery.available());
            discovery.stop();
            discovery = null;
            callback.onStage(R.string.quick_stage_authorizing);
            ThreadUtils.execute(() -> verifyLanCandidates(
                    request, peer, callback, candidates));
        }, 8000);
    }

    private void verifyLanCandidates(
            int request, PairedDeviceStore.Peer peer, Callback callback,
            List<LanDeviceDiscovery.Device> candidates) {
        boolean provenDeviceFound = false;
        for (LanDeviceDiscovery.Device candidate : candidates) {
            if (!isCurrent(request)) return;
            if (candidate.wakePort != WakeLanService.PORT
                    || !PairQrCode.isPrivateIpv4(candidate.address)) continue;

            WakeLanClient.RemoteInfo info = WakeLanClient.queryRemoteInfo(
                    context, candidate.address, peer.secret);
            if (!info.ok()) continue; // Name and IP alone prove nothing.
            provenDeviceFound = true;
            int port = TrustedPairEndpoint.validAdbPort(info.adbPort)
                    ? info.adbPort
                    : TrustedPairEndpoint.validAdbPort(candidate.adbPort)
                        ? candidate.adbPort
                        : candidate.address.equals(peer.host) ? peer.adbPort : 0;
            if (!TrustedPairEndpoint.validAdbPort(port)) continue;
            if (checkOnline(candidate.address, port)) {
                complete(request, callback, candidate.address, port);
                return;
            }
        }
        final Failure reason = provenDeviceFound
                ? Failure.WIRELESS_DEBUGGING_NOT_READY
                : Failure.DEVICE_NOT_FOUND;
        fail(request, callback, reason);
    }

    private void fail(int request, Callback callback, Failure reason) {
        main.post(() -> {
            if (!isCurrent(request)) return;
            cancel();
            callback.onFailed(reason);
        });
    }

    private void complete(int request, Callback callback, String host, int port) {
        main.post(() -> {
            if (!isCurrent(request)) return;
            cancel();
            callback.onConnected(host, port);
        });
    }
}
