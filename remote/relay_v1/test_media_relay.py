"""Real socket tests for video-only WSS relay (localhost transport in CI).

The TLS termination layer is intentionally excluded from this unit test.
Public exposure requires an independently configured HTTPS/WSS reverse proxy.
No Android ADB or screen capture is started in these tests.
"""
import json
import asyncio
from unittest.mock import patch
import tempfile
import unittest
from pathlib import Path

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed

import server as api
import media_relay as media


def video_packet(type_byte=1, flag=1):
    return bytes([type_byte, flag]) + bytes(8) + b"mock-h264-keyframe"


class RelayMediaUnitTests(unittest.TestCase):
    def test_android_python_shared_golden_binary_frame(self):
        packet = bytes([1, 1]) + bytes(8) + bytes([1, 2, 3, 4])
        session = "abcdef0123456789abcdef0123456789"
        wire = media.video_frame(session, packet)
        self.assertEqual(wire.hex(),
            "abcdef0123456789abcdef0123456789"
            "0000000e0101000000000000000001020304")
        self.assertEqual(media.parse_video_frame(wire), (session, packet))

    def test_only_scrcpy_video_is_valid(self):
        self.assertTrue(media.legal_video_packet(video_packet()))
        self.assertTrue(media.legal_video_packet(bytes([1, 4]) + bytes(8)))
        for type_byte in (0, 2, 3, 255):
            self.assertFalse(media.legal_video_packet(video_packet(type_byte)))
        self.assertFalse(media.legal_video_packet(bytes([1, 9]) + bytes(16)))
        self.assertFalse(media.legal_video_packet(b"\x01\x01"))
        self.assertFalse(media.legal_video_packet(bytes([1, 1]) + bytes(8)))
        self.assertFalse(media.legal_video_packet(bytes([1, 0]) +
                                                 bytes(media.MAX_VIDEO)))
        for wrong in ([1080, 2400, 0, 300], [True, 100, 100, 100],
                      [1080, 2400, 32000, 100], ["1080", 2400, 200, 400]):
            self.assertFalse(media.legal_dimensions(wrong))
        self.assertTrue(media.legal_dimensions([1080, 2400, 720, 1600]))
        session_id = "b" * 32
        frame = media.video_frame(session_id, video_packet())
        self.assertEqual(media.parse_video_frame(frame), (session_id, video_packet()))
        self.assertIsNone(media.parse_video_frame(frame[:16] + b"\x00" * 4 +
                                                   frame[20:]))
        self.assertIsNone(media.parse_video_frame(frame[:-1]))


    def test_jpeg_wire_is_strictly_bounded_and_distinct_from_video(self):
        payload = bytes([2, 0]) + bytes(8) + b"\xff\xd8" + bytes(68) + b"\xff\xd9"
        session = "a" * 32
        self.assertTrue(media.legal_jpeg_packet(payload))
        self.assertFalse(media.legal_video_packet(payload))
        self.assertEqual(media.parse_video_frame(media.video_frame(session, payload)),
                         (session, payload))
        for wrong in (payload[:-2], payload[:11] + b"X" + payload[12:],
                      bytes([2, 3]) + payload[2:], bytes([2, 0]) + bytes(8)):
            self.assertFalse(media.legal_jpeg_packet(wrong))
        self.assertFalse(media.legal_jpeg_packet(payload + bytes(1024 * 1024)))


class RelayMediaSocketTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old_database = api.DATABASE
        api.DATABASE = Path(self.temp.name) / "media-test.db"
        api.schema()
        self.tokens = {}
        self.ids = {}
        for group in ("a", "b"):
            group_id = (group * 32)
            one = ("1" if group == "a" else "3") * 32
            two = ("2" if group == "a" else "4") * 32
            with api.db_connect() as db:
                db.execute("INSERT INTO groups VALUES (?,?)", (group_id, one))
                for role, dev in (("owner", one), ("guest", two)):
                    token = ("a" if dev == one else "b") * 24 + dev[:24]
                    assert len(token) == 48
                    self.tokens[dev] = token
                    db.execute("INSERT INTO devices(id,group_id,role,name,token_hash,"
                               "state,last_seen) VALUES (?,?,?,?,?,'paired',0)",
                               (dev, group_id, role, role, api.sha(token)))
            self.ids[group] = (one, two)
        self.relay = media.MediaRelay()
        self.server = await serve(self.relay.handle, "127.0.0.1", 0,
                                  origins=[None], compression=None,
                                  max_size=media.MAX_BINARY, max_queue=2)
        self.url = "ws://127.0.0.1:" + str(self.server.sockets[0].getsockname()[1])
        self.sockets = []

    async def asyncTearDown(self):
        for sock in self.sockets:
            await sock.close()
        self.server.close()
        await self.server.wait_closed()
        api.DATABASE = self.old_database
        self.temp.cleanup()

    async def login(self, device_id):
        socket = await connect(self.url, compression=None)
        self.sockets.append(socket)
        await socket.send(json.dumps({"type": "auth",
                                      "token": self.tokens[device_id]}))
        self.assertEqual((await self.read(socket))["type"], "authenticated")
        return socket

    async def read(self, socket):
        value = await socket.recv()
        return json.loads(value) if isinstance(value, str) else value

    async def open_session(self, viewer, source):
        await viewer.send(json.dumps({"type": "watch_request"}))
        request = await self.read(source)
        self.assertEqual(request["type"], "watch_request")
        sid = request["session_id"]
        self.assertEqual((await self.read(viewer))["session_id"], sid)
        return sid



    async def test_session_resume_requires_approved_same_session(self):
        viewer_id, source_id = self.ids["a"]
        viewer = await self.login(viewer_id)
        source = await self.login(source_id)
        sid = await self.open_session(viewer, source)

        # Pending requests cannot be treated as consent to resume.
        await source.send(json.dumps({"type": "session_resume", "session_id": sid}))
        self.assertEqual((await self.read(source))["code"], "RESUME_NOT_ALLOWED")

        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_active")
        self.assertEqual((await self.read(source))["type"], "stream_allowed")

        # Source switches network; original visible approval remains in memory.
        await source.close()
        interruption = await self.read(viewer)
        self.assertEqual(interruption["type"], "session_interrupted")
        self.assertIn(sid, self.relay.sessions)
        self.assertFalse(self.relay.sessions[sid].source_ready)

        reconnect = await self.login(source_id)
        await reconnect.send(json.dumps({"type": "session_resume", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "session_resumed")
        self.assertEqual((await self.read(reconnect))["type"], "session_resumed")

        # Need new video setup; never pass old frames until restored.
        frame = media.video_frame(sid, video_packet())
        await reconnect.send(frame)
        self.assertEqual((await self.read(reconnect))["code"], "VIDEO_SESSION_NOT_ACTIVE")
        await reconnect.send(json.dumps({"type": "video_setup", "session_id": sid,
                                           "dimensions": [1080, 2400, 720, 1600],
                                           "codec": "h264"}))
        self.assertEqual((await self.read(viewer))["type"], "video_setup")
        await reconnect.send(frame)
        self.assertEqual(await self.read(viewer), frame)
        await reconnect.send(json.dumps({"type": "watch_end", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(reconnect))["type"], "watch_end")

    async def test_viewer_rejoins_while_source_socket_stays_online(self):
        viewer_id, source_id = self.ids["a"]
        viewer = await self.login(viewer_id)
        source = await self.login(source_id)
        sid = await self.open_session(viewer, source)
        await source.send(json.dumps({"type":"watch_accept","session_id":sid}))
        self.assertEqual((await self.read(viewer))["type"],"watch_active")
        self.assertEqual((await self.read(source))["type"],"stream_allowed")
        await viewer.close()
        self.assertEqual((await self.read(source))["type"],"session_interrupted")
        reconnect = await self.login(viewer_id)
        await reconnect.send(json.dumps({"type":"session_resume","session_id":sid}))
        self.assertEqual((await self.read(reconnect))["type"],"session_resumed")
        self.assertEqual((await self.read(source))["type"],"session_resumed")
        # The original local approval is still required for video_setup.
        await source.send(json.dumps({"type":"video_setup","session_id":sid,
                          "dimensions":[1080,2400,720,1600],"codec":"jpeg"}))
        self.assertEqual((await self.read(reconnect))["type"],"video_setup")
        await source.send(json.dumps({"type":"watch_end","session_id":sid}))
        self.assertEqual((await self.read(source))["type"],"watch_end")
        self.assertEqual((await self.read(reconnect))["type"],"watch_end")

    async def test_paused_session_disallows_new_capture_and_expires(self):
        viewer_id, source_id = self.ids["a"]
        viewer = await self.login(viewer_id)
        source = await self.login(source_id)
        sid = await self.open_session(viewer, source)
        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        await self.read(viewer)
        await self.read(source)
        await source.close()
        self.assertEqual((await self.read(viewer))["type"], "session_interrupted")
        self.relay.sessions[sid].rejoin_deadline = media.time.monotonic() - 1
        await self.relay.expire_sessions()
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertNotIn(sid, self.relay.sessions)

    async def test_invalid_token_cannot_join(self):
        socket = await connect(self.url, compression=None)
        self.sockets.append(socket)
        await socket.send(json.dumps({"type": "auth", "token": "f" * 48}))
        with self.assertRaises(ConnectionClosed):
            await socket.recv()

    async def test_connection_cap_rejects_before_authentication(self):
        with patch.object(media, "MAX_CONNECTIONS", 1):
            first = await connect(self.url, compression=None)
            self.sockets.append(first)
            second = await connect(self.url, compression=None)
            self.sockets.append(second)
            with self.assertRaises(ConnectionClosed):
                await asyncio.wait_for(second.recv(), 2)
            self.assertEqual(second.close_code, 4008)
            await first.close()

    async def test_command_flood_closes_only_offending_device(self):
        one, _ = self.ids["a"]
        third, _ = self.ids["b"]
        offender = await self.login(one)
        other = await self.login(third)
        with patch.object(media, "MAX_COMMANDS_PER_SECOND", 1):
            self.relay.online[one].command_window = media.time.monotonic() + 60
            await offender.send(json.dumps({"type": "hello"}))
            await self.read(offender)
            await offender.send(json.dumps({"type": "hello"}))
            with self.assertRaises(ConnectionClosed):
                await offender.recv()
            self.assertEqual(offender.close_code, 4008)
        await other.send(json.dumps({"type": "hello"}))
        self.assertEqual((await self.read(other))["type"], "peer_presence")

    async def test_late_old_connection_cleanup_preserves_replacement_session(self):
        one, two = self.ids["a"]
        authenticated = asyncio.Event()
        disconnected = asyncio.Event()

        class DelayedSocket:
            async def recv(inner):
                return json.dumps({"type": "auth", "token": self.tokens[one]})

            async def send(inner, payload):
                authenticated.set()

            def __aiter__(inner):
                return inner

            async def __anext__(inner):
                await disconnected.wait()
                raise StopAsyncIteration

        old = DelayedSocket()
        task = asyncio.create_task(self.relay.handle(old))
        try:
            await asyncio.wait_for(authenticated.wait(), 2)
            replacement = media.Device(object(), one, "a" * 32,
                                       api.sha(self.tokens[one]))
            self.relay.online[one] = replacement
            sid = "c" * 32
            self.relay.sessions[sid] = media.Session(
                sid, one, two, "a" * 32, media.time.monotonic() + 30)
            # The old transport can finish closing after a replacement has
            # already established a newly consented session.
            with patch.object(self.relay, "tell", return_value=True):
                disconnected.set()
                await asyncio.wait_for(task, 2)
            self.assertIs(self.relay.online[one], replacement)
            self.assertIn(sid, self.relay.sessions)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_video_must_have_live_source_approval(self):
        one, two = self.ids["a"]
        viewer = await self.login(one)
        source = await self.login(two)
        sid = await self.open_session(viewer, source)

        await source.send(media.video_frame(sid, video_packet()))
        self.assertEqual((await self.read(source))["code"], "VIDEO_SESSION_NOT_ACTIVE")

        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_active")
        self.assertEqual((await self.read(source))["type"], "stream_allowed")

        await source.send(json.dumps({"type": "video_setup", "session_id": sid,
                                      "dimensions": [1080, 2400, 720, 1600],
                                      "codec": "h264"}))
        self.assertEqual((await self.read(viewer))["type"], "video_setup")

        # No raw ADB CONTROL or COMMAND, nor audio or random bytes.
        for invalid in (video_packet(0), video_packet(2), video_packet(3)):
            frame = bytes.fromhex(sid) + len(invalid).to_bytes(4, "big") + invalid
            await source.send(frame)
            self.assertEqual((await self.read(source))["code"], "NON_VIDEO_REJECTED")

        good = media.video_frame(sid, video_packet())
        await source.send(good)
        self.assertEqual(await self.read(viewer), good)

        # A cannot impersonate B and inject frames into a B -> A session.
        await viewer.send(good)
        self.assertEqual((await self.read(viewer))["code"], "VIDEO_SESSION_NOT_ACTIVE")
        await viewer.send(json.dumps({"type": "watch_end", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(source))["type"], "watch_end")

    async def test_jpeg_requires_explicit_consent_and_matching_codec(self):
        one, two = self.ids["a"]
        viewer = await self.login(one)
        source = await self.login(two)
        sid = await self.open_session(viewer, source)
        jpeg = bytes([2, 0]) + bytes(8) + b"\xff\xd8" + bytes(68) + b"\xff\xd9"
        frame = media.video_frame(sid, jpeg)
        await source.send(frame)
        self.assertEqual((await self.read(source))["code"], "VIDEO_SESSION_NOT_ACTIVE")
        await source.send(json.dumps({"type":"watch_accept","session_id":sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_active")
        self.assertEqual((await self.read(source))["type"], "stream_allowed")
        await source.send(json.dumps({"type":"video_setup","session_id":sid,
                                      "dimensions":[1080, 2400, 432, 960],
                                      "codec":"jpeg"}))
        self.assertEqual((await self.read(viewer))["codec"], "jpeg")
        await source.send(frame)
        self.assertEqual(await self.read(viewer), frame)
        await source.send(media.video_frame(sid, video_packet()))
        self.assertEqual((await self.read(source))["code"], "MEDIA_CODEC_MISMATCH")
        await viewer.send(frame)
        self.assertEqual((await self.read(viewer))["code"], "VIDEO_SESSION_NOT_ACTIVE")
        await viewer.send(json.dumps({"type":"watch_end","session_id":sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(source))["type"], "watch_end")

    async def test_jpeg_burst_drops_excess_without_ending_approved_share(self):
        one, two = self.ids["a"]
        viewer = await self.login(one)
        source = await self.login(two)
        sid = await self.open_session(viewer, source)
        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_active")
        self.assertEqual((await self.read(source))["type"], "stream_allowed")
        await source.send(json.dumps({"type": "video_setup", "session_id": sid,
                                      "dimensions": [1080, 2400, 540, 1200],
                                      "codec": "jpeg"}))
        self.assertEqual((await self.read(viewer))["type"], "video_setup")
        packet = (bytes([2, 0]) + bytes(8) + b"\\xff\\xd8"
                  + bytes(68) + b"\\xff\\xd9")
        frame = media.video_frame(sid, packet)
        # The existing server limit is at most 4 delivered JPEGs per second.
        self.relay.sessions[sid].window_at = media.time.monotonic()
        for _ in range(6):
            await source.send(frame)
        for _ in range(4):
            self.assertEqual(await self.read(viewer), frame)
        await asyncio.sleep(0.05)  # Let the relay process surplus packets.
        self.assertIn(sid, self.relay.sessions)
        self.assertTrue(self.relay.sessions[sid].approved)
        self.relay.sessions[sid].window_at -= 2
        await source.send(frame)
        self.assertEqual(await self.read(viewer), frame)
        await viewer.send(json.dumps({"type": "watch_end", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(source))["type"], "watch_end")

    async def test_valid_delivered_frames_renew_only_approved_session(self):
        one, two = self.ids["a"]
        viewer = await self.login(one)
        source = await self.login(two)
        sid = await self.open_session(viewer, source)
        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_active")
        self.assertEqual((await self.read(source))["type"], "stream_allowed")
        await source.send(json.dumps({"type": "video_setup", "session_id": sid,
                                      "dimensions": [1080, 2400, 648, 1440],
                                      "codec": "jpeg"}))
        self.assertEqual((await self.read(viewer))["codec"], "jpeg")
        packet = bytes([2, 0]) + bytes(8) + bytes.fromhex("ffd8") + bytes(68) + bytes.fromhex("ffd9")
        frame = media.video_frame(sid, packet)
        self.relay.sessions[sid].expires = media.time.monotonic() + 1
        with patch.object(media, "SESSION_TTL_SECONDS", 7):
            await source.send(frame)
            self.assertEqual(await self.read(viewer), frame)
            renewed = self.relay.sessions[sid].expires
            self.assertGreater(renewed, media.time.monotonic() + 6)
            # Invalid frames are not authorized to extend sharing.
            await source.send(media.video_frame(sid, video_packet()))
            self.assertEqual((await self.read(source))["code"], "MEDIA_CODEC_MISMATCH")
            self.assertEqual(self.relay.sessions[sid].expires, renewed)
        # The consent owner can still end the rolling session immediately.
        await source.send(json.dumps({"type": "watch_end", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(source))["type"], "watch_end")
        self.assertNotIn(sid, self.relay.sessions)

    async def test_reconnect_revokes_old_session_and_requires_new_consent(self):
        one, two = self.ids["a"]
        viewer, source = await self.login(one), await self.login(two)
        old_sid = await self.open_session(viewer, source)
        replacement = await self.login(one)
        ended = await asyncio.wait_for(self.read(source), 2)
        self.assertEqual(ended["type"], "watch_end")
        self.assertEqual(ended["session_id"], old_sid)
        self.assertNotIn(old_sid, self.relay.sessions)
        with self.assertRaises(ConnectionClosed):
            await viewer.recv()
        new_sid = await self.open_session(replacement, source)
        self.assertNotEqual(new_sid, old_sid)
        await source.send(media.video_frame(new_sid, video_packet()))
        self.assertEqual((await self.read(source))["code"], "VIDEO_SESSION_NOT_ACTIVE")

    async def test_two_customer_pairs_stream_independently_and_reverse_watch(self):
        a1, b1 = self.ids["a"]
        a2, b2 = self.ids["b"]
        a_viewer, b_source = await self.login(a1), await self.login(b1)
        c_viewer, d_source = await self.login(a2), await self.login(b2)

        sid1 = await self.open_session(a_viewer, b_source)
        sid2 = await self.open_session(c_viewer, d_source)
        self.assertNotEqual(sid1, sid2)
        for source, viewer, sid in (
            (b_source, a_viewer, sid1), (d_source, c_viewer, sid2)):
            await source.send(json.dumps({
                "type": "watch_accept", "session_id": sid}))
            self.assertEqual((await self.read(viewer))["type"], "watch_active")
            self.assertEqual((await self.read(source))["type"], "stream_allowed")
            await source.send(json.dumps({
                "type": "video_setup", "session_id": sid,
                "dimensions": [1080, 2400, 720, 1600], "codec": "h264"}))
            self.assertEqual((await self.read(viewer))["type"], "video_setup")

        # Each customer receives only their pair's video.
        frame_one = media.video_frame(sid1, video_packet())
        frame_two = media.video_frame(sid2, video_packet())
        await b_source.send(frame_one)
        self.assertEqual(await self.read(a_viewer), frame_one)
        await d_source.send(frame_two)
        self.assertEqual(await self.read(c_viewer), frame_two)

        # One-to-one can be bidirectional: B1 can also watch A1.
        reverse = await self.open_session(b_source, a_viewer)
        await a_viewer.send(json.dumps({
            "type": "watch_accept", "session_id": reverse}))
        self.assertEqual((await self.read(b_source))["type"], "watch_active")
        self.assertEqual((await self.read(a_viewer))["type"], "stream_allowed")
        await a_viewer.send(json.dumps({
            "type": "video_setup", "session_id": reverse,
            "dimensions": [1080, 2400, 720, 1600], "codec": "h264"}))
        self.assertEqual((await self.read(b_source))["type"], "video_setup")
        reverse_frame = media.video_frame(reverse, video_packet())
        await a_viewer.send(reverse_frame)
        self.assertEqual(await self.read(b_source), reverse_frame)

        # A duplicate watch in the same direction is not an extra viewer.
        await a_viewer.send(json.dumps({"type": "watch_request"}))
        self.assertEqual((await self.read(a_viewer))["code"],
                         "DIRECTION_ALREADY_ACTIVE")

        # Disconnecting the first pair does not disturb the second pair.
        await a_viewer.send(json.dumps({"type": "watch_end", "session_id": sid1}))
        self.assertEqual((await self.read(a_viewer))["session_id"], sid1)
        self.assertEqual((await self.read(b_source))["session_id"], sid1)
        await d_source.send(frame_two)
        self.assertEqual(await self.read(c_viewer), frame_two)

    async def test_eight_independent_customer_pairs_in_same_server(self):
        # 16 simultaneous device sockets belonging to 8 separate pairs.
        # This is a protocol isolation test, NOT a production bandwidth test.
        import secrets
        pairs = []
        with api.db_connect() as db:
            for i in range(20, 28):
                group = f"{i:032x}"
                owner_id, guest_id = f"{i+100:032x}", f"{i+200:032x}"
                db.execute("INSERT INTO groups VALUES (?,?)", (group, owner_id))
                for role, device_id in (("owner", owner_id), ("guest", guest_id)):
                    secret = secrets.token_hex(24)
                    self.tokens[device_id] = secret
                    db.execute("INSERT INTO devices VALUES (?,?,?,?,?,'paired',0)",
                               (device_id, group, role, "test-" + role, api.sha(secret)))
                pairs.append((owner_id, guest_id, group))

        clients = []
        for a, b, group in pairs:
            viewer = await self.login(a)
            source = await self.login(b)
            sid = await self.open_session(viewer, source)
            await source.send(json.dumps({"type": "watch_accept",
                                          "session_id": sid}))
            self.assertEqual((await self.read(viewer))["type"], "watch_active")
            self.assertEqual((await self.read(source))["type"], "stream_allowed")
            await source.send(json.dumps({
                "type": "video_setup", "session_id": sid,
                "dimensions": [1080, 2400, 720, 1600], "codec": "h264"}))
            self.assertEqual((await self.read(viewer))["type"], "video_setup")
            clients.append((viewer, source, sid, group))

        self.assertEqual(len(self.relay.sessions), 8)
        self.assertEqual(len(self.relay.online), 16)
        for index, (viewer, source, sid, group) in enumerate(clients):
            data = video_packet() + bytes([index])
            frame = media.video_frame(sid, data)
            await source.send(frame)
            self.assertEqual(await self.read(viewer), frame)

        # First customer cancels; second customer must continue normally.
        viewer, source, sid, group = clients[0]
        await viewer.send(json.dumps({"type": "watch_end", "session_id": sid}))
        self.assertEqual((await self.read(viewer))["type"], "watch_end")
        self.assertEqual((await self.read(source))["type"], "watch_end")
        other_viewer, other_source, other_sid, _ = clients[1]
        data = media.video_frame(other_sid, video_packet() + b"still-independent")
        await other_source.send(data)
        self.assertEqual(await self.read(other_viewer), data)

    async def test_group_isolation_and_revocation(self):
        one, two = self.ids["a"]
        three, four = self.ids["b"]
        viewer = await self.login(one)
        source = await self.login(two)
        third = await self.login(three)
        fourth = await self.login(four)
        await viewer.send(json.dumps({"type": "watch_request",
                                      "to_device_id": four}))
        self.assertEqual((await self.read(viewer))["code"],
                         "RECIPIENT_NOT_AUTHORIZED")
        sid = await self.open_session(viewer, source)

        # A different tenant cannot accept or end another group's session.
        await fourth.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        self.assertEqual((await self.read(fourth))["code"], "SESSION_UNKNOWN")

        await source.send(json.dumps({"type": "watch_accept", "session_id": sid}))
        await self.read(viewer)
        await self.read(source)
        await source.send(json.dumps({"type": "video_setup", "session_id": sid,
                                      "dimensions": [1080, 2400, 720, 1600],
                                      "codec": "h264"}))
        await self.read(viewer)
        with api.db_connect() as db:
            db.execute("DELETE FROM signals WHERE group_id=?", ("a" * 32,))
            db.execute("DELETE FROM invites WHERE group_id=?", ("a" * 32,))
            db.execute("DELETE FROM devices WHERE group_id=?", ("a" * 32,))
            db.execute("DELETE FROM groups WHERE id=?", ("a" * 32,))
        await source.send(media.video_frame(sid, video_packet()))
        self.assertEqual((await self.read(source))["type"], "watch_end")
        self.assertEqual((await self.read(viewer))["reason"], "pairing_revoked")

        # The unrelated tenant remains connected and may signal their own peer.
        await third.send(json.dumps({"type": "watch_request"}))
        self.assertEqual((await self.read(fourth))["type"], "watch_request")
        self.assertEqual((await self.read(third))["type"], "watch_pending")


if __name__ == "__main__":
    unittest.main()

