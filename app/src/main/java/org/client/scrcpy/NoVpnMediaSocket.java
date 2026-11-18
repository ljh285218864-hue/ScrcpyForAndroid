package org.client.scrcpy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Viewer-visible, HTTPS/WSS-only video signaling transport. Never opens ADB,
 * starts scrcpy, records the screen, launches a foreground service or requests
 * VPN/MediaProjection permission. Screen acquisition remains a separate,
 * explicitly authorized, user-stoppable component.
 *
 * The companion server sends binary frames only after an accepted session.
 * A binary frame alone does NOT cause recording or an ADB command.
 */
public final class NoVpnMediaSocket {
    public interface Listener {
        void onStatus(String state);
        void onRequest(String sessionId, String requestingPeerId);
        void onVideoSetup(String sessionId, int[] dimensions, String codec);
        void onVideoPacket(String sessionId, byte[] packet);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build();

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile boolean shutdown;
    private volatile String waitingRequest = "";
    private volatile String locallyApprovedRequest = "";
    private volatile String pendingViewerSession = "";
    private volatile String sourceSession = "";
    private volatile String viewerSession = "";
    private int reconnectAttempts;
    private final Runnable retryConnection = () -> {
        if (shutdown || socket != null) return;
        try {
            connect();
        } catch (Exception invalidConfig) {
            status("reconnect_blocked_check_https_settings");
        }
    };

    public NoVpnMediaSocket(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private static void requireValidWss(String text) throws Exception {
        URI uri = new URI(text);
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getHost().endsWith(".invalid") || uri.getFragment() != null) {
            throw new IllegalStateException("视频服务器尚未配置有效 WSS 地址");
        }
        if (uri.getPort() == 8767) {
            throw new IllegalStateException("禁止把内部视频端口直接暴露给手机");
        }
    }

    public static boolean endpointConfigured() {
        try {
            requireValidWss(BuildConfig.SCREENSYNC_MEDIA_URL);
            return true;
        } catch (Exception notConfigured) {
            return false;
        }
    }

    public synchronized void connect() throws Exception {
        if (shutdown) throw new IllegalStateException("连接已关闭");
        if (socket != null) return;
        requireValidWss(BuildConfig.SCREENSYNC_MEDIA_URL);
        String token = NoVpnApi.mediaToken(context);
        if (NoVpnApi.peerId(context).isEmpty())
            throw new IllegalStateException("必须先完成两台手机的双方身份确认");

        WebSocketListener callbacks = new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                if (shutdown || socket != ws) {
                    ws.close(1000, "stale_connection");
                    return;
                }
                try {
                    JSONObject auth = new JSONObject();
                    auth.put("type", "auth");
                    auth.put("token", token);
                    ws.send(auth.toString());
                    status("authenticating");
                } catch (JSONException error) {
                    ws.close(1008, "invalid_auth");
                    status("auth_failed");
                }
            }
            @Override public void onMessage(WebSocket ws, String value) {
                if (shutdown || socket != ws) return;
                if (value.length() > 1024) {
                    ws.close(1008, "unexpected_message");
                    return;
                }
                try {
                    handleMessage(value);
                } catch (Exception error) {
                    ws.close(1008, "invalid_message");
                    status("protocol_error");
                }
            }
            @Override public void onMessage(WebSocket ws, ByteString data) {
                if (shutdown || socket != ws || !authenticated) return;
                try {
                    ScrcpyRelayWire.Frame frame =
                            ScrcpyRelayWire.decode(data.toByteArray());
                    if (!frame.sessionId.equals(viewerSession)) {
                        ws.close(1008, "wrong_session");
                        status("video_identity_mismatch");
                        return;
                    }
                    // Deliver on OkHttp's reader thread, not Android's UI
                    // thread. Viewer queues decoding on its bounded worker.
                    if (!shutdown) listener.onVideoPacket(
                            frame.sessionId, frame.videoPacket);
                } catch (IllegalArgumentException error) {
                    ws.close(1008, "unsafe_video");
                    status("unsafe_video_rejected");
                }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                if (socket == ws) ws.close(1000, "closed");
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (NoVpnMediaSocket.this) {
                    if (socket != ws) return; // stale failed attempt cannot reset newer session
                    socket = null;
                    authenticated = false;
                    clearSessions();
                }
                status("disconnected");
                scheduleReconnect();
            }
            @Override public void onFailure(WebSocket ws, Throwable error,
                                            Response response) {
                synchronized (NoVpnMediaSocket.this) {
                    if (socket != ws) return;
                    socket = null;
                    authenticated = false;
                    clearSessions();
                }
                status("network_unavailable");
                scheduleReconnect();
            }
        };
        socket = CLIENT.newWebSocket(
                new Request.Builder().url(BuildConfig.SCREENSYNC_MEDIA_URL).build(),
                callbacks);
        status("connecting");
    }

    private void scheduleReconnect() {
        if (shutdown) return;
        main.removeCallbacks(retryConnection);
        // No hidden service or unbounded tight retry loop. Runs only while
        // this screen/process is alive; reconnect NEVER resumes screen media
        // or bypasses the source's per-session approval.
        int number = Math.min(++reconnectAttempts, 5);
        long delayMs = Math.min(30000L, 1000L << (number - 1));
        main.postDelayed(retryConnection, delayMs);
    }

    private void handleMessage(String message) throws JSONException {
        JSONObject body = new JSONObject(message);
        String type = body.optString("type");
        switch (type) {
            case "authenticated":
                if (!NoVpnApi.deviceId(context).equals(body.optString("device_id"))
                        || !NoVpnApi.groupId(context).equals(body.optString("group_id"))) {
                    throw new SecurityException("服务器返回的设备身份不一致");
                }
                authenticated = true;
                reconnectAttempts = 0;
                main.removeCallbacks(retryConnection);
                status("authenticated");
                break;
            case "peer_presence":
                status(body.optBoolean("peer_connected") ? "peer_online" : "peer_offline");
                break;
            case "watch_request": {
                String sid = body.optString("session_id");
                String from = body.optString("viewer_device_id");
                if (!authenticated || !ScrcpyRelayWire.validSessionId(sid)
                        || !NoVpnApi.peerId(context).equals(from))
                    throw new SecurityException("接收请求身份不一致");
                waitingRequest = sid;
                main.post(() -> {
                    if (!shutdown) listener.onRequest(sid, from);
                });
                break;
            }
            case "watch_pending":
                if (!ScrcpyRelayWire.validSessionId(body.optString("session_id")))
                    throw new SecurityException("无效观看等待会话");
                pendingViewerSession = body.optString("session_id");
                status("awaiting_peer_approval");
                break;
            case "watch_active": {
                String sid = body.optString("session_id");
                if (!ScrcpyRelayWire.validSessionId(sid))
                    throw new SecurityException("无效观看会话");
                if (!sid.equals(pendingViewerSession))
                    throw new SecurityException("不是本机发起的观看会话");
                pendingViewerSession = "";
                viewerSession = sid;
                status("viewer_session_active");
                break;
            }
            case "stream_allowed": {
                String sid = body.optString("session_id");
                if (!ScrcpyRelayWire.validSessionId(sid)
                        || !sid.equals(waitingRequest)
                        || !sid.equals(locallyApprovedRequest))
                    throw new SecurityException("用户未同意本次视频发送");
                sourceSession = sid;
                waitingRequest = "";
                locallyApprovedRequest = "";
                status("video_source_permitted");
                break;
            }
            case "video_setup": {
                String sid = body.optString("session_id");
                if (!sid.equals(viewerSession)) throw new SecurityException("错误的画面会话");
                JSONArray list = body.getJSONArray("dimensions");
                if (list.length() != 4) throw new SecurityException("画面尺寸无效");
                int[] sizes = new int[4];
                for (int i = 0; i < 4; i++) {
                    sizes[i] = list.getInt(i);
                    if (sizes[i] < 16 || sizes[i] > 8192)
                        throw new SecurityException("画面尺寸越界");
                }
                if (sizes[2] > sizes[0] * 2L || sizes[3] > sizes[1] * 2L)
                    throw new SecurityException("画面尺寸不匹配");
                String codec = body.optString("codec");
                if (!("h264".equals(codec) || "h265".equals(codec)))
                    throw new SecurityException("视频编码格式不支持");
                // Keep video_setup and binary frames in WSS receive order.
                // Posting setup to the UI queue can lose the first CONFIG
                // and keyframe when the decoder has not yet been initialized.
                if (!shutdown) listener.onVideoSetup(sid, sizes, codec);
                break;
            }
            case "watch_end":
                String id = body.optString("session_id");
                boolean sourceEnded = id.equals(sourceSession);
                boolean viewerEnded = id.equals(viewerSession)
                        || id.equals(pendingViewerSession);
                if (id.equals(viewerSession)) viewerSession = "";
                if (id.equals(pendingViewerSession)) pendingViewerSession = "";
                if (id.equals(sourceSession)) sourceSession = "";
                if (id.equals(waitingRequest)) waitingRequest = "";
                if (id.equals(locallyApprovedRequest)) locallyApprovedRequest = "";
                status(sourceEnded ? "source_session_ended"
                        : viewerEnded ? "viewer_session_ended" : "session_ended");
                break;
            case "error":
                status("server_error:" + body.optString("code", "UNKNOWN"));
                break;
            default:
                throw new SecurityException("不允许的中转消息类型");
        }
    }

    private void status(String value) {
        main.post(() -> {
            if (!shutdown) listener.onStatus(value);
        });
    }

    private boolean send(JSONObject message) {
        WebSocket ws = socket;
        return !shutdown && authenticated && ws != null && ws.send(message.toString());
    }

    public boolean requestWatch() {
        String peer = NoVpnApi.peerId(context);
        if (peer.isEmpty()) return false;
        try {
            return send(new JSONObject().put("type", "watch_request")
                    .put("to_device_id", peer));
        } catch (JSONException error) { return false; }
    }

    /** Call only from a real acceptance in the local user interface. */
    public boolean acceptRequest(String sid) {
        if (!ScrcpyRelayWire.validSessionId(sid) || !sid.equals(waitingRequest))
            return false;
        // This method is called ONLY from the visible user's approve button.
        // A forged or premature server stream_allowed must not start ADB.
        locallyApprovedRequest = sid;
        try {
            boolean sent = send(new JSONObject().put("type", "watch_accept")
                    .put("session_id", sid));
            if (!sent) locallyApprovedRequest = "";
            return sent;
        } catch (JSONException error) {
            locallyApprovedRequest = "";
            return false;
        }
    }

    public boolean declineRequest(String sid) {
        if (!ScrcpyRelayWire.validSessionId(sid) || !sid.equals(waitingRequest))
            return false;
        waitingRequest = "";
        locallyApprovedRequest = "";
        try {
            return send(new JSONObject().put("type", "watch_decline")
                    .put("session_id", sid));
        } catch (JSONException error) { return false; }
    }

    /** Used by a separately user-approved scrcpy capture bridge, not by UI. */
    public boolean sendVideoSetup(int[] dims, String codec) {
        if (dims == null || dims.length != 4 || sourceSession.isEmpty()
                || !("h264".equals(codec) || "h265".equals(codec)
                        || "jpeg".equals(codec))) return false;
        for (int value : dims) if (value < 16 || value > 8192) return false;
        try {
            return send(new JSONObject().put("type", "video_setup")
                    .put("session_id", sourceSession)
                    .put("dimensions", new JSONArray(dims)).put("codec", codec));
        } catch (JSONException error) { return false; }
    }

    /** Accepts video payload only, never the raw bidirectional ADB stream. */
    public boolean sendVideoPacket(byte[] scrcpyVideoPacket) {
        WebSocket ws = socket;
        if (!authenticated || shutdown || ws == null || sourceSession.isEmpty()
                || ws.queueSize() > 8L * 1024 * 1024)
            return false;
        try {
            return ws.send(ByteString.of(
                    ScrcpyRelayWire.encode(sourceSession, scrcpyVideoPacket)));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /** JPEG-only frames, only inside a per-session approved source stream. */
    public boolean sendJpegFrame(byte[] jpeg) {
        WebSocket ws = socket;
        if (!authenticated || shutdown || ws == null || sourceSession.isEmpty()
                || ws.queueSize() > 1024L * 1024) return false;
        try {
            return ws.send(ByteString.of(ScrcpyRelayWire.encodeJpeg(sourceSession, jpeg)));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void clearSessions() {
        viewerSession = "";
        pendingViewerSession = "";
        sourceSession = "";
        waitingRequest = "";
        locallyApprovedRequest = "";
    }

    public void endSourceSession() {
        String source = sourceSession;
        sourceSession = "";
        if (!ScrcpyRelayWire.validSessionId(source)) return;
        try {
            send(new JSONObject().put("type", "watch_end").put("session_id", source));
        } catch (JSONException ignored) { }
        status("source_session_stopped");
    }

    public void endSession() {
        String[] ids = {viewerSession, pendingViewerSession, sourceSession, waitingRequest};
        clearSessions();
        for (String sid : ids) {
            if (!ScrcpyRelayWire.validSessionId(sid)) continue;
            try {
                send(new JSONObject().put("type", "watch_end")
                        .put("session_id", sid));
            } catch (JSONException ignored) { }
        }
        status("session_stopped_by_user");
    }

    /** Closing the Activity releases the connection. No hidden background media. */
    public synchronized void close() {
        endSession();
        shutdown = true;
        main.removeCallbacks(retryConnection);
        WebSocket ws = socket;
        socket = null;
        authenticated = false;
        if (ws != null) ws.close(1000, "user_left");
    }
}
