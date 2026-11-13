#!/usr/bin/env python3
"""Local-only dual Android emulator integration test.
The two emulators run under the same GitHub Actions job.
No ADB ports are intentionally exposed to the public Internet.
"""
import os
import re
import subprocess
import sys
import threading
import socket
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path("dual-emulator-results")
OUT.mkdir(exist_ok=True)
A = "emulator-5554"
B = "emulator-5556"
HOST = "10.0.2.2:5555"  # Android emulator B -> GitHub runner -> emulator A local ADB port
WAKE_HOST = "10.0.2.2:10055"  # Private authenticated LAN wake endpoint forwarded to A
PKG = "org.client.scrcpy"
summary = []

def record(msg):
    print(msg, flush=True)
    summary.append(msg)
    (OUT / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")

def run(args, *, timeout=60, check=False, capture=True):
    result = subprocess.run(args, text=True, encoding="utf-8", errors="replace",
                            capture_output=capture, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{args} exit {result.returncode}: {result.stderr[-1500:]}")
    return result

def adb(device, *args, timeout=60):
    return run(["adb", "-s", device, *args], timeout=timeout)

def shell(device, *args, timeout=60):
    return adb(device, "shell", *args, timeout=timeout)

def screenshot(device, name):
    with (OUT / name).open("wb") as f:
        subprocess.run(["adb", "-s", device, "exec-out", "screencap", "-p"],
                       stdout=f, stderr=subprocess.DEVNULL, timeout=30)

def dump_ui(device, name):
    shell(device, "uiautomator", "dump", "/sdcard/window.xml", timeout=35)
    r = adb(device, "exec-out", "cat", "/sdcard/window.xml", timeout=20)
    xml = r.stdout
    (OUT / name).write_text(xml, encoding="utf-8")
    return ET.fromstring(xml) if xml.strip().startswith("<?xml") else None

def find_node(root, *, resource_id=None, texts=()):
    if root is None:
        return None
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        txt = (node.attrib.get("text", "") + " " + node.attrib.get("content-desc", "")).lower()
        if resource_id and rid.endswith("/" + resource_id):
            return node
        if texts and any(t.lower() in txt for t in texts):
            return node
    return None


def wait_for_viewer_home(device, *, label, attempts=10):
    """Wait for the B viewer UI and handle only synthetic emulator launcher ANRs.

    Never sends input to the screen being mirrored (A); touches here are solely
    for dismissing a local B emulator system dialog.
    """
    for n in range(attempts):
        root = dump_ui(device, f"{label}-ui-attempt-{n}.xml")
        host = find_node(root, resource_id="editText_server_host")
        if host is not None:
            return root

        # The Google API emulator can have a temporary Pixel Launcher ANR as
        # it boots. It overlays our app even when the app itself is healthy.
        launcher_anr = find_node(root, texts=("Pixel Launcher isn't responding",
                                              "Pixel Launcher keeps stopping",
                                              "Pixel Launcher is not responding"))
        if launcher_anr is not None:
            choice = ("Wait", "等待") if n < 2 else ("Close app", "关闭应用")
            action = next((node for node in root.iter("node")
                           if node.attrib.get("class") == "android.widget.Button"
                           and node.attrib.get("text", "").strip() in choice), None)
            if action is not None and tap(device, action):
                record(f"{label}: dismissed synthetic Pixel Launcher ANR via {choice[0]}")
            else:
                record(f"{label}: found Pixel Launcher ANR but no actionable button")
            time.sleep(6)
            continue

        top = [node.attrib.get("text", "") for node in root.iter("node")
               if node.attrib.get("text")]
        record(f"{label}: viewer UI not ready (attempt {n+1}/{attempts}); visible={top[:6]!r}")
        # Ensure the viewer is foreground without tapping any remote device.
        shell(device, "am", "start", "-n", PKG + "/.MainActivity", timeout=30)
        time.sleep(6)
    raise RuntimeError(f"{label}: viewer home not available after {attempts} attempts")

def tap(device, node):
    if node is None:
        return False
    bounds = node.attrib.get("bounds", "")
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    shell(device, "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True

def wait_boot(device, seconds=240):
    adb(device, "wait-for-device", timeout=seconds)
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if shell(device, "getprop", "sys.boot_completed").stdout.strip() == "1":
            return
        time.sleep(3)
    raise TimeoutError(f"{device} did not finish booting")

def start_b():
    avd_home = Path.home() / ".android" / "avd"
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT not configured")
    api_level = int(os.environ.get("ANDROID_TEST_API", "35"))
    image = f"system-images;android-{api_level};google_apis;x86_64"
    record(f"Preparing second Android {api_level - 20} AVD (API {api_level})...")
    r = run(["avdmanager", "create", "avd", "--force", "--name", "phone_b",
             "--package", image, "--device", "pixel_5"], timeout=90)
    if r.returncode:
        raise RuntimeError("avdmanager failed: " + r.stderr[-1500:])
    emulator_bin = str(Path(sdk) / "emulator" / "emulator")
    logfile = (OUT / "emulator_b.log").open("w")
    p = subprocess.Popen([emulator_bin, "-avd", "phone_b", "-port", "5556",
                          "-no-window", "-no-snapshot", "-gpu", "swiftshader_indirect",
                          "-noaudio", "-no-boot-anim", "-camera-back", "none"],
                         stdout=logfile, stderr=subprocess.STDOUT,
                         start_new_session=True)
    record(f"B emulator process started pid={p.pid}")
    wait_boot(B)
    record("B emulator boot completed.")

def simulate_owner_approval():
    # Synthetic emulator only: this test simulates the device owner's explicit
    # ADB trust approval in the system prompt. Never applied to a real device.
    for _ in range(12):
        try:
            root = dump_ui(A, "a-permission-window.xml")
            nodes = list(root.iter("node")) if root is not None else []
            text_blob = " ".join(n.attrib.get("text", "") for n in nodes).lower()
            if "usb debugging" in text_blob or "usb调试" in text_blob or "usb 调试" in text_blob:
                cb = next((n for n in nodes
                           if ("always allow" in n.attrib.get("text", "").lower()
                               or "始终允许" in n.attrib.get("text", ""))
                           and n.attrib.get("checkable") == "true"), None)
                if cb is not None and cb.attrib.get("checked") != "true":
                    tap(A, cb)
                allow = next((n for n in nodes
                              if n.attrib.get("class") == "android.widget.Button"
                              and n.attrib.get("text", "").strip().lower() in
                                  ("allow", "允许", "确定", "ok")), None)
                if allow is not None and tap(A, allow):
                    record("A emulator: simulated device owner accepting ADB authorization")
                    return
        except Exception as err:
            record("A permission UI check: " + str(err)[:120])
        time.sleep(3)
    record("No ADB authorization dialog found in emulator A; may already be authorized or connection unsuccessful")


def find_visible_control(device, resource_id, label, *, scroll_down=True, attempts=11):
    for turn in range(attempts):
        tree = dump_ui(device, f"{label}-find-{resource_id}-{turn}.xml")
        node = find_node(tree, resource_id=resource_id)
        if node is not None:
            return node
        if scroll_down:
            shell(device, "input", "swipe", "500", "1750", "500", "550", "400")
        else:
            shell(device, "input", "swipe", "500", "550", "500", "1750", "400")
        time.sleep(0.6)
    raise RuntimeError(f"{label}: {resource_id} not visible after scrolling")

def fill_field(device, field_id, value, label):
    node = find_visible_control(device, field_id, label)
    if not tap(device, node):
        raise RuntimeError(f"{label}: failed to tap {field_id}")
    shell(device, "input", "keyevent", "123")
    for _ in range(70):
        shell(device, "input", "keyevent", "67")
    shell(device, "input", "text", value)
    root = dump_ui(device, f"{label}-{field_id}-typed.xml")
    entered = find_node(root, resource_id=field_id)
    if entered is None or entered.attrib.get("text", "").strip() != value:
        raise RuntimeError(f"{label}: {field_id} text did not match")
    shell(device, "input", "keyevent", "KEYCODE_BACK")

def start_owner_wake_listener():
    install_a = adb(A, "install", "-r", str(apk), timeout=120)
    if install_a.returncode:
        raise RuntimeError("Failed to install A owner app: " + install_a.stdout[-700:])
    shell(A, "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(4)
    root = wait_for_viewer_home(A, label="owner-wake-app")
    more = find_node(root, resource_id="btn_more_settings")
    if not tap(A, more):
        raise RuntimeError("A: failed to open wake settings")
    time.sleep(1)
    on = find_visible_control(A, "button_wake_listen_on", "owner-wake-enable")
    if not tap(A, on):
        raise RuntimeError("A: failed to opt in to LAN wake listening")
    record("A owner explicitly opted into LAN wake listening")
    token = None
    for retry in range(16):
        result = shell(A, "run-as", PKG, "cat", "shared_prefs/wake_lan_test.xml")
        match = re.search(r'<string name="temporary_code">([0-9a-f]{32})</string>', result.stdout)
        if match:
            token = match.group(1)
            break
        time.sleep(0.5)
    if token is None:
        raise RuntimeError("A wake code not generated after explicit activation")
    # Never log or upload the secret code. Only return it to synthetic B.
    adb(A, "forward", "tcp:10055", "tcp:10055", timeout=20)
    # A bad proof must be rejected; B may start scrcpy only after a good proof.
    with socket.create_connection(("127.0.0.1", 10055), timeout=8) as raw:
        raw.settimeout(8)
        stream = raw.makefile("rwb", buffering=0)
        stream.write(b"HELLO 0123456789abcdef\\n")
        response = stream.readline()
        if not response.startswith(b"CHALLENGE "):
            raise RuntimeError("A wake server did not issue a challenge")
        stream.write(b"AUTH " + b"0" * 64 + b"\\n")
        denied = stream.readline().strip()
        if denied != b"DENIED":
            raise RuntimeError("A wake listener accepted an invalid authentication proof")
    record("PASS: incorrect wake proof rejected by emulator A")
    return token

def collect():
    for device, name in ((A, "a"), (B, "b")):
        try:
            screenshot(device, f"{name}-screen.png")
            shell(device, "dumpsys", "activity", "activities", timeout=15)
            adb(device, "logcat", "-d", "-v", "threadtime", timeout=30)
            log = adb(device, "logcat", "-d", "-v", "threadtime", timeout=30)
            (OUT / f"{name}-logcat.txt").write_text(log.stdout, encoding="utf-8")
            activity = shell(device, "dumpsys", "activity", "activities", timeout=30)
            (OUT / f"{name}-activity.txt").write_text(activity.stdout, encoding="utf-8")
        except Exception as err:
            record(f"Diagnostic collection issue on {device}: {err}")

try:
    wait_boot(A)
    record("A Android version " + shell(A, "getprop", "ro.build.version.release").stdout.strip())
    start_b()
    record("B Android version " + shell(B, "getprop", "ro.build.version.release").stdout.strip())
    for device in (A, B):
        shell(device, "input", "keyevent", "82")
        shell(device, "svc", "power", "stayon", "true")
        shell(device, "settings", "put", "system", "screen_off_timeout", "600000")
    apk = next(Path("app/build/outputs/apk/scrcpy/debug").glob("*.apk"))
    wake_token = start_owner_wake_listener()
    install = adb(B, "install", "-r", str(apk), timeout=120)
    if install.returncode:
        raise RuntimeError("Failed to install B app: " + (install.stdout + install.stderr)[-1200:])
    record("B app installed: " + str(apk))
    launched = shell(B, "am", "start", "-W", "-n", PKG + "/.MainActivity", timeout=30)
    record("B app launch: " + " ".join(launched.stdout.strip().splitlines()[-3:]))
    time.sleep(5)
    screenshot(B, "b-before-connection.png")
    # Android emulators can show Pixel Launcher ANR over the newly opened app.
    # Recover the local emulator UI before looking for our EditText.
    wait_for_viewer_home(B, label="first-launch")

    # Reach host-side emulator A ADB listener using Android emulator networking.
    ip = shell(B, "ping", "-c", "1", "-W", "3", "10.0.2.2", timeout=10)
    record("B -> emulator host gateway ping: " + ("success" if ip.returncode == 0 else "unavailable"))

    root = wait_for_viewer_home(B, label="connection")
    host = find_node(root, resource_id="editText_server_host")
    if not tap(B, host):
        raise RuntimeError("B remote host input not found in UI hierarchy")
    shell(B, "input", "keyevent", "123")  # move cursor to end
    for _ in range(60):
        shell(B, "input", "keyevent", "67")
    shell(B, "input", "text", HOST)
    record("B host entry set to local synthetic emulator A address")

    root = dump_ui(B, "b-address-set.xml")
    host_node = find_node(root, resource_id="editText_server_host")
    typed_address = host_node.attrib.get("text", "") if host_node is not None else ""
    record("B address field before closing keyboard: " + typed_address)
    if typed_address != HOST:
        raise RuntimeError(f"Remote address input mismatch: expected {HOST}, got {typed_address!r}")

    # Configure test-only A<->B authenticated wake from the B viewer UI.
    more = find_node(root, resource_id="btn_more_settings")
    if not tap(B, more):
        more = find_visible_control(B, "btn_more_settings", "viewer-wake-more", scroll_down=False)
        if not tap(B, more):
            raise RuntimeError("B: could not expand wake settings")
    fill_field(B, "editText_wake_host", WAKE_HOST, "viewer-wake-host")
    fill_field(B, "editText_wake_key", wake_token, "viewer-wake-key")
    record("B configured A's authenticated LAN wake endpoint; code kept out of logs")

    # Hide the soft keyboard before tapping Start. The UI hierarchy can still
    # list an underlying button even when the IME covers its coordinates.
    # The earlier test accidentally tapped the 'y' key over the Start button.
    shell(B, "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    root = dump_ui(B, "b-keyboard-hidden.xml")
    host_node = find_node(root, resource_id="editText_server_host")
    typed_address = host_node.attrib.get("text", "") if host_node is not None else ""
    if typed_address != HOST:
        raise RuntimeError(f"Remote address mutated after hiding keyboard: {typed_address!r}")
    screenshot(B, "b-ready-to-start.png")
    start = find_node(root, resource_id="button_start")
    for attempt in range(3):
        if start is not None:
            break
        shell(B, "input", "swipe", "500", "1500", "500", "450", "350")
        root = dump_ui(B, f"b-scrolled-{attempt}.xml")
        start = find_node(root, resource_id="button_start")
    if start is None:
        start = find_visible_control(B, "button_start", "viewer-wake-start")
    if not tap(B, start):
        raise RuntimeError("B Start control missing from UI hierarchy")
    record("B Start button tapped with soft keyboard dismissed; waiting for local ADB scrcpy connection")
    # The viewer now checks authorization BEFORE starting the scrcpy server.
    # Simulate the consenting owner promptly in the synthetic A emulator;
    # otherwise the preflight may time out while the test waits to click Allow.
    permission_thread = threading.Thread(target=simulate_owner_approval, daemon=True)
    permission_thread.start()
    time.sleep(5)
    screenshot(B, "b-five-seconds-after-start.png")
    permission_thread.join(timeout=40)
    time.sleep(25)
    collect()
    log = (OUT / "b-logcat.txt").read_text(encoding="utf-8", errors="replace")
    handshake = re.search(r"Handshake device=\d+x\d+ video=\d+x\d+", log)
    started = "Start clicked host=" in log
    record("B connection request logged: " + str(started))
    record("B video handshake recorded: " + str(handshake is not None))
    if handshake:
        record("Handshake: " + handshake.group(0))
    record("Screenshots and per-device logs are available in the workflow artifact.")
    a_logcat = (OUT / "a-logcat.txt").read_text(encoding="utf-8", errors="replace")
    if "Authorized LAN wake request accepted" not in a_logcat:
        raise RuntimeError("A did not log an authenticated wake acceptance")
    record("PASS: A authenticated B's wake request before B started viewing")
    if not handshake:
        raise RuntimeError("No scrcpy video handshake; review both logcat files and device screenshots")
    record("PASS: dual Android 15 emulators established a scrcpy video handshake.")

    # Live-content change test: A opens YouTube, while B continues mirroring A.
    # The previous GitHub Actions emulators are ephemeral, so this happens in
    # the newly launched pair, not in any past (already stopped) run.
    screenshot(A, "a-before-youtube.png")
    screenshot(B, "b-before-youtube.png")
    record("Opening YouTube on emulator A while B is connected...")

    youtube_installed = "package:" in shell(A, "pm", "path", "com.google.android.youtube").stdout
    if youtube_installed:
        attempt = shell(A, "monkey", "-p", "com.google.android.youtube",
                        "-c", "android.intent.category.LAUNCHER", "1", timeout=30)
        record("A native YouTube app launch: " +
               ("requested" if attempt.returncode == 0 and "Events injected: 1" in attempt.stdout
                else "did not start; falling back to browser"))
        youtube_installed = attempt.returncode == 0 and "Events injected: 1" in attempt.stdout

    if not youtube_installed:
        attempt = shell(A, "am", "start", "-W", "-a", "android.intent.action.VIEW",
                        "-d", "https://m.youtube.com", timeout=40)
        record("A YouTube website launch: " +
               " ".join(attempt.stdout.strip().splitlines()[-4:]))
        if attempt.returncode != 0 or "Error:" in attempt.stdout:
            raise RuntimeError("A emulator could not open YouTube URL: " +
                               (attempt.stdout + attempt.stderr)[-700:])

    time.sleep(18)
    screenshot(A, "a-youtube.png")
    screenshot(B, "b-youtube.png")
    focus_a = shell(A, "dumpsys", "window", timeout=25).stdout
    focus_b = shell(B, "dumpsys", "window", timeout=25).stdout
    (OUT / "a-youtube-window.txt").write_text(focus_a, encoding="utf-8")
    (OUT / "b-youtube-window.txt").write_text(focus_b, encoding="utf-8")

    from hashlib import sha256
    def file_digest(file_name):
        f = OUT / file_name
        return sha256(f.read_bytes()).hexdigest() if f.exists() and f.stat().st_size else "MISSING"

    a_changed = file_digest("a-before-youtube.png") != file_digest("a-youtube.png")
    b_changed = file_digest("b-before-youtube.png") != file_digest("b-youtube.png")
    record("A screen changed after opening YouTube: " + str(a_changed))
    record("B mirrored screen changed after A opened YouTube: " + str(b_changed))
    record("A YouTube and B mirrored screenshots are saved in the test artifact.")
    if not a_changed:
        raise RuntimeError("YouTube launch did not visibly change A screenshot")
    if not b_changed:
        raise RuntimeError("B screen remained identical after A opened YouTube")
    record("PASS: A opened YouTube and B displayed a changed screen.")
    record("Note: screenshot-change check alone does not prove individual YouTube video playback or frame-perfect mirroring; review a-youtube.png and b-youtube.png.")

    record("Screen mirroring only: no remote touch/key/command testing.")
    record("Testing viewer reconnection without any control of A.")

    # Reconnection: force-stop only the viewing app B, then relaunch and
    # reconnect using the same locally authorized ADB connection.
    # This is a foreground, synthetic-device recovery test.
    record("Disconnecting B viewer to test reconnection")
    shell(B, "am", "force-stop", PKG)
    time.sleep(5)
    relaunch = shell(B, "am", "start", "-W", "-n", PKG + "/.MainActivity", timeout=30)
    if relaunch.returncode != 0:
        raise RuntimeError("Could not relaunch B after disconnect")
    time.sleep(5)
    root_b = wait_for_viewer_home(B, label="reconnect")
    addr = find_node(root_b, resource_id="editText_server_host")
    if addr is None:
        raise RuntimeError("B connection screen unavailable after restart")
    actual_addr = addr.attrib.get("text", "").strip()
    if actual_addr != HOST:
        record(f"Restoring B target address after relaunch (was {actual_addr!r})")
        if not tap(B, addr):
            raise RuntimeError("Unable to select address input after relaunch")
        shell(B, "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(60):
            shell(B, "input", "keyevent", "KEYCODE_DEL")
        shell(B, "input", "text", HOST)
        shell(B, "input", "keyevent", "KEYCODE_BACK")
        time.sleep(2)
        root_b = dump_ui(B, "b-reconnected-address.xml")
        addr = find_node(root_b, resource_id="editText_server_host")
        if addr is None or addr.attrib.get("text", "").strip() != HOST:
            raise RuntimeError("B reconnect address was not set correctly")

    start2 = find_node(root_b, resource_id="button_start")
    if start2 is None:
        raise RuntimeError("B Start missing after viewer restart")
    screenshot(B, "b-before-reconnect-start.png")
    if not tap(B, start2):
        raise RuntimeError("Unable to tap B reconnect Start")
    record("B reconnect Start tapped")
    time.sleep(35)
    screenshot(A, "a-after-reconnect.png")
    screenshot(B, "b-after-reconnect.png")
    second_log = adb(B, "logcat", "-d", "-v", "threadtime", timeout=40).stdout
    (OUT / "b-after-reconnect-logcat.txt").write_text(second_log, encoding="utf-8")
    count = len(re.findall(r"Handshake device=\d+x\d+ video=\d+x\d+", second_log))
    record(f"Scrcpy video handshakes seen across connection cycles: {count}")
    if count < 2:
        raise RuntimeError("B did not record a second video handshake after reconnect")
    record("PASS: B viewing app reconnected to A after foreground restart")

    # Screen-only continuity: changes originate on test emulator A itself.
    # The viewer on B must not inject any touch/key event into A.
    # Dismiss only a local Android fullscreen help tooltip on B, if present.
    for tip_attempt in range(3):
        b_root = dump_ui(B, f"b-local-fullscreen-tip-{tip_attempt}.xml")
        got_it = next((n for n in b_root.iter("node")
                       if n.attrib.get("text", "").strip().lower() == "got it"),
                      None) if b_root is not None else None
        if got_it is None:
            break
        if not tap(B, got_it):
            raise RuntimeError("B's local fullscreen tutorial could not be dismissed")
        record("Dismissed Android fullscreen tutorial on viewer B (local UI only)")
        time.sleep(3)

    # The YouTube first-run dialog can be replaced by normal Android settings.
    shell(A, "am", "force-stop", "com.google.android.youtube")
    time.sleep(2)
    screenshot(B, "b-continuity-before.png")
    previous_b = file_digest("b-continuity-before.png")
    transitions = [
        ("settings", "android.settings.SETTINGS"),
        ("wifi", "android.settings.WIFI_SETTINGS"),
        ("display", "android.settings.DISPLAY_SETTINGS"),
    ]
    successful_updates = 0
    for label, action in transitions:
        record(f"A local emulator opening {label} page; viewer B remains read-only")
        outcome = shell(A, "am", "start", "-W", "-a", action, timeout=45)
        if outcome.returncode != 0 or "Error:" in outcome.stdout:
            raise RuntimeError(f"A could not open {label}: " + (outcome.stdout + outcome.stderr)[-900:])
        time.sleep(9)
        screenshot(A, f"a-continuity-{label}.png")
        screenshot(B, f"b-continuity-{label}.png")
        a_size = (OUT / f"a-continuity-{label}.png").stat().st_size
        b_hash = file_digest(f"b-continuity-{label}.png")
        b_size = (OUT / f"b-continuity-{label}.png").stat().st_size
        changed_b = b_hash not in ("MISSING", previous_b)
        record(f"Screen continuity {label}: A png={a_size} bytes, B png={b_size} bytes, B updated={changed_b}")
        if a_size < 3000 or b_size < 3000:
            raise RuntimeError(f"Invalid screenshot generated during {label}")
        successful_updates += int(changed_b)
        previous_b = b_hash

    record(f"B screen changes across 3 A-side transitions: {successful_updates}/3")
    if successful_updates != len(transitions):
        raise RuntimeError("Read-only viewer did not display a changed frame after every A-side page transition")
    record("PASS: B screen updated through three A-side page transitions without viewer controlling A")
    record("Screenshot hashes confirm visible changes, not precise frame latency or pixel-for-pixel equality.")

    # Additional 60-second viewer stability test. A stays on a static page;
    # B remains a passive viewer. No commands or control requests are sent
    # from B to A. Screenshot timestamps and logs are retained for diagnosis.
    record("Starting 60-second continuous view-only monitoring on B")
    for sample in range(1, 4):
        time.sleep(20)
        live = shell(B, "pidof", PKG, timeout=12).stdout.strip()
        if not live:
            raise RuntimeError(f"Viewer process exited during stability sample {sample}")
        a_file = f"a-stability-{sample}.png"
        b_file = f"b-stability-{sample}.png"
        screenshot(A, a_file)
        screenshot(B, b_file)
        a_bytes = (OUT / a_file).stat().st_size
        b_bytes = (OUT / b_file).stat().st_size
        if a_bytes < 3000 or b_bytes < 3000:
            raise RuntimeError(f"Invalid screenshot while monitoring sample {sample}")
        record(f"Viewer stability +{sample * 20}s: B process alive, A frame={a_bytes} bytes, B frame={b_bytes} bytes")
    record("PASS: B viewer process stayed active for 60 seconds with screenshots from both virtual phones")

    # Approximately four additional minutes of live screen mirroring, added
    # to the preceding one-minute stable session. All screen changes are
    # generated LOCALLY on emulator A by the authorized test harness.
    # Nothing is injected by the B viewer or the mirrored video surface.
    record("Starting 10-stage dynamic read-only screen continuity stress test")
    test_begin = time.monotonic()
    # Use two reliably distinct Settings destinations. The Google emulator
    # sometimes redirects WIFI_SETTINGS to Display & touch during a launcher
    # ANR, falsely indicating a mirroring problem if we require 3 unique pages.
    dynamic_stages = [
        ("settings", "android.settings.SETTINGS"),
        ("display", "android.settings.DISPLAY_SETTINGS"),
    ]
    for idx in range(10):
        label, action = dynamic_stages[idx % len(dynamic_stages)]
        result = shell(A, "am", "start", "-W", "-a", action, timeout=45)
        if result.returncode != 0 or "Error:" in result.stdout:
            raise RuntimeError(f"Stress step {idx+1}: A failed to open {label}")
        time.sleep(8)
        a_name = f"a-stress-{idx+1:02d}.png"
        b_name = f"b-stress-{idx+1:02d}.png"
        screenshot(A, a_name)
        screenshot(B, b_name)
        if idx > 0:
            previous_a = OUT / f"a-stress-{idx:02d}.png"
            if previous_a.read_bytes() == (OUT / a_name).read_bytes():
                record(f"WARNING: A emulator did not visibly change at stress step {idx+1}")
        if (OUT / a_name).stat().st_size < 3000 or (OUT / b_name).stat().st_size < 3000:
            raise RuntimeError(f"Stress step {idx+1}: missing valid A/B frames")
        time.sleep(17)
        if not shell(B, "pidof", PKG, timeout=15).stdout.strip():
            raise RuntimeError(f"Stress step {idx+1}: viewer process stopped")
        record(f"Read-only stress step {idx+1}/10: A={label}, B alive, images saved")
    elapsed = round(time.monotonic() - test_begin, 1)
    record(f"PASS: 10 A-side screen transitions with B staying alive for {elapsed}s")
    record("Image fidelity is independently assessed in analyze-mirror-frames.py.")
except Exception as err:
    record("FAIL: " + repr(err))
    collect()
    sys.exit(1)
finally:
    (OUT / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
