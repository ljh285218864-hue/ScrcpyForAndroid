#!/usr/bin/env python3
"""Local-only dual Android emulator integration test.
The two emulators run under the same GitHub Actions job.
No ADB ports are intentionally exposed to the public Internet.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path("dual-emulator-results")
OUT.mkdir(exist_ok=True)
A = "emulator-5554"
B = "emulator-5556"
HOST = "10.0.2.2:5555"  # Android emulator B -> GitHub runner -> emulator A local ADB port
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
    image = "system-images;android-35;google_apis;x86_64"
    record("Preparing second Android 15 AVD...")
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
    install = adb(B, "install", "-r", str(apk), timeout=120)
    if install.returncode:
        raise RuntimeError("Failed to install B app: " + (install.stdout + install.stderr)[-1200:])
    record("B app installed: " + str(apk))
    launched = shell(B, "am", "start", "-W", "-n", PKG + "/.MainActivity", timeout=30)
    record("B app launch: " + " ".join(launched.stdout.strip().splitlines()[-3:]))
    time.sleep(5)
    screenshot(B, "b-before-connection.png")

    # Reach host-side emulator A ADB listener using Android emulator networking.
    ip = shell(B, "ping", "-c", "1", "-W", "3", "10.0.2.2", timeout=10)
    record("B -> emulator host gateway ping: " + ("success" if ip.returncode == 0 else "unavailable"))

    root = dump_ui(B, "b-before.xml")
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
    if not tap(B, start):
        raise RuntimeError("B Start control missing from UI hierarchy")
    record("B Start button tapped with soft keyboard dismissed; waiting for local ADB scrcpy connection")
    time.sleep(5)
    screenshot(B, "b-five-seconds-after-start.png")
    simulate_owner_approval()
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

    # Next phase: verify remote INPUT, not merely matching screenshots.
    # The fullscreen tutorial is an Android SYSTEM modal on B; tapping the
    # mirrored surface before dismissing it would never reach A.
    root_b = dump_ui(B, "b-fullscreen-tip.xml")
    got_it = find_node(root_b, texts=("Got it",))
    if got_it:
        if not tap(B, got_it):
            raise RuntimeError("Unable to dismiss B's fullscreen system tutorial")
        time.sleep(3)
        record("B Android fullscreen tutorial dismissed")

    # Prefer the deterministic YouTube permission dialog on the fresh AVD.
    # If YouTube has no permission dialog, use Android Settings instead.
    a_before = dump_ui(A, "a-before-remote-touch.xml")
    target = find_node(a_before, texts=("Don't allow", "Don’t allow"))
    touch_context = "YouTube notification dialog"
    if target is None:
        record("YouTube permission dialog not present; using Settings navigation")
        result = shell(A, "am", "start", "-a", "android.settings.SETTINGS", timeout=30)
        if result.returncode != 0:
            raise RuntimeError("Could not open Settings on A")
        time.sleep(8)
        a_before = dump_ui(A, "a-before-remote-touch.xml")
        target = find_node(a_before, texts=("Network & internet", "Network & Internet"))
        touch_context = "Android Settings Network & internet"
    if target is None:
        raise RuntimeError("No safe, visible A UI target available for B-to-A touch test")

    # Coordinate parity is valid for this specific test: both AVDs use the
    # same 1080x2340 resolution, with B in full-screen scrcpy mode.
    screenshot(A, "a-before-remote-touch.png")
    screenshot(B, "b-before-remote-touch.png")
    target_bounds = target.attrib.get("bounds", "")
    record(f"Remote input target on A: {touch_context}, bounds={target_bounds}")
    if not tap(B, target):
        raise RuntimeError("B could not send touch to mirrored A target")

    time.sleep(9)
    a_after = dump_ui(A, "a-after-remote-touch.xml")
    screenshot(A, "a-after-remote-touch.png")
    screenshot(B, "b-after-remote-touch.png")
    target_after = find_node(a_after, texts=("Don't allow", "Don’t allow")) if touch_context.startswith("YouTube") else None
    ui_changed = ET.tostring(a_before) != ET.tostring(a_after) if a_before is not None and a_after is not None else False
    if touch_context.startswith("YouTube"):
        touch_ok = target_after is None and ui_changed
    else:
        # The settings dashboard and its Network subpage have different
        # exported hierarchy; check that we navigated and UI changed.
        touch_ok = ui_changed and find_node(a_after, texts=("Network & internet", "Network & Internet")) is None
    record("A UI changed after B touch: " + str(ui_changed))
    record("B-to-A remote touch verified: " + str(touch_ok))
    if not touch_ok:
        raise RuntimeError("B tap did not produce the expected UI transition on A")
    record("PASS: B touch input controlled A's UI")

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
    root_b = dump_ui(B, "b-reconnected-home.xml")
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
except Exception as err:
    record("FAIL: " + repr(err))
    collect()
    sys.exit(1)
finally:
    (OUT / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
