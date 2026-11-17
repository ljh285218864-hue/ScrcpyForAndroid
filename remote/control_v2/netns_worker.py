#!/usr/bin/env python3
"""Control-plane driver for the sudo-only *real kernel* netns integration test.

Run only from test_kernel_network.sh, within its ephemeral server namespace.
Creates test-only WireGuard peers, approval records and nft ACLs. No customer
VPS, real keys, public endpoints or deployment services are involved.
"""
from __future__ import annotations

import os
import sys

import server

NAMES = ("group1a", "group1b", "group2a", "group2b")


def in_transaction(operation):
    with server.db_connect() as db:
        db.execute("BEGIN IMMEDIATE")
        result = operation(db)
        db.commit()
        return result


def owner(name: str, public_key: str):
    code = server.issue_owner_code(600).split("code=", 1)[1]
    return in_transaction(lambda db: server.api_activate(
        db, {"code": code, "name": name, "public_key": public_key}
    ))


def join(the_owner: dict, name: str, public_key: str):
    def request(db):
        row = server.authorize(db, "Bearer " + the_owner["token"])
        invite = server.api_invite(db, row)["invite_code"]
        return server.api_join(
            db, {"invite_code": invite, "name": name, "public_key": public_key}
        )
    return in_transaction(request)


def change_pair(name: str, revoke: bool = False):
    def apply(db):
        caller = db.execute("SELECT * FROM devices WHERE name=?", (name,)).fetchone()
        assert caller is not None, f"missing test device: {name}"
        if revoke:
            return server.api_revoke(db, caller)
        guest = db.execute("SELECT * FROM devices WHERE group_id=? AND role='guest'",
                           (caller["group_id"],)).fetchone()
        assert guest is not None and guest["state"] == "pending"
        return server.api_approve(db, caller, {"pending_id": guest["id"]})
    return in_transaction(apply)


def validate_db(expected_pairs: int):
    with server.db_connect() as db:
        pairs = server.allowed_pairs(db)
        assert len(pairs) == expected_pairs, (pairs, expected_pairs)
        ips = [r["ip"] for r in db.execute("SELECT ip FROM devices")]
        assert len(ips) == len(set(ips))
        assert all(x.startswith("100.81.") for x in ips)
    print(f"PASS: persisted DB contains {expected_pairs} authorized pairs")


def main():
    cmd = sys.argv[1]
    if cmd == "prepare":
        server.init_runtime()
        pubs = [os.environ[f"SS4_PUB_{i}"] for i in range(1, 5)]
        a = owner(NAMES[0], pubs[0])
        join(a, NAMES[1], pubs[1])
        c = owner(NAMES[2], pubs[2])
        join(c, NAMES[3], pubs[3])
        validate_db(0)
        print("PASS: two independent owner groups, pending requests and unique IPs")
    elif cmd == "approve1":
        change_pair(NAMES[0])
        validate_db(1)
    elif cmd == "approve2":
        change_pair(NAMES[2])
        validate_db(2)
    elif cmd == "revoke1":
        change_pair(NAMES[1], revoke=True)
        validate_db(1)
    elif cmd == "restart":
        server.init_runtime()
        validate_db(1)
    else:
        raise SystemExit("unknown integration test phase")


if __name__ == "__main__":
    main()
