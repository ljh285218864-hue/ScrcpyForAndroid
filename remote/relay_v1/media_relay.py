#!/usr/bin/env python3
"""Experimental, video-only ADB+scrcpy WSS relay (not a screen capturer).

Run on loopback behind an independently configured TLS reverse proxy.
Each phone must initiate its own authenticated websocket. Device pairing
doesn't automatically grant a video session: the source device must explicitly
accept a request, and either side can immediately stop. This server neither
opens ADB ports nor sends touch/keyboard/shell commands.

Wire format for BINARY VIDEO: 16 bytes session ID, 4-byte big-endian length,
then the original scrcpy VIDEO packet payload (type=1, flag=0/1/2/4).
Only this video data can be sent in a confirmed, short-lived session.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
import time
from dataclasses import dataclass
from typing import Any

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

import server as api


BIND_HOST = os.getenv("SS_MEDIA_HOST", "127.0.0.1")
BIND_PORT = int(os.getenv("SS_MEDIA_PORT", "8767"))
MAX_VIDEO = 4 * 1024 * 1024
MAX_BINARY = 16 + 4 + MAX_VIDEO
MAX_MESSAGE = 1024
MAX_GROUP_SESSIONS = 2
REQUEST_TTL_SECONDS = 30
SESSION_TTL_SECONDS = 20 * 60
MAX_FRAMES_PER_SECOND = 90
MAX_BYTES_PER_SECOND = 8 * 1024 * 1024
SESSION_ID = re.compile(r"[a-f0-9]{32}\Z")


def legal_video_packet(packet: bytes) -> bool:
    if not (10 <= len(packet) <= MAX_VIDEO) or packet[0] != 1:
        return False
    if packet[1] not in (0, 1, 2, 4):
        return False
    return packet[1] == 4 or len(packet) > 10


def legal_dimensions(values: Any) -> bool:
    if not isinstance(values, list) or len(values) != 4:
        return False
    if any(type(value) is not int or not 16 <= value <= 8192 for value in values):
        return False
    return values[2] <= values[0] * 2 and values[3] <= values[1] * 2


def parse_video_frame(data: bytes) -> tuple[str, bytes] | None:
    if len(data) < 30 or len(data) > MAX_BINARY:
        return None
    session_id = data[:16].hex()
    packet_length = int.from_bytes(data[16:20], "big")
    packet = data[20:]
    if packet_length != len(packet) or not legal_video_packet(packet):
        return None
    return session_id, packet


def video_frame(session_id: str, packet: bytes) -> bytes:
    """Test helper for the documented wire protocol, not a source of media."""
    if not SESSION_ID.fullmatch(session_id) or not legal_video_packet(packet):
        raise ValueError("INVALID_VIDEO_PACKET")
    return bytes.fromhex(session_id) + len(packet).to_bytes(4, "big") + packet


@dataclass(eq=False)
class Device:
    socket: Any
    device_id: str
    group_id: str
    token_hash: str


@dataclass
class Session:
    session_id: str
    viewer: str
    source: str
    group_id: str
    expires: float
    approved: bool = False
    configured: bool = False
    window_at: float = 0.
    frames_in_window: int = 0
    bytes_in_window: int = 0


class MediaRelay:
    def __init__(self):
        self.online: dict[str, Device] = {}
        self.sessions: dict[str, Session] = {}

    @staticmethod
    def authenticated(token: str) -> tuple[str, str, str] | None:
        if not api.TOKEN.fullmatch(token):
            return None
        with api.db_connect() as db:
            row = db.execute("SELECT id,group_id,state FROM devices WHERE token_hash=?",
                             (api.sha(token),)).fetchone()
            if not row or row["state"] != "paired":
                return None
            other = db.execute("SELECT 1 FROM devices WHERE group_id=? AND "
                               "id<>? AND state='paired'",
                               (row["group_id"], row["id"])).fetchone()
            return (row["id"], row["group_id"], api.sha(token)) if other else None

    @staticmethod
    def valid_pair(session: Session) -> bool:
        # Check on EVERY binary send: HTTP revocation immediately stops data
        # propagation, even when sockets remain open.
        with api.db_connect() as db:
            rows = db.execute("SELECT id FROM devices WHERE group_id=? AND "
                              "id IN (?,?) AND state='paired'",
                              (session.group_id, session.viewer, session.source)).fetchall()
            return len(rows) == 2

    async def tell(self, device_id: str, payload: dict) -> bool:
        client = self.online.get(device_id)
        if not client:
            return False
        try:
            await asyncio.wait_for(client.socket.send(
                json.dumps(payload, separators=(",", ":"))), 2)
            return True
        except (ConnectionClosed, OSError, asyncio.TimeoutError):
            return False

    async def discard(self, session_id: str, reason: str) -> None:
        session = self.sessions.pop(session_id, None)
        if session:
            for device_id in (session.source, session.viewer):
                await self.tell(device_id, {
                    "type": "watch_end", "session_id": session_id, "reason": reason,
                })

    def paired_online(self, client: Device) -> str | None:
        with api.db_connect() as db:
            row = db.execute("SELECT id FROM devices WHERE group_id=? "
                             "AND id<>? AND state='paired'",
                             (client.group_id, client.device_id)).fetchone()
            if not row:
                return None
            other = row["id"]
            peer = self.online.get(other)
            return other if peer and peer.group_id == client.group_id else None

    async def receive_command(self, client: Device, message: str) -> None:
        if len(message) > MAX_MESSAGE:
            await self.tell(client.device_id, {"type": "error", "code": "COMMAND_TOO_LARGE"})
            return
        try:
            body = json.loads(message)
        except (ValueError, TypeError):
            body = None
        if not isinstance(body, dict) or not isinstance(body.get("type"), str):
            await self.tell(client.device_id, {"type": "error", "code": "INVALID_COMMAND"})
            return
        command = body["type"]
        if command == "hello":
            peer_id = self.paired_online(client)
            await self.tell(client.device_id, {"type": "peer_presence",
                                              "peer_connected": bool(peer_id)})
            return
        if command == "watch_request":
            other = self.paired_online(client)
            if not other:
                await self.tell(client.device_id, {"type": "error", "code": "PEER_OFFLINE"})
                return
            count = sum(s.group_id == client.group_id for s in self.sessions.values())
            if count >= MAX_GROUP_SESSIONS:
                await self.tell(client.device_id, {"type": "error", "code": "SESSION_LIMIT"})
                return
            session_id = secrets.token_hex(16)
            self.sessions[session_id] = Session(session_id, client.device_id, other,
                                                 client.group_id,
                                                 time.monotonic() + REQUEST_TTL_SECONDS)
            if not await self.tell(other, {
                    "type": "watch_request", "session_id": session_id,
                    "viewer_device_id": client.device_id,
                    "expires_in": REQUEST_TTL_SECONDS}):
                await self.discard(session_id, "source_unreachable")
                return
            await self.tell(client.device_id, {"type": "watch_pending",
                                              "session_id": session_id})
            return
        session_id = body.get("session_id")
        if not isinstance(session_id, str) or not SESSION_ID.fullmatch(session_id):
            await self.tell(client.device_id, {"type": "error", "code": "INVALID_SESSION"})
            return
        session = self.sessions.get(session_id)
        if not session or session.group_id != client.group_id \
                or client.device_id not in (session.viewer, session.source):
            await self.tell(client.device_id, {"type": "error", "code": "SESSION_UNKNOWN"})
            return
        if time.monotonic() > session.expires or not self.valid_pair(session):
            await self.discard(session_id, "session_expired_or_revoked")
            return
        if command == "watch_end":
            await self.discard(session_id, "stopped_by_device")
            return
        if command == "watch_decline" and client.device_id == session.source:
            await self.discard(session_id, "declined_by_source")
            return
        if command == "watch_accept" and client.device_id == session.source:
            if not session.approved:
                session.approved = True
                session.expires = time.monotonic() + SESSION_TTL_SECONDS
                await self.tell(session.viewer, {"type": "watch_active",
                                                "session_id": session_id})
                await self.tell(session.source, {"type": "stream_allowed",
                                                "session_id": session_id})
            return
        if command == "video_setup" and client.device_id == session.source \
                and session.approved:
            dims, codec = body.get("dimensions"), body.get("codec")
            if not legal_dimensions(dims) or codec not in ("h264", "h265"):
                await self.tell(client.device_id, {"type": "error",
                                                  "code": "INVALID_VIDEO_SETUP"})
                return
            session.configured = True
            await self.tell(session.viewer, {
                "type": "video_setup", "session_id": session_id,
                "dimensions": dims, "codec": codec,
            })
            return
        await self.tell(client.device_id, {"type": "error",
                                           "code": "NOT_AUTHORIZED_FOR_COMMAND"})

    async def receive_video(self, client: Device, data: bytes) -> None:
        parsed = parse_video_frame(data)
        if parsed is None:
            await self.tell(client.device_id, {"type": "error", "code": "NON_VIDEO_REJECTED"})
            return
        session_id, _ = parsed
        session = self.sessions.get(session_id)
        if session is None or session.source != client.device_id \
                or session.group_id != client.group_id \
                or not session.approved or not session.configured \
                or time.monotonic() > session.expires:
            await self.tell(client.device_id, {"type": "error",
                                               "code": "VIDEO_SESSION_NOT_ACTIVE"})
            return
        if not self.valid_pair(session):
            await self.discard(session_id, "pairing_revoked")
            return
        if self.online.get(session.viewer) is None:
            await self.discard(session_id, "viewer_disconnected")
            return
        current = time.monotonic()
        if current - session.window_at >= 1:
            session.window_at = current
            session.frames_in_window = 0
            session.bytes_in_window = 0
        session.frames_in_window += 1
        session.bytes_in_window += len(data)
        if session.frames_in_window > MAX_FRAMES_PER_SECOND \
                or session.bytes_in_window > MAX_BYTES_PER_SECOND:
            await self.discard(session_id, "media_rate_limit")
            return
        try:
            # Never buffer unbounded frames for a slow watcher.
            await asyncio.wait_for(self.online[session.viewer].socket.send(data), 2)
        except (ConnectionClosed, asyncio.TimeoutError, OSError):
            await self.discard(session_id, "viewer_unreachable")

    async def handle(self, socket) -> None:
        client = None
        try:
            first = await asyncio.wait_for(socket.recv(), 8)
            if not isinstance(first, str) or len(first) > 256:
                await socket.close(code=4003, reason="AUTH_REQUIRED")
                return
            try:
                auth = json.loads(first)
            except (TypeError, ValueError):
                auth = None
            if not isinstance(auth, dict) or auth.get("type") != "auth" \
                    or not isinstance(auth.get("token"), str):
                await socket.close(code=4003, reason="AUTH_REQUIRED")
                return
            identity = self.authenticated(auth["token"])
            if identity is None:
                await socket.close(code=4003, reason="AUTH_REQUIRED")
                return
            client = Device(socket, *identity)
            old = self.online.get(client.device_id)
            if old:
                # Reconnect after Wi-Fi/cellular switch: old sessions are
                # terminated, never blindly resumed or auto-approved.
                await old.socket.close(code=4005, reason="DEVICE_RECONNECTED")
            self.online[client.device_id] = client
            await self.tell(client.device_id, {"type": "authenticated",
                                              "device_id": client.device_id,
                                              "group_id": client.group_id})
            async for packet in socket:
                if self.online.get(client.device_id) is not client:
                    break
                if isinstance(packet, bytes):
                    await self.receive_video(client, packet)
                else:
                    await self.receive_command(client, packet)
        except (ConnectionClosed, asyncio.TimeoutError, OSError):
            pass
        finally:
            if client and self.online.get(client.device_id) is client:
                self.online.pop(client.device_id, None)
            if client:
                affected = [sid for sid, s in self.sessions.items()
                            if client.device_id in (s.viewer, s.source)]
                for sid in affected:
                    await self.discard(sid, "device_disconnected")


async def run():
    if BIND_HOST not in ("127.0.0.1", "::1"):
        raise SystemExit("Media relay only supports localhost + WSS TLS reverse proxy")
    api.schema()
    relay = MediaRelay()
    async with serve(relay.handle, BIND_HOST, BIND_PORT,
                     origins=[None], compression=None,
                     max_size=MAX_BINARY, max_queue=2,
                     ping_interval=20, ping_timeout=20) as server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(run())
