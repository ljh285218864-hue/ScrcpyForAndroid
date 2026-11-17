"""Real localhost HTTP tests for no-VPN pairing / reconnect signaling.

No production VPS, Android device, VPN, ADB or screen capture involved.
"""
from __future__ import annotations

import importlib.util
import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

path = Path(__file__).with_name("server.py")
spec = importlib.util.spec_from_file_location("novpn_relay", path)
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)


class RelayHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.old_db = relay.DATABASE
        relay.DATABASE = Path(self.temp.name) / "relay.db"
        self.addCleanup(setattr, relay, "DATABASE", self.old_db)
        relay.schema()
        self.http = relay.BoundedHTTPServer(("127.0.0.1", 0), relay.Handler)
        self.host = "http://127.0.0.1:" + str(self.http.server_address[1])
        self.worker = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.http.shutdown)
        self.addCleanup(self.http.server_close)

    def post(self, path, data, token=None):
        hdr = {"Content-Type": "application/json"}
        if token:
            hdr["Authorization"] = "Bearer " + token
        req = Request(self.host + path, json.dumps(data).encode(), hdr, method="POST")
        try:
            with urlopen(req, timeout=4) as resp:
                self.assertIn("no-store", resp.headers["Cache-Control"])
                return resp.status, json.load(resp)
        except HTTPError as resp:
            return resp.code, json.load(resp)

    def create_owner(self, name):
        code = relay.issue_owner_code().split("code=")[1]
        status, result = self.post("/v1/activate", {"name": name, "code": code})
        self.assertEqual(status, 200)
        self.assertEqual(self.post("/v1/activate", {"name": name, "code": code})[0], 403)
        return result

    def pair(self, owner_name="A", guest_name="B"):
        owner = self.create_owner(owner_name)
        _, invite = self.post("/v1/invite", {}, owner["token"])
        _, guest = self.post("/v1/join", {"name": guest_name,
                                "invite_code": invite["invite_code"]})
        _, pending_a = self.post("/v1/status", {}, owner["token"])
        _, pending_b = self.post("/v1/status", {}, guest["token"])
        self.assertEqual(pending_a["pending_verify_code"],
                         pending_b["pending_verify_code"])
        self.assertEqual(pending_a["pending_id"], guest["device_id"])
        self.assertNotIn("peer_device_id", pending_a)
        self.assertEqual(self.post("/v1/approve", {
            "pending_id": guest["device_id"]}, owner["token"])[0], 200)
        return owner, guest

    def test_two_direction_identity_and_presence_without_vpn(self):
        owner, guest = self.pair()
        _, oa = self.post("/v1/status", {}, owner["token"])
        _, gb = self.post("/v1/status", {}, guest["token"])
        self.assertEqual(oa["peer_device_id"], guest["device_id"])
        self.assertEqual(gb["peer_device_id"], owner["device_id"])
        self.assertEqual(oa["pair_verify_code"], gb["pair_verify_code"])
        self.assertNotIn("overlay_cidr", oa)
        self.assertNotIn("endpoint", oa)
        self.assertNotIn("shared_secret", oa)
        self.assertFalse(oa["peer_online"])
        self.post("/v1/heartbeat", {}, owner["token"])
        self.assertTrue(self.post("/v1/heartbeat", {}, guest["token"])[1]["peer_online"])

    def test_delivered_message_can_be_replayed_after_client_network_restart(self):
        owner, guest = self.pair()
        self.post("/v1/heartbeat", {}, owner["token"])
        payload = {"request_id": "r1"}
        self.assertEqual(self.post("/v1/signal/send", {
            "to_device_id": guest["device_id"], "type": "watch_request",
            "payload": payload}, owner["token"])[0], 200)
        status, got = self.post("/v1/signal/poll", {"after": 0}, guest["token"])
        self.assertEqual(status, 200)
        self.assertEqual(got["messages"][0]["from_device_id"], owner["device_id"])
        self.assertEqual(got["messages"][0]["payload"], payload)
        self.assertEqual(self.post("/v1/signal/poll", {
            "after": got["cursor"]}, guest["token"])[1]["messages"], [])
        # Repeated poll with original cursor recovers a lost HTTP response.
        self.assertEqual(self.post("/v1/signal/poll", {"after": 0},
                guest["token"])[1]["messages"][0]["seq"], got["cursor"])
        self.assertEqual(self.post("/v1/signal/poll", {"after": 0},
                owner["token"])[1]["messages"], [])
        # Reverse-direction signaling uses the same saved pairing.
        self.assertEqual(self.post("/v1/signal/send", {
            "to_device_id": owner["device_id"], "type": "watch_decline",
            "payload": {"request_id": "r1"}}, guest["token"])[0], 200)
        self.assertEqual(self.post("/v1/signal/poll", {"after": 0},
                owner["token"])[1]["messages"][0]["from_device_id"], guest["device_id"])

    def test_cross_group_messaging_and_approval_forbidden(self):
        a, b = self.pair("one", "two")
        c, d = self.pair("three", "four")
        self.assertEqual(self.post("/v1/signal/send", {
            "to_device_id": d["device_id"], "type": "watch_request",
            "payload": {}}, a["token"])[0], 403)
        self.assertEqual(self.post("/v1/approve", {
            "pending_id": b["device_id"]}, c["token"])[0], 403)
        self.assertEqual(self.post("/v1/signal/poll", {"after": 0},
            d["token"])[1]["messages"], [])

    def test_unapproved_party_cannot_signal_and_revoke_erases_group(self):
        owner = self.create_owner("owner")
        _, invite = self.post("/v1/invite", {}, owner["token"])
        _, guest = self.post("/v1/join", {
            "invite_code": invite["invite_code"], "name": "guest"})
        self.assertEqual(self.post("/v1/signal/send", {
            "to_device_id": owner["device_id"], "type": "hello",
            "payload": {}}, guest["token"])[0], 403)
        self.assertEqual(self.post("/v1/revoke", {}, owner["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, owner["token"])[0], 401)
        self.assertEqual(self.post("/v1/status", {}, guest["token"])[0], 401)

    def test_revoke_one_group_does_not_affect_other(self):
        a, b = self.pair("one", "two")
        c, d = self.pair("three", "four")
        self.assertEqual(self.post("/v1/revoke", {}, b["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, a["token"])[0], 401)
        self.assertEqual(self.post("/v1/heartbeat", {}, d["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, c["token"])[1]["state"], "paired")

    def test_bad_payloads_and_unknown_endpoints_rejected(self):
        a, b = self.pair()
        for event in ("shell", "adb_command", "remote_input", None):
            self.assertEqual(self.post("/v1/signal/send", {
                "type": event, "to_device_id": b["device_id"], "payload": {}},
                a["token"])[0], 400)
        self.assertEqual(self.post("/v1/signal/send", {
            "type": "hello", "to_device_id": b["device_id"],
            "payload": {"blob": "x" * 2500}}, a["token"])[0], 400)
        for cursor in (-1, True, "2"):
            self.assertEqual(self.post("/v1/signal/poll", {"after": cursor},
                                      a["token"])[0], 400)
        self.assertEqual(self.post("/v1/status", {}, "a" * 48)[0], 401)
        self.assertEqual(self.post("/v1/signal/send", {
            "type": "hello", "payload": {}}, a["token"])[0], 403)
        self.assertEqual(self.post("/v1/nonexistent", {}, a["token"])[0], 404)

    def test_queue_bound_and_ages_out(self):
        a, b = self.pair()
        for i in range(relay.MAX_SIGNAL_COUNT):
            self.assertEqual(self.post("/v1/signal/send", {
                "type": "hello", "to_device_id": b["device_id"],
                "payload": {"n": i}}, a["token"])[0], 200)
        self.assertEqual(self.post("/v1/signal/send", {
            "type": "hello", "to_device_id": b["device_id"],
            "payload": {}}, a["token"])[0], 429)
        with relay.db_connect() as db:
            db.execute("UPDATE signals SET created=0")
        self.assertEqual(self.post("/v1/signal/poll", {"after": 0},
                                  b["token"])[1]["messages"], [])

    def test_health_never_claims_video_stream_ready(self):
        with urlopen(self.host + "/health", timeout=3) as resp:
            data = json.load(resp)
            self.assertFalse(data["video_ready"])
            self.assertEqual(data["service"], "screensync-novpn-signaling")


if __name__ == "__main__":
    unittest.main()
