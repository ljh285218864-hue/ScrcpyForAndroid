#!/usr/bin/env python3
"""Experimental ScreenSync Beta4 multi-pair control plane.

Dedicated WireGuard interface only. Never attaches its firewall to a Beta3
interface. No ADB or screen access is granted by this service: Android must
separately obtain explicit system authorization.
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
import sys
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATABASE = Path(os.getenv("SCREENSYNC_DB", "/var/lib/screensync-v2/registration.db"))
INTERFACE = os.getenv("SCREENSYNC_WG_INTERFACE", "wg-ss-beta4")
ENDPOINT = os.getenv("SCREENSYNC_WG_ENDPOINT", "")
POOL = ipaddress.ip_network("100.81.0.0/16")
SERVER_IP = "100.81.0.1"
ADMIN_KEY = os.getenv("SCREENSYNC_ADMIN_SECRET", "")
MAX_PAIRS = int(os.getenv("SCREENSYNC_MAX_PAIRS", "2"))
PORT = int(os.getenv("SCREENSYNC_LISTEN_PORT", "8765"))
TABLE = "screensync_beta4"
LOCK = threading.RLock()
HITS = defaultdict(deque)
HEX48 = re.compile(r"^[0-9a-f]{48}$")
HEX32 = re.compile(r"^[0-9a-f]{32}$")
NAME = re.compile(r"^[\w\u4e00-\u9fff .-]{1,60}$")
IFACE = re.compile(r"^[a-zA-Z0-9_.-]{1,15}$")


class ApiError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


def key(value: str) -> str:
    if not isinstance(value, str) or len(value) != 44:
        raise ApiError("INVALID_PUBLIC_KEY")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, base64.binascii.Error):
        raise ApiError("INVALID_PUBLIC_KEY") from None
    if len(raw) != 32 or raw == bytes(32):
        raise ApiError("INVALID_PUBLIC_KEY")
    return value


def token_hash(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def new_token() -> str:
    return secrets.token_hex(24)


def device_name(value: str) -> str:
    if not isinstance(value, str) or not NAME.fullmatch(value):
        raise ApiError("INVALID_DEVICE_NAME")
    return value


def validate_settings() -> None:
    if not IFACE.fullmatch(INTERFACE) or INTERFACE == "wg-screensync":
        raise RuntimeError("a dedicated, non-Beta3 interface is required")
    host, sep, port = ENDPOINT.rpartition(":")
    if not sep or not host or not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise RuntimeError("endpoint must have a valid explicit hostname and port")
    if not port.isdecimal() or not (1 <= int(port) <= 65535) or port == "51820":
        raise RuntimeError("Beta3 UDP 51820 cannot be reused")
    if not HEX48.fullmatch(ADMIN_KEY):
        raise RuntimeError("root-only administrator secret must be a random 48-hex code")
    if MAX_PAIRS < 1 or MAX_PAIRS > 1000:
        raise RuntimeError("invalid pair capacity")


def run(*argv: str, input_text: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(argv, input=input_text, capture_output=True,
                          text=True, check=True, timeout=8)


def db_connect() -> sqlite3.Connection:
    db = sqlite3.connect(str(DATABASE), isolation_level=None, timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA busy_timeout=15000")
    return db


def schema() -> None:
    DATABASE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(DATABASE.parent, 0o700)
    with db_connect() as db:
        db.executescript("""
          CREATE TABLE IF NOT EXISTS groups (
            id TEXT PRIMARY KEY,
            created INTEGER NOT NULL
          );
          CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK(role IN ('owner','guest')),
            name TEXT NOT NULL,
            public_key TEXT UNIQUE NOT NULL,
            token_hash TEXT UNIQUE NOT NULL,
            ip TEXT UNIQUE NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('ready','pending','paired')),
            shared_secret TEXT,
            UNIQUE(group_id,role)
          );
          CREATE INDEX IF NOT EXISTS device_group ON devices(group_id);
          CREATE TABLE IF NOT EXISTS owner_codes (
            hash TEXT PRIMARY KEY, expires INTEGER NOT NULL, used INTEGER
          );
          CREATE TABLE IF NOT EXISTS invites (
            hash TEXT PRIMARY KEY,
            group_id TEXT UNIQUE NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            guest_id TEXT,
            expires INTEGER NOT NULL
          );
        """)
        os.chmod(DATABASE, 0o600)


def allocate_ip(db: sqlite3.Connection) -> str:
    """Deterministic free address, never Beta3's 100.80.0.0/24."""
    allocated = {row[0] for row in db.execute("SELECT ip FROM devices")}
    for n in range(2, min(65534, MAX_PAIRS * 4 + 256)):
        ip = str(POOL.network_address + n)
        if ip not in allocated:
            return ip
    raise ApiError("ADDRESS_POOL_EXHAUSTED", 409)


def authorize(db: sqlite3.Connection, header: str | None) -> sqlite3.Row:
    if not header or not header.startswith("Bearer "):
        raise ApiError("AUTH_REQUIRED", 401)
    bearer = header[7:]
    if not HEX48.fullmatch(bearer):
        raise ApiError("AUTH_REQUIRED", 401)
    result = db.execute("SELECT * FROM devices WHERE token_hash=?",
                        (token_hash(bearer),)).fetchone()
    if result is None:
        raise ApiError("AUTH_REQUIRED", 401)
    return result


def read_peer_routes() -> dict[str, list[str]]:
    result = {}
    for line in run("wg", "show", INTERFACE, "allowed-ips").stdout.splitlines():
        parts = line.split()
        if not parts:
            continue
        if len(parts) != 2:
            raise RuntimeError("unrecognized WireGuard peer routes")
        result[key(parts[0])] = parts[1].split(",")
    return result


def check_dedicated(db: sqlite3.Connection) -> None:
    """Reject peers absent from THIS database, including disconnected Beta3 peers."""
    validate_settings()
    expected_port = ENDPOINT.rpartition(":")[2]
    port = run("wg", "show", INTERFACE, "listen-port").stdout.strip()
    if port != expected_port:
        raise RuntimeError("dedicated WireGuard listen port mismatch")
    routes = read_peer_routes()
    known = {
        row["public_key"]: row["ip"] + "/32"
        for row in db.execute("SELECT public_key, ip FROM devices")
    }
    for public_key, allowed in routes.items():
        if public_key not in known or allowed != [known[public_key]]:
            raise RuntimeError("unmanaged/legacy WireGuard peer; refusing modifications")
    # Must not run on an interface sharing Beta3's address space.
    addrs = json.loads(run("ip", "-j", "address", "show", "dev", INTERFACE).stdout)
    if len(addrs) != 1 or not any(
        a.get("local") == SERVER_IP and a.get("prefixlen") == POOL.prefixlen
        for a in addrs[0].get("addr_info", [])
    ):
        raise RuntimeError("dedicated WireGuard interface IPv4 address mismatch")


def wg_add(public_key: str, address: str) -> None:
    if ipaddress.ip_address(address) not in POOL or address == SERVER_IP:
        raise RuntimeError("invalid private peer address")
    run("wg", "set", INTERFACE, "peer", key(public_key),
        "allowed-ips", address + "/32")


def wg_remove(public_key: str) -> None:
    run("wg", "set", INTERFACE, "peer", key(public_key), "remove")


def allowed_pairs(db: sqlite3.Connection, exclude_group: str | None = None):
    rows = db.execute("""
      SELECT g.id, o.ip AS owner_ip, p.ip AS guest_ip
      FROM groups g
      JOIN devices o ON o.group_id=g.id AND o.role='owner' AND o.state='paired'
      JOIN devices p ON p.group_id=g.id AND p.role='guest' AND p.state='paired'
    """).fetchall()
    return [
        (r["owner_ip"], r["guest_ip"])
        for r in rows if r["id"] != exclude_group
    ]


def render_acl(pairs: list[tuple[str, str]]) -> str:
    """Atomic nft transaction: default-deny ONLY traffic within Beta4 WG."""
    # A fixed non-user-controlled table/interface prevents nft injection.
    # Rebuild our *own* table within one atomic nft transaction.
    # 'flush table' only deletes rules; it keeps the chains, so a later
    # 'add chain' would fail on the second pairing or unpair operation.
    # The table is created once by init_runtime() before this is called.
    lines = [
        "delete table inet " + TABLE,
        "add table inet " + TABLE,
        "add chain inet " + TABLE +
        " forward { type filter hook forward priority -150; policy accept; }",
        "add chain inet " + TABLE +
        " input { type filter hook input priority -150; policy accept; }",
    ]
    for a, b in pairs:
        for src, dst in ((a, b), (b, a)):
            if ipaddress.ip_address(src) not in POOL or ipaddress.ip_address(dst) not in POOL:
                raise RuntimeError("invalid ACL address")
            lines.append(
                'add rule inet ' + TABLE +
                ' forward iifname "' + INTERFACE + '" oifname "' + INTERFACE +
                '" ip saddr ' + src + ' ip daddr ' + dst + ' accept'
            )
    # Deny ALL other forwarding from/to this dedicated interface, not
    # just cross-tenant traffic. Otherwise an unpaired client could use the
    # VPS as an exit node or reach private networks behind it.
    lines.append('add rule inet ' + TABLE +
                 ' forward iifname "' + INTERFACE + '" drop')
    lines.append('add rule inet ' + TABLE +
                 ' forward oifname "' + INTERFACE + '" drop')
    # Device traffic to the VPS itself must not reach x-ui/SSH/local services.
    # WireGuard's UDP handshake enters on the public interface, not on wg.
    lines.append('add rule inet ' + TABLE +
                 ' input iifname "' + INTERFACE + '" drop')
    return "\n".join(lines) + "\n"


def update_acl(pairs: list[tuple[str, str]]) -> None:
    """No shared iptables FORWARD chain changes: dedicated nft hook only."""
    run("nft", "-f", "-", input_text=render_acl(pairs))


def init_runtime() -> None:
    validate_settings()
    schema()
    with db_connect() as db:
        check_dedicated(db)
        # Table creation is idempotent; follow with one atomic deny-by-default update.
        exists = subprocess.run(["nft", "list", "table", "inet", TABLE],
                                capture_output=True, timeout=8).returncode == 0
        if not exists:
            run("nft", "add", "table", "inet", TABLE)
        update_acl(allowed_pairs(db))
        for row in db.execute("SELECT public_key,ip,state FROM devices"):
            if row["state"] != "pending":
                wg_add(row["public_key"], row["ip"])


def issue_owner_code(ttl_seconds: int) -> str:
    if not 60 <= ttl_seconds <= 86400:
        raise ValueError("TTL must be between 60 and 86400 seconds")
    schema()
    code = new_token()
    with db_connect() as db:
        db.execute("INSERT INTO owner_codes VALUES (?,?,NULL)",
                   (token_hash(code), int(time.time()) + ttl_seconds))
    return "screensync://activate?code=" + code


def api_activate(db: sqlite3.Connection, body: dict) -> dict:
    code = body.get("code", "")
    public_key = key(body.get("public_key", ""))
    name = device_name(body.get("name", ""))
    code_row = db.execute("SELECT expires,used FROM owner_codes WHERE hash=?",
                          (token_hash(code),)).fetchone() if (
                              isinstance(code, str) and HEX48.fullmatch(code)) else None
    if code_row is None or code_row["used"] is not None or code_row["expires"] < time.time():
        raise ApiError("ACTIVATION_DENIED", 403)
    if db.execute("SELECT COUNT(*) FROM groups").fetchone()[0] >= MAX_PAIRS:
        raise ApiError("CAPACITY_REACHED", 409)
    if db.execute("SELECT 1 FROM devices WHERE public_key=?", (public_key,)).fetchone():
        raise ApiError("DEVICE_ALREADY_REGISTERED", 409)
    group_id, device_id, token = secrets.token_hex(16), secrets.token_hex(16), new_token()
    ip = allocate_ip(db)
    db.execute("INSERT INTO groups VALUES (?,?)", (group_id, int(time.time())))
    db.execute("INSERT INTO devices VALUES (?,?,?,?,?,?,?,'ready',NULL)",
               (device_id, group_id, "owner", name, public_key, token_hash(token), ip))
    db.execute("UPDATE owner_codes SET used=? WHERE hash=?",
               (int(time.time()), token_hash(code)))
    wg_add(public_key, ip)  # ACL still denies every unpaired device.
    return {"device_id": device_id, "token": token, "state": "ready"}


def api_invite(db: sqlite3.Connection, owner: sqlite3.Row) -> dict:
    if owner["role"] != "owner" or owner["state"] != "ready":
        raise ApiError("INVITE_NOT_ALLOWED", 403)
    guest = db.execute("SELECT id,state FROM devices WHERE group_id=? AND role='guest'",
                       (owner["group_id"],)).fetchone()
    if guest:
        invitation = db.execute("SELECT expires FROM invites WHERE group_id=?",
                                (owner["group_id"],)).fetchone()
        if not invitation or invitation["expires"] >= time.time():
            raise ApiError("PAIRING_ALREADY_PENDING", 409)
        db.execute("DELETE FROM invites WHERE group_id=?", (owner["group_id"],))
        db.execute("DELETE FROM devices WHERE id=? AND state='pending'", (guest["id"],))
    code = new_token()
    db.execute("DELETE FROM invites WHERE group_id=?", (owner["group_id"],))
    db.execute("INSERT INTO invites VALUES (?,?,NULL,?)",
               (token_hash(code), owner["group_id"], int(time.time()) + 300))
    return {"invite_code": code, "expires_in": 300}


def api_join(db: sqlite3.Connection, body: dict) -> dict:
    code = body.get("invite_code", "")
    public_key = key(body.get("public_key", ""))
    name = device_name(body.get("name", ""))
    if not isinstance(code, str) or not HEX48.fullmatch(code):
        raise ApiError("INVITE_INVALID", 403)
    invite = db.execute("SELECT * FROM invites WHERE hash=?", (token_hash(code),)).fetchone()
    if not invite or invite["guest_id"] or invite["expires"] < time.time():
        raise ApiError("INVITE_INVALID_OR_EXPIRED", 403)
    if db.execute("SELECT 1 FROM devices WHERE group_id=? AND role='guest'",
                  (invite["group_id"],)).fetchone():
        raise ApiError("PAIRING_ALREADY_PENDING", 409)
    if db.execute("SELECT 1 FROM devices WHERE public_key=?", (public_key,)).fetchone():
        raise ApiError("DEVICE_ALREADY_REGISTERED", 409)
    device_id, token = secrets.token_hex(16), new_token()
    ip = allocate_ip(db)
    db.execute("INSERT INTO devices VALUES (?,?,?,?,?,?,?,'pending',NULL)",
               (device_id, invite["group_id"], "guest", name,
                public_key, token_hash(token), ip))
    db.execute("UPDATE invites SET guest_id=? WHERE hash=?",
               (device_id, invite["hash"]))
    return {"device_id": device_id, "token": token, "state": "pending"}


def api_approve(db: sqlite3.Connection, owner: sqlite3.Row, body: dict) -> dict:
    if owner["role"] != "owner" or owner["state"] != "ready":
        raise ApiError("APPROVAL_NOT_ALLOWED", 403)
    guest_id = body.get("pending_id", "")
    guest = db.execute(
        "SELECT * FROM devices WHERE id=? AND group_id=? AND state='pending'",
        (guest_id, owner["group_id"])
    ).fetchone()
    invite = db.execute("SELECT * FROM invites WHERE group_id=? AND guest_id=? AND expires>=?",
                        (owner["group_id"], guest_id, int(time.time()))).fetchone()
    if guest is None or invite is None:
        raise ApiError("PENDING_REQUEST_NOT_FOUND", 404)
    # Owner has explicitly approved; other groups remain isolated.
    secret = secrets.token_hex(16)
    wg_add(guest["public_key"], guest["ip"])
    db.execute("UPDATE devices SET state='paired',shared_secret=? WHERE group_id=?",
               (secret, owner["group_id"]))
    db.execute("DELETE FROM invites WHERE group_id=?", (owner["group_id"],))
    update_acl(allowed_pairs(db))
    return {"state": "paired"}


def api_decline(db: sqlite3.Connection, owner: sqlite3.Row, body: dict) -> dict:
    if owner["role"] != "owner" or owner["state"] != "ready":
        raise ApiError("DECLINE_NOT_ALLOWED", 403)
    pending_id = body.get("pending_id", "")
    result = db.execute(
        "SELECT id FROM devices WHERE id=? AND group_id=? AND state='pending'",
        (pending_id, owner["group_id"])
    ).fetchone()
    if result is None:
        raise ApiError("PENDING_REQUEST_NOT_FOUND", 404)
    db.execute("DELETE FROM invites WHERE group_id=?", (owner["group_id"],))
    db.execute("DELETE FROM devices WHERE id=?", (pending_id,))
    return {"state": "ready"}


def api_status(db: sqlite3.Connection, caller: sqlite3.Row) -> dict:
    state = caller["state"]
    result = {
        "state": state, "role": caller["role"], "device_ip": caller["ip"],
        "overlay_cidr": str(POOL), "endpoint": ENDPOINT,
        "server_public_key": key(run("wg", "show", INTERFACE, "public-key").stdout.strip())
    }
    if caller["role"] == "owner" and state == "ready":
        row = db.execute("""
          SELECT d.id,d.name,i.expires FROM devices d JOIN invites i ON i.guest_id=d.id
          WHERE i.group_id=? AND d.state='pending'
        """, (caller["group_id"],)).fetchone()
        if row:
            result.update({"pending_id": row["id"], "pending_name": row["name"],
                           "pending_expired": row["expires"] < time.time()})
    if caller["role"] == "guest" and state == "pending":
        inv = db.execute("SELECT expires FROM invites WHERE guest_id=?", (caller["id"],)).fetchone()
        if inv is None or inv["expires"] < time.time():
            result["state"] = "expired"
    if state == "paired":
        other = db.execute("SELECT * FROM devices WHERE group_id=? AND role!=? AND state='paired'",
                           (caller["group_id"], caller["role"])).fetchone()
        if other:
            result.update({"peer_name": other["name"], "peer_ip": other["ip"],
                           "shared_secret": caller["shared_secret"]})
    return result


def api_revoke(db: sqlite3.Connection, caller: sqlite3.Row) -> dict:
    """Fail-closed first: drop the group ACL before deleting either key."""
    group_id = caller["group_id"]
    update_acl(allowed_pairs(db, exclude_group=group_id))
    if caller["role"] == "owner":
        for row in db.execute("SELECT public_key FROM devices WHERE group_id=?", (group_id,)):
            wg_remove(row["public_key"])
        db.execute("DELETE FROM devices WHERE group_id=?", (group_id,))
        db.execute("DELETE FROM invites WHERE group_id=?", (group_id,))
        db.execute("DELETE FROM groups WHERE id=?", (group_id,))
    else:
        wg_remove(caller["public_key"])
        db.execute("DELETE FROM devices WHERE id=?", (caller["id"],))
        db.execute("DELETE FROM invites WHERE group_id=?", (group_id,))
        db.execute("UPDATE devices SET state='ready',shared_secret=NULL WHERE group_id=?",
                   (group_id,))
    return {"state": "revoked"}


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """Reject excess sockets before allocating threads on tiny VPS instances.

    Reverse proxy must also enforce request and connection limits; this only
    bounds *backend* request thread count and is not DDoS protection.
    """
    MAX_REQUEST_THREADS = 8
    request_queue_size = 16
    daemon_threads = True

    def __init__(self, address, handler):
        self._request_slots = threading.BoundedSemaphore(self.MAX_REQUEST_THREADS)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self._request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._request_slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    timeout = 10

    def log_message(self, *args) -> None:
        # Never write tokens or one-time invitations to plaintext logs.
        return

    def reply(self, status: int, data: dict) -> None:
        raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def throttled(self) -> None:
        remote = self.client_address[0]
        if remote == "127.0.0.1":
            # Reverse proxy must replace, not forward untrusted X-Forwarded-For.
            raw = self.headers.get("X-Forwarded-For", "")
            try:
                remote = str(ipaddress.ip_address(raw))
            except ValueError:
                remote = "unverified"
        bucket = remote + ":" + self.path
        now = time.monotonic()
        if bucket not in HITS and len(HITS) >= 1024:
            stale = [k for k, hits in HITS.items() if not hits or hits[-1] < now - 60]
            for k in stale:
                del HITS[k]
            if len(HITS) >= 1024:
                raise ApiError("RATE_LIMITED", 429)
        recent = HITS[bucket]
        while recent and recent[0] < now - 60:
            recent.popleft()
        if len(recent) >= 30:
            raise ApiError("RATE_LIMITED", 429)
        recent.append(now)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.reply(200, {"ok": True, "service": "screensync-v2"})
        else:
            self.reply(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        try:
            if self.path not in (
                "/v1/activate", "/v1/join", "/v1/invite",
                "/v1/status", "/v1/approve", "/v1/decline", "/v1/revoke"
            ):
                raise ApiError("NOT_FOUND", 404)
            with LOCK:
                self.throttled()
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096:
                raise ApiError("INVALID_BODY", 413)
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ApiError("INVALID_BODY")
            with LOCK, db_connect() as db:
                db.execute("BEGIN IMMEDIATE")
                if self.path == "/v1/activate":
                    result = api_activate(db, body)
                elif self.path == "/v1/join":
                    result = api_join(db, body)
                else:
                    caller = authorize(db, self.headers.get("Authorization"))
                    if self.path == "/v1/invite":
                        result = api_invite(db, caller)
                    elif self.path == "/v1/status":
                        result = api_status(db, caller)
                    elif self.path == "/v1/approve":
                        result = api_approve(db, caller, body)
                    elif self.path == "/v1/decline":
                        result = api_decline(db, caller, body)
                    else:
                        result = api_revoke(db, caller)
                db.commit()
            self.reply(200, result)
        except ApiError as err:
            self.reply(err.status, {"error": err.code})
        except (OSError, ValueError, sqlite3.Error, subprocess.SubprocessError,
                json.JSONDecodeError, RuntimeError):
            self.reply(503, {"error": "SERVICE_UNAVAILABLE"})


def main() -> None:
    if len(sys.argv) >= 2 and sys.argv[1] == "issue":
        validate_settings()
        ttl = int(sys.argv[2]) if len(sys.argv) > 2 else 600
        print(issue_owner_code(ttl))
        return
    init_runtime()
    server = BoundedThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print("ScreenSync v2 control plane listening only on loopback", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
