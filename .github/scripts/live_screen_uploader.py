#!/usr/bin/env python3
"""Read-only Android emulator screenshot uplink to private Hostinger PHP service."""
import base64
import json
import os
import subprocess
import threading
import urllib.parse
import urllib.request

class LiveScreenUploader:
    def __init__(self, progress, interval=5.0):
        self.url = os.environ.get("SCREEN_MONITOR_URL", "").strip()
        self.key = os.environ.get("SCREEN_MONITOR_KEY", "").strip()
        self.run_id = int(os.environ.get("GITHUB_RUN_ID", "0") or "0")
        self.progress = progress
        self.interval = interval
        self.enabled = (self.url.startswith("https://")
                        and urllib.parse.urlsplit(self.url).path.endswith("/ingest.php")
                        and len(self.key) >= 32 and self.run_id > 0)
        self.stopped = threading.Event()
        self.thread = None
        self.sent = 0
        self.failed = 0

    @staticmethod
    def _capture(device):
        try:
            result = subprocess.run(
                ["adb", "-s", device, "exec-out", "screencap", "-p"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=18)
            frame = result.stdout
            if result.returncode == 0 and frame.startswith(b"\x89PNG\r\n\x1a\n") and len(frame) < 2*1024*1024:
                return base64.b64encode(frame).decode("ascii")
        except (subprocess.SubprocessError, OSError):
            pass
        return None

    def _post(self, state, include_frames):
        body = {"run_id": self.run_id, "state": state, "step": str(self.progress())[-220:]}
        if include_frames:
            for name, device in (("a", "emulator-5554"), ("b", "emulator-5556")):
                frame = self._capture(device)
                if frame:
                    body[name + "_png"] = frame
        raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.url, data=raw, method="POST",
                  headers={"Content-Type": "application/json",
                           "X-Monitor-Key": self.key,
                           "User-Agent": "AndroidEmulatorReadOnlyMonitor/1.0"})
        with urllib.request.urlopen(request, timeout=18) as response:
            if not 200 <= response.status < 300:
                raise RuntimeError("Monitor HTTP " + str(response.status))

    def _loop(self):
        while not self.stopped.is_set():
            try:
                self._post("running", include_frames=True)
                self.sent += 1
                if self.sent == 1 or self.sent % 12 == 0:
                    print("SCREEN_MONITOR: uploaded frame pair; total=", self.sent, flush=True)
            except Exception as exc:
                self.failed += 1
                if self.failed <= 3 or self.failed % 12 == 0:
                    print("SCREEN_MONITOR: upload failed:", type(exc).__name__, str(exc)[:160], flush=True)
            self.stopped.wait(self.interval)

    def start(self):
        if not self.enabled:
            print("SCREEN_MONITOR: disabled: missing HTTPS upload URL and key in GitHub secrets", flush=True)
            return
        print("SCREEN_MONITOR: enabled for run", self.run_id, flush=True)
        self.thread = threading.Thread(target=self._loop, daemon=True, name="hostinger-monitor")
        self.thread.start()

    def finish(self, success):
        if not self.enabled:
            return
        self.stopped.set()
        if self.thread:
            self.thread.join(timeout=22)
        try:
            self._post("completed" if success else "failed", include_frames=False)
            print("SCREEN_MONITOR: final status sent; uploaded pairs:", self.sent, flush=True)
        except Exception as exc:
            print("SCREEN_MONITOR: final status failed:", type(exc).__name__, str(exc)[:160], flush=True)
