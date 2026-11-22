# Persistent one-time approval for remote viewing — integration contract

Scope of this change: two new, self-contained Java helpers. Existing files and
behavior have **not** been changed. This document is written solely from the
code excerpts supplied in the conversation; no repository source was read.

## Desired semantics

- Local device owner explicitly approves one authenticated paired device once.
- Saved approval survives Activity recreation, network reconnect, and reboot.
- Viewer **Watch** opens a new media session when platform capture permission
  is currently available; no second app-specific confirmation is required.
- Viewer **Exit** stops only that media session, not the persistent pairing,
  permission, or communication channel.
- Local **Stop / Revoke** stops capture immediately and removes long-lived
  approval. A subsequent viewing session needs fresh local approval.
- Only during the active capture session show the user a working
  "being viewed" indicator and stop control. Do not capture while idle.
- System accessibility/screen-capture permission cannot be granted by this
  approval record; platform permission requirements still apply.

## Integrate into existing classes (not implemented in this commit)

1. Local pairing approval UI, **after** the device owner explicitly accepts
   an explanation of future remote viewing:
   `PersistentWatchConsent.grantAfterExplicitLocalApproval(context, verifiedPeerId)`.
   Never call this from a push notification, receiver, service restart, or
   remote command. Ensure the method returns true before confirming success.

2. Construct one `WatchSessionController` in the capture-service owner.
   Do **not** put it only in an Activity. Recreating a UI screen must not
   recreate the active capture session.

3. On authenticated `WATCH_START` request, call
   `controller.requestWatch(context, authenticatedPeerId, bridge)`.
   The server and client MUST authenticate the same paired peer ID, using
   credentials that cannot be spoofed by changing the message payload.
   Never grant consent as a consequence of a remote request.

4. Implement `CaptureBridge` against existing capture/notification code:
   - `hasSystemCaptureAccess()` checks the actual Android capability.
   - `showWatchingStateAndStopControl()` presents a real, usable stop action.
   - `isWatchingStateAndStopControlAvailable()` verifies it remains usable.
   - `startCaptureForCurrentSession()` starts JPEG delivery only after checks.
   - `stopCaptureForCurrentSession()` stops JPEG/media delivery.
   - `hideWatchingStateAndStopControl()` removes the viewing-only indicator.
   Do not implement visible-control checks as unconditional `true`.

5. On authenticated viewer `WATCH_EXIT`, call `controller.exitWatch(bridge)`.
   Leave the paired device registration and server command connection intact.

6. On the viewed device's **Revoke shared access** action, call
   `controller.revokeAndStop(context, bridge)`, then notify the server that
   previously paired viewing credentials have been invalidated.

7. During active capture, call `controller.checkVisibleStopOrEnd(bridge)`.
   If the user-visible stop control disappears, capture must stop.
   A disconnect or an OS capture error also ends this media session.

8. Change existing `ensureVisibleStopControl()` only once a genuine
   non-notification, user-visible stopping mechanism is connected. Deleting
   the check or returning `true` is not an implementation of a stop control.

9. Exclude `screensync_persistent_watch_consent_v1.xml` from Auto Backup and
   device-to-device transfer. A reinstalled / restored app should not silently
   inherit remote-viewing consent from a different installation.

## Reboot and wakeup

- Reboot may restore *saved approval*; it does NOT start screenshot capture
  by itself. The system may revoke or disable capture capabilities.
- Server messages / supported push notifications may wake an app for bounded
  work, but cannot guarantee immediate execution on every Android device.
- WorkManager periodic work has a 15-minute minimum interval. A five-minute
  exact, always-running wakeup is not guaranteed under Doze / background
  restrictions. Do not spin recurring alarms as a substitute for consent.
- Restoring online presence, maintaining an authenticated control channel,
  and starting an authorized screenshot session are separate operations.
- An activity Exit / task removal should not automatically revoke saved
  approval. A force-stop or OS kill can still end a running connection.

## Known code excerpts supplied by the user

- `AccessibilityJpegStreamer.ensureVisibleStopControl()`: currently
  requires both `noticePosted` and `ShareNotificationPreference.mayPost(app)`.
- `start()`: currently stops when the visible-stop check fails.
- `capture()`: periodically repeats the visible-stop check and stops media.
- `RemoteSetupActivity`: holds notification settings, but its lifetime must
  not own the media session.

No changes to those files are included in this branch because repository
source access is explicitly disallowed.

## Required verification before release

- Local approval -> Watch -> Exit -> Watch again, with no second app dialog.
- Reboot -> permission preserved; new request checks Android capability.
- Device owner revokes -> active stream stops; subsequent requests are denied.
- When no viewer is active, no JPEG capture runs and no "being viewed" badge
  is displayed.
- During capture, a visible state/stop action is available and functional.
- Reject forged peer IDs, stale watch requests, and commands after revocation.
- Background/task-swipe cases on target OEM Android versions.
- Android permission turned off, process killed, and force-stop behavior.
- Build APK and test on real paired phones; none of these tests have run yet.
