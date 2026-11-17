#!/usr/bin/env python3
"""ScreenSync Beta 4 two-phone provisioning control plane.

Bind ONLY to 127.0.0.1 behind a correctly configured HTTPS reverse proxy.
The process needs explicit permission to run wg/iptables for a dedicated interface.
Never place the bootstrap code or client private keys in an APK.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sqlite3
import subprocess
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATABASE = Path(os.environ.get("SCREENSYNC_DB", "/var/lib/screensync/registration.db"))
INTERFACE = os.environ.get("SCREENSYNC_WG_INTERFACE", "wg-screensync")
WG_ENDPOINT = os.environ.get("SCREENSYNC_WG_ENDPOINT", "166.88.95.233:51820")
BOOTSTRAP_CODE = os.environ.get("SCREENSYNC_BOOTSTRAP_CODE", "")
PORT = int(os.environ.get("SCREENSYNC_LISTEN_PORT", "8765"))
LOCK = threading.RLock()
HITS: dict[str, deque] = defaultdict(deque)
IP_OWNER = "100.80.0.2"
IP_GUEST = "100.80.0.3"
CHAIN = "SCREENSYNC_PAIR"
CODE_PATTERN = re.compile(r"^[a-f0-9]{48}$")
DEVICE_NAME_PATTERN = re.compile(r"^[\w\u4e00-\u9fff -]{1,40}$")
IFACE_PATTERN = re.compile(r"^[a-zA-Z0-9_.-]{1,15}$")


class ApiError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code, self.status = code, status


def safe_key(public_key: str) -> str:
    if not isinstance(public_key, str) or len(public_key) != 44:
        raise ApiError("INVALID_PUBLIC_KEY")
    try:
        raw = base64.b64decode(public_key, validate=True)
    except (ValueError, base64.binascii.Error):
        raise ApiError("INVALID_PUBLIC_KEY") from None
    if len(raw) != 32 or raw == b"\x00" * 32:
        raise ApiError("INVALID_PUBLIC_KEY")
    return public_key


def safe_name(value: str) -> str:
    if not isinstance(value, str) or not DEVICE_NAME_PATTERN.fullmatch(value):
        raise ApiError("INVALID_DEVICE_NAME")
    return value


def valid_config() -> None:
    if not IFACE_PATTERN.fullmatch(INTERFACE):
        raise RuntimeError("invalid WireGuard interface name")
    if not BOOTSTRAP_CODE or not CODE_PATTERN.fullmatch(BOOTSTRAP_CODE):
        raise RuntimeError("bootstrap token must be 48 lowercase hex digits")
    host, sep, port = WG_ENDPOINT.rpartition(":")
    if not sep or not (1 <= int(port) <= 65535):
        raise RuntimeError("invalid WireGuard endpoint")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise RuntimeError("invalid WireGuard endpoint host")


def cmd(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), check=check, capture_output=True,
                          text=True, timeout=8)


def server_public_key() -> str:
    try:
        return safe_key(cmd("wg", "show", INTERFACE, "public-key").stdout.strip())
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("WireGuard interface unavailable") from exc


def peer_set(public_key: str, address: str) -> None:
    if address not in (IP_OWNER, IP_GUEST):
        raise RuntimeError("invalid peer IP")
    cmd("wg", "set", INTERFACE, "peer", safe_key(public_key),
        "allowed-ips", address + "/32")


def peer_remove(public_key: str) -> None:
    cmd("wg", "set", INTERFACE, "peer", safe_key(public_key), "remove")


def ensure_acl() -> None:
    """First rule overrides old wide wg-quick FORWARD rules; only peer pair may pass."""
    exists = cmd("iptables", "-n", "-L", CHAIN, check=False).returncode == 0
    if not exists:
        cmd("iptables", "-N", CHAIN)
    entry = ("-i", INTERFACE, "-o", INTERFACE, "-j", CHAIN)
    if cmd("iptables", "-C", "FORWARD", *entry, check=False).returncode != 0:
        cmd("iptables", "-I", "FORWARD", "1", *entry)


def apply_acl(paired: bool) -> None:
    ensure_acl()
    cmd("iptables", "-F", CHAIN)
    if paired:
        for src, dst in ((IP_OWNER, IP_GUEST), (IP_GUEST, IP_OWNER)):
            cmd("iptables", "-A", CHAIN, "-s", src + "/32",
                "-d", dst + "/32", "-j", "ACCEPT")
    cmd("iptables", "-A", CHAIN, "-j", "DROP")


def connect_db() -> sqlite3.Connection:
    db = sqlite3.connect(str(DATABASE), timeout=10, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout=10000")
    db.execute("PRAGMA foreign_keys=ON")
    return db


def assert_exclusive_wireguard_interface(devices: list[sqlite3.Row]) -> None:
    """Refuse to modify a shared/legacy WG interface before any ACL changes.

    A globally inserted iptables FORWARD rule affects every device on the
    interface, not just Beta4 peers. Existing Beta3 peers must never have
    their AllowedIPs reassigned or lose routing because a new DB is empty.
    """
    output = cmd("wg", "show", INTERFACE, "allowed-ips").stdout
    expected = {
        (row["public_key"], row["ip"] + "/32")
        for row in devices
    }
    active = set()
    for line in output.splitlines():
        fields = line.strip().split()
        if not fields:
            continue
        if len(fields) < 2:
            raise RuntimeError("cannot parse existing WireGuard peers")
        public_key = safe_key(fields[0])
        for allowed in fields[1].split(","):
            active.add((public_key, allowed.strip()))
    if not active.issubset(expected):
        raise RuntimeError(
            "WireGuard interface includes unmanaged or legacy peers: "
            "refusing to change forwarding rules or device routes"
        )


def initialize() -> None:
    valid_config()
    DATABASE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(DATABASE.parent, 0o700)
    with connect_db() as db:
        db.executescript("""
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                role TEXT UNIQUE NOT NULL CHECK(role IN ('owner', 'guest')),
                name TEXT NOT NULL,
                public_key TEXT UNIQUE NOT NULL,
                token_hash TEXT UNIQUE NOT NULL,
                ip TEXT UNIQUE NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('ready', 'pending', 'paired')),
                secret TEXT
            );
            CREATE TABLE IF NOT EXISTS invites (
                code_hash TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL,
                guest_id TEXT,
                expires INTEGER NOT NULL,
                FOREIGN KEY(owner_id) REFERENCES devices(id),
                FOREIGN KEY(guest_id) REFERENCES devices(id)
            );
        """)
        os.chmod(DATABASE, 0o600)
        devices = list(db.execute("SELECT public_key, ip, state FROM devices"))
        paired = sum(d["state"] == "paired" for d in devices) == 2
    server_public_key()
    # Guard BEFORE touching iptables or peers. Empty Beta4 DB cannot own
    # an interface containing earlier Beta3 peer addresses.
    assert_exclusive_wireguard_interface(devices)
    # Only a dedicated/fully managed interface can have its ACL changed.
    apply_acl(paired)
    for device in devices:
        if device["state"] != "pending":
            peer_set(device["public_key"], device["ip"])


def hash_token(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def random_token() -> str:
    return secrets.token_hex(24)


def authenticate(db: sqlite3.Connection, header: str | None) -> sqlite3.Row:
    if not header or not header.startswith("Bearer "):
        raise ApiError("AUTH_REQUIRED", 401)
    candidate = header[7:]
    if not CODE_PATTERN.fullmatch(candidate):
        raise ApiError("AUTH_REQUIRED", 401)
    row = db.execute("SELECT * FROM devices WHERE token_hash=?",
                     (hash_token(candidate),)).fetchone()
    if row is None:
        raise ApiError("AUTH_REQUIRED", 401)
    return row


def api_activate(db: sqlite3.Connection, body: dict) -> dict:
    code = body.get("code", "")
    key = safe_key(body.get("public_key", ""))
    name = safe_name(body.get("name", ""))
    if not isinstance(code, str) or not hmac.compare_digest(
            hash_token(code) if CODE_PATTERN.fullmatch(code) else "",
            hash_token(BOOTSTRAP_CODE)):
        raise ApiError("ACTIVATION_DENIED", 403)
    if db.execute("SELECT 1 FROM devices WHERE role='owner'").fetchone():
        raise ApiError("OWNER_ALREADY_ACTIVATED", 409)
    token = random_token()
    device_id = secrets.token_hex(16)
    db.execute("INSERT INTO devices VALUES (?, 'owner', ?, ?, ?, ?, 'ready', NULL)",
               (device_id, name, key, hash_token(token), IP_OWNER))
    peer_set(key, IP_OWNER)
    return {"device_id": device_id, "token": token, "state": "ready"}


def api_invite(db: sqlite3.Connection, device: sqlite3.Row) -> dict:
    if device["role"] != "owner" or device["state"] == "paired":
        raise ApiError("INVITE_NOT_ALLOWED", 403)
    if db.execute("SELECT 1 FROM devices WHERE role='guest'").fetchone():
        raise ApiError("PAIRING_ALREADY_PENDING", 409)
    db.execute("DELETE FROM invites WHERE owner_id=?", (device["id"],))
    code = random_token()
    expires = int(time.time()) + 300
    db.execute("INSERT INTO invites(code_hash,owner_id,expires) VALUES (?,?,?)",
               (hash_token(code), device["id"], expires))
    return {"invite_code": code, "expires_in": 300}


def api_join(db: sqlite3.Connection, body: dict) -> dict:
    code = body.get("invite_code", "")
    key = safe_key(body.get("public_key", ""))
    name = safe_name(body.get("name", ""))
    if not isinstance(code, str) or not CODE_PATTERN.fullmatch(code):
        raise ApiError("INVITE_INVALID", 403)
    invitation = db.execute(
        "SELECT * FROM invites WHERE code_hash=?", (hash_token(code),)
    ).fetchone()
    if invitation is None or invitation["expires"] < int(time.time()) \
            or invitation["guest_id"] is not None:
        raise ApiError("INVITE_INVALID_OR_EXPIRED", 403)
    if db.execute("SELECT 1 FROM devices WHERE role='guest'").fetchone():
        raise ApiError("PAIRING_ALREADY_PENDING", 409)
    token, device_id = random_token(), secrets.token_hex(16)
    db.execute("INSERT INTO devices VALUES (?, 'guest', ?, ?, ?, ?, 'pending', NULL)",
               (device_id, name, key, hash_token(token), IP_GUEST))
    db.execute("UPDATE invites SET guest_id=? WHERE code_hash=?",
               (device_id, invitation["code_hash"]))
    return {"device_id": device_id, "token": token, "state": "pending"}


def api_approve(db: sqlite3.Connection, device: sqlite3.Row, body: dict) -> dict:
    if device["role"] != "owner" or device["state"] == "paired":
        raise ApiError("APPROVAL_NOT_ALLOWED", 403)
    pending_id = body.get("pending_id", "")
    invitation = db.execute(
        "SELECT * FROM invites WHERE owner_id=? AND guest_id=? AND expires>=?",
        (device["id"], pending_id, int(time.time()))
    ).fetchone()
    guest = db.execute(
        "SELECT * FROM devices WHERE id=? AND role='guest' AND state='pending'",
        (pending_id,)
    ).fetchone()
    if invitation is None or guest is None:
        raise ApiError("PENDING_REQUEST_NOT_FOUND", 404)
    secret = secrets.token_hex(16)  # Same format as WakeAuth: 32 hex chars.
    try:
        peer_set(guest["public_key"], IP_GUEST)
        apply_acl(True)
        db.execute("UPDATE devices SET state='paired', secret=? "
                   "WHERE role IN ('owner', 'guest')", (secret,))
        db.execute("DELETE FROM invites")
    except Exception:
        # Deny cross-device forwarding even if a later SQLite write fails.
        try:
            apply_acl(False)
        finally:
            peer_remove(guest["public_key"])
        raise
    return {"state": "paired"}


def api_decline(db: sqlite3.Connection, device: sqlite3.Row, body: dict) -> dict:
    """A's explicit rejection revokes pending B before any VPN route is granted."""
    if device["role"] != "owner" or device["state"] != "ready":
        raise ApiError("DECLINE_NOT_ALLOWED", 403)
    pending_id = body.get("pending_id", "")
    pending = db.execute(
        "SELECT 1 FROM invites WHERE owner_id=? AND guest_id=?",
        (device["id"], pending_id)
    ).fetchone()
    if pending is None:
        raise ApiError("PENDING_REQUEST_NOT_FOUND", 404)
    db.execute("DELETE FROM invites WHERE guest_id=?", (pending_id,))
    db.execute("DELETE FROM devices WHERE id=? AND role='guest' AND state='pending'",
               (pending_id,))
    return {"state": "ready"}


def api_status(db: sqlite3.Connection, device: sqlite3.Row) -> dict:
    response = {"state": device["state"], "role": device["role"],
                "device_ip": device["ip"], "server_public_key": server_public_key(),
                "endpoint": WG_ENDPOINT}
    if device["role"] == "owner" and device["state"] == "ready":
        pending = db.execute("""
            SELECT d.id,d.name,i.expires FROM devices d
            JOIN invites i ON i.guest_id=d.id
            WHERE i.owner_id=? AND d.state='pending'
        """, (device["id"],)).fetchone()
        if pending:
            response["pending_id"] = pending["id"]
            response["pending_name"] = pending["name"]
            response["pending_expired"] = pending["expires"] < int(time.time())
    if device["role"] == "guest" and device["state"] == "pending":
        invitation = db.execute(
            "SELECT expires FROM invites WHERE guest_id=?", (device["id"],)
        ).fetchone()
        if invitation is None or invitation["expires"] < int(time.time()):
            response["state"] = "expired"
    if device["state"] == "paired":
        other_role = "guest" if device["role"] == "owner" else "owner"
        peer = db.execute("SELECT name,ip FROM devices WHERE role=? AND state='paired'",
                          (other_role,)).fetchone()
        if peer:
            response.update({"peer_ip": peer["ip"], "peer_name": peer["name"],
                             "shared_secret": device["secret"]})
    return response


def api_revoke(db: sqlite3.Connection, device: sqlite3.Row) -> dict:
    # Owner revoke resets both; guest revoke removes guest and unpairs owner.
    apply_acl(False)  # deny first, before removing keys
    if device["role"] == "owner":
        for row in db.execute("SELECT public_key FROM devices"):
            peer_remove(row["public_key"])
        db.execute("DELETE FROM invites")
        db.execute("DELETE FROM devices")
    else:
        peer_remove(device["public_key"])
        db.execute("DELETE FROM invites")
        db.execute("DELETE FROM devices WHERE role='guest'")
        db.execute("UPDATE devices SET state='ready', secret=NULL WHERE role='owner'")
    return {"state": "revoked"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        # No HTTP URI, Bearer token, activation/invite code, or body in logs.
        return

    def respond(self, status: int, data: dict) -> None:
        payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def rate_limit(self) -> None:
        # Reverse proxy must overwrite X-Forwarded-For and limit request sizes.
        remote = self.client_address[0]
        if remote in ("127.0.0.1", "::1"):
            raw = self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
            try:
                remote = str(ipaddress.ip_address(raw)) if raw else remote
            except ValueError:
                remote = "unverified"
        key = remote + ":" + ("activate" if self.path == "/v1/activate" else "default")
        now = time.monotonic()
        history = HITS[key]
        window, maximum = (900, 5) if self.path == "/v1/activate" else (60, 60)
        while history and history[0] < now - window:
            history.popleft()
        if len(history) >= maximum:
            raise ApiError("RATE_LIMITED", 429)
        history.append(now)
        if len(HITS) > 4096:
            for stale_key in list(HITS)[:1024]:
                if not HITS[stale_key] or HITS[stale_key][-1] < now - 900:
                    del HITS[stale_key]

    def do_GET(self) -> None:
        if self.path == "/health":
            self.respond(200, {"ok": True, "service": "screensync-enrollment"})
        else:
            self.respond(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        with LOCK:
            try:
                self.rate_limit()
                size = int(self.headers.get("Content-Length", "0"))
                if size <= 0 or size > 4096:
                    raise ApiError("INVALID_BODY", 413)
                try:
                    body = json.loads(self.rfile.read(size))
                except (ValueError, UnicodeDecodeError):
                    raise ApiError("INVALID_JSON") from None
                if not isinstance(body, dict):
                    raise ApiError("INVALID_JSON")
                if self.path not in (
                    "/v1/activate", "/v1/join", "/v1/invite",
                    "/v1/status", "/v1/approve", "/v1/decline", "/v1/revoke"
                ):
                    raise ApiError("NOT_FOUND", 404)
                with connect_db() as db:
                    db.execute("BEGIN IMMEDIATE")
                    if self.path == "/v1/activate":
                        result = api_activate(db, body)
                    elif self.path == "/v1/join":
                        result = api_join(db, body)
                    else:
                        device = authenticate(db, self.headers.get("Authorization"))
                        if self.path == "/v1/invite":
                            result = api_invite(db, device)
                        elif self.path == "/v1/status":
                            result = api_status(db, device)
                        elif self.path == "/v1/approve":
                            result = api_approve(db, device, body)
                        elif self.path == "/v1/decline":
                            result = api_decline(db, device, body)
                        else:
                            result = api_revoke(db, device)
                    db.commit()
                self.respond(200, result)
            except ApiError as exc:
                self.respond(exc.status, {"error": exc.code})
            except (sqlite3.Error, subprocess.SubprocessError,
                    RuntimeError, OSError, ValueError) as exc:
                # Fail closed; do not return sensitive database/process details.
                print("screensync request failed:", type(exc).__name__, flush=True)
                self.respond(503, {"error": "SERVICE_UNAVAILABLE"})


def main() -> None:
    initialize()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print("ScreenSync control plane listening on loopback only", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
