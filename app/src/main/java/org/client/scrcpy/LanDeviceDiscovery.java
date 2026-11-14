package org.client.scrcpy;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional local network discovery. A advertises an owner-enabled wake listener;
 * B finds it without IP entry and looks for an ADB TLS connect service on
 * the SAME IP. Discovery is never proof of trust: WakeAuth remains mandatory.
 */
public final class LanDeviceDiscovery {
    public static final String WAKE_TYPE = "_screenwake._tcp.";
    private static final String ADB_TYPE = "_adb-tls-connect._tcp.";
    private static final String ADB_PAIR_TYPE = "_adb-tls-pairing._tcp.";
    private static final String TAG = "LanDiscovery";
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> discovered = new LinkedHashMap<>();
    private final Listener listener;
    private NsdManager.DiscoveryListener wakeListener;
    private NsdManager.DiscoveryListener adbListener;
    private NsdManager.DiscoveryListener adbPairListener;
    private WifiManager.MulticastLock multicastLock;
    private boolean active;

    public interface Listener {
        void onChanged(List<Device> devices);
        void onError(String message);
    }

    public static final class Device {
        public final String name;
        public final String address;
        public final int wakePort;
        public final int adbPort;
        public final int pairingPort;

        Device(String name, String address, int wakePort, int adbPort, int pairingPort) {
            this.name = name;
            this.address = address;
            this.wakePort = wakePort;
            this.adbPort = adbPort;
            this.pairingPort = pairingPort;
        }

        public String label() {
            return name + (adbPort > 0 ? " · 可尝试连接" : " · 等待无线调试");
        }
    }

    public LanDeviceDiscovery(Context context, Listener callback) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        listener = callback;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("screenwake-lan-discovery");
                multicastLock.setReferenceCounted(false);
            }
        } catch (RuntimeException ignored) { }
    }

    public void start() {
        if (active || nsd == null) {
            if (nsd == null) listener.onError("系统不支持局域网设备发现");
            return;
        }
        active = true;
        discovered.clear();
        if (multicastLock != null) {
            try { multicastLock.acquire(); } catch (RuntimeException ignored) { }
        }
        wakeListener = makeListener(WAKE_TYPE);
        adbListener = makeListener(ADB_TYPE);
        adbPairListener = makeListener(ADB_PAIR_TYPE);
        try {
            nsd.discoverServices(WAKE_TYPE, NsdManager.PROTOCOL_DNS_SD, wakeListener);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Wake discovery could not start", exception);
            listener.onError("自动搜索无法启动，请确认已连接 Wi-Fi");
        }
        try {
            nsd.discoverServices(ADB_TYPE, NsdManager.PROTOCOL_DNS_SD, adbListener);
        } catch (RuntimeException exception) {
            Log.w(TAG, "ADB discovery could not start", exception);
            listener.onError("ADB 服务自动搜索不可用，可能仍需首次无线调试授权");
        }
        try {
            nsd.discoverServices(ADB_PAIR_TYPE, NsdManager.PROTOCOL_DNS_SD, adbPairListener);
        } catch (RuntimeException exception) {
            Log.w(TAG, "ADB pairing discovery unavailable", exception);
        }
    }

    private NsdManager.DiscoveryListener makeListener(final String serviceType) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!active) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            if (!active) return;
                            final InetAddress host = resolved.getHost();
                            if (host == null || resolved.getPort() <= 0) return;
                            final String address = host.getHostAddress();
                            if (address == null || !(host.isSiteLocalAddress()
                                    || host.isLoopbackAddress())) return;
                            main.post(() -> putResolved(serviceType, resolved, address));
                        }
                        @Override public void onResolveFailed(NsdServiceInfo ignored, int code) {
                            Log.d(TAG, "NSD resolve unavailable: " + code);
                        }
                    });
                } catch (RuntimeException error) {
                    Log.d(TAG, "NSD resolve failed", error);
                }
            }
            @Override public void onServiceLost(NsdServiceInfo info) {
                // Results are refreshed on every explicit user search.
            }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) {
                if (active) main.post(() -> listener.onError("设备发现失败（" + code + "）"));
            }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
        };
    }

    private void putResolved(String type, NsdServiceInfo info, String address) {
        if (!active) return;
        final Device previous = discovered.get(address);
        final boolean isWake = WAKE_TYPE.equals(type);
        final String name = isWake ? info.getServiceName() :
                previous == null ? "" : previous.name;
        final int wakePort = isWake ? info.getPort() : previous == null ? 0 : previous.wakePort;
        final boolean isAdb = ADB_TYPE.equals(type);
        final boolean isPairing = ADB_PAIR_TYPE.equals(type);
        final int adbPort = isAdb ? info.getPort() : previous == null ? 0 : previous.adbPort;
        final int pairingPort = isPairing ? info.getPort() : previous == null ? 0 : previous.pairingPort;
        discovered.put(address, new Device(name, address, wakePort, adbPort, pairingPort));
        listener.onChanged(available());
    }

    public List<Device> available() {
        List<Device> out = new ArrayList<>();
        for (Device device : discovered.values()) {
            if (device.wakePort == WakeLanService.PORT) out.add(device);
        }
        return Collections.unmodifiableList(out);
    }

    public void stop() {
        if (!active) return;
        active = false;
        try { if (wakeListener != null) nsd.stopServiceDiscovery(wakeListener); }
        catch (RuntimeException ignored) { }
        try { if (adbListener != null) nsd.stopServiceDiscovery(adbListener); }
        catch (RuntimeException ignored) { }
        try { if (adbPairListener != null) nsd.stopServiceDiscovery(adbPairListener); }
        catch (RuntimeException ignored) { }
        wakeListener = null;
        adbListener = null;
        adbPairListener = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (RuntimeException ignored) { }
        }
    }
}
