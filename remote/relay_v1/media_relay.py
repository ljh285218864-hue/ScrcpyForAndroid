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
MAX_CONNECTIONS = 128
MAX_COMMANDS_PER_SECOND = 30
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
    command_window: float = 0.
    command_count: int = 0


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
        self.connection_count = 0

    @staticmethod
    def authenticated(token: str) -> tuple[str, str, str] | None:
        if not api.TOKEN.fullmatch(token):
            return None
        with api.db_connect() as db:
            row = db.execute("SELECT * FROM devices WHERE token_hash=?",
                             (api.sha(token),)).fetchone()
            if not row or row["state"] != "paired" or not api.peer(db, row):
                return None
            return row["id"], row["group_id"], api.sha(token)

    @staticmethod
    def valid_pair(session: Session) -> bool:
        # Check on EVERY binary send: HTTP revocation immediately stops data
        # propagation, even when sockets remain open.
        with api.db_connect() as db:
            caller = db.execute(
                "SELECT * FROM devices WHERE id=? AND group_id=? AND state='paired'",
                (session.viewer, session.group_id)).fetchone()
            other = api.peer(db, caller) if caller else None
            return bool(other and other["id"] == session.source)

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

    async def expire_sessions(self) -> None:
        current = time.monotonic()
        for session_id, session in list(self.sessions.items()):
            if session.expires <= current:
                await self.discard(session_id, "session_expired")

    async def maintain_sessions(self) -> None:
        while True:
            await asyncio.sleep(1)
            for session_id, session in list(self.sessions.items()):
                if session.expires <= time.monotonic():
                    await self.discard(session_id, "session_expired")
                elif not self.valid_pair(session):
                    await self.discard(session_id, "pairing_revoked")

    def paired_online(self, client: Device) -> str | None:
        with api.db_connect() as db:
            caller = db.execute(
                "SELECT * FROM devices WHERE id=? AND group_id=? AND state='paired'",
                (client.device_id, client.group_id)).fetchone()
            member = api.peer(db, caller) if caller else None
            if not member:
                return None
            other = member["id"]
            connected = self.online.get(other)
            return other if connected and connected.group_id == client.group_id else None

    async def receive_command(self, client: Device, message: str) -> None:
        current = time.monotonic()
        if current - client.command_window >= 1:
            client.command_window = current
            client.command_count = 0
        client.command_count += 1
        if client.command_count > MAX_COMMANDS_PER_SECOND:
            await client.socket.close(code=4008, reason="COMMAND_RATE_LIMIT")
            return
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
        await self.expire_sessions()
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
            if body.get("to_device_id") not in (None, other):
                await self.tell(client.device_id, {"type": "error",
                                                   "code": "RECIPIENT_NOT_AUTHORIZED"})
                return
            count = sum(s.group_id == client.group_id for s in self.sessions.values())
            # Within each pair, one A->B and one B->A may coexist, but
            # duplicate viewing requests in the same direction are forbidden.
            if any(s.group_id == client.group_id
                   and s.viewer == client.device_id and s.source == other
                   for s in self.sessions.values()):
                await self.tell(client.device_id, {"type": "error",
                                                   "code": "DIRECTION_ALREADY_ACTIVE"})
                return
            if count >= MAX_GROUP_SESSIONS:
                await self.tell(client.device_id, {"type": "error", "code": "SESSION_LIMIT"})
                return
            session_id = secrets.token_hex(16)
            self.sessions[session_id] = Session(session_id, client.device_id, other,
                                                 client.group_id,
                                                 time.monotonic() + REQUEST_TTL_SECONDS)
            # Tell the requesting viewer about the session ID BEFORE
            # notifying the source. Otherwise an exceptionally fast acceptance
            # could reach the viewer before its watch_pending message.
            if not await self.tell(client.device_id, {
                    "type": "watch_pending", "session_id": session_id}):
                await self.discard(session_id, "viewer_unreachable")
                return
            if not await self.tell(other, {
                    "type": "watch_request", "session_id": session_id,
                    "viewer_device_id": client.device_id,
                    "expires_in": REQUEST_TTL_SECONDS}):
                await self.discard(session_id, "source_unreachable")
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
        if self.connection_count >= MAX_CONNECTIONS:
            await socket.close(code=4008, reason="CONNECTION_LIMIT")
            return
        self.connection_count += 1
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
            # Claim the generation before awaiting I/O. An older handler must
            # never clean up sessions belonging to this replacement socket.
            self.online[client.device_id] = client
            if old:
                affected = [sid for sid, s in self.sessions.items()
                            if client.device_id in (s.viewer, s.source)]
                ended = [self.sessions.pop(sid) for sid in affected]
                for session in ended:
                    other = (session.source if session.viewer == client.device_id
                             else session.viewer)
                    await self.tell(other, {"type": "watch_end",
                                           "session_id": session.session_id,
                                           "reason": "device_reconnected"})
                await old.socket.close(code=4005, reason="DEVICE_RECONNECTED")
            if self.online.get(client.device_id) is not client:
                return
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
            self.connection_count -= 1
            if client and self.online.get(client.device_id) is client:
                self.online.pop(client.device_id, None)
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
        maintenance = asyncio.create_task(relay.maintain_sessions())
        try:
            await server.serve_forever()
        finally:
            maintenance.cancel()
            await asyncio.gather(maintenance, return_exceptions=True)


if __name__ == "__main__":
    asyncio.run(run())

