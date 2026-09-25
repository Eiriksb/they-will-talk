#!/usr/bin/env python3
"""Minimal RCON client for testing the dev server: scripts/rcon.py "twt status" "list" ..."""
import os, socket, struct, sys

HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))
PASSWORD = os.environ.get("RCON_PASSWORD", "twt-dev")


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    raw = b""
    while len(raw) < 4:
        raw += sock.recv(4 - len(raw))
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


def main():
    with socket.create_connection((HOST, PORT), timeout=30) as s:
        s.sendall(packet(1, 3, PASSWORD))
        req_id, _ = read(s)
        if req_id == -1:
            sys.exit("RCON auth failed")
        for i, cmd in enumerate(sys.argv[1:], start=2):
            s.sendall(packet(i, 2, cmd))
            _, body = read(s)
            print(f"> {cmd}\n{body}")


if __name__ == "__main__":
    main()
