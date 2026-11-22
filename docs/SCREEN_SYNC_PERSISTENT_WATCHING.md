# ScreenSync — persistent pairing consent and viewer-only Exit

## Implemented in this feature branch

- `PersistentWatchConsent` stores the local phone owner's explicit, revocable
  permission for the authenticated paired peer. Android app backup is disabled.
- `LongTermSharePolicy` now requires the matching explicit peer permission in
  addition to its existing device/group-scoped background-enabled policy.
- `RemoteSetupActivity` offers the consent choice once when a new pair is
  confirmed (and also retains a settings toggle for later action). Approval
  **does not** grant the underlying Android Accessibility permission.
- `NoVpnMediaSocket` validates authenticated paired device identity before
  owner-enabled persistent auto-approval.
- `ScreenSyncMediaKeeper` uses `WatchSessionController` for persistent-source
  JPEG session start/stop. The actual `AccessibilityJpegStreamer.start()` shows
  and verifies the live STOP notification *before* any screenshot frame.
- Viewer `Watch` starts a new session. Viewer `Exit` cancels only the
  viewer's pending/active session and preserves paired identity, persistent
  consent and authenticated WSS control transport.
- Closing fullscreen viewer or pressing Back while viewing now exits viewing
  instead of leaving the remote source capturing needlessly.
- On owner revocation, the live local source is stopped and the stored
  permission cleared. Unpairing still terminates the signaling connection.
- Activity background/task swipe alone doesn't intentionally kill opted-in
  WSS; OS can still terminate an app's process.
- Existing WorkManager 15-minute presence repair, network monitoring and
  boot restore remain. New `NoVpnStandbyAlarm` adds an **inexact**, opt-in
  5-minute target signaling recovery check. It reschedules after each delivery
  and is disabled with background maintenance. It never starts capture itself.
- `NoVpnStandbyAlarmReceiver` is private and explicit in the manifest.
- `.github/workflows/screensync-viewing-ci.yml` was added to compile/debug-test
  this change without publishing a signed release.

## Consent and Android permission rules

- Only the source phone's human owner can opt into persistent viewing.
  Remote server commands, push messages, reboot or receiver tasks never
  grant permission.
- A paired, authenticated viewer can request media without a second app
  confirmation *only* after the source phone opted in.
- Background transport is not an Android system screenshot authorization.
  Screenshot capability requires the user-enabled Accessibility service.
- Actual screenshot transmission still requires a currently visible,
  functional active-sharing STOP notification. Closing notification access
  blocks capture; networking and pairing continue.
- While no media session is active, no screenshot is taken and no "being
  viewed" status notification is posted.
- System/OEM battery restrictions, force-stop, Doze and reboot mean remote
  wake is best-effort. Android may delay an inexact 5-minute alarm by more
  than 5 minutes; this is not a guaranteed realtime delivery system.
- FCM/remote push is **not** added in this branch; it requires a configured
  push provider and device registration. The current server sends commands
  over an already connected WSS channel, with periodic reconnection attempts.

## Test before shipping

1. Pair A/B and verify both devices explicitly approve long-term viewing.
2. With system Accessibility and active-sharing notification available,
   request Watch from B, close fullscreen, check stream stops, then Watch again
   with no second software approval dialog.
3. Verify WSS/pairing stay alive after viewer Exit and Activity Back/Home.
4. On A disable long-term grant; confirm active source stops and later remote
   Watch cannot auto-approve.
5. Swipe away A's Activity; watch if process survives, inspect diagnostics.
6. Reboot A; verify saved pairing/permission, network reconnect on supported
   devices, new Watch only when all live Android permissions are still valid.
7. Turn off notifications or Accessibility; no screenshot capture should
   proceed without visible STOP control and system capability.
8. Test idle 5-minute alarm only as a best-effort transport wake; verify
   cancelled when background maintenance is disabled.
9. Validate network-loss, queued-pending viewer Exit, backpressure,
   reconnection and multiple concurrent requests.
10. Build APK, run tests and test on real paired Android phones.

These are required verification steps, not claims of completed real-device
testing. The branch has not been merged into the original feature branch.
