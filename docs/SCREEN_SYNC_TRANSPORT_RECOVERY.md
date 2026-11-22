# ScreenSync transport stability — JPEG relay / Android client audit

Scope: `feature/screensync-persistent-watch-consent-20261122`. This is a
source-code fix, not a VPS deployment or physical-device test.

## Confirmed root causes and changes

1. **Old JPEG cap was 4 frames/second**, with a fixed per-second bucket.
   Sudden arrival bursts invoked `discard(session_id, "jpeg_rate_limit")`,
   ending the entire authorized session. A new relay advertises
   `jpeg_max_fps=8` after authenticated WSS login. It now drops excess JPEG
   frames instead of treating a momentary burst as permission revocation.
   A client on an old relay without this field stays at its safe 4-FPS rate.
   The 90-FPS general rate cap and per-second byte cap still apply.
2. **One slow viewer send killed a session.** The relay now tolerates one
   sequential send timeout, resetting its failure count after a successful
   send; repeated failures or closed sockets still end the session.
   No unbounded frame queue is introduced.
3. **JPEG capture+encode was expensive.** Downscale maximum edge from 1440
   to 1152 pixels, initial JPEG quality from 74 to 70, and negotiate 4/8 FPS.
   Capture only schedules a subsequent frame after the prior one finishes,
   observes bounded OkHttp queue sizes, and lowers quality under congestion.
4. **Stale viewer frames could pile up on the UI queue.** Decode still uses a
   queue of size one, but now at most one decoded bitmap waits for UI display.
   Pending, never-rendered bitmaps are recycled when superseded. Viewer logs
   received, displayed and dropped frame counters.
5. **Server errors were all presented as generic refusals.** WSS
   authentication rejection, connection replacement, media/session errors,
   request-ACK timeouts and relay `watch_end` reasons now have distinct
   diagnostics and more specific UI messages. One error unrelated to the
   currently active source/viewer no longer resets unrelated watch state.
   A rejected source-media stream ends its own transmission, but preserves
   valid pairing and user consent.
6. A new CI step runs the **actual Python relay socket tests** alongside
   Android build and JVM tests, including JPEG bursts and one-send timeouts.

## Real-world constraints

- This remains **JPEG screenshots**, not a smooth 30-FPS hardware encoder.
  Actual FPS may be below the negotiated maximum due to Android's
  `AccessibilityService.takeScreenshot` rate, phone CPU and network.
- User-approved, background WSS is held **in process**. Home, Activity close
  and recent-task swipe do not intentionally call `disableSelf()`; however
  an Android/OEM process kill or user force-stop drops that WSS connection
  and prevents immediate remote viewing. Rebind/Alarm/WorkManager are
  opportunistic, not guaranteed to wake instantaneously.
- A source's currently approved screen stream still requires a locally
  visible, working STOP control. Owner revocation or pairing invalidation
  immediately blocks further authorized screen frames.
- Java, Python and integration tests do not validate the live deployment.
  The currently running relay on the VPS may still be the **old** version.
  Deploy via audited update to the separate `remote/relay_v1/` service only,
  retaining 3X-UI, Reality and old WireGuard services untouched.
- A new configured APK requires repository Secrets:
  `SCREENSYNC_NOVPN_URL` (https://) and `SCREENSYNC_MEDIA_URL` (wss://).
  Never hard-code tokens or use the placeholder `relay.invalid`.

## Field validation after installing matching client+relay

1. On source B, approve long-term viewing while Accessibility is enabled,
   and keep the mandated sharing/Stop control operable.
2. Watch B from A. Record source `JPEG_SEND` (screenshot_ms, encode_ms,
   queued_bytes) and viewer `JPEG_DISPLAY` received/displayed counts.
3. Trigger a 10-second screen scroll. Verify no `jpeg_rate_limit` stop;
   count real delivered FPS and compare with relay-advertised `jpeg_max_fps`.
4. Try A-side Exit → Watch again, while B is Home/backgrounded, with B's app
   still alive and then with the B process intentionally terminated. Record
   different expectations: background process alive may serve requests;
   killed process has no guaranteed instant WSS recovery.
5. Wi-Fi ↔ cellular switch. Check `WSS_CLOSE`, `WSS_FAIL`, `WATCH_END_REMOTE`,
   `WSS_SERVER_ERROR`, `session_end_reason` and reconnect transitions.
6. In B Android Settings, inspect Accessibility switch after Home, swipe,
   reboot, and OEM battery restrictions. No app code turns it off or on.
7. Verify stopping from B, revoking pairing, permission-off and notification
   stop-control loss all still halt actual screenshots and prevent new media.
8. Check server logs for `AUTH_REQUIRED`, `viewer_send_timeout`,
   `viewer_unreachable`, `SESSION_UNKNOWN`, `PEER_OFFLINE` and configured
   relay version. A server-side HTTP refusal is a different path from WSS.

No production server changes or device installation were performed here.
