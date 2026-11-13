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
            title = find_node(root, texts=("Allow USB debugging", "允许USB调试", "Always allow from this computer"))
            if title is not None:
                cb = find_node(root, texts=("Always allow from this computer", "始终允许使用这台计算机"))
                if cb is not None and cb.attrib.get("checked") != "true":
                    tap(A, cb)
                allow = find_node(root, texts=("Allow", "允许"))
                if allow and tap(A, allow):
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
    start = find_node(root, resource_id="button_start")
    for attempt in range(3):
        if start is not None:
            break
        shell(B, "input", "swipe", "500", "1500", "500", "450", "350")
        root = dump_ui(B, f"b-scrolled-{attempt}.xml")
        start = find_node(root, resource_id="button_start")
    if not tap(B, start):
        raise RuntimeError("B Start control missing from UI hierarchy")
    record("B app Start tapped; waiting for local ADB scrcpy connection")
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
    record("Decoded frame visibility requires reviewing b-screen.png; handshake alone is not end-to-end visual verification.")
except Exception as err:
    record("FAIL: " + repr(err))
    collect()
    sys.exit(1)
finally:
    (OUT / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
