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
from unittest.mock import patch
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

    def test_many_customer_groups_are_independent_one_to_one_pairs(self):
        # Twelve independent customer pairs, NOT one account watching many.
        groups = [self.pair(f"customer-{i}-A", f"customer-{i}-B")
                  for i in range(12)]
        self.assertEqual(len({a["group_id"] for a, b in groups}), 12)
        with relay.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0], 12)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM devices").fetchone()[0], 24)
            for owner, guest in groups:
                self.assertEqual(db.execute(
                    "SELECT COUNT(*) FROM devices WHERE group_id=?",
                    (owner["group_id"],)).fetchone()[0], 2)
                self.assertNotEqual(owner["device_id"], guest["device_id"])
                self.assertEqual(owner["group_id"], guest["group_id"])
        for i, (owner, guest) in enumerate(groups):
            foreign = groups[(i + 1) % len(groups)][1]
            _, own_status = self.post("/v1/status", {}, owner["token"])
            self.assertEqual(own_status["peer_device_id"], guest["device_id"])
            self.assertNotEqual(own_status["peer_device_id"], foreign["device_id"])
            self.assertEqual(self.post("/v1/signal/send", {
                "to_device_id": foreign["device_id"], "type": "hello",
                "payload": {"nonce": i}}, owner["token"])[0], 403)
            self.assertEqual(self.post("/v1/signal/send", {
                "to_device_id": guest["device_id"], "type": "hello",
                "payload": {"nonce": i}}, owner["token"])[0], 200)
            _, queued = self.post("/v1/signal/poll", {"after": 0}, guest["token"])
            self.assertEqual(len(queued["messages"]), 1)
            self.assertEqual(queued["messages"][0]["from_device_id"],
                             owner["device_id"])
            # Other groups may have their OWN previously queued messages.
            # They must never see THIS group's sender or payload.
            _, foreign_queue = self.post("/v1/signal/poll", {"after": 0},
                                         foreign["token"])
            self.assertTrue(all(message["from_device_id"] != owner["device_id"]
                                for message in foreign_queue["messages"]))
        # Unbinding one customer cannot erase eleven unrelated pairs.
        self.assertEqual(self.post("/v1/revoke", {}, groups[0][0]["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, groups[0][1]["token"])[0], 401)
        for owner, guest in groups[1:]:
            self.assertEqual(self.post("/v1/status", {}, owner["token"])[1]["state"], "paired")
            self.assertEqual(self.post("/v1/status", {}, guest["token"])[1]["state"], "paired")

    def test_single_guest_slot_and_pending_invite_replacement(self):
        owner = self.create_owner("pair-owner")
        _, invitation = self.post("/v1/invite", {}, owner["token"])
        _, first = self.post("/v1/join", {
            "invite_code": invitation["invite_code"], "name": "candidate-one"})
        # A second participant cannot join an already claimed invitation.
        self.assertEqual(self.post("/v1/join", {
            "invite_code": invitation["invite_code"], "name": "candidate-two"})[0], 403)
        # Reissuing a 5-minute invitation expires the previous candidate.
        _, second_invitation = self.post("/v1/invite", {}, owner["token"])
        self.assertEqual(self.post("/v1/status", {}, first["token"])[0], 401)
        _, second = self.post("/v1/join", {
            "invite_code": second_invitation["invite_code"],
            "name": "candidate-two"})
        self.assertEqual(self.post("/v1/approve", {
            "pending_id": second["device_id"]}, owner["token"])[0], 200)
        self.assertEqual(self.post("/v1/invite", {}, owner["token"])[0], 403)
        with relay.db_connect() as db:
            self.assertEqual(db.execute(
                "SELECT COUNT(*) FROM devices WHERE group_id=?",
                (owner["group_id"],)).fetchone()[0], 2)
            try:
                db.execute("INSERT INTO devices VALUES (?,?,?,?,?,'paired',0)",
                           ("f" * 32, owner["group_id"], "guest",
                            "illegal-third-device", "e" * 64))
            except Exception as error:
                self.assertEqual(error.__class__.__name__, "IntegrityError")
            else:
                self.fail("database allowed third member in customer group")

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

    def test_server_restart_preserves_pairing_and_queued_message(self):
        a, b = self.pair()
        self.assertEqual(self.post("/v1/signal/send", {
            "type": "hello", "to_device_id": a["device_id"],
            "payload": {"nonce": "after-restart"}}, b["token"])[0], 200)
        original = self.http
        original.shutdown()
        original.server_close()
        replacement = relay.BoundedHTTPServer(("127.0.0.1", 0), relay.Handler)
        self.addCleanup(replacement.shutdown)
        self.addCleanup(replacement.server_close)
        self.http = replacement
        self.host = "http://127.0.0.1:" + str(replacement.server_address[1])
        threading.Thread(target=replacement.serve_forever, daemon=True).start()
        self.assertEqual(self.post("/v1/status", {}, a["token"])[1]["state"], "paired")
        _, delivered = self.post("/v1/signal/poll", {"after": 0}, a["token"])
        self.assertEqual(delivered["messages"][0]["payload"]["nonce"], "after-restart")

    def test_bad_payloads_and_unknown_endpoints_rejected(self):
        a, b = self.pair()
        for event in ("shell", "adb_command", "remote_input", "offer", "answer", "ice_candidate", None):
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

    def test_self_service_requires_explicit_server_enable(self):
        previous = relay.SELF_SERVICE_ENABLED
        self.addCleanup(setattr, relay, "SELF_SERVICE_ENABLED", previous)
        relay.SELF_SERVICE_ENABLED = False
        status, rejected = self.post("/v1/self-register", {"name": "Phone A"})
        self.assertEqual(status, 403)
        self.assertEqual(rejected["error"], "SELF_REGISTER_DISABLED")
        with relay.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0], 0)

    def test_self_service_owner_qr_join_approval_and_revoke(self):
        previous = relay.SELF_SERVICE_ENABLED
        self.addCleanup(setattr, relay, "SELF_SERVICE_ENABLED", previous)
        relay.SELF_SERVICE_ENABLED = True
        status, owner = self.post("/v1/self-register", {"name": "Phone A"})
        self.assertEqual(status, 200)
        self.assertEqual(owner["state"], "ready")
        self.assertEqual(len(owner["invite_code"]), 48)
        self.assertEqual(owner["expires_in"], 300)
        self.assertEqual(len(owner["token"]), 48)
        with relay.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0], 1)
            # Never write the plaintext invite, authorization or QR to DB.
            hashes = [row[0] for row in db.execute("SELECT hash FROM invites")]
            self.assertEqual(hashes, [relay.sha(owner["invite_code"])])
            self.assertNotEqual(hashes[0], owner["invite_code"])
        status, guest = self.post("/v1/join", {
            "name": "Phone B", "invite_code": owner["invite_code"]})
        self.assertEqual(status, 200)
        self.assertEqual(guest["state"], "pending")
        self.assertEqual(owner["group_id"], guest["group_id"])
        self.assertEqual(self.post("/v1/join", {
            "name": "Phone C", "invite_code": owner["invite_code"]})[0], 403)
        _, a = self.post("/v1/status", {}, owner["token"])
        _, b = self.post("/v1/status", {}, guest["token"])
        self.assertEqual(a["pending_verify_code"], b["pending_verify_code"])
        self.assertNotIn("peer_device_id", a)
        # Before owner approval, guest cannot signal or access video.
        self.assertEqual(self.post("/v1/heartbeat", {}, guest["token"])[0], 403)
        self.assertEqual(self.post("/v1/approve", {
            "pending_id": guest["device_id"]}, owner["token"])[0], 200)
        _, approved_a = self.post("/v1/status", {}, owner["token"])
        _, approved_b = self.post("/v1/status", {}, guest["token"])
        self.assertEqual(approved_a["peer_device_id"], guest["device_id"])
        self.assertEqual(approved_b["peer_device_id"], owner["device_id"])
        self.assertEqual(self.post("/v1/revoke", {}, owner["token"])[0], 200)
        self.assertEqual(self.post("/v1/status", {}, guest["token"])[0], 401)

    def test_self_service_global_hourly_capacity_and_expired_invitation(self):
        previous = (relay.SELF_SERVICE_ENABLED, relay.MAX_SELF_REGISTER_HOUR,
                    relay.MAX_SELF_REGISTER_DAY)
        self.addCleanup(setattr, relay, "SELF_SERVICE_ENABLED", previous[0])
        self.addCleanup(setattr, relay, "MAX_SELF_REGISTER_HOUR", previous[1])
        self.addCleanup(setattr, relay, "MAX_SELF_REGISTER_DAY", previous[2])
        relay.SELF_SERVICE_ENABLED = True
        relay.MAX_SELF_REGISTER_HOUR = 2
        relay.MAX_SELF_REGISTER_DAY = 3
        _, first = self.post("/v1/self-register", {"name": "First"})
        self.assertEqual(self.post("/v1/self-register", {"name": "Second"})[0], 200)
        status, limited = self.post("/v1/self-register", {"name": "Third"})
        self.assertEqual(status, 429)
        self.assertEqual(limited["error"], "REGISTRATION_RATE_LIMIT")
        with relay.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0], 2)
            db.execute("UPDATE invites SET expires=0 WHERE group_id=?",
                       (first["group_id"],))
        self.assertEqual(self.post("/v1/join", {
            "name": "Late B", "invite_code": first["invite_code"]})[0], 403)
        # Already registered owners can renew their QR without a new group.
        self.assertEqual(self.post("/v1/invite", {}, first["token"])[0], 200)

    def test_self_service_invalid_registration_rejected_without_group(self):
        previous = relay.SELF_SERVICE_ENABLED
        self.addCleanup(setattr, relay, "SELF_SERVICE_ENABLED", previous)
        relay.SELF_SERVICE_ENABLED = True
        for name in ("", "x" * 80, None):
            self.assertEqual(self.post("/v1/self-register", {"name": name})[0], 400)
        with relay.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0], 0)

    def test_fcm_wake_token_scoped_to_paired_peer_and_rate_limited(self):
        a, b = self.pair("FCM-A", "FCM-B")
        c, d = self.pair("FCM-C", "FCM-D")
        token = "fcmToken_1234567890:ABCDEFGHIJKLMNOP"
        self.assertEqual(self.post("/v1/push/token", {"token": token})[0], 401)
        self.assertEqual(self.post("/v1/push/token", {"token": "bad"},
                                   b["token"])[0], 400)
        self.assertEqual(self.post("/v1/push/token", {"token": token},
                                   b["token"])[1]["state"], "registered")
        with relay.db_connect() as db:
            self.assertEqual(db.execute(
                "SELECT device_id,group_id FROM fcm_registrations").fetchone()["device_id"],
                b["device_id"])
        with patch.object(relay, "fcm_configured", return_value=True), \\
                patch.object(relay, "send_fcm_wake", return_value=True) as sender:
            # Only currently paired identity A can wake B. Another group
            # never receives B's token and always targets their own peer.
            self.assertEqual(self.post("/v1/push/wake", {}, c["token"])[1]["state"],
                             "peer_not_registered")
            code, result = self.post("/v1/push/wake", {}, a["token"])
            self.assertEqual(code, 200)
            self.assertEqual(result, {"state": "sent"})
            sender.assert_called_once_with(token, a["group_id"], a["device_id"])
            self.assertNotIn(token, str(result))
            self.assertEqual(self.post("/v1/push/wake", {},
                                       a["token"])[1]["state"], "throttled")
            # Reverse-direction wake requires own token registration.
            self.assertEqual(self.post("/v1/push/wake", {},
                                       b["token"])[1]["state"], "peer_not_registered")
        self.assertEqual(self.post("/v1/push/clear", {},
                                   b["token"])[1]["state"], "cleared")
        with patch.object(relay, "fcm_configured", return_value=True):
            self.assertEqual(self.post("/v1/push/wake", {},
                                       a["token"])[1]["state"], "peer_not_registered")
        self.assertEqual(self.post("/v1/push/token", {"token": token},
                                   b["token"])[0], 200)
        self.assertEqual(self.post("/v1/revoke", {}, a["token"])[0], 200)
        with relay.db_connect() as db:
            self.assertEqual(db.execute(
                "SELECT count(*) FROM fcm_registrations").fetchone()[0], 0)

    def test_fcm_opt_in_not_configured_does_not_break_pairing(self):
        a, b = self.pair()
        with patch.object(relay, "fcm_configured", return_value=False):
            self.assertEqual(self.post("/v1/push/wake", {},
                                       a["token"])[1]["state"], "not_configured")
        self.assertEqual(self.post("/v1/heartbeat", {},
                                   a["token"])[0], 200)

    def test_health_never_claims_video_stream_ready(self):
        with urlopen(self.host + "/health", timeout=3) as resp:
            data = json.load(resp)
            self.assertFalse(data["video_ready"])
            self.assertEqual(data["service"], "screensync-novpn-signaling")


if __name__ == "__main__":
    unittest.main()
