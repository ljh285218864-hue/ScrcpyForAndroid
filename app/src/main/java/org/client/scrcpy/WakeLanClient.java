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

    public static String request(String address, String code) {
        try {
            if (!WakeAuth.isHex(code, 32)) return "临时密钥格式不正确（应为32位十六进制）";
            String[] parts = address.trim().split(":");
            if (parts.length != 2) return "接收地址格式应为 IP:10055";
            String host = parts[0].trim();
            int port = Integer.parseInt(parts[1]);
            if (port != WakeLanService.PORT) return "测试接收端口必须为10055";
            InetAddress ip = InetAddress.getByName(host);
            if (!(ip.isSiteLocalAddress() || ip.isLoopbackAddress()))
                return "仅允许局域网地址，暂不支持公网直连";
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
