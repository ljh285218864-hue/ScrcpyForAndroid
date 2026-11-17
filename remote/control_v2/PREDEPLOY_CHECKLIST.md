# ScreenSync Beta4 — Production gate checklist (November 2026)

> DO NOT DEPLOY this experimental branch as a customer-facing read-only screen-sharing product. Existing ADB+scrcpy grants powerful ADB capabilities; the UI's read-only switch does not constrain the ADB trust boundary. Android VPN and wireless debugging grants still require explicit user consent.

## Completed in the repository (disposable CI only)

- One-time owner enrollment, 5-minute pairing invites, owner approval or rejection, per-device identities and dynamic 100.81.x.x/32 allocation, group-scoped server ACLs.
- Android Keystore AES-256-GCM encryption at rest for private WireGuard keys, enrollment tokens and receiver pairing secret. Former Beta4 and legacy local peer credentials are migrated locally when read; no secret needs to be uploaded for migration.
- Scoped WireGuard interface wg-ss-beta4, planned UDP 51821, independent of Beta3 wg-screensync/51820.
- PreUp nft script establishes default-deny for the dedicated interface before WireGuard keys and UDP listener are activated. Control service reconstructs authorized ACLs on restart.
- Unit tests, real localhost HTTP registration/approve/revoke tests, bash syntax checks, Android debug build.
- Real disposable kernel-network tests: four independent clients, actual WireGuard handshakes, group-only forwarding, no cross-group/VPS management access, revocation, startup deny, real systemd SIGKILL and authorized restore.

## HARD BLOCK: not yet completed outside CI

1. **VPS resources**: Work observed OOM, 512MiB RAM, 0 Swap, approximately 192MiB available and 1.5GiB free disk. The installer now refuses to proceed when MemAvailable <384MiB or disk free <1GiB. These values are preliminary guardrails, not capacity certification. Prefer 1–2GiB RAM or an independent test server; no production change before a fresh check.
2. **Live existing services**: confirm x-ui/Reality external handshake, original WireGuard handshake, ports 443/51820/51821/8443/8765, nft and iptables coexistence. Preserve provider snapshot and test restoration path, not just archive integrity.
3. **DNS and HTTPS**: choose correct valid hostname and certificate, proxy only to 127.0.0.1:8765, rate-limit at proxy, never publish management port 8765. The APK currently uses a default *unverified* HTTPS base URL. Set the GitHub Actions variable SCREENSYNC_API_URL to the real HTTPS API endpoint and rebuild when DNS/TLS is ready.
4. **Android device testing**: actual Xiaomi/vivo separate WANs, approval before access, authorized VPN handshake, wake/ADB availability under each ROM, view-only UI, explicit disconnect, revoke, reboot, background execution and error recovery. CI cannot prove Android hardware/ROM compatibility.
5. **Read-only security**: current full ADB capability is not a constrained video-only transport. Implement user-consented, revocable restricted screen streaming and do a permissions/security review before scaling to external customers.
6. **App signing/migration**: CI APK is debug signed; different CI runs may use different signing keys. Plan a stable test/release signing process before OTA updates; do not advise uninstalling a paired app without warning that it loses encrypted on-device credentials.

## Run only after server owner has accepted resource and safety gates

Read-only, no package installation, no restarts:

```bash
sudo bash remote/control_v2/preflight_ubuntu.sh
```

Expected preflight checks: memory, disk, CLI dependencies, old WireGuard listener, reserved UDP/TCP ports, existing 3X-UI and Beta4 files/interfaces. A PASS here is necessary but not sufficient for deployment.

Before any installation, additionally inspect and rehearse rollback. Installer uses remote/control_v2/ensure_deny.sh (wg-quick PreUp). Double-check that the systemd unit is configured to stop traffic on abnormal exit and that a failed partial installation does not leave orphaned services/files.

## Status meanings

- **CI passed**: code compiles and controlled synthetic/Linux namespace scenarios passed.
- **VPS deployed**: actually installed on target after safe resource checks; internal and HTTPS health endpoints tested.
- **Phone validated**: both phones independently performed OS grants, WireGuard handshakes and actual screen-watch tests.
- **Customer release**: read-only transport reviewed; group isolation and revocation assessed under load and real devices; stable signing/upgrade plan completed.
