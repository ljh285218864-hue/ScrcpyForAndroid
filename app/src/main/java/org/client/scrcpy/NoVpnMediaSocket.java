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
            // The Python WSS relay already sends Ping frames every 20s and
            // expects Pong. Using a second 20s client-side watchdog can abort
            // an otherwise functioning stream on congested mobile networks.
            // OkHttp still automatically replies to server-initiated pings.
            .pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build();

    private final Context context;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile boolean shutdown;
    private volatile String waitingRequest = "";
    private volatile String locallyApprovedRequest = "";
    private volatile String persistentApprovedRequest = "";
    private volatile boolean persistentSourceStartPending;
    private volatile String pendingViewerSession = "";
    private volatile String sourceSession = "";
    private volatile String viewerSession = "";
    private volatile boolean watchRequestInFlight;
    private volatile boolean cancelViewerWhenPending;
    private volatile boolean viewerHasVideoSetup;
    private int reconnectAttempts;
    private int receivedFrames;
    private volatile long connectedAtMs;
    private volatile long lastSourceFrameAtMs;
    private volatile int sourceFramesSent;
    private static final long REJOIN_WINDOW_MS = 60_000L;
    private volatile long rejoinUntilElapsedMs;
    private volatile boolean rejoinPending;
    private volatile boolean sourceSetupReady = true;
    private final Runnable expireRejoin = () -> {
        if (!rejoinPending || android.os.SystemClock.elapsedRealtime()
                < rejoinUntilElapsedMs) return;
        rejoinPending = false;
        clearSessions();
        AccessibilityJpegStreamer.stopActiveWithoutSending();
        status("session_resume_expired");
    };
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

    /** Rebind UI callbacks after the Activity is recreated, without replacing
     * the established transport or restarting an approved sharing session. */
    public void setListener(Listener next) {
        listener = next;
    }

    public void clearListenerIf(Listener previous) {
        if (listener == previous) listener = null;
    }

    /** Used by the visible Activity to avoid racing with authorized
     * auto-start from a separate, still visible source owner grant. */
    public boolean isPersistentSourceStartPending() {
        return persistentSourceStartPending;
    }

    public boolean hasApprovedSourceSession() {
        return authenticated && !rejoinPending
                && ScrcpyRelayWire.validSessionId(sourceSession);
    }

    /** Only the exact in-memory, previously approved session can rejoin. */
    public boolean hasRecoverableSession() {
        return rejoinPending && android.os.SystemClock.elapsedRealtime()
                < rejoinUntilElapsedMs;
    }

    /** Pause screenshots while the old session awaits relay revalidation. */
    public boolean isSourceTransportReady() {
        return authenticated && !rejoinPending && sourceSetupReady
                && socket != null && !shutdown
                && ScrcpyRelayWire.validSessionId(sourceSession);
    }

    private boolean prepareRejoin() {
        if (shutdown) return false;
        boolean approvedSource = ScrcpyRelayWire.validSessionId(sourceSession)
                && AccessibilityJpegStreamer.sharingActive()
                && AccessibilityJpegStreamer.hasVisibleStopControl();
        boolean activeViewer = ScrcpyRelayWire.validSessionId(viewerSession);
        if (!approvedSource && !activeViewer) return false;
        rejoinPending = true;
        sourceSetupReady = false;
        rejoinUntilElapsedMs = android.os.SystemClock.elapsedRealtime()
                + REJOIN_WINDOW_MS;
        main.removeCallbacks(expireRejoin);
        main.postDelayed(expireRejoin, REJOIN_WINDOW_MS + 25);
        return true;
    }

    public String activeViewerSessionId() {
        return authenticated ? viewerSession : "";
    }

    public void redispatchIncomingRequest() {
        String pending = waitingRequest;
        Listener target = listener;
        if (target != null && !shutdown && ScrcpyRelayWire.validSessionId(pending))
            main.post(() -> {
                Listener current = listener;
                if (current != null && !shutdown && pending.equals(waitingRequest))
                    current.onRequest(pending, NoVpnApi.peerId(context));
            });
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
                connectedAtMs = android.os.SystemClock.elapsedRealtime();
                ScreenSyncDiagnostics.record(context, "WSS_OPEN",
                        "server_keepalive=true client_ping=false");
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
                    ScreenSyncDiagnostics.record(context, "WSS_PARSE",
                            "reason=" + error.getClass().getSimpleName());
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
                    if (!shutdown) {
                        if (++receivedFrames == 1 || receivedFrames % 25 == 0) {
                            ScreenSyncDiagnostics.record(context, "WSS_RX",
                                    "frames=" + receivedFrames + " bytes=" + data.size());
                        }
                        Listener target = listener;
                        if (target != null)
                            target.onVideoPacket(frame.sessionId, frame.videoPacket);
                    }
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
                    if (!prepareRejoin()) {
                        clearSessions();
                        AccessibilityJpegStreamer.stopActiveWithoutSending();
                    }
                }
                ScreenSyncWatchNotice.clear(context);
                ScreenSyncDiagnostics.record(context, "WSS_CLOSE",
                        "code=" + code + " live_s="
                        + ((android.os.SystemClock.elapsedRealtime() - connectedAtMs) / 1000));
                status("disconnected");
                scheduleReconnect();
            }
            @Override public void onFailure(WebSocket ws, Throwable error,
                                            Response response) {
                synchronized (NoVpnMediaSocket.this) {
                    if (socket != ws) return;
                    socket = null;
                    authenticated = false;
                    if (!prepareRejoin()) {
                        clearSessions();
                        AccessibilityJpegStreamer.stopActiveWithoutSending();
                    }
                }
                ScreenSyncWatchNotice.clear(context);
                ScreenSyncDiagnostics.record(context, "WSS_FAIL",
                        "error=" + error.getClass().getSimpleName()
                                + " http=" + (response == null ? 0 : response.code())
                                + " live_s="
                                + ((android.os.SystemClock.elapsedRealtime() - connectedAtMs) / 1000)
                                + " sent_frames=" + sourceFramesSent
                                + " idle_tx_s=" + (lastSourceFrameAtMs == 0
                                        ? -1 : (android.os.SystemClock.elapsedRealtime()
                                                - lastSourceFrameAtMs) / 1000));
                status("network_unavailable");
                scheduleReconnect();
            }
        };
        connectedAtMs = android.os.SystemClock.elapsedRealtime();
        socket = CLIENT.newWebSocket(
                new Request.Builder().url(BuildConfig.SCREENSYNC_MEDIA_URL).build(),
                callbacks);
        status("connecting");
    }

    /** Expedite failed-socket reconnect after a Wi-Fi / mobile-data change.
     * Leaves healthy sockets and consent state unchanged. */
    public void reconnectWhenNetworkAvailable() {
        if (shutdown) return;
        main.removeCallbacks(retryConnection);
        main.postDelayed(retryConnection, 300);
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
                receivedFrames = 0;
                sourceFramesSent = 0;
                lastSourceFrameAtMs = 0;
                reconnectAttempts = 0;
                main.removeCallbacks(retryConnection);
                if (rejoinPending) {
                    String approved = ScrcpyRelayWire.validSessionId(sourceSession)
                            && AccessibilityJpegStreamer.sharingActive()
                            && AccessibilityJpegStreamer.hasVisibleStopControl()
                            ? sourceSession : "";
                    String candidate = !approved.isEmpty() ? approved : viewerSession;
                    if (hasRecoverableSession()
                            && ScrcpyRelayWire.validSessionId(candidate)) {
                        send(new JSONObject().put("type", "session_resume")
                                .put("session_id", candidate));
                        status("session_rejoin_requested");
                    } else {
                        rejoinPending = false;
                        main.removeCallbacks(expireRejoin);
                        clearSessions();
                        AccessibilityJpegStreamer.stopActiveWithoutSending();
                        status("session_resume_expired");
                    }
                }
                status("authenticated");
                break;
            case "session_interrupted": {
                String interrupted = body.optString("session_id");
                if (interrupted.equals(viewerSession)
                        || interrupted.equals(sourceSession)) {
                    // The other device lost its socket. Even if OUR WSS is
                    // healthy, stop sending frames while the server pauses
                    // this approved session. Keep only the in-memory consent.
                    if (interrupted.equals(sourceSession)
                            && (!AccessibilityJpegStreamer.sharingActive()
                                || !AccessibilityJpegStreamer.hasVisibleStopControl())) {
                        endSourceSession("missing_visible_stop");
                        break;
                    }
                    if (!rejoinPending) {
                        rejoinPending = true;
                        sourceSetupReady = false;
                        rejoinUntilElapsedMs = android.os.SystemClock.elapsedRealtime()
                                + REJOIN_WINDOW_MS;
                        main.removeCallbacks(expireRejoin);
                        main.postDelayed(expireRejoin, REJOIN_WINDOW_MS + 25);
                    }
                    status("session_network_interrupted");
                }
                break;
            }
            case "session_resumed": {
                String resumed = body.optString("session_id");
                if (!rejoinPending || !hasRecoverableSession()
                        || !(resumed.equals(sourceSession)
                             || resumed.equals(viewerSession)))
                    throw new SecurityException("未授权的旧会话恢复");
                rejoinPending = false;
                main.removeCallbacks(expireRejoin);
                if (resumed.equals(sourceSession)) {
                    // Sending only the setup of a still-live, locally
                    // approved, visible source. Never creates a new capture.
                    if (!AccessibilityJpegStreamer.resendCurrentSetup(this)) {
                        endSourceSession("source_setup_rejoin_failed");
                        AccessibilityJpegStreamer.stopActiveWithoutSending();
                        status("session_resume_expired");
                        return;
                    }
                    sourceSetupReady = true;
                } else {
                    viewerHasVideoSetup = false;
                }
                status("session_resumed");
                break;
            }
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
                    if (shutdown || !sid.equals(waitingRequest)) return;
                    // This mode is OFF by default, paired-device-scoped,
                    // and was explicitly enabled on the SOURCE phone.
                    // The explicit opt-in remains in the owner's app only.
                    // Before the first JPEG frame, source.start() must display
                    // one currently visible, immediate STOP control.
                    if (LongTermSharePolicy.mayAutoApprove(context)
                            && PersistentWatchConsent.permitsWatch(context, from)) {
                        persistentApprovedRequest = sid;
                        if (acceptRequest(sid)) {
                            status("long_term_auto_approved_from_app_setting");
                            return;
                        }
                        persistentApprovedRequest = "";
                    }
                    Listener target = listener;
                    if (target != null) target.onRequest(sid, from);
                    else ScreenSyncWatchNotice.show(context);
                });
                break;
            }
            case "watch_pending":
                if (!ScrcpyRelayWire.validSessionId(body.optString("session_id")))
                    throw new SecurityException("无效观看等待会话");
                pendingViewerSession = body.optString("session_id");
                watchRequestInFlight = true;
                if (cancelViewerWhenPending) {
                    endViewingSession();
                    break;
                }
                status("awaiting_peer_approval");
                break;
            case "watch_active": {
                String sid = body.optString("session_id");
                if (!ScrcpyRelayWire.validSessionId(sid))
                    throw new SecurityException("无效观看会话");
                if (!sid.equals(pendingViewerSession))
                    throw new SecurityException("不是本机发起的观看会话");
                pendingViewerSession = "";
                watchRequestInFlight = false;
                viewerSession = sid;
                viewerHasVideoSetup = false;
                if (cancelViewerWhenPending) {
                    endViewingSession();
                    break;
                }
                status("viewer_session_active");
                break;
            }
            case "stream_allowed": {
                String sid = body.optString("session_id");
                if (!ScrcpyRelayWire.validSessionId(sid)
                        || !sid.equals(waitingRequest)
                        || !sid.equals(locallyApprovedRequest))
                    throw new SecurityException("用户未同意本次视频发送");
                boolean persistent = sid.equals(persistentApprovedRequest);
                persistentApprovedRequest = "";
                sourceSession = sid;
                waitingRequest = "";
                locallyApprovedRequest = "";
                persistentSourceStartPending = persistent;
                status("video_source_permitted");
                main.post(() -> {
                    if (shutdown || !hasApprovedSourceSession()) {
                        persistentSourceStartPending = false;
                        return;
                    }
                    if (persistent) {
                        // Source user can revoke between approval and start.
                        if (!LongTermSharePolicy.mayAutoApprove(context)
                                || !ScreenSyncMediaKeeper.startPersistentlyApprovedViewing(
                                    context, NoVpnMediaSocket.this)) {
                            endSourceSession("long_term_capture_unavailable");
                        }
                        persistentSourceStartPending = false;
                    } else if (listener == null) {
                        // Only the specific user-accepted session, not a
                        // general auto-recording fallback.
                        AccessibilityJpegStreamer.startPreviouslyApprovedWithoutUi(
                                context, NoVpnMediaSocket.this);
                    }
                });
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
                if (!MediaCodecPolicy.isAllowed(codec))
                    throw new SecurityException("视频编码格式不支持");
                viewerHasVideoSetup = true;
                ScreenSyncDiagnostics.record(context, "VIDEO_SETUP",
                        "codec=" + codec + " width=" + sizes[2] + " height=" + sizes[3]);
                // Keep video_setup and binary frames in WSS receive order.
                // Posting setup to the UI queue can lose the first CONFIG
                // and keyframe when the decoder has not yet been initialized.
                Listener target = listener;
                if (!shutdown && target != null)
                    target.onVideoSetup(sid, sizes, codec);
                break;
            }
            case "watch_end":
                String id = body.optString("session_id");
                boolean sourceEnded = id.equals(sourceSession);
                boolean viewerEnded = id.equals(viewerSession)
                        || id.equals(pendingViewerSession);
                String reason = body.optString("reason", "unspecified");
                ScreenSyncDiagnostics.record(context, "WATCH_END_REMOTE",
                        "role=" + (sourceEnded ? "source" : viewerEnded ? "viewer" : "other")
                        + " reason=" + reason
                        + " configured=" + viewerHasVideoSetup
                        + " frames=" + receivedFrames);
                if (id.equals(viewerSession)) {
                    viewerSession = "";
                    viewerHasVideoSetup = false;
                }
                if (id.equals(pendingViewerSession)) watchRequestInFlight = false;
                if (id.equals(pendingViewerSession)) pendingViewerSession = "";
                if (id.equals(sourceSession)) sourceSession = "";
                if (id.equals(waitingRequest)) waitingRequest = "";
                if (id.equals(locallyApprovedRequest)) locallyApprovedRequest = "";
                if (sourceEnded) {
                    ScreenSyncMediaKeeper.stopPersistentlyApprovedViewing(this);
                    AccessibilityJpegStreamer.stopActiveWithoutSending();
                }
                ScreenSyncWatchNotice.clear(context);
                status(sourceEnded ? "source_session_ended"
                        : viewerEnded ? "viewer_session_ended" : "session_ended");
                break;
            case "error":
                watchRequestInFlight = false;
                cancelViewerWhenPending = false;
                status("server_error:" + body.optString("code", "UNKNOWN"));
                break;
            default:
                throw new SecurityException("不允许的中转消息类型");
        }
    }

    private void status(String value) {
        ScreenSyncDiagnostics.record(context, "WSS_STATUS", value);
        main.post(() -> {
            Listener target = listener;
            if (!shutdown && target != null) target.onStatus(value);
        });
    }

    private boolean send(JSONObject message) {
        WebSocket ws = socket;
        return !shutdown && authenticated && ws != null && ws.send(message.toString());
    }

    public boolean requestWatch() {
        String peer = NoVpnApi.peerId(context);
        if (peer.isEmpty() || !authenticated || shutdown || watchRequestInFlight
                || !viewerSession.isEmpty() || !pendingViewerSession.isEmpty())
            return false;
        cancelViewerWhenPending = false;
        watchRequestInFlight = true;
        try {
            boolean sent = send(new JSONObject().put("type", "watch_request")
                    .put("to_device_id", peer));
            if (!sent) watchRequestInFlight = false;
            ScreenSyncDiagnostics.record(context, "WATCH_REQUEST", "sent=" + sent);
            return sent;
        } catch (JSONException error) {
            watchRequestInFlight = false;
            return false;
        }
    }

    /** The viewer may reopen its picture without issuing another request. */
    public boolean hasActiveViewerSession() {
        return authenticated && ScrcpyRelayWire.validSessionId(viewerSession);
    }

    public boolean hasViewerRequestOutstanding() {
        return watchRequestInFlight || !pendingViewerSession.isEmpty();
    }

    public boolean hasConfiguredVideo() {
        return hasActiveViewerSession() && viewerHasVideoSetup;
    }


    /** Call only from direct local approval or an existing, explicit,
     * identity-matched owner grant for this paired peer. */
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
            ScreenSyncDiagnostics.record(context, "WATCH_APPROVE", "sent=" + sent);
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

    /** Viewer-only Exit: keep WSS and any reverse-direction source share intact. */
    public void endViewingSession() {
        if (watchRequestInFlight && pendingViewerSession.isEmpty()
                && viewerSession.isEmpty()) {
            // Wait for the server-assigned session ID, then cancel it.
            cancelViewerWhenPending = true;
            return;
        }
        String active = viewerSession;
        String pending = pendingViewerSession;
        viewerSession = "";
        pendingViewerSession = "";
        watchRequestInFlight = false;
        cancelViewerWhenPending = false;
        viewerHasVideoSetup = false;
        String[] ids = {active, pending};
        for (String sid : ids) {
            if (!ScrcpyRelayWire.validSessionId(sid)) continue;
            try {
                send(new JSONObject().put("type", "watch_end")
                        .put("session_id", sid));
            } catch (JSONException ignored) { }
        }
        status("viewer_exited");
    }

    /** Used by a separately user-approved scrcpy capture bridge, not by UI. */
    public boolean sendVideoSetup(int[] dims, String codec) {
        if (dims == null || dims.length != 4 || sourceSession.isEmpty()
                || !MediaCodecPolicy.isAllowed(codec)) return false;
        for (int value : dims) if (value < 16 || value > 8192) return false;
        try {
            boolean sent = send(new JSONObject().put("type", "video_setup")
                    .put("session_id", sourceSession)
                    .put("dimensions", new JSONArray(dims)).put("codec", codec));
            ScreenSyncDiagnostics.record(context, "SOURCE_SETUP",
                    "codec=" + codec + " sent=" + sent);
            return sent;
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

    /** True when sending another screenshot would grow an old-frame queue. */
    public boolean isSourceBackpressured() {
        WebSocket ws = socket;
        return ws != null
                && ws.queueSize() > JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_CAPTURE;
    }

    /** JPEG-only frames, only inside a per-session approved source stream. */
    public boolean sendJpegFrame(byte[] jpeg) {
        WebSocket ws = socket;
        if (!isSourceTransportReady() || ws == null
                || ws.queueSize() > JpegStreamTuning.MAX_PENDING_BYTES_BEFORE_SEND)
            return false;
        try {
            boolean sent = ws.send(ByteString.of(
                    ScrcpyRelayWire.encodeJpeg(sourceSession, jpeg)));
            if (sent) {
                lastSourceFrameAtMs = android.os.SystemClock.elapsedRealtime();
                sourceFramesSent++;
            }
            return sent;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void clearSessions() {
        rejoinPending = false;
        sourceSetupReady = true;
        main.removeCallbacks(expireRejoin);
        ScreenSyncWatchNotice.clear(context);
        receivedFrames = 0;
        watchRequestInFlight = false;
        cancelViewerWhenPending = false;
        viewerHasVideoSetup = false;
        viewerSession = "";
        pendingViewerSession = "";
        sourceSession = "";
        waitingRequest = "";
        locallyApprovedRequest = "";
        persistentApprovedRequest = "";
        persistentSourceStartPending = false;
    }

    public void endSourceSession() {
        endSourceSession("user_stopped");
    }

    public void endSourceSession(String reason) {
        ScreenSyncMediaKeeper.stopPersistentlyApprovedViewing(this);
        String source = sourceSession;
        sourceSession = "";
        persistentApprovedRequest = "";
        persistentSourceStartPending = false;
        rejoinPending = false;
        main.removeCallbacks(expireRejoin);
        ScreenSyncDiagnostics.record(context, "SOURCE_END_LOCAL", "reason=" + reason
                + " had_session=" + ScrcpyRelayWire.validSessionId(source));
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
        ScreenSyncMediaKeeper.stopPersistentlyApprovedViewing(this);
        endSession();
        shutdown = true;
        main.removeCallbacks(retryConnection);
        main.removeCallbacks(expireRejoin);
        WebSocket ws = socket;
        socket = null;
        authenticated = false;
        if (ws != null) ws.close(1000, "user_left");
    }
}
