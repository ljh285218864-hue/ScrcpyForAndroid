package org.client.scrcpy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Optional Android VPN transport for an already owner-approved screen-sharing
 * relationship. Only RFC6598 100.64.0.0/10 overlay IPs are accepted here.
 * This class never enables a VPN or disables Android debugging/recording alerts.
 */
public final class RemoteOverlayNetwork {
    private RemoteOverlayNetwork() { }

    public static boolean isOverlayAddress(String host) {
        if (host == null) return false;
        String[] parts = host.split("[.]");
        if (parts.length != 4) return false;
        try {
            int[] nums = new int[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                for (int j = 0; j < parts[i].length(); j++) {
                    if (parts[i].charAt(j) < '0' || parts[i].charAt(j) > '9')
                        return false;
                }
                nums[i] = Integer.parseInt(parts[i]);
                if (nums[i] < 0 || nums[i] > 255) return false;
            }
            return nums[0] == 100 && nums[1] >= 64 && nums[1] < 128;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    public static boolean isAllowedPeerIp(InetAddress address) {
        return address != null
                && (address.isSiteLocalAddress() || address.isLoopbackAddress()
                || isOverlayAddress(address.getHostAddress()));
    }

    public static String localVpnIp(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                    continue;
                LinkProperties links = manager.getLinkProperties(network);
                if (links == null) continue;
                for (LinkAddress link : links.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (address instanceof Inet4Address
                            && isOverlayAddress(address.getHostAddress())) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.d("RemoteOverlayNetwork", "VPN IP not available: " + error.getMessage());
        }
        return null;
    }

    public static String localWifiIp(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                String host = (ip & 255) + "." + ((ip >> 8) & 255) + "."
                        + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
                if (PairQrCode.isPrivateIpv4(host)
                        && !isOverlayAddress(host)) return host;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
}
