package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/** Single trusted peer per phone in the local prototype. No remote access without ADB consent. */
public final class PairedDeviceStore {
    private static final String PREFS = "paired_peer_v1";
    private PairedDeviceStore() { }

    public static final class Peer {
        public final String name;
        public final String host;
        public final String secret;
        public final int adbPort;
        Peer(String name, String host, String secret, int adbPort) {
            this.name = name; this.host = host; this.secret = secret;
            this.adbPort = adbPort;
        }
    }
    public static void save(Context context, String name, String host, String secret, int adbPort) {
        if (!WakeAuth.isHex(secret, 32) || TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("Invalid paired peer");
        }
        try {
            DeviceSecretStore.write(context, "paired_secret", secret);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot securely store paired-device key", error);
        }
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name", TextUtils.isEmpty(name) ? "Android" : name)
                .putString("host", host).remove("secret")
                .remove("overlay_host")
                .putInt("adb_port", Math.max(0, adbPort)).commit();
        if (!saved) throw new IllegalStateException("Cannot save paired-device metadata");
    }
    public static Peer load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code;
        try {
            String legacy = p.getString("secret", "");
            if (WakeAuth.isHex(legacy, 32)) {
                // One-time migration of previously stored Beta3/Beta4 LAN
                // pairing credentials. Preserve legacy data on failure.
                DeviceSecretStore.write(context, "paired_secret", legacy);
                if (!p.edit().remove("secret").commit())
                    throw new IllegalStateException("pairing migration not committed");
                code = legacy;
            } else {
                code = DeviceSecretStore.read(context, "paired_secret");
            }
        } catch (Exception error) {
            return null;
        }
        String host = p.getString("host", "");
        if (!WakeAuth.isHex(code, 32) || TextUtils.isEmpty(host)) return null;
        return new Peer(p.getString("name", "Android"), host, code, p.getInt("adb_port", 0));
    }
    public static void updateAddress(Context context, String newHost) {
        if (TextUtils.isEmpty(newHost)) return;
        Peer previous = load(context);
        if (previous == null) return;
        // ADB connect ports are assigned by Android and may change whenever
        // the target rejoins Wi-Fi. Never reuse the former IP's port blindly.
        android.content.SharedPreferences.Editor edit = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("host", newHost);
        if (!newHost.equals(previous.host)) edit.putInt("adb_port", 0);
        edit.apply();
    }

    public static void updatePort(Context context, int newPort) {
        if (newPort > 0 && newPort <= 65535 && load(context) != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt("adb_port", newPort).apply();
        }
    }
    public static void clear(Context context) {
        DeviceSecretStore.remove(context, "paired_secret");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }
}
