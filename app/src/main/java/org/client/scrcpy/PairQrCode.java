package org.client.scrcpy;

import android.content.Context;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkAddress;
import android.net.LinkProperties;
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
        if (!isLocalLanIpv4(host) || sixDigitCode == null
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
            if (!isLocalLanIpv4(host) || code == null
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
        if (host == null || host.isEmpty()) return false;
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
                || p[0] == 192 && p[1] == 168
                || RemoteOverlayNetwork.isOverlayAddress(host);
    }

    /**
     * LAN QR may only contain an address of the currently active Wi-Fi link.
     * Never use VPN overlay endpoints here: Beta4 QR uses a server-generated
     * invite code with no IP or port encoded in the QR.
     */
    public static boolean isLocalLanIpv4(String host) {
        return isPrivateIpv4(host)
                && !RemoteOverlayNetwork.isOverlayAddress(host)
                && !"0.0.0.0".equals(host);
    }

    public static String localWifiIp(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            // Verify the link's transport rather than guessing an arbitrary
            // "wlan*" adapter or using WifiManager's potentially stale IP.
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (LinkAddress address : properties.getLinkAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address
                            && isLocalLanIpv4(ip.getHostAddress())) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        // No Wi-Fi IPv4: report unavailable instead of advertising a guessed
        // private address that could point to another phone after DHCP churn.
        return null;
    }
}
