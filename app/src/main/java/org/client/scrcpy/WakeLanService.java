package org.client.scrcpy;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * User-enabled LAN wake receiver for read-only mirroring TESTS.
 * No ADB, screen capture, remote input, pairing bypass, or public Intent API.
 * A must start this explicitly and may stop it at any time.
 * A private temporary 128-bit code authenticates B via challenge-response.
 */
public final class WakeLanService extends Service {
    private static final String TAG = "WakeLan";
    public static final int PORT = 10055;
    public static final String PREF = "wake_lan_test";
    public static final String KEY_CODE = "temporary_code";
    public static final String KEY_LAST = "last_accepted";
    public static final String ACTION_START = "org.client.scrcpy.lan.START";
    public static final String ACTION_STOP = "org.client.scrcpy.lan.STOP";
    private static final int FGS_ID = 8451;
    private static final long PAIR_WINDOW_MS = 3 * 60 * 1000L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Object PAIR_LOCK = new Object();
    private static PendingPair pendingPair;

    public static final class PendingPair {
        public final String id;
        public final String remoteAddress;
        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile boolean accepted;
        PendingPair(String id, String remoteAddress) {
            this.id = id;
            this.remoteAddress = remoteAddress;
        }
    }

    public static String openPairingWindow(Context context) {
        String code = String.format(java.util.Locale.ROOT, "%06d",
                SECURE_RANDOM.nextInt(1_000_000));
        context.getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("pair_short_code", code)
                .putLong("pair_expires", System.currentTimeMillis() + PAIR_WINDOW_MS)
                .apply();
        return code;
    }

    public static PendingPair getPendingPair() {
        synchronized (PAIR_LOCK) { return pendingPair; }
    }

    public static void decidePairing(String id, boolean accept) {
        synchronized (PAIR_LOCK) {
            if (pendingPair == null || !pendingPair.id.equals(id)) return;
            pendingPair.accepted = accept;
            pendingPair.decision.countDown();
        }
    }

    private int rejectedPairAttempts = 0;
    private String lastPairCode = "";
    private static final int REQUEST_ID = 8452;
    private static final String CHANNEL = "screen_mirror_active_listener";
    private static final String REQ_CHANNEL = "screen_mirror_incoming_request";
    private volatile boolean running;
    private ServerSocket server;
    private Thread worker;
    private NsdManager localNsdManager;
    private NsdManager.RegistrationListener localNsdRegistration;

    public static void startByOwner(Context context) {
        Intent intent = new Intent(context, WakeLanService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopByOwner(Context context) {
        Intent intent = new Intent(context, WakeLanService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static String currentCode(Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_CODE, "");
    }

    public static long lastAcceptedAt(Context context) {
        return context.getSharedPreferences(PREF, MODE_PRIVATE).getLong(KEY_LAST, 0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .remove("pair_short_code").remove("pair_expires").apply();
            closeListener();
            getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_CODE).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || ACTION_START.equals(intent.getAction())) {
            prepareChannels();
            Notification notification = notification(
                    "屏幕同步接收已开启", "仅接受持有临时密钥的局域网测试请求", CHANNEL, true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FGS_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(FGS_ID, notification);
            }
            if (!running) {
                String code = currentCode(this);
                if (!WakeAuth.isHex(code, 32)) {
                    code = WakeAuth.randomHex(16);
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString(KEY_CODE, code).apply();
                }
                startListener(code);
            }
        }
        return START_STICKY;
    }

    private void startListener(String code) {
        running = true;
        worker = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket(PORT)) {
                server = socket;
                socket.setSoTimeout(1200);
                Log.i(TAG, "Wake LAN receiver listening on TCP " + PORT);
                registerLocalDiscovery();
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = socket.accept();
                        handleClient(client, code);
                    } catch (SocketTimeoutException ignored) {
                        // Periodically observe the owner stop flag.
                    } catch (Exception error) {
                        if (running) Log.w(TAG, "Wake handshake failed");
                    }
                }
            } catch (Exception error) {
                Log.e(TAG, "Receiver could not bind TCP port " + PORT, error);
                running = false;
                getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_CODE).apply();
                stopSelf();
            }
        }, "wake-lan-receiver");
        worker.start();
    }

    private void handleClient(Socket client, String code) {
        try (Socket socket = client) {
            socket.setSoTimeout(2400);
            // Only directly connected private LAN or loopback peers, never an
            // unauthenticated public-Internet endpoint.
            if (!(socket.getInetAddress().isSiteLocalAddress()
                    || socket.getInetAddress().isLoopbackAddress())) return;
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));
            String hello = in.readLine();
            if (hello != null && hello.startsWith("PAIR ")) {
                handlePairing(socket, hello, out);
                return;
            }
            if (hello == null || hello.length() != 22
                    || !hello.startsWith("HELLO ")
                    || !WakeAuth.isHex(hello.substring(6), 16)) {
                out.write("DENIED\n");
                out.flush();
                return;
            }
            String challenge = WakeAuth.randomHex(16);
            out.write("CHALLENGE " + challenge + "\n");
            out.flush();
            String response = in.readLine();
            String nonce = hello.substring(6);
            String expected = WakeAuth.mac(code, nonce, challenge);
            PairedDeviceStore.Peer peer = PairedDeviceStore.load(this);
            // HMAC establishes peer identity even when DHCP changes the IP.
            // Do not bind a trusted device to a permanent LAN address.
            boolean acceptedByPeer = peer != null
                    && WakeAuth.verify(response != null && response.startsWith("AUTH ")
                                ? response.substring(5) : null,
                            WakeAuth.mac(peer.secret, nonce, challenge));
            if (acceptedByPeer && peer != null
                    && !socket.getInetAddress().getHostAddress().equals(peer.host)) {
                PairedDeviceStore.updateAddress(this,
                        socket.getInetAddress().getHostAddress());
            }
            // After a persistent pairing exists, disable the old temporary
            // test token. Only the saved peer's random long token may wake us.
            boolean legacyAccepted = peer == null && response != null
                    && response.startsWith("AUTH ")
                    && WakeAuth.verify(response.substring(5), expected);
            if (response == null || response.length() != 69
                    || !response.startsWith("AUTH ")
                    || !(legacyAccepted || acceptedByPeer)) {
                out.write("DENIED\n");
                out.flush();
                return;
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putLong(KEY_LAST, System.currentTimeMillis()).apply();
            out.write("OK " + nonce + "\n");
            out.flush();
            Log.i(TAG, "Authorized LAN wake request accepted; screen sharing not triggered");
            postIncomingNotification();
        } catch (Exception error) {
            Log.w(TAG, "Wake request rejected or interrupted");
        }
    }

    private void handlePairing(Socket client, String command, BufferedWriter out)
            throws Exception {
        String[] parts = command.split(" ");
        // This phase deliberately supports one mutually trusted device.
        // Replacing an existing owner-approved peer requires explicit unpairing.
        if (PairedDeviceStore.load(this) != null) {
            out.write("DENIED\n");
            out.flush();
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        String activeCode = prefs.getString("pair_short_code", "");
        long expiry = prefs.getLong("pair_expires", 0L);
        if (!activeCode.equals(lastPairCode)) {
            lastPairCode = activeCode;
            rejectedPairAttempts = 0;
        }
        boolean validFormat = parts.length == 3 && parts[1].matches("[0-9]{6}")
                && WakeAuth.isHex(parts[2], 16);
        boolean validCode = validFormat
                && WakeAuth.verify(parts[1], activeCode)
                && System.currentTimeMillis() < expiry
                && rejectedPairAttempts < 5;
        if (!validCode) {
            rejectedPairAttempts++;
            out.write("DENIED\\n");
            out.flush();
            return;
        }
        PendingPair pending = new PendingPair(parts[2], client.getInetAddress().getHostAddress());
        synchronized (PAIR_LOCK) {
            if (pendingPair != null) {
                out.write("BUSY\\n");
                out.flush();
                return;
            }
            pendingPair = pending;
        }
        Log.i(TAG, "Owner approval required for new app pairing");
        notifyPairingPending();
        try {
            // A must visibly approve; merely knowing a six-digit code
            // cannot grant a long-lived viewing credential.
            boolean replied = pending.decision.await(50, TimeUnit.SECONDS);
            if (!replied || !pending.accepted || !running) {
                out.write("DENIED\\n");
                out.flush();
                return;
            }
            String sharedSecret = WakeAuth.randomHex(16);
            PairedDeviceStore.save(this, "已配对手机",
                    pending.remoteAddress, sharedSecret, 0);
            prefs.edit().remove("pair_short_code").remove("pair_expires").apply();
            out.write("PAIRED " + pending.id + " " + sharedSecret + "\\n");
            out.flush();
            Log.i(TAG, "Owner approved app peer; ADB consent remains separate");
        } finally {
            synchronized (PAIR_LOCK) {
                if (pendingPair == pending) pendingPair = null;
            }
        }
    }

    private void notifyPairingPending() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(REQUEST_ID + 1, notification(
                "新的配对请求需要确认", "请打开屏幕同步，核对并批准对方手机的配对请求",
                REQ_CHANNEL, false));
    }

    private void prepareChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "屏幕同步接收状态", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                REQ_CHANNEL, "屏幕观看请求", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification notification(String title, String message, String channel, boolean ongoing) {
        Intent show = new Intent(this, MainActivity.class);
        show.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 40, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title).setContentText(message)
                .setContentIntent(tap).setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_STATUS).build();
    }

    private void postIncomingNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(REQUEST_ID, notification(
                "收到屏幕观看请求", "一台持有测试密钥的设备请求观看；点击查看共享状态",
                REQ_CHANNEL, false));
    }

    private void registerLocalDiscovery() {
        try {
            localNsdManager = (NsdManager) getSystemService(NSD_SERVICE);
            if (localNsdManager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            String model = Build.MODEL == null ? "Android" : Build.MODEL;
            info.setServiceName("屏幕同步-" + model);
            info.setServiceType(LanDeviceDiscovery.WAKE_TYPE);
            info.setPort(PORT);
            localNsdRegistration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo registered) {
                    Log.i(TAG, "LAN auto-discovery active");
                }
                @Override public void onRegistrationFailed(NsdServiceInfo service, int code) {
                    Log.w(TAG, "LAN auto-discovery register failed: " + code);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo service) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo service, int code) { }
            };
            localNsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, localNsdRegistration);
        } catch (RuntimeException error) {
            Log.w(TAG, "LAN receiver listening but discovery unavailable", error);
        }
    }

    private void closeListener() {
        synchronized (PAIR_LOCK) {
            if (pendingPair != null) {
                pendingPair.accepted = false;
                pendingPair.decision.countDown();
                pendingPair = null;
            }
        }
        if (localNsdManager != null && localNsdRegistration != null) {
            try { localNsdManager.unregisterService(localNsdRegistration); }
            catch (RuntimeException ignored) { }
        }
        localNsdRegistration = null;
        localNsdManager = null;
        running = false;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) { }
            server = null;
        }
        if (worker != null) worker.interrupt();
    }

    @Override public void onDestroy() {
        closeListener();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
