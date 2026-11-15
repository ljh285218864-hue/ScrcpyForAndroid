package org.client.scrcpy;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Short-lived QR payload for the APP pairing protocol only.
 * System ADB QR uses a different Android-defined WIFI:T:ADB protocol.
 * Receiving a QR never authorizes a device: A must still approve PairLanClient.
 */
public final class PairQrCode {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PairQrCode() { }

    public static final class Target {
        public final String host;
        public final int port;
        public final String code;

        Target(String host, int port, String code) {
            this.host = host;
            this.port = port;
            this.code = code;
        }
    }

    public static String createAppPayload(String host, String sixDigitCode) {
        if (!isPrivateIpv4(host) || sixDigitCode == null
                || !sixDigitCode.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("Invalid local QR pairing information");
        }
        return "screensync://pair?host=" + host + "&port="
                + WakeLanService.PORT + "&code=" + sixDigitCode;
    }

    public static Target parseAppPayload(String value) {
        if (TextUtils.isEmpty(value) || value.length() > 256) return null;
        try {
            Uri uri = Uri.parse(value);
            if (!"screensync".equals(uri.getScheme())
                    || !"pair".equals(uri.getAuthority())) return null;
            String host = uri.getQueryParameter("host");
            String portString = uri.getQueryParameter("port");
            String code = uri.getQueryParameter("code");
            if (!isPrivateIpv4(host) || code == null
                    || !code.matches("[0-9]{6}")
                    || !String.valueOf(WakeLanService.PORT).equals(portString)) {
                return null;
            }
            return new Target(host, WakeLanService.PORT, code);
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static String newSystemQrService() {
        return "studio-" + randomLetters(10);
    }

    public static String newSystemQrPassword() {
        return randomLetters(16);
    }

    private static String randomLetters(int length) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    public static String createSystemQrPayload(String service, String password) {
        if (service == null || !service.matches("studio-[A-Za-z0-9]{10}")
                || password == null || !password.matches("[A-Za-z0-9]{16}")) {
            throw new IllegalArgumentException("Invalid system ADB QR credentials");
        }
        // AOSP's Wireless debugging QR code parser, NOT an ordinary Wi-Fi login QR.
        return "WIFI:T:ADB;S:" + service + ";P:" + password + ";;";
    }

    /** Only same-LAN RFC1918 IPv4 hosts are accepted from untrusted scans. */
    public static boolean isPrivateIpv4(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String[] octets = host.split("[.]");
        if (octets.length != 4) return false;
        int[] p = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (octets[i].isEmpty() || octets[i].length() > 3) return false;
                for (int k = 0; k < octets[i].length(); k++) {
                    if (!Character.isDigit(octets[i].charAt(k))) return false;
                }
                p[i] = Integer.parseInt(octets[i]);
                if (p[i] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return p[0] == 10 || p[0] == 172 && p[1] >= 16 && p[1] <= 31
                || p[0] == 192 && p[1] == 168;
    }

    /**
     * Prefer the active Wi-Fi IPv4. Avoid cellular/VPN IPs appearing in a QR.
     * Apps cannot silently enable Wi-Fi on the other device.
     */
    public static String localWifiIp(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                String address = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        ip & 255, (ip >> 8) & 255, (ip >> 16) & 255, (ip >> 24) & 255);
                if (isPrivateIpv4(address)) return address;
            }
        } catch (RuntimeException ignored) { }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface adapter : Collections.list(interfaces)) {
                if (!adapter.isUp() || adapter.isLoopback()
                        || !adapter.getName().toLowerCase(Locale.ROOT).startsWith("wlan")) {
                    continue;
                }
                for (InetAddress inet : Collections.list(adapter.getInetAddresses())) {
                    if (inet instanceof Inet4Address && isPrivateIpv4(inet.getHostAddress())) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
