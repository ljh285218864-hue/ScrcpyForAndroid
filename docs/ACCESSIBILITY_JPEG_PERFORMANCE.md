# ScreenSync live JPEG performance investigation

## What is measured (Android 15 CI; isolated HTTPS/WSS relay)

The validated 26-second post-boot viewing scenario (run 45044792284)
received 25 fully decodable JPEG frames, 0.96 FPS, maximum 1.46-second
inter-frame gap. The source-device log records:
- `CAPTURE_STOP captured=25 sent=25 send_failed=0 capture_failed=20`
- Repeated `CAPTURE_OS_FAIL code=3`
- JPEG encoding only ~8–55 milliseconds at 518x1152 and quality 70
- OkHttp WebSocket queued bytes only around 28 KiB for representative frames

This proves that a network bitrate or Python relay frame limit was **not**
the primary bottleneck on this controlled test. Android's documented
`AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (3)
rejects calls placed too close together. The old capture loop requested the
next capture 140 ms after completion, frequently caused a rate-limit error,
and then idled for **1 full second**, reducing video to about 1 FPS.

## Patch

- `JpegCapturePacer` is shared across adjacent watch sessions within a
  process. It targets a conservative 385-ms request-to-request interval
  near Android's 333-ms AOSP request-rate limit (but **never bypasses** it).
- Successful captures schedule their next attempt as soon as the pacing
  deadline permits, instead of adding an extra fixed 140/265 ms.
- Error 3 increases the interval by 65 ms up to 1100 ms; 18 successes
  decrease it by 15 ms back toward baseline to adapt to vendor differences.
- Other screenshot errors continue to use the longer retry; no emergency
  busy loop is introduced.
- Existing media queue/backpressure, pairing checks, screen-share STOP
  notification, revocation, JPEG size cap and source-side service readiness
  protections are unchanged.
- A unit test covers intervals, too-fast failures and recovery.

## Initial emulator improvement (run 45045812138, attempt 2)

OS-aware pacing completed all paired/background/reboot checks with **51
full JPEG frames in 26.1 seconds** = **1.91 FPS**, versus the previous
25 frames / 0.96 FPS. There were **zero** screenshot rate-limit errors
(code 3), zero JPEG send failures, and no websocket disconnects during the
26-second session. First frame arrived after 0.09 seconds. 5 visual
signatures confirmed the screen content changed.

The remaining worst inter-frame gap was **7.04 seconds**. Actual
`boot-long-view-ended-diagnostics.txt` recorded
`CAPTURE_TIMEOUT callback_timeout_ms=6000`, proving that a rare OS
screenshot callback stall, not the network, caused the long freeze. The
follow-up patch therefore reduces the no-callback watchdog from 6000 to
1900 ms, and its retry from 850 to 240 ms. Old callbacks are still discarded
through the serial guard. This follow-up MUST be validated by a subsequent
26-second emulator run before a final max-gap claim is made.

## Verified shorter watchdog performance (CI run 45046808144)

The Android 15 emulator's full pairing, source-owner consent, no-recent-task,
reboot without launching the app, renewed authentic WSS connection and real
JPEG-viewing assertions all passed (zero failures).

**26.3-second continuous stream: 69 fully decoded JPEG frames = 2.58 FPS,
maximum inter-frame gap 0.83 seconds, first frame 0.11 seconds, 12 unique
screen-image signatures, no disconnect.** On the same CI test class, before
both fixes there were 25 frames / 0.96 FPS / 1.46s maximum gap; with pacing
alone there were 51 frames / 1.91 FPS but one 7.04-second OS callback stall.
The watchdog adjustment eliminated that long stall in this measurement.

This is about 2.7x more frames than the original test, with no new server
changes, permissions, or user prompts. **2.58 FPS remains low for smooth
real-time screen casting**; a true 15–30 FPS mode needs a consent-compliant
video screen source such as MediaProjection + MediaCodec (Android 14+ needs
new user consent per capture session), or an explicitly administered device.
The CI emulator is not a vivo/OPPO/Xiaomi device, and cannot predict OEM
battery killing and actual wide-area cellular delay.

## What this CAN and CANNOT deliver

Expected ceiling under public AccessibilityService screenshot API is low
single-digit FPS; this cannot match 25–30 FPS real-time video. Actual FPS
must be read from the Android emulator/real devices after the patch,
**not** extrapolated from the relay's 8-FPS acceptance setting.

High-frame-rate Github references:
- https://github.com/Genymobile/scrcpy — H.264 stream from Android shell
  via ADB / `app_process` and `MediaCodec`, **not** a normal third-party
  APK silently obtaining the same capture privilege.
- https://github.com/magicsih/AndroidScreenCaster — ordinary Android
  `MediaProjection` + hardware `MediaCodec` approach. This requires the
  Android user's system recording consent and appropriate foreground
  service and cannot be silently restarted as an unrelated new projection
  on Android 14+.
- Official Android SDK: `AccessibilityService` screenshot error 3
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Official Android screen projection:
  https://developer.android.com/media/grow/media-projection

To obtain sustained 15–30 FPS, implement a separate **explicitly consented**
MediaProjection/H.264 transport mode and honor Android's projection session
rules, foreground status, and STOP control. Do not present it as an invisible
post-reboot mode or reuse a revoked screen capture token.
