#!/usr/bin/env python3
"""ScreenSync Beta4 no-VPN signaling prototype.

Stdlib-only. Bind to localhost behind a TLS reverse proxy; NEVER publish this
port directly or use it to forward ADB / screen contents. Persistent device
IDs, per-group pairing, capped signaling queues, scoped revocation. The
receiving phone still requires explicit, revocable screen-sharing consent.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATABASE = Path(os.getenv("SS_NOVPN_DB", "/var/lib/screensync-relay/relay.db"))
BIND_HOST = os.getenv("SS_NOVPN_HOST", "127.0.0.1")
BIND_PORT = int(os.getenv("SS_NOVPN_PORT", "8766"))
MAX_GROUPS = int(os.getenv("SS_NOVPN_MAX_GROUPS", "100"))
MAX_THREADS = 8
MAX_BODY = 4096
TOKEN = re.compile(r"[a-f0-9]{48}\Z")
DEVICE_ID = re.compile(r"[a-f0-9]{32}\Z")
ALLOWED_EVENTS = {"hello", "watch_request", "watch_decline", "watch_accept",
                  "watch_end"}
# No WebRTC offer/answer/ICE and no ADB-command channel in this relay.
MAX_SIGNAL_AGE = 90
MAX_SIGNAL_COUNT = 32
# Self-service enrollment has no administrator-issued code. Disable by default
# until this trusted install is explicitly configured and verified.
SELF_SERVICE_ENABLED = os.getenv("SS_ALLOW_SELF_SERVICE", "0") == "1"
MAX_SELF_REGISTER_HOUR = max(1, min(100, int(os.getenv("SS_SELF_REGISTER_HOUR", "12"))))
MAX_SELF_REGISTER_DAY = max(1, min(300, int(os.getenv("SS_SELF_REGISTER_DAY", "30"))))
STALE_ONLINE_SECONDS = 45
LOCK = threading.RLock()
# FCM is optional: never put service-account keys in Git or in the APK.
FCM_PROJECT_ID = os.getenv("SS_FCM_PROJECT_ID", "")
FCM_CREDENTIALS = os.getenv("SS_FCM_SERVICE_ACCOUNT_JSON", "")
FCM_PROJECT = re.compile(r"[a-z][a-z0-9-]{3,63}")
FCM_TOKEN = re.compile(r"[a-zA-Z0-9_:~.%-]{30,2048}")
WAKE_COOLDOWN_SECONDS = 15
WAKE_HOURLY_LIMIT = 60


def fcm_configured() -> bool:
    return bool(FCM_PROJECT.fullmatch(FCM_PROJECT_ID)
                and FCM_CREDENTIALS and Path(FCM_CREDENTIALS).is_file())


def send_fcm_wake(token: str, group_id: str, sender_id: str) -> bool:
    """Best effort NORMAL-priority data-only connectivity reminder.

    NEVER grants screen capture consent and never contains a screen image.
    Sender/receiver are server-verified members of the same paired group.
    """
    if not fcm_configured() or not FCM_TOKEN.fullmatch(token):
        return False
    from google.oauth2 import service_account
    from google.auth.transport.requests import Request as AuthRequest

    credentials = service_account.Credentials.from_service_account_file(
        FCM_CREDENTIALS,
        scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    credentials.refresh(AuthRequest())
    payload = {
        "message": {
            "token": token,
            "data": {"type": "screensync_wake", "group_id": group_id,
                     "from_device_id": sender_id},
            "android": {"priority": "NORMAL", "ttl": "30s",
                        "collapse_key": "screensync_wake"}
        }
    }
    url = ("https://fcm.googleapis.com/v1/projects/"
           + FCM_PROJECT_ID + "/messages:send")
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={"Authorization": "Bearer " + credentials.token,
                 "Content-Type": "application/json; charset=utf-8"},
        method="POST")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


class ApiError(Exception):
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code = code
        self.status = status


def sha(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def now() -> int:
    return int(time.time())


def db_connect() -> sqlite3.Connection:
    db = sqlite3.connect(str(DATABASE), timeout=8, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA busy_timeout=8000")
    return db


def schema() -> None:
    DATABASE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(DATABASE.parent, 0o700)
    with db_connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS groups(
            id TEXT PRIMARY KEY, owner_id TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices(
            id TEXT PRIMARY KEY, group_id TEXT NOT NULL,
            role TEXT NOT NULL CHECK (role IN ('owner','guest')),
            name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
            state TEXT NOT NULL CHECK (state IN ('ready','pending','paired')),
            last_seen INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS codes(
            hash TEXT PRIMARY KEY, expires INTEGER NOT NULL
        );
        -- Global admission throttling for anonymous owner enrollment.
        -- No client IP, QR contents or identity are stored in this audit.
        CREATE TABLE IF NOT EXISTS self_register_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            created INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS self_register_age
            ON self_register_events(created);
        CREATE TABLE IF NOT EXISTS invites(
            hash TEXT PRIMARY KEY, group_id TEXT NOT NULL,
            guest_id TEXT, expires INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS signals(
            seq INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL,
            sender_id TEXT NOT NULL, recipient_id TEXT NOT NULL,
            kind TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL
        );
        -- One customer group = one owner and one guest, never one-to-many.
        -- An existing corrupt database with duplicates fails closed at startup.
        CREATE UNIQUE INDEX IF NOT EXISTS device_group_role_unique
            ON devices(group_id,role);
        CREATE INDEX IF NOT EXISTS device_group ON devices(group_id);
        CREATE INDEX IF NOT EXISTS sig_to ON signals(recipient_id,seq);
        CREATE INDEX IF NOT EXISTS sig_age ON signals(created);
        CREATE TABLE IF NOT EXISTS fcm_registrations(
            device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
            group_id TEXT NOT NULL,
            push_token TEXT NOT NULL,
            created INTEGER NOT NULL,
            last_wake INTEGER NOT NULL DEFAULT 0,
            wake_window INTEGER NOT NULL DEFAULT 0,
            wake_count INTEGER NOT NULL DEFAULT 0
        );
        """)
    os.chmod(DATABASE, 0o600)


def issue_owner_code(ttl: int = 600) -> str:
    if ttl < 60 or ttl > 3600:
        raise ValueError("TTL must be between 60 and 3600 seconds")
    code = secrets.token_hex(24)
    with LOCK, db_connect() as db:
        db.execute("INSERT INTO codes VALUES (?,?)", (sha(code), now() + ttl))
    return "screensync://activate?code=" + code


def valid_token(value: object) -> str:
    if not isinstance(value, str) or not TOKEN.fullmatch(value):
        raise ApiError("INVALID_CODE", 403)
    return value


def nice_name(value: object) -> str:
    if not isinstance(value, str) or not (1 <= len(value) <= 48):
        raise ApiError("INVALID_NAME")
    return value


def caller_row(db: sqlite3.Connection, authorization: str) -> sqlite3.Row:
    if not authorization.startswith("Bearer "):
        raise ApiError("AUTH_REQUIRED", 401)
    secret = valid_token(authorization[7:])
    row = db.execute("SELECT * FROM devices WHERE token_hash=?",
                     (sha(secret),)).fetchone()
    if row is None:
        raise ApiError("AUTH_REQUIRED", 401)
    return row


def peer(db: sqlite3.Connection, caller: sqlite3.Row) -> sqlite3.Row | None:
    """Fail closed unless exactly ONE approved owner and ONE approved guest.

    The server may host hundreds of separate groups. Never select an
    arbitrary peer with LIMIT 1, or allow a group to become one-to-many.
    """
    group = db.execute("SELECT owner_id FROM groups WHERE id=?",
                       (caller["group_id"],)).fetchone()
    members = db.execute(
        "SELECT * FROM devices WHERE group_id=? ORDER BY role",
        (caller["group_id"],)).fetchall()
    if (group is None or len(members) != 2
            or {member["role"] for member in members} != {"owner", "guest"}
            or any(member["state"] != "paired" for member in members)
            or sum(member["id"] == caller["id"] for member in members) != 1
            or next(member["id"] for member in members
                    if member["role"] == "owner") != group["owner_id"]):
        return None
    return next(member for member in members if member["id"] != caller["id"])


def pair_code(group_id: str, other: str) -> str:
    digest = hashlib.sha256((group_id + ":" + other).encode("ascii")).digest()
    return f"{int.from_bytes(digest[:4], 'big') % 1000000:06d}"


def status(db: sqlite3.Connection, caller: sqlite3.Row) -> dict:
    state = caller["state"]
    out = {"state": state, "device_id": caller["id"],
           "group_id": caller["group_id"], "role": caller["role"]}
    if state == "ready" and caller["role"] == "owner":
        invitation = db.execute(
            "SELECT * FROM invites WHERE group_id=? AND guest_id IS NOT NULL "
            "ORDER BY expires DESC LIMIT 1",
            (caller["group_id"],)).fetchone()
        if invitation:
            candidate = db.execute(
                "SELECT * FROM devices WHERE id=? AND state='pending'",
                (invitation["guest_id"],)).fetchone()
            if candidate:
                out.update({"pending_id": candidate["id"],
                            "pending_name": candidate["name"],
                            "pending_verify_code": pair_code(
                                caller["group_id"], candidate["id"]),
                            "pending_expired": invitation["expires"] < now()})
    elif state == "pending":
        out["pending_verify_code"] = pair_code(caller["group_id"], caller["id"])
    elif state == "paired":
        other = peer(db, caller)
        if not other:
            raise ApiError("PAIR_NOT_FOUND", 409)
        out.update({"peer_device_id": other["id"], "peer_name": other["name"],
                    "peer_online": now() - other["last_seen"] <= STALE_ONLINE_SECONDS,
                    "peer_last_seen": other["last_seen"],
                    "pair_verify_code": pair_code(caller["group_id"],
                        ":".join(sorted((caller["id"], other["id"]))))})
    return out


def dispatch(db: sqlite3.Connection, path: str, body: dict, authorization: str) -> dict:
    timestamp = now()
    db.execute("DELETE FROM codes WHERE expires<?", (timestamp,))
    db.execute("DELETE FROM signals WHERE created<?", (timestamp-MAX_SIGNAL_AGE,))
    if path == "/v1/self-register":
        # Never turn signaling registration into automatic screen-sharing
        # permission. A guest can join ONLY with the one-time QR invite, and
        # a paired source still explicitly approves each media session.
        if not SELF_SERVICE_ENABLED:
            raise ApiError("SELF_REGISTER_DISABLED", 403)
        name = nice_name(body.get("name"))
        db.execute("DELETE FROM self_register_events WHERE created<?",
                   (timestamp - 86400,))
        if db.execute("SELECT COUNT(*) FROM self_register_events "
                      "WHERE created>=?", (timestamp - 3600,)).fetchone()[0] \
                >= MAX_SELF_REGISTER_HOUR:
            raise ApiError("REGISTRATION_RATE_LIMIT", 429)
        if db.execute("SELECT COUNT(*) FROM self_register_events "
                      "WHERE created>=?", (timestamp - 86400,)).fetchone()[0] \
                >= MAX_SELF_REGISTER_DAY:
            raise ApiError("REGISTRATION_RATE_LIMIT", 429)
        if db.execute("SELECT COUNT(*) FROM groups").fetchone()[0] >= MAX_GROUPS:
            raise ApiError("GROUP_LIMIT", 409)
        device_id = secrets.token_hex(16)
        group_id = secrets.token_hex(16)
        token = secrets.token_hex(24)
        invite_code = secrets.token_hex(24)
        db.execute("INSERT INTO groups VALUES (?,?)", (group_id, device_id))
        db.execute("INSERT INTO devices VALUES (?,?,?,?,?,'ready',0)",
                   (device_id, group_id, "owner", name, sha(token)))
        db.execute("INSERT INTO invites VALUES (?,?,NULL,?)",
                   (sha(invite_code), group_id, timestamp + 300))
        db.execute("INSERT INTO self_register_events(created) VALUES (?)",
                   (timestamp,))
        return {"device_id": device_id, "group_id": group_id,
                "token": token, "state": "ready",
                "invite_code": invite_code, "expires_in": 300}
    if path == "/v1/activate":
        code = valid_token(body.get("code"))
        name = nice_name(body.get("name"))
        c = db.execute("SELECT * FROM codes WHERE hash=?", (sha(code),)).fetchone()
        if not c or c["expires"] < timestamp:
            raise ApiError("CODE_INVALID_OR_EXPIRED", 403)
        if db.execute("SELECT COUNT(*) FROM groups").fetchone()[0] >= MAX_GROUPS:
            raise ApiError("GROUP_LIMIT", 409)
        device_id, group_id, token = secrets.token_hex(16), secrets.token_hex(16), secrets.token_hex(24)
        db.execute("INSERT INTO groups VALUES (?,?)", (group_id, device_id))
        db.execute("INSERT INTO devices VALUES (?,?,?,?,?,'ready',0)",
                   (device_id, group_id, "owner", name, sha(token)))
        db.execute("DELETE FROM codes WHERE hash=?", (sha(code),))
        return {"device_id": device_id, "group_id": group_id, "token": token, "state": "ready"}
    if path == "/v1/join":
        code = valid_token(body.get("invite_code"))
        name = nice_name(body.get("name"))
        inv = db.execute("SELECT * FROM invites WHERE hash=?", (sha(code),)).fetchone()
        if not inv or inv["guest_id"] or inv["expires"] < timestamp:
            raise ApiError("INVITE_INVALID_OR_EXPIRED", 403)
        if db.execute("SELECT 1 FROM devices WHERE group_id=? AND role='guest'",
                      (inv["group_id"],)).fetchone():
            raise ApiError("GROUP_ALREADY_HAS_GUEST", 409)
        device_id, token = secrets.token_hex(16), secrets.token_hex(24)
        db.execute("INSERT INTO devices VALUES (?,?,?,?,?,'pending',0)",
                   (device_id, inv["group_id"], "guest", name, sha(token)))
        db.execute("UPDATE invites SET guest_id=? WHERE hash=?",
                   (device_id, inv["hash"]))
        return {"device_id": device_id, "group_id": inv["group_id"],
                "token": token, "state": "pending"}

    caller = caller_row(db, authorization)
    if path == "/v1/status":
        return status(db, caller)
    if path == "/v1/invite":
        if caller["role"] != "owner" or caller["state"] != "ready":
            raise ApiError("NOT_ALLOWED", 403)
        # A new invitation replaces a previous pending applicant. Do not
        # leave a stale guest blocking the single guest slot indefinitely.
        db.execute("DELETE FROM devices WHERE group_id=? AND role='guest' "
                   "AND state='pending'", (caller["group_id"],))
        db.execute("DELETE FROM invites WHERE group_id=?", (caller["group_id"],))
        code = secrets.token_hex(24)
        db.execute("INSERT INTO invites VALUES (?,?,NULL,?)",
                   (sha(code), caller["group_id"], timestamp+300))
        return {"invite_code": code, "expires_in": 300}
    if path in ("/v1/approve", "/v1/decline"):
        if caller["role"] != "owner" or caller["state"] != "ready":
            raise ApiError("NOT_ALLOWED", 403)
        pending_id = body.get("pending_id")
        if not isinstance(pending_id, str) or not DEVICE_ID.fullmatch(pending_id):
            raise ApiError("INVALID_DEVICE_ID")
        guest = db.execute(
            "SELECT * FROM devices WHERE id=? AND group_id=? "
            "AND role='guest' AND state='pending'",
            (pending_id, caller["group_id"])).fetchone()
        inv = db.execute(
            "SELECT * FROM invites WHERE guest_id=? AND group_id=? AND expires>=?",
            (pending_id, caller["group_id"], timestamp)).fetchone()
        if not guest or not inv:
            raise ApiError("PENDING_NOT_FOUND", 404)
        if path.endswith("decline"):
            db.execute("DELETE FROM devices WHERE id=?", (pending_id,))
            db.execute("DELETE FROM invites WHERE group_id=?", (caller["group_id"],))
            return {"state": "declined"}
        db.execute("UPDATE devices SET state='paired' WHERE id IN (?,?)",
                   (caller["id"], pending_id))
        db.execute("DELETE FROM invites WHERE group_id=?", (caller["group_id"],))
        return {"state": "paired"}
    if path == "/v1/revoke":
        group_id = caller["group_id"]
        # Revoke BOTH parties' old credentials; do not leave usable invite,
        # presence or messages for a deleted group.
        db.execute("DELETE FROM signals WHERE group_id=?", (group_id,))
        db.execute("DELETE FROM invites WHERE group_id=?", (group_id,))
        db.execute("DELETE FROM devices WHERE group_id=?", (group_id,))
        db.execute("DELETE FROM groups WHERE id=?", (group_id,))
        return {"state": "revoked"}
    if caller["state"] != "paired":
        raise ApiError("PAIRING_NOT_APPROVED", 403)
    other = peer(db, caller)
    if not other:
        raise ApiError("PAIR_NOT_FOUND", 409)
    if path == "/v1/heartbeat":
        db.execute("UPDATE devices SET last_seen=? WHERE id=?",
                   (timestamp, caller["id"]))
        return {"state": "online", "peer_online":
                timestamp-other["last_seen"] <= STALE_ONLINE_SECONDS,
                "peer_device_id": other["id"]}
    if path == "/v1/push/token":
        push_token = body.get("token")
        if not isinstance(push_token, str) or not FCM_TOKEN.fullmatch(push_token):
            raise ApiError("INVALID_PUSH_TOKEN")
        # Token stays scoped to this authenticated, already-paired device.
        # Rotate old tokens atomically; never return a token to clients.
        db.execute(
            "INSERT INTO fcm_registrations"
            "(device_id, group_id, push_token, created) VALUES (?,?,?,?) "
            "ON CONFLICT(device_id) DO UPDATE SET "
            "group_id=excluded.group_id, push_token=excluded.push_token, "
            "created=excluded.created",
            (caller["id"], caller["group_id"], push_token, timestamp))
        return {"state": "registered"}
    if path == "/v1/push/clear":
        db.execute("DELETE FROM fcm_registrations WHERE device_id=?",
                   (caller["id"],))
        return {"state": "cleared"}
    if path == "/v1/push/wake":
        # Only the exact currently paired peer can be signaled. A wake
        # is not a watch_accept and cannot start capture by itself.
        if not fcm_configured():
            return {"state": "not_configured"}
        entry = db.execute(
            "SELECT push_token,last_wake,wake_window,wake_count "
            "FROM fcm_registrations WHERE device_id=? AND group_id=?",
            (other["id"], caller["group_id"])).fetchone()
        if not entry:
            return {"state": "peer_not_registered"}
        if timestamp - entry["last_wake"] < WAKE_COOLDOWN_SECONDS:
            return {"state": "throttled"}
        window = entry["wake_window"]
        count = entry["wake_count"]
        if timestamp - window >= 3600:
            window, count = timestamp, 0
        if count >= WAKE_HOURLY_LIMIT:
            return {"state": "rate_limited"}
        db.execute(
            "UPDATE fcm_registrations SET last_wake=?,wake_window=?,wake_count=? "
            "WHERE device_id=?",
            (timestamp, window, count + 1, other["id"]))
        # Internal-only fields MUST be stripped by the HTTP handler.
        return {"state": "queued", "_push_token": entry["push_token"],
                "_push_group": caller["group_id"], "_push_sender": caller["id"]}
    if path == "/v1/signal/send":
        kind = body.get("type")
        payload = body.get("payload")
        if kind not in ALLOWED_EVENTS or not isinstance(payload, dict):
            raise ApiError("INVALID_SIGNAL")
        if len(json.dumps(payload, separators=(",", ":")).encode("utf-8")) > 2048:
            raise ApiError("SIGNAL_TOO_LARGE")
        # Never accept an arbitrary recipient ID, IP, URL, shell command or
        # remote-control channel from clients.
        if body.get("to_device_id") != other["id"]:
            raise ApiError("RECIPIENT_NOT_AUTHORIZED", 403)
        count = db.execute("SELECT COUNT(*) FROM signals WHERE recipient_id=?",
                           (other["id"],)).fetchone()[0]
        if count >= MAX_SIGNAL_COUNT:
            raise ApiError("SIGNAL_BACKLOG_FULL", 429)
        db.execute("INSERT INTO signals(group_id,sender_id,recipient_id,kind,payload,created) "
                   "VALUES (?,?,?,?,?,?)",
                   (caller["group_id"], caller["id"], other["id"], kind,
                    json.dumps(payload, separators=(",", ":")), timestamp))
        return {"state": "queued"}
    if path == "/v1/signal/poll":
        # Client acknowledges only messages it previously received.
        # Cursor-based queue survives process/network restart for 90s.
        cursor = body.get("after", 0)
        if isinstance(cursor, bool) or not isinstance(cursor, int) or cursor < 0:
            raise ApiError("INVALID_CURSOR")
        rows = db.execute(
            "SELECT seq,sender_id,kind,payload FROM signals "
            "WHERE group_id=? AND recipient_id=? AND seq>? ORDER BY seq LIMIT 16",
            (caller["group_id"], caller["id"], cursor)).fetchall()
        return {"messages": [{"seq": row["seq"], "from_device_id": row["sender_id"],
                              "type": row["kind"], "payload": json.loads(row["payload"])}
                             for row in rows],
                "cursor": rows[-1]["seq"] if rows else cursor}
    raise ApiError("NOT_FOUND", 404)


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16
    def __init__(self, address, handler):
        self.slots = threading.BoundedSemaphore(MAX_THREADS)
        super().__init__(address, handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "ScreenSync-NoVpn-Experimental"
    sys_version = ""

    def log_message(self, *args):
        pass  # Never log secrets, tokens or QR contents.

    def reply(self, status: int, data: dict):
        data = json.dumps(data, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store, private")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"ok": True, "service": "screensync-novpn-signaling",
                             "video_ready": False})
        else:
            self.reply(404, {"error": "NOT_FOUND"})

    def do_POST(self):
        path = self.path
        if path not in ("/v1/self-register", "/v1/activate", "/v1/join",
                        "/v1/status", "/v1/invite",
                        "/v1/approve", "/v1/decline", "/v1/revoke", "/v1/heartbeat",
                        "/v1/push/token", "/v1/push/clear", "/v1/push/wake",
                        "/v1/signal/send", "/v1/signal/poll"):
            self.reply(404, {"error": "NOT_FOUND"})
            return
        try:
            raw_length = self.headers.get("Content-Length", "")
            if not raw_length.isdigit() or int(raw_length) > MAX_BODY:
                raise ApiError("INVALID_BODY")
            content = self.rfile.read(int(raw_length))
            body = json.loads(content.decode("utf-8"))
            if not isinstance(body, dict):
                raise ApiError("INVALID_BODY")
            with LOCK, db_connect() as db:
                db.execute("BEGIN IMMEDIATE")
                reply = dispatch(db, path, body, self.headers.get("Authorization", ""))
                db.commit()
            # Never hold the SQLite transaction lock while calling Google.
            # Strip all internal fields before an HTTP response, including
            # when FCM times out or credentials are invalid.
            push_token = reply.pop("_push_token", None)
            push_group = reply.pop("_push_group", None)
            push_sender = reply.pop("_push_sender", None)
            if push_token:
                try:
                    sent = send_fcm_wake(push_token, push_group, push_sender)
                    reply = {"state": "sent" if sent else "delivery_unavailable"}
                except Exception:
                    reply = {"state": "delivery_unavailable"}
            self.reply(200, reply)
        except ApiError as error:
            self.reply(error.status, {"error": error.code})
        except (ValueError, UnicodeError, json.JSONDecodeError, sqlite3.Error,
                OSError, TypeError):
            self.reply(503, {"error": "SERVICE_UNAVAILABLE"})


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["serve", "issue-owner"])
    parser.add_argument("--ttl", type=int, default=600)
    parser.add_argument("--count", type=int, default=1,
                        help="how many independent customer activation QR links to issue")
    args = parser.parse_args()
    if not 1 <= args.count <= min(MAX_GROUPS, 100):
        parser.error("--count must be between 1 and the configured group cap (max 100)")
    if args.command != "issue-owner" and args.count != 1:
        parser.error("--count is only valid for issue-owner")
    schema()
    if args.command == "issue-owner":
        # Each code activates ONE new owner who may subsequently invite
        # exactly ONE guest. This is multi-customer 1:1, not one-to-many.
        for _ in range(args.count):
            print(issue_owner_code(args.ttl))
    else:
        if BIND_HOST not in ("127.0.0.1", "::1"):
            raise SystemExit("Refusing public bind. Use an HTTPS reverse proxy.")
        with BoundedHTTPServer((BIND_HOST, BIND_PORT), Handler) as server:
            server.serve_forever(poll_interval=0.25)


if __name__ == "__main__":
    main()
