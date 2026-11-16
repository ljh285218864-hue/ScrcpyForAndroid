package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;

import com.wireguard.crypto.KeyPair;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Device-specific automatic WireGuard provisioning for explicitly approved pairings.
 *
 * Never sends a private key to the VPS. All requests use Android's HTTPS trust
 * validation; no custom insecure trust manager, plaintext transport, or APK
 * embedded administrator credentials. Invoke on a background thread only.
 */
public final class AutoRemoteProvisioner {
    private static final String PREF = "screensync_remote_v4";
    private static final Pattern CODE = Pattern.compile("[a-f0-9]{48}");
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern SECRET = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern WG_KEY = Pattern.compile("[A-Za-z0-9+/]{43}=");
    private static final int MAX_REPLY_BYTES = 8192;

    private AutoRemoteProvisioner() { }

    public static final class Failure extends Exception {
        public final String reason;
        Failure(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isRegistered(Context context) {
        return CODE.matcher(prefs(context).getString("token", "")).matches();
    }

    public static String registrationRole(Context context) {
        return prefs(context).getString("role", "");
    }

    public static String parseQr(String payload, String kind) {
        if (payload == null || payload.length() > 200) return null;
        try {
            android.net.Uri parsed = android.net.Uri.parse(payload);
            if (!"screensync".equals(parsed.getScheme())
                    || !kind.equals(parsed.getAuthority())) return null;
            String code = parsed.getQueryParameter("code");
            return code != null && CODE.matcher(code).matches() ? code : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String inviteQr(String code) throws Failure {
        if (!CODE.matcher(code == null ? "" : code).matches())
            throw new Failure("配对码无效");
        return "screensync://remote?code=" + code;
    }

    private static JSONObject identity(Context context) throws Exception {
        SharedPreferences p = prefs(context);
        String privateKey = p.getString("private_key", "");
        String publicKey = p.getString("public_key", "");
        if (!WG_KEY.matcher(privateKey).matches() ||
                !WG_KEY.matcher(publicKey).matches()) {
            KeyPair generated = new KeyPair();
            privateKey = generated.getPrivateKey().toBase64();
            publicKey = generated.getPublicKey().toBase64();
            p.edit().putString("private_key", privateKey)
                    .putString("public_key", publicKey).commit();
        }
        JSONObject body = new JSONObject();
        body.put("public_key", publicKey);
        body.put("name", android.os.Build.MANUFACTURER + "-" +
                android.os.Build.MODEL);
        return body;
    }

    private static JSONObject post(Context context, String path, JSONObject body,
                                   boolean authenticated) throws Exception {
        String api = BuildConfig.SCREENSYNC_API_URL;
        URL url = new URL(api + path);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || url.getUserInfo() != null || url.getPort() == 80) {
            throw new Failure("服务器必须使用有效 HTTPS 地址");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // System trust store + hostname verification remain enabled.
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        if (authenticated) {
            String token = prefs(context).getString("token", "");
            if (!CODE.matcher(token).matches()) throw new Failure("设备未开通");
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.setDoOutput(true);
        try {
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            if (request.length > 4096) throw new Failure("请求数据过大");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(request);
            }
            int status = connection.getResponseCode();
            try (InputStream in = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream()) {
                if (in == null) throw new Failure("服务器未返回结果");
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (result.size() + n > MAX_REPLY_BYTES)
                        throw new Failure("服务器响应太大");
                    result.write(buffer, 0, n);
                }
                JSONObject reply = new JSONObject(
                        new String(result.toByteArray(), StandardCharsets.UTF_8));
                if (status != 200) {
                    String error = reply.optString("error", "SERVER_ERROR");
                    throw new Failure("服务器拒绝请求：" + error);
                }
                return reply;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void storeRegistration(Context context, JSONObject response,
                                          String role) throws Exception {
        String token = response.getString("token");
        String deviceId = response.getString("device_id");
        if (!CODE.matcher(token).matches() || !ID.matcher(deviceId).matches())
            throw new Failure("服务器身份信息不合法");
        if (!prefs(context).edit().putString("token", token)
                .putString("device_id", deviceId).putString("role", role)
                .commit()) throw new Failure("本机无法保存设备身份");
    }

    public static JSONObject activate(Context context, String code) throws Exception {
        if (isRegistered(context)) throw new Failure("本机已开通，无需重复激活");
        if (!CODE.matcher(code == null ? "" : code).matches())
            throw new Failure("管理员开通码格式错误");
        JSONObject request = identity(context);
        request.put("code", code);
        JSONObject result = post(context, "/v1/activate", request, false);
        storeRegistration(context, result, "owner");
        return result;
    }

    public static JSONObject join(Context context, String inviteCode) throws Exception {
        if (isRegistered(context)) throw new Failure("本机已经注册，请先解绑");
        if (!CODE.matcher(inviteCode == null ? "" : inviteCode).matches())
            throw new Failure("配对邀请码格式错误");
        JSONObject request = identity(context);
        request.put("invite_code", inviteCode);
        JSONObject result = post(context, "/v1/join", request, false);
        storeRegistration(context, result, "guest");
        return result;
    }

    public static JSONObject createInvite(Context context) throws Exception {
        if (!"owner".equals(registrationRole(context)))
            throw new Failure("只有已激活的主手机可以生成配对邀请");
        return post(context, "/v1/invite", new JSONObject(), true);
    }

    public static JSONObject approve(Context context, String pendingId) throws Exception {
        if (!ID.matcher(pendingId == null ? "" : pendingId).matches())
            throw new Failure("待审批设备无效");
        JSONObject body = new JSONObject();
        body.put("pending_id", pendingId);
        return post(context, "/v1/approve", body, true);
    }

    public static JSONObject status(Context context) throws Exception {
        if (!isRegistered(context)) throw new Failure("设备尚未开通");
        return post(context, "/v1/status", new JSONObject(), true);
    }

    /**
     * Invoked only after server reports both owners have approved. Config
     * stays in app-private storage; no Android file picker or public downloads.
     * An existing active tunnel must be stopped before replacing its identity.
     */
    public static void applyPairedConfig(Context context, JSONObject status) throws Exception {
        if (!"paired".equals(status.optString("state")))
            throw new Failure("双方尚未完成确认");
        String deviceIp = status.getString("device_ip");
        String peerIp = status.getString("peer_ip");
        String serverKey = status.getString("server_public_key");
        String sharedSecret = status.getString("shared_secret");
        String endpoint = status.getString("endpoint");
        String privateKey = prefs(context).getString("private_key", "");
        if (!RemoteOverlayNetwork.isOverlayAddress(deviceIp)
                || !RemoteOverlayNetwork.isOverlayAddress(peerIp)
                || deviceIp.equals(peerIp)
                || !SECRET.matcher(sharedSecret).matches()
                || !WG_KEY.matcher(serverKey).matches()
                || !WG_KEY.matcher(privateKey).matches()
                || endpoint.length() > 256
                || !endpoint.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) {
            throw new Failure("服务端返回的加密配置无效");
        }
        String config = "[Interface]\nAddress = " + deviceIp + "/32\n"
                + "PrivateKey = " + privateKey + "\n\n"
                + "[Peer]\nPublicKey = " + serverKey + "\n"
                + "AllowedIPs = 100.80.0.0/24\n"
                + "Endpoint = " + endpoint + "\n"
                + "PersistentKeepalive = 25\n";
        // Avoid importing repeatedly while an active VPN is running.
        if (!EmbeddedRemoteTunnel.hasConfig(context))
            EmbeddedRemoteTunnel.importConfig(context,
                    config.getBytes(StandardCharsets.UTF_8));
        PairedDeviceStore.Peer existing = PairedDeviceStore.load(context);
        if (existing == null || !sharedSecret.equals(existing.secret)
                || !peerIp.equals(existing.overlayHost)) {
            PairedDeviceStore.save(context, status.optString("peer_name", "异地手机"),
                    peerIp, sharedSecret, 0);
        }
    }

    public static JSONObject revoke(Context context) throws Exception {
        JSONObject result = post(context, "/v1/revoke", new JSONObject(), true);
        prefs(context).edit().clear().commit();
        PairedDeviceStore.clear(context);
        return result;
    }

    public static void removeLocalTunnelConfig(Context context) {
        EmbeddedRemoteTunnel.removeConfig(context);
    }
}
