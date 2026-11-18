# ScreenSync Accessibility JPEG proof of concept

This is an **experimental alternative** to the Beta4 ADB/scrcpy source.
It is built only on the feature/screensync-accessibility-jpeg-poc branch.
Do not merge or deploy it as production software without device testing.

## Properties and trust

- Android 11+ `AccessibilityService.takeScreenshot()`; does **not** request
  MediaProjection, root, VPN, or ADB to use JPEG screenshot mode.
- Explicit manual accessibility permission from the device owner, never
  granted through an incoming server message.
- Every new viewing session requires the source phone to accept the viewing
  request in its visible app UI. Pairing alone never starts capture.
- An ongoing **ordinary Android notification** announces the screenshot share,
  with an immediate Stop Sharing action. This is **not** a foreground service.
  Android may show an accessibility privacy reminder; do not hide it.
- A restarted process, revoked permission, or disconnected WSS session does
  **not** automatically resume screenshot capture.
- Protected `FLAG_SECURE` surfaces can be black or rejected by Android.
- Only encrypted `wss:` and `https:` endpoints are accepted by the existing
  client and reverse proxy.

## Source phone

1. Install the experimental arm64 APK and complete the existing two-way
   customer pairing workflow.
2. Enter the *ScreenSync no-VPN pairing* page. Enable the named accessibility
   service in system settings, acknowledging the system permission warning.
3. On Android 13+, grant notification permission; without a visible Stop
   notification, screenshot sharing refuses to start.
4. Keep the media page connected to WSS to receive an authenticated request.
   Approve each incoming viewing session **on the source phone**.
5. After source approval, source can open another app. Screenshot transmission
   continues best-effort as long as the process/socket remain alive. Use the
   notification Stop Sharing action to terminate it.
6. Stop both devices' session when done; this is not a durable unattended
   wake or screen-capture authorization system.

## Viewer

Connect to the same WSS server, request the paired phone, and observe the JPEG
stream in the image viewer. All frames are checked for session identity,
codec type, maximum size, and JPEG markers. Old render frames are discarded.
Expected capture frequency is low (AOSP restricts rapid requests), so **do not
market this as video-rate smooth screen sharing**.

## Wire protocol (independent of scrcpy H.264)

Keep the 20-byte wire header:
- 16 raw bytes of server-provided session ID
- 4-byte network-order size
- payload begins `0x02,0x00`, followed by 8-byte timestamp and JPEG bytes
  starting `FFD8`, ending `FFD9`.

The server only accepts JPEG payloads after `watch_accept` and matching
`video_setup(codec="jpeg")` from the authorized source. It rejects a JPEG
packet inside an H.264 session, and vice versa. It preserves per-pair group
isolation, limits JPEG to 1 MiB and 4 frames/second, and does not store images.

## Server deployment status

The source code in `remote/relay_v1/media_relay.py` has changed to understand
the JPEG codec. The **already running Osaka relay has NOT been updated**.
The previous production H.264 relay will reject JPEG codec frames until the
new server code is deployed. Do not modify existing 3X-UI, VLESS Reality,
legacy WireGuard, or the 443 listener. Deploy only the ScreenSync media relay
after backup and test, with existing nginx :8443 TLS proxy unchanged.

## Key limitations

No foreground service is used. The OS can terminate background work, and
Android will not guarantee a new session after reboot or force-stop.
A source app must be reachable and approve the request. Screen capture
can be stopped from the device even when the viewer remains connected.
