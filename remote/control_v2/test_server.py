"""Offline authorization and tenant-isolation tests for ScreenSync v2.

Tests mock nft/wg/ip, so they never touch the host firewall.
"""
import base64
import importlib.util
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock
from types import SimpleNamespace

path = Path(__file__).with_name("server.py")
spec = importlib.util.spec_from_file_location("screensync_control_v2", path)
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
K = [base64.b64encode(bytes([x])*32).decode() for x in (10, 20, 30, 40, 50)]
ADMIN = "f"*48


class MultiTenantTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        olddb, oldmax, oldadmin, oldendpoint = (
            server.DATABASE, server.MAX_PAIRS, server.ADMIN_KEY, server.ENDPOINT)
        self.addCleanup(setattr, server, "DATABASE", olddb)
        self.addCleanup(setattr, server, "MAX_PAIRS", oldmax)
        self.addCleanup(setattr, server, "ADMIN_KEY", oldadmin)
        self.addCleanup(setattr, server, "ENDPOINT", oldendpoint)
        server.DATABASE = Path(self.temp.name) / "v2.db"
        server.MAX_PAIRS = 3
        server.ADMIN_KEY = ADMIN
        server.ENDPOINT = "166.88.95.233:51821"
        self.wg_add = mock.patch.object(server, "wg_add").start()
        self.wg_remove = mock.patch.object(server, "wg_remove").start()
        self.nft_update = mock.patch.object(server, "update_acl").start()
        self.run = mock.patch.object(server, "run", return_value=SimpleNamespace(
            stdout=K[0] + "\n")).start()
        for name in ("wg_add","wg_remove","update_acl","run"):
            self.addCleanup(mock.patch.stopall)
        server.schema()

    def make_owner(self, index, name):
        url = server.issue_owner_code(600)
        invite = url.split("code=")[1]
        with server.db_connect() as db:
            owner = server.api_activate(
                db, {"code": invite, "name": name, "public_key": K[index]})
        return owner

    def guest_join(self, owner, idx, name):
        with server.db_connect() as db:
            principal = server.authorize(db, "Bearer " + owner["token"])
            invitation = server.api_invite(db, principal)["invite_code"]
            guest = server.api_join(
                db, {"invite_code": invitation, "name": name, "public_key": K[idx]})
        return guest

    def approve(self, owner, guest):
        with server.db_connect() as db:
            principal = server.authorize(db, "Bearer " + owner["token"])
            return server.api_approve(db, principal, {"pending_id": guest["device_id"]})

    def test_two_distinct_tenants_have_unique_ips_and_separate_acl(self):
        a = self.make_owner(0, "Xiaomi")
        b = self.guest_join(a, 1, "vivo")
        c = self.make_owner(2, "Parent")
        d = self.guest_join(c, 3, "Tablet")
        with server.db_connect() as db:
            guests = [server.authorize(db, "Bearer " + item["token"]) for item in (b,d)]
            self.assertEqual({g["state"] for g in guests}, {"pending"})
            self.assertEqual(server.allowed_pairs(db), [])
            ips = [r[0] for r in db.execute("SELECT ip FROM devices")]
            self.assertEqual(len(ips), len(set(ips)))
            self.assertTrue(all(x.startswith("100.81.") for x in ips))
        self.approve(a,b)
        with server.db_connect() as db:
            grants = server.allowed_pairs(db)
            self.assertEqual(len(grants), 1)
            self.assertNotIn(K[3], [c.args[0] for c in self.wg_add.call_args_list])
            acl = server.render_acl(grants)
            self.assertEqual(acl.count(" ip saddr "), 2)
            self.assertIn(" drop\n", acl)
        self.approve(c,d)
        with server.db_connect() as db:
            grants = server.allowed_pairs(db)
            self.assertEqual(len(grants), 2)
            acl = server.render_acl(grants)
            self.assertEqual(acl.count(" ip saddr "), 4)
            for x,y in grants:
                self.assertIn("ip saddr " + x + " ip daddr " + y, acl)
                self.assertIn("ip saddr " + y + " ip daddr " + x, acl)
            self.assertNotIn("100.80.", acl)
            # Cannot approve a guest owned by another customer.
            with self.assertRaises(server.ApiError):
                server.api_approve(db, server.authorize(db, "Bearer "+a["token"]),
                                   {"pending_id": d["device_id"]})

    def test_guest_revocation_does_not_break_other_group(self):
        a = self.make_owner(0, "A")
        b = self.guest_join(a, 1, "B")
        c = self.make_owner(2, "C")
        d = self.guest_join(c, 3, "D")
        self.approve(a,b); self.approve(c,d)
        with server.db_connect() as db:
            guest = server.authorize(db, "Bearer " + b["token"])
            server.api_revoke(db, guest)
        with server.db_connect() as db:
            self.assertEqual(len(server.allowed_pairs(db)), 1)
            owner = server.authorize(db, "Bearer "+a["token"])
            self.assertEqual(owner["state"], "ready")
            other = server.authorize(db, "Bearer "+c["token"])
            self.assertEqual(other["state"], "paired")
            with self.assertRaises(server.ApiError):
                server.authorize(db, "Bearer "+b["token"])
        self.wg_remove.assert_called_with(K[1])

    def test_codes_are_one_time_and_capacity_limited(self):
        a = self.make_owner(0, "A")
        second = self.make_owner(2, "C")
        third = self.make_owner(4, "E")
        with server.db_connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM groups").fetchone()[0],3)
            with self.assertRaises(server.ApiError):
                server.api_activate(db, {
                    "code":server.issue_owner_code(600).split("code=")[1],
                    "name":"Denied","public_key":K[1]})

    def test_expired_or_unknown_invites_denied(self):
        a = self.make_owner(0,"A")
        with server.db_connect() as db:
            owner = server.authorize(db,"Bearer "+a["token"])
            code = server.api_invite(db,owner)["invite_code"]
            db.execute("UPDATE invites SET expires=0")
            with self.assertRaises(server.ApiError):
                server.api_join(db, {"invite_code":code,"name":"B",
                                    "public_key":K[1]})

    def test_v2_interface_and_port_reject_beta3(self):
        old_iface, old_endpoint = server.INTERFACE, server.ENDPOINT
        self.addCleanup(setattr,server,"INTERFACE",old_iface)
        server.INTERFACE="wg-screensync"
        with self.assertRaises(RuntimeError):
            server.validate_settings()
        server.INTERFACE=old_iface
        server.ENDPOINT="166.88.95.233:51820"
        with self.assertRaises(RuntimeError):
            server.validate_settings()

    def test_dedicated_interface_rejects_unmanaged_and_wrong_ip_peers(self):
        real_check=server.check_dedicated
        with server.db_connect() as db:
            self.run.side_effect=[
                SimpleNamespace(stdout="51821\n"),
                SimpleNamespace(stdout=K[0]+"\t100.80.0.2/32\n"),
                SimpleNamespace(stdout='[{"addr_info":[{"local":"100.81.0.1","prefixlen":16}]}]')
            ]
            with self.assertRaisesRegex(RuntimeError, "unmanaged/legacy"):
                real_check(db)
        self.run.side_effect=None

    def test_thread_limit_rejects_excess_requests_without_worker_spawn(self):
        httpd = server.BoundedThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        try:
            for _ in range(httpd.MAX_REQUEST_THREADS):
                self.assertTrue(httpd._request_slots.acquire(blocking=False))
            self.assertFalse(httpd._request_slots.acquire(blocking=False))
            fake_request = mock.Mock()
            with mock.patch.object(httpd, "shutdown_request") as rejected:
                httpd.process_request(fake_request, ("127.0.0.1", 9999))
                rejected.assert_called_once_with(fake_request)
            httpd._request_slots.release()
            self.assertTrue(httpd._request_slots.acquire(blocking=False))
        finally:
            httpd.server_close()

    def test_render_acl_rejects_foreign_ips(self):
        with self.assertRaises(RuntimeError):
            server.render_acl([("100.80.0.2","100.81.0.5")])


if __name__ == "__main__":
    unittest.main()
