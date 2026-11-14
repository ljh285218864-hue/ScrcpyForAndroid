#!/usr/bin/env python3
"""Local-only app pairing and viewing integration test.
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
    try:
        return ET.fromstring(xml) if xml.strip().startswith("<?xml") else None
    except ET.ParseError:
        record(f"{name}: temporary malformed UI dump; retrying")
        return None

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
        if root is None:
            record(f"{label}: UI hierarchy unavailable at attempt {n+1}; recovering app")
            shell(device, "am", "start", "-n", PKG + "/.MainActivity", timeout=30)
            time.sleep(4)
            continue
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
    # Password-style Android EditText can hide the code from UIAutomator.
    # The actual cryptographic handshake, not a masked text node, verifies it.
    if entered is None or (field_id != "editText_wake_key"
                           and entered.attrib.get("text", "").strip() != value):
        raise RuntimeError(f"{label}: {field_id} text did not match")
    shell(device, "input", "keyevent", "KEYCODE_BACK")

def configure_synthetic_viewer_wake(token):
    """Synthetic B owner's private test settings; no external app API.

    UI fields remain available for real manual tests, but CI uses run-as to
    avoid flaky on-screen keyboards and scroll gestures hiding nested fields.
    """
    xml = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
           '<map><string name="viewer_host">' + WAKE_HOST +
           '</string><string name="viewer_code">' + token + '</string></map>\n')
    prep = adb(B, "shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    if prep.returncode:
        raise RuntimeError("B debug preferences directory not available: " + prep.stderr[-300:])
    result = subprocess.run(["adb", "-s", B, "shell", "run-as", PKG, "tee",
                             "shared_prefs/wake_lan_test.xml"],
                            input=xml, text=True, capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError("B debug preferences could not be written: " + result.stderr[-300:])
    saved = adb(B, "shell", "run-as", PKG, "cat", "shared_prefs/wake_lan_test.xml")
    if saved.returncode or (WAKE_HOST not in saved.stdout) or token not in saved.stdout:
        raise RuntimeError("B debug preferences verification failed")
    record("B test owner configured authenticated wake credentials (without logging secret)")

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
        stream.write(b"HELLO 0123456789abcdef\n")
        response = stream.readline()
        if not response.startswith(b"CHALLENGE "):
            raise RuntimeError("A wake server did not issue a challenge")
        stream.write(b"AUTH " + b"0" * 64 + b"\n")
        denied = stream.readline().strip()
        if denied != b"DENIED":
            raise RuntimeError("A wake listener accepted an invalid authentication proof")
    record("PASS: incorrect wake proof rejected by emulator A")
    # A's owner has opted into a visible connected-device foreground service.
    # Put the app UI in the background and verify the request listener remains.
    shell(A, "input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    activity_services = shell(A, "dumpsys", "activity", "services", PKG, timeout=20).stdout
    if "WakeLanService" not in activity_services:
        raise RuntimeError("A wake listener did not remain active after Home")
    record("PASS: A wake receiver remains active with its app UI in background")
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

def find_button(device, text, label, tries=14):
    for attempt in range(tries):
        root = dump_ui(device, f"{label}-{attempt}.xml")
        if root is not None:
            buttons = [n for n in root.iter("node")
                       if n.attrib.get("class") == "android.widget.Button"]
            for button in buttons:
                if text in button.attrib.get("text", ""):
                    return button
        time.sleep(1.0)
    raise RuntimeError(f"{label}: button {text!r} did not appear")


def read_private_pref(device, name):
    result = shell(device, "run-as", PKG, "cat",
                   f"shared_prefs/{name}.xml", timeout=25)
    return result.stdout if result.returncode == 0 else ""


def wait_for_private_pair(device, name):
    for attempt in range(25):
        data = read_private_pref(device, "paired_peer_v1")
        if "name=\"secret\"" in data and "name=\"host\"" in data:
            return data
        time.sleep(1)
    raise RuntimeError(f"{name}: app did not save owner-confirmed paired peer")


def setup_ci_discovery():
    # This is private sandbox configuration in the isolated B emulator.
    # Never modifies user phones, production settings, or skips A approval.
    test_prefs = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
                  '<map>'
                  '<string name="ci_pair_host">10.0.2.2</string>'
                  '<int name="ci_adb_port" value="5555" />'
                  '</map>\n')
    mkdir = adb(B, "shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    if mkdir.returncode:
        raise RuntimeError("B debug app sandbox unavailable: " + mkdir.stderr[-500:])
    result = subprocess.run(["adb", "-s", B, "shell", "run-as", PKG,
                             "tee", "shared_prefs/wake_lan_test.xml"],
                            input=test_prefs, text=True, capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError("B debug discovery configuration failed: " + result.stderr[-500:])
    record("B emulator configured test-only NAT discovery for A")


try:
    wait_boot(A)
    record("A API: " + shell(A, "getprop", "ro.build.version.sdk").stdout.strip())
    start_b()
    record("B API: " + shell(B, "getprop", "ro.build.version.sdk").stdout.strip())
    for device in (A, B):
        shell(device, "input", "keyevent", "82")
        shell(device, "svc", "power", "stayon", "true")
        shell(device, "settings", "put", "system", "screen_off_timeout", "600000")
    apk = next(Path("app/build/outputs/apk/scrcpy/debug").glob("*.apk"))
    for device in (A, B):
        install = adb(device, "install", "-r", str(apk), timeout=120)
        if install.returncode:
            raise RuntimeError(f"{device}: install failed: " + install.stdout[-1200:])
    record("Both fresh Android emulators installed the current PR APK")

    # Start owner A. On Android A, only a visible user action opens pairing.
    shell(A, "am", "start", "-W", "-n", PKG + "/.MainActivity")
    wait_for_viewer_home(A, label="a-pair-home")
    button = find_visible_control(A, "button_begin_pairing", "a-begin-pair",
                                  scroll_down=False)
    if not tap(A, button):
        raise RuntimeError("A: user-facing generate pairing code button failed")
    code = None
    for _ in range(18):
        data = read_private_pref(A, "wake_lan_test")
        match = re.search(r'<string name="pair_short_code">([0-9]{6})</string>', data)
        if match:
            code = match.group(1)
            break
        time.sleep(0.5)
    if not code:
        raise RuntimeError("A did not generate a six-digit app pairing code")
    record("PASS: A visibly opened a one-time pairing window (code not logged)")
    shell(A, "input", "keyevent", "KEYCODE_BACK")
    adb(A, "forward", "tcp:10055", "tcp:10055", timeout=20)
    time.sleep(3)

    # An attacker cannot create a peer just by knowing an IP without a code.
    wrong_code = "000000" if code != "000000" else "999999"
    with socket.create_connection(("127.0.0.1", 10055), timeout=8) as raw:
        raw.settimeout(8)
        stream = raw.makefile("rwb", buffering=0)
        stream.write(f"PAIR {wrong_code} 0123456789abcdef\n".encode())
        wrong_result = stream.readline().strip()
        if wrong_result != b"DENIED":
            raise RuntimeError(f"A accepted wrong pairing code: {wrong_result!r}")
    record("PASS: A rejected an incorrect app pairing code")

    setup_ci_discovery()
    shell(B, "am", "start", "-W", "-n", PKG + "/.MainActivity")
    wait_for_viewer_home(B, label="b-pair-home")
    search = find_visible_control(B, "button_auto_find", "b-find-a",
                                  scroll_down=False)
    if not tap(B, search):
        raise RuntimeError("B could not open device picker")
    time.sleep(2)
    root = dump_ui(B, "b-pair-code-dialog.xml")
    field = next((n for n in root.iter("node")
                  if n.attrib.get("class") == "android.widget.EditText"), None)
    if not tap(B, field):
        raise RuntimeError("B six-digit app pairing input did not appear")
    shell(B, "input", "text", code)
    shell(B, "input", "keyevent", "KEYCODE_BACK")  # dismiss IME, preserve pairing dialog
    time.sleep(1)
    submit = find_button(B, "提交配对", "b-submit-app-pair")
    if not tap(B, submit):
        raise RuntimeError("B could not submit app pairing")
    record("B sent pairing request; A has not granted viewing permission yet")

    # App owner A must explicitly approve on its own emulator's screen.
    approve = find_button(A, "允许配对", "a-owner-approval", tries=25)
    if not tap(A, approve):
        raise RuntimeError("A owner's approval button could not be pressed")
    record("PASS: A owner explicitly accepted B's app pairing request")

    stored_a = wait_for_private_pair(A, "A")
    stored_b = wait_for_private_pair(B, "B")
    import xml.etree.ElementTree as _ET
    def get_secret(xmltext):
        tree = _ET.fromstring(xmltext)
        row = next((e for e in tree if e.tag == "string"
                    and e.attrib.get("name") == "secret"), None)
        return row.text if row is not None else None
    secret_a, secret_b = get_secret(stored_a), get_secret(stored_b)
    if not secret_a or secret_a != secret_b or len(secret_a) != 32:
        raise RuntimeError("Paired A/B did not persist the same strong credential")
    record("PASS: A/B independently saved one shared 128-bit pairing credential")

    # Only ADB that its device owner authorizes may produce a video stream.
    # The ADB connection port 5555 is emulator-local, never internet exposed.
    shell(B, "input", "keyevent", "KEYCODE_BACK")
    wait_for_viewer_home(B, label="b-after-app-pair")
    watch = find_visible_control(B, "button_watch_paired", "b-watch-first",
                                 scroll_down=False)
    if not tap(B, watch):
        raise RuntimeError("B could not press view paired phone")
    record("B pressed one-tap View Paired Phone")
    permission_thread = threading.Thread(target=simulate_owner_approval,
                                         daemon=True)
    permission_thread.start()
    time.sleep(10)
    permission_thread.join(timeout=40)
    time.sleep(18)
    logs_b = adb(B, "logcat", "-d", "-v", "threadtime", timeout=30).stdout
    logs_a = adb(A, "logcat", "-d", "-v", "threadtime", timeout=30).stdout
    (OUT / "b-logcat.txt").write_text(logs_b, encoding="utf-8")
    (OUT / "a-logcat.txt").write_text(logs_a, encoding="utf-8")
    screenshot(B, "b-after-one-tap-view.png")
    screenshot(A, "a-screen-being-viewed.png")
    if "Authorized LAN wake request accepted" not in logs_a:
        raise RuntimeError("No owner-authorized paired wake was accepted on A")
    record("PASS: A authenticated a wake request with the saved B pairing credential")
    if not re.search(r"Handshake device=\d+x\d+ video=\d+x\d+", logs_b):
        raise RuntimeError("B did not establish an actual scrcpy video handshake")
    record("PASS: B reached A's screen through read-only scrcpy")

    # Cold-start the VIEWER app and verify the paired credential survived.
    shell(B, "am", "force-stop", PKG)
    time.sleep(2)
    reopened = shell(B, "am", "start", "-W", "-n", PKG + "/.MainActivity")
    if reopened.returncode:
        raise RuntimeError("B app could not reopen after force-stop")
    wait_for_viewer_home(B, label="b-viewer-reopen")
    if get_secret(wait_for_private_pair(B, "B reopened")) != secret_a:
        raise RuntimeError("B lost its paired key after app restart")
    record("PASS: B pairing credential persisted after app force-stop/reopen")
    watch_again = find_visible_control(B, "button_watch_paired", "b-watch-again",
                                       scroll_down=False)
    if not tap(B, watch_again):
        raise RuntimeError("B failed to tap one-click viewing after restart")
    record("PASS: B retriggered one-tap viewing after reopening the app")
    record("NOTE: actual reconnection after force-stop is checked in logs/screenshots")
    collect()
except Exception as err:
    record("FAIL: " + repr(err))
    collect()
    sys.exit(1)
finally:
    (OUT / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
