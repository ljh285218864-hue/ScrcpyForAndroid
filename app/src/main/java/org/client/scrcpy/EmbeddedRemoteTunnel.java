package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;

import org.client.scrcpy.utils.ThreadUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;

/**
 * In-app WireGuard network transport; no Tailscale/WireGuard companion app.
 *
 * This manages ONLY a user-authorized private 100.64.0.0/10 network. It never
 * pairs ADB, launches screen capture, disables a notification or establishes
 * a tunnel without explicit Android VPN consent. No public-facing ADB ports.
 *
 * Each phone needs its own server-issued .conf; credentials are never logged.
 */
public final class EmbeddedRemoteTunnel {
    private static final String CONFIG_FILE = "screensync_private_vpn.conf";
    private static final Object LOCK = new Object();
    // Construct Android's main-thread handler only at runtime. Class loading
    // must remain safe for JVM-only config validation tests.
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }
    private static final LocalTunnel TUNNEL = new LocalTunnel();
    private static GoBackend backend;
    private static volatile boolean active;
    private static volatile boolean starting;

    public interface Callback {
        void onResult(boolean connected, String message);
    }

    private static final class LocalTunnel implements Tunnel {
        @Override public String getName() { return "ScreenSync"; }
        @Override public void onStateChange(State newState) {
            active = newState == State.UP;
        }
    }

    private EmbeddedRemoteTunnel() { }

    public static boolean hasConfig(Context context) {
        return new File(context.getFilesDir(), CONFIG_FILE).isFile();
    }

    /** Only erase the private VPN config when the tunnel is fully stopped. */
    public static boolean removeConfig(Context context) {
        synchronized (LOCK) {
            if (active || starting) return false;
            return !hasConfig(context) || new File(context.getFilesDir(), CONFIG_FILE).delete();
        }
    }

    /** Compare without returning any private key to callers. */
    public static boolean configMatches(Context context, byte[] desired) {
        if (desired == null || desired.length == 0 || !hasConfig(context))
            return false;
        try (FileInputStream in = context.openFileInput(CONFIG_FILE)) {
            byte[] actual = new byte[desired.length + 1];
            int size = 0;
            while (size < actual.length) {
                int n = in.read(actual, size, actual.length - size);
                if (n < 0) break;
                size += n;
            }
            if (size != desired.length || in.read() != -1) return false;
            return java.security.MessageDigest.isEqual(
                    java.util.Arrays.copyOf(actual, size), desired);
        } catch (IOException ignored) {
            return false;
        }
    }

    public static boolean isActive() { return active; }
    public static boolean isStarting() { return starting; }

    /**
     * Only accept point-to-point configurations scoped to our dedicated
     * private overlay. Refuse 0.0.0.0/0 so enabling ScreenSync does NOT
     * route parents' banking, messaging, or ordinary Internet traffic.
     */
    public static void validateConfig(Config config) throws IOException {
        if (config.getPeers().size() != 1) {
            throw new IOException("异地配置必须包含一台经过验证的服务器");
        }
        boolean hasAddress = false;
        for (InetNetwork range : config.getInterface().getAddresses()) {
            if (range.getAddress() instanceof Inet4Address
                    && RemoteOverlayNetwork.isOverlayAddress(
                            range.getAddress().getHostAddress())) {
                hasAddress = true;
            } else {
                throw new IOException("仅支持专用私有网络 100.64.0.0/10");
            }
        }
        if (!hasAddress) throw new IOException("配置缺少设备私有网络地址");
        if (!config.getPeers().get(0).getEndpoint().isPresent()) {
            throw new IOException("配置未指定加密服务器地址");
        }
        if (config.getPeers().get(0).getAllowedIps().isEmpty()) {
            throw new IOException("配置缺少远程路由范围");
        }
        for (InetNetwork range : config.getPeers().get(0).getAllowedIps()) {
            if (!(range.getAddress() instanceof Inet4Address)
                    || range.getMask() < 10
                    || !RemoteOverlayNetwork.isOverlayAddress(
                            range.getAddress().getHostAddress())) {
                throw new IOException("禁止将整部手机的上网流量转入远程服务器");
            }
        }
    }

    public static void importConfig(Context context, byte[] contents) throws Exception {
        if (contents == null || contents.length == 0 || contents.length > 20_000)
            throw new IOException("文件为空或超过 20KB");
        Config config = Config.parse(new ByteArrayInputStream(contents));
        validateConfig(config);
        if (active || starting) throw new IOException("请先断开异地网络再更换配置");
        File file = new File(context.getFilesDir(), CONFIG_FILE);
        // Internal app-only storage; Android app backup is disabled.
        try (FileOutputStream out = context.openFileOutput(CONFIG_FILE, Context.MODE_PRIVATE)) {
            out.write(contents);
            out.flush();
            out.getFD().sync();
        }
        if (!file.isFile()) throw new IOException("无法保存设备配置");
    }

    public static void start(Context context, Callback callback) {
        if (starting) return;
        if (!hasConfig(context)) {
            if (callback != null) callback.onResult(false, "请先导入服务器签发的配置");
            return;
        }
        starting = true;
        final Context app = context.getApplicationContext();
        ThreadUtils.execute(() -> {
            boolean success = false;
            String message;
            try {
                if (AutoRemoteProvisioner.isRegistered(app)) {
                    // Fail closed: revalidate the current pair and load only
                    // its server-assigned config BEFORE starting the VPN.
                    // This prevents booting an imported Beta3 config simply
                    // because a stale private .conf file still exists.
                    org.json.JSONObject current = AutoRemoteProvisioner.status(app);
                    AutoRemoteProvisioner.applyPairedConfig(app, current);
                }
                GoBackend go;
                synchronized (LOCK) {
                    if (backend == null) backend = new GoBackend(app);
                    go = backend;
                }
                Config config;
                try (FileInputStream in = app.openFileInput(CONFIG_FILE)) {
                    config = Config.parse(in);
                }
                validateConfig(config);
                // GoBackend refuses to connect if Android VPN consent was
                // revoked. The Activity obtains consent before calling us.
                success = go.setState(TUNNEL, Tunnel.State.UP, config) == Tunnel.State.UP;
                message = success ? "内置异地加密网络已启动" : "异地网络未能建立";
            } catch (Exception error) {
                Log.w("EmbeddedRemoteTunnel", "VPN start failed: " +
                        error.getClass().getSimpleName());
                message = "内置异地网络启动失败，请检查 VPS 和手机 VPN 授权";
            }
            final boolean result = success;
            final String response = message;
            starting = false;
            active = result;
            mainHandler().post(() -> {
                if (callback != null) callback.onResult(result, response);
            });
        });
    }

    public static void stop(Context context, Callback callback) {
        if (starting) return;
        starting = true;
        ThreadUtils.execute(() -> {
            boolean success = true;
            try {
                GoBackend go;
                synchronized (LOCK) {
                    go = backend;
                }
                if (go != null) go.setState(TUNNEL, Tunnel.State.DOWN, null);
            } catch (Exception error) {
                success = false;
                Log.w("EmbeddedRemoteTunnel", "VPN stop failed: " +
                        error.getClass().getSimpleName());
            }
            if (success) active = false;
            starting = false;
            final boolean ok = success;
            mainHandler().post(() -> {
                if (callback != null) callback.onResult(false,
                        ok ? "内置异地网络已断开" : "异地网络断开遇到问题，请在系统 VPN 中检查");
            });
        });
    }
}
