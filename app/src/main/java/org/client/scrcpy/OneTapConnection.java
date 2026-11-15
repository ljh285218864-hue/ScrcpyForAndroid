package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects to an already owner-approved local peer, without ever granting ADB,
 * turning on wireless debugging, or starting a screen stream on the remote.
 * Call start/cancel on the main thread.
 */
public final class OneTapConnection {
    public enum Failure {
        DEVICE_NOT_FOUND,
        WIRELESS_DEBUGGING_NOT_READY
    }

    public interface Callback {
        void onStage(int stringRes);
        void onConnected(String host, int adbPort);
        void onFailed(Failure reason);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LanDeviceDiscovery discovery;
    private int generation;
    private boolean active;

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
        if (peer == null || callback == null || active) return;
        active = true;
        final int request = ++generation;
        callback.onStage(R.string.quick_stage_saved_connection);
        ThreadUtils.execute(() -> {
            boolean online = peer.adbPort > 0
                    && checkOnline(peer.host, peer.adbPort);
            main.post(() -> {
                if (!isCurrent(request)) return;
                if (online) {
                    complete(request, callback, peer.host, peer.adbPort);
                } else {
                    scanForPeer(request, peer, callback);
                }
            });
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

    private void scanForPeer(int request, PairedDeviceStore.Peer peer,
                             Callback callback) {
        callback.onStage(R.string.quick_stage_discovering);
        discovery = new LanDeviceDiscovery(context, new LanDeviceDiscovery.Listener() {
            @Override public void onChanged(List<LanDeviceDiscovery.Device> devices) {
                // Allow mDNS time to resolve wake and ADB services on the same host.
            }

            @Override public void onError(String message) {
                // A missing mDNS broadcast is handled by the explicit fallback.
            }
        });
        discovery.start();
        main.postDelayed(() -> {
            if (!isCurrent(request) || discovery == null) return;
            List<LanDeviceDiscovery.Device> candidates =
                    new ArrayList<>(discovery.available());
            discovery.stop();
            discovery = null;
            callback.onStage(R.string.quick_stage_authorizing);
            ThreadUtils.execute(() -> verifyCandidates(request, peer, callback, candidates));
        }, 8000);
    }

    private void verifyCandidates(int request, PairedDeviceStore.Peer peer,
                                  Callback callback,
                                  List<LanDeviceDiscovery.Device> candidates) {
        boolean trustedPeerFound = false;
        for (LanDeviceDiscovery.Device candidate : candidates) {
            if (!isCurrent(request)) return;
            if (candidate.wakePort != WakeLanService.PORT) continue;
            // A matching name or IP is not sufficient. Require the owner-approved
            // long-lived pairing secret before trusting any discovered endpoint.
            String failure = WakeLanClient.request(
                    candidate.address + ":" + candidate.wakePort, peer.secret);
            if (failure != null) continue;
            trustedPeerFound = true;
            int candidatePort = candidate.adbPort > 0 ? candidate.adbPort
                    : candidate.address.equals(peer.host) ? peer.adbPort : 0;
            if (candidatePort <= 0) continue;
            final int port = candidatePort;
            if (checkOnline(candidate.address, port)) {
                main.post(() -> {
                    if (isCurrent(request)) {
                        complete(request, callback, candidate.address, port);
                    }
                });
                return;
            }
        }
        // Direct authenticated ADB fallback for the saved App-paired host.
        // On some Android ROMs the ScreenSync wake mDNS record is suppressed,
        // while the official ADB TLS connect service remains discoverable.
        // This does not bypass Android's own TLS pairing/authorization.
        if (isCurrent(request)) {
            try {
                org.client.scrcpy.utils.ExecUtil.AdbCommandResult mdns =
                        AdbHelper.adbMdnsServices(context);
                int port = SystemAdbQrPairingSession.findAdbConnectPort(
                        mdns.combinedOutput(), peer.host);
                if (port > 0 && checkOnline(peer.host, port)) {
                    final int verifiedPort = port;
                    main.post(() -> {
                        if (isCurrent(request)) {
                            complete(request, callback, peer.host, verifiedPort);
                        }
                    });
                    return;
                }
            } catch (RuntimeException error) {
                android.util.Log.w("Scrcpy", "Saved peer ADB mDNS fallback failed", error);
            }
        }

        final Failure reason = trustedPeerFound
                ? Failure.WIRELESS_DEBUGGING_NOT_READY
                : Failure.DEVICE_NOT_FOUND;
        main.post(() -> {
            if (!isCurrent(request)) return;
            cancel();
            callback.onFailed(reason);
        });
    }

    private void complete(int request, Callback callback, String host, int port) {
        if (!isCurrent(request)) return;
        cancel();
        callback.onConnected(host, port);
    }
}
