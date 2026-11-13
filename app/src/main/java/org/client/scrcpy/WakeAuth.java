package org.client.scrcpy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One-time challenge response shared by local LAN wake receiver and client. */
public final class WakeAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private WakeAuth() { }

    public static String randomHex(int count) {
        if (count < 8 || count > 32) {
            throw new IllegalArgumentException("Random byte count out of range");
        }
        byte[] data = new byte[count];
        RANDOM.nextBytes(data);
        char[] chars = new char[count * 2];
        for (int i = 0; i < data.length; i++) {
            chars[i * 2] = HEX[(data[i] >> 4) & 15];
            chars[i * 2 + 1] = HEX[data[i] & 15];
        }
        return new String(chars);
    }

    public static String mac(String key, String clientNonce, String serverNonce) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] mac = hmac.doFinal(
                ("screenwake/1:" + clientNonce + ":" + serverNonce).getBytes(StandardCharsets.UTF_8));
        char[] result = new char[mac.length * 2];
        for (int i = 0; i < mac.length; i++) {
            result[i * 2] = HEX[(mac[i] >> 4) & 15];
            result[i * 2 + 1] = HEX[mac[i] & 15];
        }
        return new String(result);
    }

    public static boolean verify(String actual, String expected) {
        if (actual == null || expected == null) return false;
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean isHex(String text, int length) {
        if (text == null || text.length() != length) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
