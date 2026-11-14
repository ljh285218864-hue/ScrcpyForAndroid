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
            this.name = name; this.host = host; this.secret = secret; this.adbPort = adbPort;
        }
    }
    public static void save(Context context, String name, String host, String secret, int adbPort) {
        if (!WakeAuth.isHex(secret, 32) || TextUtils.isEmpty(host)) {
            throw new IllegalArgumentException("Invalid paired peer");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("name", TextUtils.isEmpty(name) ? "Android" : name)
                .putString("host", host).putString("secret", secret)
                .putInt("adb_port", Math.max(0, adbPort)).apply();
    }
    public static Peer load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = p.getString("secret", "");
        String host = p.getString("host", "");
        if (!WakeAuth.isHex(code, 32) || TextUtils.isEmpty(host)) return null;
        return new Peer(p.getString("name", "Android"), host, code, p.getInt("adb_port", 0));
    }
    public static void updatePort(Context context, int newPort) {
        if (newPort > 0 && newPort <= 65535 && load(context) != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt("adb_port", newPort).apply();
        }
    }
    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
