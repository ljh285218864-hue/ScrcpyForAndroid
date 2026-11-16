"""Offline control-plane authorization and state transition tests.

No VPS, keys, VPN or iptables are modified by these tests.
Run from repository root: python3 -m unittest discover -s remote/control -p 'test_*.py'
"""
import base64
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

FILE = Path(__file__).with_name("server.py")
spec = importlib.util.spec_from_file_location("screensync_backend", FILE)
backend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend)

AKEY = base64.b64encode(bytes([0x11]) * 32).decode("ascii")
BKEY = base64.b64encode(bytes([0x22]) * 32).decode("ascii")
C = "a" * 48


class ControlPlaneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous_db = backend.DATABASE
        self.previous_bootstrap = backend.BOOTSTRAP_CODE
        backend.DATABASE = Path(self.temp.name) / "test.db"
        backend.BOOTSTRAP_CODE = C
        self.addCleanup(setattr, backend, "DATABASE", self.previous_db)
        self.addCleanup(setattr, backend, "BOOTSTRAP_CODE", self.previous_bootstrap)
        self.mocks = [
            mock.patch.object(backend, "server_public_key", return_value=AKEY),
            mock.patch.object(backend, "peer_set"),
            mock.patch.object(backend, "peer_remove"),
            mock.patch.object(backend, "apply_acl"),
            mock.patch.object(backend, "assert_exclusive_wireguard_interface"),
        ]
        self.patches = [p.start() for p in self.mocks]
        for p in self.mocks:
            self.addCleanup(p.stop)
        backend.initialize()

    def owner(self):
        with backend.connect_db() as db:
            return backend.api_activate(db, {"code": C, "name": "Xiaomi",
                                             "public_key": AKEY})

    def register_pair(self):
        owner = self.owner()
        with backend.connect_db() as db:
            a = backend.authenticate(db, "Bearer " + owner["token"])
            invite = backend.api_invite(db, a)["invite_code"]
            b = backend.api_join(db, {"invite_code": invite, "name": "vivo",
                                      "public_key": BKEY})
        return owner, b

    def test_exclusive_interface_rejects_unknown_legacy_peers(self):
        from types import SimpleNamespace
        legacy = SimpleNamespace(stdout=AKEY + "\t100.80.0.2/32\n")
        with mock.patch.object(backend, "cmd", return_value=legacy):
            with self.assertRaisesRegex(RuntimeError, "unmanaged or legacy"):
                backend.assert_exclusive_wireguard_interface([])
        # A matching peer created by this DB is allowed.
        with mock.patch.object(backend, "cmd", return_value=legacy):
            backend.assert_exclusive_wireguard_interface([
                {"public_key": AKEY, "ip": backend.IP_OWNER}
            ])

    def test_exclusive_interface_does_not_accept_same_ip_wrong_key(self):
        from types import SimpleNamespace
        legacy = SimpleNamespace(stdout=BKEY + "\t100.80.0.2/32\n")
        with mock.patch.object(backend, "cmd", return_value=legacy):
            with self.assertRaises(RuntimeError):
                backend.assert_exclusive_wireguard_interface([
                    {"public_key": AKEY, "ip": backend.IP_OWNER}
                ])

    def test_owner_uses_bootstrap_once(self):
        with backend.connect_db() as db:
            with self.assertRaises(backend.ApiError):
                backend.api_activate(db, {"code": "b"*48, "name": "Xiaomi",
                                          "public_key": AKEY})
        self.owner()
        with backend.connect_db() as db:
            with self.assertRaises(backend.ApiError):
                backend.api_activate(db, {"code": C, "name": "Other",
                                          "public_key": BKEY})

    def test_pair_requires_owner_approval(self):
        owner, guest = self.register_pair()
        with backend.connect_db() as db:
            a = backend.authenticate(db, "Bearer " + owner["token"])
            b = backend.authenticate(db, "Bearer " + guest["token"])
            self.assertEqual(backend.api_status(db, b)["state"], "pending")
            self.assertNotIn("shared_secret", backend.api_status(db, b))
            self.assertEqual(backend.api_status(db, a)["pending_id"],
                             guest["device_id"])
            self.patches[2].assert_not_called()  # peer_remove
            self.patches[1].assert_called_once()  # owner-only WG peer

            with self.assertRaises(backend.ApiError):
                backend.api_approve(db, b, {"pending_id": guest["device_id"]})
            with self.assertRaises(backend.ApiError):
                backend.api_approve(db, a, {"pending_id": "0"*32})
            backend.api_approve(db, a, {"pending_id": guest["device_id"]})
            # sqlite3.Row is a snapshot: simulate the following authenticated
            # API requests after the approval transaction has committed.
            a = backend.authenticate(db, "Bearer " + owner["token"])
            b = backend.authenticate(db, "Bearer " + guest["token"])
            sa, sb = backend.api_status(db, a), backend.api_status(db, b)
            self.assertEqual(sa["state"], "paired")
            self.assertEqual(sb["state"], "paired")
            self.assertEqual(sa["shared_secret"], sb["shared_secret"])
            self.assertEqual(sa["peer_ip"], backend.IP_GUEST)
            self.assertEqual(sb["peer_ip"], backend.IP_OWNER)
            self.assertEqual(len(sa["shared_secret"]), 32)
            self.assertEqual(self.patches[3].call_args, mock.call(True))

    def test_guest_revocation_denies_tunnel_and_invalidates_token(self):
        owner, guest = self.register_pair()
        with backend.connect_db() as db:
            a = backend.authenticate(db, "Bearer " + owner["token"])
            b = backend.authenticate(db, "Bearer " + guest["token"])
            backend.api_approve(db, a, {"pending_id": guest["device_id"]})
            backend.api_revoke(db, b)
            self.assertEqual(backend.api_status(db, a)["state"], "ready")
            self.assertNotIn("shared_secret", backend.api_status(db, a))
            with self.assertRaises(backend.ApiError):
                backend.authenticate(db, "Bearer " + guest["token"])
            self.assertEqual(self.patches[3].call_args, mock.call(False))

    def test_invalid_invite_cannot_register_guest(self):
        self.owner()
        with backend.connect_db() as db:
            with self.assertRaises(backend.ApiError):
                backend.api_join(db, {"invite_code": "b"*48, "name": "vivo",
                                      "public_key": BKEY})
        self.assertEqual(self.patches[1].call_count, 1)

    def test_expired_invite_can_be_declined_by_owner(self):
        owner, guest = self.register_pair()
        with backend.connect_db() as db:
            db.execute("UPDATE invites SET expires=0")
            a = backend.authenticate(db, "Bearer " + owner["token"])
            b = backend.authenticate(db, "Bearer " + guest["token"])
            self.assertEqual(backend.api_status(db, b)["state"], "expired")
            with self.assertRaises(backend.ApiError):
                backend.api_approve(db, a, {"pending_id": guest["device_id"]})
            backend.api_decline(db, a, {"pending_id": guest["device_id"]})
            with self.assertRaises(backend.ApiError):
                backend.authenticate(db, "Bearer " + guest["token"])
            self.assertEqual(backend.api_invite(db, a)["expires_in"], 300)

    def test_guest_cannot_decline_another_phone(self):
        owner, guest = self.register_pair()
        with backend.connect_db() as db:
            b = backend.authenticate(db, "Bearer " + guest["token"])
            with self.assertRaises(backend.ApiError):
                backend.api_decline(db, b, {"pending_id": guest["device_id"]})

    def test_token_scope(self):
        owner = self.owner()
        with backend.connect_db() as db:
            with self.assertRaises(backend.ApiError):
                backend.authenticate(db, "Bearer " + "d"*48)
            user = backend.authenticate(db, "Bearer " + owner["token"])
            self.assertEqual(user["role"], "owner")

    def test_reject_malformed_public_keys(self):
        for bad in ("", "invalid", "0"*44,
                    base64.b64encode(bytes(32)).decode("ascii")):
            with self.assertRaises(backend.ApiError):
                backend.safe_key(bad)


if __name__ == "__main__":
    unittest.main()
