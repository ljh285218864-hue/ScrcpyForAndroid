# ScreenSync accessibility stability — consent-preserving improvements

## Goal

When the source device's owner has left the Android accessibility switch
enabled, ScreenSync must not turn it off merely because the Activity is
closed, the viewer exits, the screen locks, or the app is backgrounded.
A long-lived *pairing* approval is separate from the system's screenshot
permission and an active *viewing* session.

## Implemented

- `AccessibilityStatePolicy` distinguishes:
  - `RUNNING`: Android service is currently bound and usable.
  - `ENABLED_NOT_CONNECTED`: system lists the user's service as enabled,
    but no service instance is currently bound to this process.
  - `DISABLED`: system no longer lists our service as enabled.
  - `UNKNOWN`: system state could not be inspected reliably.
- `AccessibilityServiceHealth` watches the OS accessibility setting and
  service `onServiceConnected`/`onUnbind`/`onDestroy` callbacks.
- Records state changes in local diagnostics without logging contents of
  other apps or screenshot frames.
- UI refreshes every ~7s while visible, showing a clear distinction between
  enabled-but-unbound and genuinely disabled permission. A settings shortcut
  is used only after the local user taps it.
- On a disabled system grant, active screenshot sharing stops; there is
  no silent re-enabling of a user-revoked permission.
- On Android's **actual** accessibility service rebind, the new
  `AccessibilityReconnectCoordinator` waits 700ms for the system binding to
  settle, rechecks that the service is RUNNING, the pair still exists, the
  user enabled background communication, and their paired long-term viewing
  approval remains valid. Only then it re-initializes the existing
  best-effort HTTPS heartbeat/WSS signaling. It never launches a remote watch,
  sends a screenshot, or flips an accessibility settings switch.
- A stale service instance's `onUnbind` / `onDestroy` can no longer stop
  media owned by a newer re-bound instance.
- Existing reboot and inexact five-minute standby signaling checks also
  inspect and log the current accessibility state, without starting capture.
- Unit tests cover interpretation of all four states and unknown transitions.
- No `disableSelf()`, privileged ADB shell writes, `WRITE_SECURE_SETTINGS`,
  simulated Accessibility Settings taps, or forced toggle/re-enable loops
  are part of this change.

## Accessibility switch off: local-only fallback

- `AccessibilityRecoveryFallback` remembers that the source device's
  accessibility service had previously reached RUNNING.
- If a current pairing still has the owner's long-term viewing approval
  but Android later reports DISABLED, the app records the need for local
  attention. It does **not** infer whether this was caused by the owner or
  the phone manufacturer's background policy.
- The source device's setup UI shows a short explanation and a direct,
  user-tapped shortcut to Android Accessibility settings. It does not
  launch this UI from a background receiver.
- On the next legitimate RUNNING state, it clears the reminder and the
  already implemented OS service rebind handler can recover opted-in WSS
  signaling; it does not initiate any unrequested screenshot session.
- Revoking long-term permission clears the reminder. No extra persistent
  notification is created.
- This deliberately does **not** request WRITE_SECURE_SETTINGS or toggle
  Settings.Secure, because a system-disabled service and a user-revoked
  service can appear identical. Overriding that switch could reinstate
  access that the device owner deliberately removed.

## Platform boundaries

- Android controls binding/rebinding a user-enabled AccessibilityService.
  It may temporarily disconnect even if the settings switch remains enabled.
- A normal third-party app cannot guarantee the service will remain running
  through force-stop, OEM battery suppression, or the phone's reboot cycle.
- A terminated app process does not run its Java health watcher. A subsequent
  OS bind, boot event, allowed background job, alarm, or user launch may
  reinstate the process and the observer, but is not guaranteed immediately.
  **Only Android can bind the actual AccessibilityService.** Our rebind
  callback is a recovery hook after the OS reconnects the service; it does
  not itself command Android to rebind or grant that permission.
- If the system setting is truly disabled, the phone owner must turn it on
  again; background code does not undo that choice.
- Screenshots still require the actual connected service and an active,
  verifiable visible sharing/STOP control.

## Verification required

1. Enable accessibility on the source phone; check `RUNNING`.
2. Return Home, close Activity, swipe task away; check the Android switch
   stays enabled and note whether the service is still bound.
3. Lock screen, unlock, alternate Wi-Fi and cellular networks; ensure
   no app code calls disableSelf or disables permission.
4. Toggle accessibility **off intentionally**: status becomes DISABLED,
   active sharing stops, and software does not flip the switch back.
5. Re-enable manually: service-bound callback yields RUNNING without
   needing to repeat the software's long-term pairing approval.
   With local long-term consent + background maintenance still enabled,
   observe `ACCESSIBILITY_REBIND signal_recovery_requested` in local
   diagnostics; WSS should later authenticate if the server is reachable.
6. Force stop and reboot: document OEM behavior honestly; no promise of
   immediate autonomous recovery.
7. Run GitHub CI and test a real pair of Android devices.

This branch is not marked production-ready until a successful APK build and
real-device verification have been confirmed.
