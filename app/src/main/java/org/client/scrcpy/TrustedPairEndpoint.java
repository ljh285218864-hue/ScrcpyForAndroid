package org.client.scrcpy;

/**
 * Only numerical server-assigned Beta4 endpoints are trusted.
 *
 * Neither mDNS names, last-seen DHCP addresses, nor an online ADB port are
 * proof of device identity. Callers MUST also authenticate the paired
 * receiver using the saved shared secret before connecting ADB.
 */
public final class TrustedPairEndpoint {
    private TrustedPairEndpoint() {}

    public static boolean isNumericalIpv4(String value) {
        if (value == null || value.length() < 7 || value.length() > 15) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 ||
                    (part.length() > 1 && part.charAt(0) == '0')) return false;
            int number = 0;
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < '0' || ch > '9') return false;
                number = number * 10 + (ch - '0');
            }
            if (number > 255) return false;
        }
        return true;
    }

    public static boolean isBeta4ClientIp(String value) {
        if (!isNumericalIpv4(value)) return false;
        String[] parts = value.split("\\.", -1);
        // The new interface and its address pool cannot overlap Beta3.
        return "100".equals(parts[0]) && "81".equals(parts[1])
                && !("0".equals(parts[2]) && "0".equals(parts[3]))
                && !("0".equals(parts[2]) && "1".equals(parts[3]))
                && !("255".equals(parts[2]) && "255".equals(parts[3]));
    }

    public static boolean validPair(String localIp, String expectedPeerIp) {
        return isBeta4ClientIp(localIp) && isBeta4ClientIp(expectedPeerIp)
                && !localIp.equals(expectedPeerIp);
    }

    public static boolean authenticatedPeerMatches(
            String expectedPeerIp, String contactedIp, String reportedVpnIp) {
        return isBeta4ClientIp(expectedPeerIp)
                && expectedPeerIp.equals(contactedIp)
                && expectedPeerIp.equals(reportedVpnIp);
    }

    public static boolean validAdbPort(int port) {
        return port >= 1 && port <= 65535;
    }
}
