package org.client.scrcpy;

/**
 * ScreenSync Beta4 no-VPN transport policy.
 *
 * This branch intentionally excludes the Android VpnService integration.
 * The future relay will use an authenticated HTTPS/WSS signaling channel
 * and consented, viewer-visible media sharing rather than a VPN interface.
 *
 * A network connection is NOT a screen-capture authorization.
 */
public final class NoVpnTransportPolicy {
    private NoVpnTransportPolicy() { }

    public static boolean usesAndroidVpn() {
        return false;
    }

    public static boolean isInternetVideoTransportReady() {
        // Do not accidentally route "Watch" via the old VPN/ADB path while
        // the replacement consented video relay is still being developed.
        return false;
    }

    public static String pendingMessage() {
        return "无 VPN 异地连接正在开发。此版本不启用系统 VPN、"
                + "不请求 VPN 授权，也不会使用旧 WireGuard 通道。"
                + "局域网配对功能不受影响；异地观看需待新传输通道完成。";
    }
}
