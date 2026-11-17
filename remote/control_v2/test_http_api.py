"""HTTP contract tests for Beta4, using the *real* localhost HTTP server.

Runs on a disposable CI runner with synthetic WireGuard keys and mocked
kernel operations. No VPS, Android devices, or public endpoints are touched.
"""
from __future__ import annotations

import base64
import importlib.util
import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen

source = Path(__file__).with_name("server.py")
spec = importlib.util.spec_from_file_location("screensync_v2_http_test", source)
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
PUBS = [base64.b64encode(bytes([v]) * 32).decode("ascii")
        for v in (10, 20, 30, 40, 50)]


class HttpApiContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        original = (server.DATABASE, server.ADMIN_KEY, server.ENDPOINT,
                    server.MAX_PAIRS)
        self.addCleanup(setattr, server, "DATABASE", original[0])
        self.addCleanup(setattr, server, "ADMIN_KEY", original[1])
        self.addCleanup(setattr, server, "ENDPOINT", original[2])
        self.addCleanup(setattr, server, "MAX_PAIRS", original[3])
        server.DATABASE = Path(self.tmp.name) / "api.db"
        server.ADMIN_KEY = "a" * 48
        server.ENDPOINT = "166.88.95.233:51821"
        server.MAX_PAIRS = 2
        server.HITS.clear()
        for operation in ("wg_add", "wg_remove", "update_acl"):
            patch = mock.patch.object(server, operation)
            patch.start()
            self.addCleanup(patch.stop)
        patch = mock.patch.object(server, "run",
                                  return_value=SimpleNamespace(stdout=PUBS[4] + "\n"))
        patch.start()
        self.addCleanup(patch.stop)
        server.schema()
        self.httpd = server.BoundedThreadingHTTPServer(
            ("127.0.0.1", 0), server.Handler)
        thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.httpd.shutdown)
        self.addCleanup(self.httpd.server_close)
        self.origin = "http://127.0.0.1:" + str(self.httpd.server_address[1])

    def post(self, path, body, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(self.origin + path,
                          json.dumps(body).encode("utf-8"), headers, method="POST")
        try:
            with urlopen(request, timeout=3) as response:
                self.assertEqual(response.headers.get("Cache-Control"), "no-store")
                return response.status, json.loads(response.read())
        except HTTPError as response:
            return response.code, json.loads(response.read())

    def test_full_two_group_pairing_and_group_scoped_revocation_via_http(self):
        owners, guests = [], []
        for index in (0, 2):
            code = server.issue_owner_code(600).split("code=", 1)[1]
            status, owner = self.post("/v1/activate", {
                "public_key": PUBS[index], "name": "owner" + str(index),
                "code": code})
            self.assertEqual(status, 200)
            owners.append(owner)
            # One-time activation code cannot create another device.
            self.assertEqual(self.post("/v1/activate", {
                "public_key": PUBS[4], "name": "duplicate", "code": code})[0], 403)
            invite = self.post("/v1/invite", {}, owner["token"])[1]["invite_code"]
            status, guest = self.post("/v1/join", {
                "public_key": PUBS[index + 1], "name": "guest" + str(index),
                "invite_code": invite})
            self.assertEqual(status, 200)
            guests.append(guest)
            pending = self.post("/v1/status", {}, guest["token"])[1]
            self.assertEqual(pending["state"], "pending")
            self.assertNotIn("shared_secret", pending)

        self.assertEqual(self.post("/v1/approve",
            {"pending_id": guests[1]["device_id"]}, owners[0]["token"])[0], 404)

        for owner, guest in zip(owners, guests):
            self.assertEqual(self.post("/v1/approve",
                {"pending_id": guest["device_id"]}, owner["token"])[0], 200)
            o = self.post("/v1/status", {}, owner["token"])[1]
            g = self.post("/v1/status", {}, guest["token"])[1]
            self.assertEqual(o["state"], "paired")
            self.assertEqual(g["state"], "paired")
            self.assertEqual(o["shared_secret"], g["shared_secret"])
            self.assertEqual(o["overlay_cidr"], "100.81.0.0/16")
            self.assertEqual(o["peer_ip"], g["device_ip"])
            self.assertEqual(g["peer_ip"], o["device_ip"])
            self.assertEqual(o["endpoint"], server.ENDPOINT)

        self.assertEqual(self.post("/v1/revoke", {}, guests[0]["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, guests[0]["token"])[0], 401)
        self.assertEqual(self.post("/v1/status", {}, owners[0]["token"])[1]["state"],
                         "ready")
        self.assertEqual(self.post("/v1/status", {}, owners[1]["token"])[1]["state"],
                         "paired")
        self.assertEqual(self.post("/v1/status", {}, guests[1]["token"])[1]["state"],
                         "paired")

    def test_invalid_credentials_input_and_no_public_privileges(self):
        status, result = self.post("/v1/status", {})
        self.assertEqual(status, 401)
        self.assertEqual(result["error"], "AUTH_REQUIRED")
        self.assertEqual(self.post("/v1/activate", {
            "code": "b" * 48, "public_key": PUBS[0], "name": "untrusted"})[0], 403)
        self.assertEqual(self.post("/v1/join", {
            "invite_code": "bad", "public_key": PUBS[1], "name": "guest"})[0], 403)
        self.assertEqual(self.post("/v1/approve", {"pending_id": "x" * 32})[0], 401)
        with urlopen(self.origin + "/health", timeout=3) as response:
            self.assertEqual(json.loads(response.read())["service"], "screensync-v2")


if __name__ == "__main__":
    unittest.main()
