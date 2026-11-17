package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Never identify a phone by its IP address, mDNS label or an online ADB port.
 * Beta4 resolves the approved device via authenticated HTTPS group/device IDs
 * and then requires a mutual HMAC proof on the EXACT assigned VPN address.
 *
 * LAN compatibility mode also requires an authenticated receiver before ADB.
 * Neither mode performs ADB discovery against an unverified candidate.
 */
public final class OneTapConnection {
    public enum Failure {
        DEVICE_NOT_FOUND,
        WIRELESS_DEBUGGING_NOT_READY,
        PAIRING_NOT_READY,
        VPN_NOT_READY,
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
        if (AutoRemoteProvisioner.isRegistered(context)) {
            // Registered Beta4 phones are ONLY allowed to use the vetted
            // overlay peer from the current server. No LAN fallback, even
            // when the phone's VPN is temporarily down or its IP changes.
            ThreadUtils.execute(() -> connectVerifiedRemote(request, callback));
        } else if (peer != null) {
            ThreadUtils.execute(() -> connectVerifiedLegacyLocal(request, peer, callback));
        } else {
            fail(request, callback, Failure.PAIRING_NOT_READY);
        }
    }

    private void connectVerifiedRemote(int request, Callback callback) {
        try {
            JSONObject status = AutoRemoteProvisioner.status(context);
            if (!"paired".equals(status.optString("state"))) {
                fail(request, callback, Failure.PAIRING_NOT_READY);
                return;
            }
            // Verifies token-associated device ID, public key, group ID,
            // peer ID and exact dynamic IP. Does not overwrite a live VPN.
            AutoRemoteProvisioner.applyPairedConfig(context, status);
            if (!isCurrent(request)) return;

            String myAddress = status.getString("device_ip");
            String peerAddress = status.getString("peer_ip");
            String sharedSecret = status.getString("shared_secret");
            if (!TrustedPairEndpoint.validPair(myAddress, peerAddress)
                    || !peerAddress.equals(AutoRemoteProvisioner.approvedPeerIp(context))
                    || !myAddress.equals(AutoRemoteProvisioner.approvedLocalIp(context))) {
                fail(request, callback, Failure.IDENTITY_MISMATCH);
                return;
            }

            // Android may expose the newly connected VPN LinkProperties
            // slightly AFTER GoBackend reports UP. Give the assigned address
            // a short bounded window to appear, never pick another VPN IP.
            String actualVpnIp = null;
            for (int attempt = 0; attempt < 6 && isCurrent(request); attempt++) {
                actualVpnIp = RemoteOverlayNetwork.localVpnIp(context);
                if (myAddress.equals(actualVpnIp)) break;
                if (attempt < 5) Thread.sleep(400);
            }
            if (!EmbeddedRemoteTunnel.isActive()
                    || !myAddress.equals(actualVpnIp)) {
                fail(request, callback, Failure.VPN_NOT_READY);
                return;
            }

            PairedDeviceStore.Peer saved = PairedDeviceStore.load(context);
            if (saved == null || !peerAddress.equals(saved.overlayHost)
                    || !sharedSecret.equals(saved.secret)) {
                fail(request, callback, Failure.IDENTITY_MISMATCH);
                return;
            }

            // The endpoint was chosen by the server, not by a LAN scan.
            // Challenge-response proves that the device at this exact IP
            // possesses the correct pair-specific secret.
            // Network switching and Android wireless debugging can take a
            // moment to publish the receiver. Retry ONLY the authenticated
            // server-approved address; never guess a LAN IP or stale port.
            boolean authenticated = false;
            for (int attempt = 0; attempt < 3 && isCurrent(request); attempt++) {
                WakeLanClient.RemoteInfo info = WakeLanClient.queryRemoteInfo(
                        context, peerAddress, sharedSecret);
                if (info.ok() && TrustedPairEndpoint.authenticatedPeerMatches(
                        peerAddress, peerAddress, info.overlayHost)) {
                    authenticated = true;
                    if (TrustedPairEndpoint.validAdbPort(info.adbPort)) {
                        if (checkOnline(peerAddress, info.adbPort)) {
                            complete(request, callback, peerAddress, info.adbPort);
                            return;
                        }
                    }
                }
                if (attempt < 2) Thread.sleep(800);
            }
            fail(request, callback, !authenticated
                    ? Failure.DEVICE_NOT_FOUND
                    : Failure.WIRELESS_DEBUGGING_NOT_READY);
        } catch (Exception error) {
            // Safe failure; do not "repair" the connection by selecting some
            // other local/private IP or a previously authorized ADB device.
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
