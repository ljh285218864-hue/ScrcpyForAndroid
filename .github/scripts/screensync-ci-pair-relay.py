#!/usr/bin/env python3
"""Disposable HTTPS + WSS pairing fixture for ScreenSync Android emulator CI.

No contact with the live Osaka VPS, no real pair IDs, no screen streaming.
All tokens live only in the ephemeral CI job and are excluded from artifacts.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import ssl
import sys
import threading
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "remote" / "relay_v1"))
TEST_DIR = Path(os.getenv("SS_CI_DIR", "/tmp/screensync-ci-pair"))
TEST_DIR.mkdir(parents=True, exist_ok=True)
os.environ["SS_NOVPN_DB"] = str(TEST_DIR / "isolated-pair.sqlite3")
os.environ["SS_ALLOW_SELF_SERVICE"] = "1"
os.environ["SS_SELF_REGISTER_HOUR"] = "10"
os.environ["SS_SELF_REGISTER_DAY"] = "10"

import server as api  # noqa: E402
import media_relay as media  # noqa: E402
from websockets.asyncio.client import connect  # noqa: E402
from websockets.asyncio.server import serve  # noqa: E402

HOST = "https://127.0.0.1:18443"
WSS = "wss://127.0.0.1:18444/media"


def tls_client():
    return ssl.create_default_context(cafile=str(TEST_DIR / "ci-ca.pem"))


def post(path, payload, token=None):
    body = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(HOST + path, data=body, headers=headers)
    with urlopen(request, context=tls_client(), timeout=10) as stream:
        return json.load(stream)


def guest_token():
    return (TEST_DIR / "guest_token").read_text().strip()


def guest_join():
    invitation = sys.stdin.readline().strip()
    if len(invitation) != 48:
        raise SystemExit("invalid single-use invitation from emulator")
    created = post("/v1/join", {"name": "CI-synthetic-guest", "invite_code": invitation})
    token_file = TEST_DIR / "guest_token"
    token_file.write_text(created["token"])
    token_file.chmod(0o600)
    state = post("/v1/status", {}, created["token"])
    assert state["state"] == "pending", state["state"]
    print(json.dumps({
        "guest_id": created["device_id"],
        "verification_code": state["pending_verify_code"],
    }))


async def guest_online_one():
    async with connect(WSS, ssl=tls_client(), open_timeout=5, close_timeout=1,
                       compression=None) as ws:
        await ws.send(json.dumps({"type": "auth", "token": guest_token()}))
        response = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert response["type"] == "authenticated", response["type"]
        await ws.send('{"type":"hello"}')
        presence = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert presence["type"] == "peer_presence"
        return presence["peer_connected"]


async def guest_online(seconds):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + seconds
    while True:
        try:
            if await guest_online_one():
                print("ONLINE: emulator ScreenSync WSS authenticated")
                return 0
        except Exception:
            pass
        if loop.time() > deadline:
            print("OFFLINE: emulator did not authenticate WSS within timeout")
            return 1
        await asyncio.sleep(2)


async def guest_watch_frames(timeout_seconds, duration=0):
    """Require a fresh authorized session, actual decodable JPEG packets and
    optionally collect a long-running frame timing sample for stutter analysis.
    Do not store the screenshot bytes or any private screen contents.
    """
    from io import BytesIO
    from PIL import Image

    async with connect(WSS, ssl=tls_client(), open_timeout=6,
                       close_timeout=2, compression=None, max_size=5_000_000) as ws:
        await ws.send(json.dumps({"type": "auth", "token": guest_token()}))
        auth = json.loads(await asyncio.wait_for(ws.recv(), 8))
        if auth.get("type") != "authenticated":
            raise RuntimeError("guest WSS auth rejected")
        await ws.send(json.dumps({"type": "watch_request"}))
        pending = None
        active = False
        dimensions = None
        frames = 0
        begin = asyncio.get_running_loop().time()
        first_frame_at = None
        frame_times = []
        messages = []
        disconnected = False
        while asyncio.get_running_loop().time() - begin < timeout_seconds:
            now = asyncio.get_running_loop().time()
            if duration and first_frame_at is not None and now - first_frame_at >= duration:
                break
            remaining = max(.5, timeout_seconds - (now - begin))
            if duration and first_frame_at is not None:
                remaining = min(remaining, max(.5, duration - (now - first_frame_at)))
            try:
                event = await asyncio.wait_for(ws.recv(), remaining)
            except asyncio.TimeoutError:
                if duration and first_frame_at is not None:
                    if asyncio.get_running_loop().time() - first_frame_at >= duration - .3:
                        break
                    continue
                break
            if isinstance(event, bytes):
                if not active or dimensions is None or pending is None:
                    raise RuntimeError("video without active authenticated session/setup")
                packet = media.parse_video_frame(event)
                if not packet or packet[0] != pending or not media.legal_jpeg_packet(packet[1]):
                    raise RuntimeError("invalid JPEG relay frame/session")
                jpeg = packet[1][10:]
                with Image.open(BytesIO(jpeg)) as frame:
                    frame.verify()
                recorded_at = asyncio.get_running_loop().time()
                if first_frame_at is None:
                    first_frame_at = recorded_at
                frame_times.append(recorded_at)
                frames += 1
                if duration == 0 and frames >= 2:
                    break
            else:
                msg = json.loads(event)
                code = msg.get("type", "?")
                messages.append(code + (":" + msg.get("code", "") if msg.get("code") else ""))
                if code == "watch_pending":
                    pending = msg.get("session_id")
                elif code == "watch_active":
                    if msg.get("session_id") != pending:
                        raise RuntimeError("watch session id changed without approval")
                    active = True
                elif code == "video_setup":
                    if msg.get("session_id") != pending or msg.get("codec") != "jpeg":
                        raise RuntimeError("bad video setup")
                    dimensions = msg.get("dimensions")
                elif code in ("error", "watch_end"):
                    disconnected = True
                    break
        if pending is not None and active:
            await ws.send(json.dumps({"type": "watch_end", "session_id": pending}))
        elapsed = (frame_times[-1] - frame_times[0]) if len(frame_times) > 1 else 0
        fps = (len(frame_times) - 1) / elapsed if elapsed > 0 else 0
        max_gap = max((b - a for a, b in zip(frame_times, frame_times[1:])), default=0)
        observed_duration = (asyncio.get_running_loop().time() - first_frame_at) if first_frame_at else 0
        # For long sessions, report measurable rate without falsely claiming
        # a guaranteed 25-30 FPS: JPEG relay is capped at 8 fps by design.
        stats = (f"count={frames} valid_jpeg=true fresh_session=true "
                 f"fps={fps:.2f} max_gap_s={max_gap:.2f} "
                 f"observed_s={observed_duration:.1f} "
                 f"dimensions={dimensions} disconnected={disconnected}")
        if frames >= 2 and not disconnected and (
                duration == 0 or observed_duration >= duration - 1):
            print("WATCH_FRAMES_OK " + stats, flush=True)
            return 0
        print("WATCH_FRAMES_FAIL " + stats + " messages=" + ",".join(messages[-12:]),
              flush=True)
        return 1


def guest_status():
    state = post("/v1/status", {}, guest_token())
    assert state["state"] == "paired", state["state"]
    print("PAIRED: isolated server confirms authenticated guest still paired")


def revoke():
    # Only the synthetic pair created inside this CI job may be revoked.
    post("/v1/revoke", {}, guest_token())
    print("CLEANUP: isolated test pair revoked")


async def run_services():
    api.schema()
    server = api.BoundedHTTPServer(("0.0.0.0", 18443), api.Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(TEST_DIR / "ci-server.crt"),
                        str(TEST_DIR / "ci-server.key"))
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    relay = media.MediaRelay()
    async with serve(relay.handle, "0.0.0.0", 18444,
                     ssl=ctx, origins=[None], compression=None,
                     max_size=media.MAX_BINARY, max_queue=2,
                     ping_interval=20, ping_timeout=20):
        maint = asyncio.create_task(relay.maintain_sessions())
        try:
            print("CI_RELAY_READY", flush=True)
            await asyncio.Future()
        finally:
            maint.cancel()
            await asyncio.gather(maint, return_exceptions=True)
            server.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["serve", "join", "online", "watch", "status", "revoke"])
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--duration", type=int, default=0)
    args = parser.parse_args()
    if args.mode == "serve":
        asyncio.run(run_services())
    elif args.mode == "join":
        guest_join()
    elif args.mode == "online":
        sys.exit(asyncio.run(guest_online(args.seconds)))
    elif args.mode == "watch":
        sys.exit(asyncio.run(guest_watch_frames(args.seconds, duration=args.duration)))
    elif args.mode == "status":
        guest_status()
    else:
        revoke()
