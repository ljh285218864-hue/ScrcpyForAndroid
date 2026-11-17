package org.client.scrcpy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * No-VPN signaling API: HTTPS only, explicit mutual pairing and scoped tokens.
 * The server never exposes or relays raw ADB/video, and a signaling message
 * DOES NOT authorize screen capture or remote control.
 */
public final class NoVpnApi {
    private static final String PREF = "screensync_novpn_v1";
    private static final String SECRET_NAME = "novpn_token";
    private static final Pattern CODE = Pattern.compile("[a-f0-9]{48}");
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final int MAX_REPLY = 12000;

    private NoVpnApi() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREF, Context.MODE_PRIVATE);
    }

    public static boolean isRegistered(Context context) {
        return DeviceSecretStore.exists(context, SECRET_NAME)
                && ID.matcher(prefs(context).getString("device_id", "")).matches();
    }

    public static String deviceId(Context context) {
        return prefs(context).getString("device_id", "");
    }

    public static String peerId(Context context) {
        return prefs(context).getString("peer_device_id", "");
    }

    static String groupId(Context context) {
        return prefs(context).getString("group_id", "");
    }

    static String mediaToken(Context context) throws Exception {
        if (!isRegistered(context))
            throw new IllegalStateException("本机尚未开通无 VPN 异地连接");
        String token = DeviceSecretStore.read(context, SECRET_NAME);
        if (!CODE.matcher(token == null ? "" : token).matches())
            throw new IllegalStateException("本机视频中转凭证无效");
        return token;
    }

    public static String parseQr(String payload, String type) {
        if (payload == null || payload.length() > 200 || type == null) return null;
        if (!("activate".equals(type) || "remote".equals(type))) return null;
        try {
            android.net.Uri uri = android.net.Uri.parse(payload);
            if (!"screensync".equals(uri.getScheme())
                    || !type.equals(uri.getAuthority())) return null;
            String code = uri.getQueryParameter("code");
            return code != null && CODE.matcher(code).matches() ? code : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static JSONObject post(Context context, String path, JSONObject request,
                                   boolean authenticated) throws Exception {
        URL url = new URL(BuildConfig.SCREENSYNC_NOVPN_URL + path);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || url.getHost().endsWith(".invalid") || url.getHost().isEmpty()) {
            throw new IllegalStateException("无 VPN 中转服务尚未配置有效 HTTPS 域名");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(9000);
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        if (authenticated) {
            String token = DeviceSecretStore.read(context, SECRET_NAME);
            if (!CODE.matcher(token).matches())
                throw new IllegalStateException("本机无有效的异地注册凭证");
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.setDoOutput(true);
        try {
            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > 4096) throw new IllegalArgumentException("请求过大");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            try (InputStream in = status >= 400
                    ? conn.getErrorStream() : conn.getInputStream()) {
                if (in == null) throw new IllegalStateException("服务器没有回复");
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (result.size() + n > MAX_REPLY)
                        throw new IllegalStateException("服务器响应超过安全限额");
                    result.write(buffer, 0, n);
                }
                JSONObject parsed = new JSONObject(new String(result.toByteArray(),
                        StandardCharsets.UTF_8));
                if (status != 200)
                    throw new IllegalStateException("服务端拒绝：" +
                            parsed.optString("error", "HTTP_" + status));
                return parsed;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void saveIdentity(Context context, JSONObject response) throws Exception {
        String token = response.getString("token");
        String id = response.getString("device_id");
        String group = response.getString("group_id");
        if (!CODE.matcher(token).matches() || !ID.matcher(id).matches()
                || !ID.matcher(group).matches())
            throw new IllegalStateException("注册返回的设备身份无效");
        DeviceSecretStore.write(context, SECRET_NAME, token);
        if (!prefs(context).edit().putString("device_id", id)
                .putString("group_id", group).putLong("cursor", 0L).commit()) {
            throw new IllegalStateException("无法保存本机设备身份");
        }
    }

    private static String ownName() {
        String name = Build.MANUFACTURER + "-" + Build.MODEL;
        return name.length() > 48 ? name.substring(0, 48) : name;
    }

    public static JSONObject activate(Context context, String code) throws Exception {
        if (isRegistered(context)) throw new IllegalStateException("本机已开通");
        if (!CODE.matcher(code == null ? "" : code).matches())
            throw new IllegalArgumentException("二维码开通码格式错误");
        JSONObject body = new JSONObject();
        body.put("name", ownName());
        body.put("code", code);
        JSONObject response = post(context, "/v1/activate", body, false);
        saveIdentity(context, response);
        return response;
    }

    public static JSONObject join(Context context, String invite) throws Exception {
        if (isRegistered(context)) throw new IllegalStateException("本机已开通");
        if (!CODE.matcher(invite == null ? "" : invite).matches())
            throw new IllegalArgumentException("邀请二维码格式错误");
        JSONObject body = new JSONObject();
        body.put("name", ownName());
        body.put("invite_code", invite);
        JSONObject response = post(context, "/v1/join", body, false);
        saveIdentity(context, response);
        return response;
    }

    public static JSONObject invite(Context context) throws Exception {
        return post(context, "/v1/invite", new JSONObject(), true);
    }

    public static JSONObject approve(Context context, String id) throws Exception {
        if (!ID.matcher(id == null ? "" : id).matches())
            throw new IllegalArgumentException("待配对设备 ID 无效");
        return post(context, "/v1/approve",
                new JSONObject().put("pending_id", id), true);
    }

    public static JSONObject decline(Context context, String id) throws Exception {
        if (!ID.matcher(id == null ? "" : id).matches())
            throw new IllegalArgumentException("待配对设备 ID 无效");
        return post(context, "/v1/decline",
                new JSONObject().put("pending_id", id), true);
    }

    public static JSONObject status(Context context) throws Exception {
        JSONObject response = post(context, "/v1/status", new JSONObject(), true);
        if (!deviceId(context).equals(response.optString("device_id"))
                || !prefs(context).getString("group_id", "")
                    .equals(response.optString("group_id"))) {
            throw new SecurityException("服务器设备/配对组身份不一致");
        }
        if ("paired".equals(response.optString("state"))) {
            String peer = response.getString("peer_device_id");
            if (!ID.matcher(peer).matches() || peer.equals(deviceId(context)))
                throw new SecurityException("对方设备 ID 无效");
            String previous = peerId(context);
            if (!previous.isEmpty() && !previous.equals(peer))
                throw new SecurityException("配对对象已更换，须重新确认");
            prefs(context).edit().putString("peer_device_id", peer).apply();
        }
        return response;
    }

    public static JSONObject heartbeat(Context context) throws Exception {
        return post(context, "/v1/heartbeat", new JSONObject(), true);
    }

    public static JSONObject send(Context context, String type, JSONObject payload)
            throws Exception {
        String peer = peerId(context);
        if (!ID.matcher(peer).matches())
            throw new SecurityException("尚未记录已批准的对方身份");
        if (!("hello".equals(type) || "watch_request".equals(type)
                || "watch_decline".equals(type) || "watch_end".equals(type))) {
            throw new IllegalArgumentException("未授权的信令类型");
        }
        return post(context, "/v1/signal/send", new JSONObject()
                .put("to_device_id", peer).put("type", type)
                .put("payload", payload), true);
    }

    public static JSONObject poll(Context context) throws Exception {
        long oldCursor = prefs(context).getLong("cursor", 0L);
        JSONObject response = post(context, "/v1/signal/poll",
                new JSONObject().put("after", oldCursor), true);
        JSONArray messages = response.getJSONArray("messages");
        long cursor = response.getLong("cursor");
        if (cursor < oldCursor) throw new SecurityException("信令序号倒退");
        // Update cursor only after parsing and verifying expected sender.
        String expected = peerId(context);
        for (int i = 0; i < messages.length(); i++) {
            JSONObject signal = messages.getJSONObject(i);
            if (!expected.equals(signal.optString("from_device_id"))
                    || signal.getLong("seq") <= oldCursor)
                throw new SecurityException("信令发送者与配对身份不一致");
        }
        // Do not ACK yet: the Activity must first render/handle the
        // messages. If the app dies before that, the next poll replays them.
        return response;
    }

    public static void acknowledge(Context context, long cursor) {
        long previous = prefs(context).getLong("cursor", 0L);
        if (cursor < previous || cursor < 0)
            throw new SecurityException("信令确认序号无效");
        if (cursor == previous) return;
        if (!prefs(context).edit().putLong("cursor", cursor).commit())
            throw new IllegalStateException("无法保存信令确认进度");
    }

    public static JSONObject revoke(Context context) throws Exception {
        JSONObject response;
        try {
            response = post(context, "/v1/revoke", new JSONObject(), true);
        } catch (IllegalStateException error) {
            // The OTHER phone may have already revoked the pair. Only a
            // verified AUTH_REQUIRED reply from our HTTPS server permits
            // local cleanup. Never lose keys on an ordinary network outage.
            if (!"服务端拒绝：AUTH_REQUIRED".equals(error.getMessage())) throw error;
            response = new JSONObject().put("state", "already_revoked");
        }
        DeviceSecretStore.remove(context, SECRET_NAME);
        if (!prefs(context).edit().clear().commit())
            throw new IllegalStateException("服务器已撤销，但本机配对清理失败");
        return response;
    }
}
