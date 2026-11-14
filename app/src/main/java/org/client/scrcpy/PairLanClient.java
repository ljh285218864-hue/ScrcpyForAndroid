package org.client.scrcpy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * App-level pairing. Device A explicitly shows a short code, then must
 * individually approve B's request before a long random wake token is saved.
 * This does not pair or authorize Android system ADB.
 */
public final class PairLanClient {
    private PairLanClient() { }
    public static final class Result {
        public final String secret;
        public final String error;
        Result(String secret, String error) { this.secret = secret; this.error = error; }
        public boolean ok() { return secret != null; }
    }
    public static Result pair(String host, int port, String pairingCode) {
        if (pairingCode == null || !pairingCode.matches("[0-9]{6}")) {
            return new Result(null, "请输入 A 手机显示的六位配对码");
        }
        try {
            InetAddress ip = InetAddress.getByName(host);
            if (!(ip.isSiteLocalAddress() || ip.isLoopbackAddress())
                    || port != WakeLanService.PORT) {
                return new Result(null, "仅支持在同一局域网配对");
            }
            String nonce = WakeAuth.randomHex(8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), 4000);
                socket.setSoTimeout(65000);
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.US_ASCII));
                out.write("PAIR " + pairingCode + " " + nonce + "\n");
                out.flush();
                String line = in.readLine();
                if (line == null) return new Result(null, "A 手机已断开配对请求");
                String prefix = "PAIRED " + nonce + " ";
                if (line.startsWith(prefix)) {
                    String secret = line.substring(prefix.length());
                    if (WakeAuth.isHex(secret, 32)) return new Result(secret, null);
                }
                if ("DENIED".equals(line)) return new Result(null, "配对码错误、过期，或 A 手机拒绝");
                if ("BUSY".equals(line)) return new Result(null, "A 手机正在处理另一个配对请求");
                return new Result(null, "A 手机未确认配对或请求已超时");
            }
        } catch (Exception e) {
            return new Result(null, "未能完成配对；检查 A 手机是否开启接收及两个手机是否在同一 Wi-Fi");
        }
    }
}
