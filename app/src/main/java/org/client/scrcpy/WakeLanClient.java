package org.client.scrcpy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Test-only authenticated LAN wake request, never starts or controls a remote screen. */
public final class WakeLanClient {
    private WakeLanClient() { }

    /** Only returned after BOTH client and receiver prove the saved pairing key. */
    public static final class RemoteInfo {
        public final String overlayHost;
        public final int adbPort;
        public final String error;
        RemoteInfo(String overlayHost, int adbPort, String error) {
            this.overlayHost = overlayHost;
            this.adbPort = adbPort;
            this.error = error;
        }
        public boolean ok() { return error == null; }
    }

    public static RemoteInfo queryRemoteInfo(
            android.content.Context context, String host, String secret) {
        if (!WakeAuth.isHex(secret, 32)) {
            return new RemoteInfo(null, 0, "尚未完成软件双方配对");
        }
        try {
            InetAddress ip = InetAddress.getByName(host);
            if (!PairQrCode.isPrivateIpv4(ip.getHostAddress())) {
                return new RemoteInfo(null, 0, "不允许连接未授权的公网地址");
            }
            String nonce = WakeAuth.randomHex(8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, WakeLanService.PORT), 3500);
                socket.setSoTimeout(11500);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.US_ASCII));
                out.write("INFO " + nonce + " -\n");
                out.flush();
                String challenge = in.readLine();
                if (challenge == null || !challenge.startsWith("CHALLENGE ")
                        || !WakeAuth.isHex(challenge.substring(10), 32)) {
                    return new RemoteInfo(null, 0, "对方接收服务未响应");
                }
                String serverNonce = challenge.substring(10);
                out.write("AUTH " + WakeAuth.mac(secret, nonce, serverNonce) + "\n");
                out.flush();
                String ack = in.readLine();
                String expected = WakeAuth.mac(secret, serverNonce, nonce);
                String[] response = ack == null ? new String[0] : ack.split(" ");
                if (response.length != 3 || !"OK".equals(response[0])
                        || !nonce.equals(response[1])
                        || !WakeAuth.verify(response[2], expected)) {
                    return new RemoteInfo(null, 0, "对方身份无法验证，已拒绝连接");
                }
                String metadata = in.readLine();
                String[] info = metadata == null ? new String[0] : metadata.split(" ");
                if (info.length != 3 || !"META".equals(info[0])) {
                    return new RemoteInfo(null, 0, "对方没有提供异地连接信息");
                }
                if (!"-".equals(info[1])) return new RemoteInfo(null, 0, "旧 VPN 元数据已禁用");
                int adbPort = Integer.parseInt(info[2]);
                if (adbPort < 0 || adbPort > 65535) adbPort = 0;
                return new RemoteInfo(null, adbPort, null);
            }
        } catch (Exception error) {
            return new RemoteInfo(null, 0, "局域网接收端未响应，请检查系统无线调试状态");
        }
    }

    public static String request(String address, String code) {
        try {
            if (!WakeAuth.isHex(code, 32)) return "临时密钥格式不正确（应为32位十六进制）";
            String[] parts = address.trim().split(":");
            if (parts.length != 2) return "接收地址格式应为 IP:10055";
            String host = parts[0].trim();
            int port = Integer.parseInt(parts[1]);
            if (port != WakeLanService.PORT) return "测试接收端口必须为10055";
            InetAddress ip = InetAddress.getByName(host);
            if (!PairQrCode.isPrivateIpv4(ip.getHostAddress()))
                return "仅允许私有局域网地址";
            String nonce = WakeAuth.randomHex(8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 3500);
                socket.setSoTimeout(3500);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.US_ASCII));
                out.write("HELLO " + nonce + "\n");
                out.flush();
                String challenge = in.readLine();
                if (challenge == null || !challenge.startsWith("CHALLENGE ")
                        || !WakeAuth.isHex(challenge.substring(10), 32))
                    return "A 未接受唤醒握手";
                out.write("AUTH " + WakeAuth.mac(code, nonce, challenge.substring(10)) + "\n");
                out.flush();
                String result = in.readLine();
                return ("OK " + nonce).equals(result) ? null : "密钥错误或请求被拒绝";
            }
        } catch (Exception error) {
            return "无法连接 A 的唤醒接收服务，请检查A是否主动开启接收及局域网连通性";
        }
    }
}
