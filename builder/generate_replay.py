#!/usr/bin/env python3
"""Turn a face container into the BLE write script the uploader app replays.

PC reference implementation of
``uploader/app/src/main/java/com/jay/wfup/ReplayBuilder.java``. The framing below
was recovered by reconstructing a real accepted transfer byte for byte:

    stream  = container || 78 66 84 28
    marker  = 00 00 00 00 <chunkCount u16le>          (6 bytes)
    chunk 1 = <seq u16le> 0E 00 <winIdx u16le> <12-byte field> [10-byte meta] <data>
    chunk N = <seq u16le> <data>                      (max 177 bytes total)

Window 1 is special: its 12-byte field is the constant
``00 10 00 00 00 00 00 00 00 00 00 00`` and it carries a 10-byte meta block
(six zero bytes, then the container length as u32le, trailer not counted).
Chunk 1 then holds 149 payload bytes and the window holds 3824 bytes.

Later windows put the first 12 bytes of their own payload into the field slot,
then 159 bytes in chunk 1 and 175 in each following chunk, so the window holds
3846 bytes. At most 22 chunks per window; the last window is the remainder.

Usage:
    python generate_replay.py face.lz replay3.txt
    python generate_replay.py face.lz replay3.txt --no-cccd
    python generate_replay.py --verify uploader/app/src/main/assets/replay.txt
"""

import argparse
import struct
import sys
from pathlib import Path

TRAILER = bytes.fromhex("78668428")
CHUNK = 177
DATA_MAX = 175
WIN1_DATA = 149
WIN_DATA = 159
MAX_CHUNKS = 22
WIN1_FIELD = bytes.fromhex("001000000000000000000000")

CCCD_LINES = ["W 22 0100", "W 25 0100", "W 28 0100", "W 2B 0100", "W 2E 0100"]

# Command exchange the vendor app performs right before the data stream. Without it
# the watch answers every 0x2A write with 00 00 01 02 00 00 and never shows its
# transfer screen. Recovered from new_face_snoop.log.
PREAMBLE = [
    "W 24 000000000100",
    "R 24 000001010000",
    "W 24 010008533A0D220B0A03393136103218322001",
    "R 24 000001000000",
    "R 21 000000000100",
    "W 21 000001010000",
    "R 21 01000853*",
    "W 21 000001000000",
]

# Flow control. The watch is not a dumb pipe: after a window marker it answers
# 00 00 01 01 00 00 and only then takes the chunks, and after the last chunk of
# the window it answers 00 00 01 00 00 00. Sending without these gates is rejected
# with 00 00 01 02 00 00 on every write.
DATA_GO = "R 2A 000001010000"
DATA_WINDOW_DONE = "R 2A 000001000000"


def frame(container):
    """Return the ordered list of writes for handle 0x2A."""
    stream = bytes(container) + TRAILER
    writes = []
    pos = 0
    win_idx = 1

    while pos < len(stream):
        chunks = []
        seq = 1

        if win_idx == 1:
            field = WIN1_FIELD
            cap = WIN1_DATA
        else:
            field = stream[pos:pos + 12]
            pos += len(field)
            field = field.ljust(12, b"\x00")
            cap = WIN_DATA

        n = min(cap, len(stream) - pos)
        c1 = bytearray()
        c1 += struct.pack("<HHH", seq, 0x000E, win_idx)
        c1 += field
        if win_idx == 1:
            c1 += b"\x00" * 6
            c1 += struct.pack("<I", len(container))
        c1 += stream[pos:pos + n]
        pos += n
        seq += 1
        chunks.append(bytes(c1))

        while pos < len(stream) and len(chunks) < MAX_CHUNKS:
            m = min(DATA_MAX, len(stream) - pos)
            chunks.append(struct.pack("<H", seq) + stream[pos:pos + m])
            pos += m
            seq += 1

        marker = b"\x00\x00\x00\x00" + struct.pack("<H", len(chunks))
        writes.append(marker)
        writes.extend(chunks)
        win_idx += 1

    return writes


def wire_bytes(writes):
    return sum(len(w) for w in writes)


def is_marker(w):
    return len(w) == 6 and w[:4] == b"\x00\x00\x00\x00"


def gated_lines(writes):
    """Replay lines with the per-window flow control gates the watch needs."""
    lines = []
    pending = 0
    for w in writes:
        lines.append("W 2A " + w.hex().upper())
        if is_marker(w):
            pending = int.from_bytes(w[4:6], "little")
            lines.append(DATA_GO)
        else:
            pending -= 1
            if pending == 0:
                lines.append(DATA_WINDOW_DONE)
    return lines


def window_count(stream_len):
    windows = 1
    remaining = stream_len - 3824
    while remaining > 0:
        remaining -= 3846
        windows += 1
    return windows


def read_capture(path):
    out = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        parts = s.split()
        if len(parts) >= 3 and parts[0].upper() == "W" and parts[1].lower() == "2a":
            out.append(bytes.fromhex(parts[2]))
    return out


def verify(capture_path):
    """Compare our framing with a real capture (needs the face it was made from)."""
    capture = read_capture(capture_path)
    root = Path(__file__).resolve().parents[3]
    face = root / "watchface.zip"
    if not face.exists():
        raise SystemExit(f"need the reference face to verify against: {face}")

    writes = frame(face.read_bytes())
    print(f"generated writes : {len(writes)}")
    print(f"capture writes   : {len(capture)}")
    print(f"wire bytes       : {wire_bytes(writes)}")

    n = min(len(writes), len(capture))
    for i in range(n):
        if writes[i] != capture[i]:
            print(f"first difference at write {i}")
            print(f"  generated len {len(writes[i])}: {writes[i][:24].hex()}")
            print(f"  capture   len {len(capture[i])}: {capture[i][:24].hex()}")
            return 1

    extra = len(capture) - len(writes)
    if extra == 0:
        print(f"EXACT MATCH, all {n} writes")
    else:
        print(f"generated matches the first {n} writes; capture has {extra} extra write(s)")
        for w in capture[n:]:
            print(f"  extra len {len(w)}: {w[:24].hex()}  (retransmission)")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Generate a BLE replay script for a face container.")
    ap.add_argument("container", nargs="?", help="input .lz container")
    ap.add_argument("out", nargs="?", default="replay3.txt", help="output replay file")
    ap.add_argument("--no-cccd", action="store_true", help="omit the five CCCD enable lines")
    ap.add_argument("--verify", metavar="CAPTURE", help="check the framing against a real capture")
    args = ap.parse_args(argv)

    if args.verify:
        return verify(args.verify)

    if not args.container:
        ap.error("container is required unless --verify is used")

    container = Path(args.container).read_bytes()
    writes = frame(container)

    lines = []
    if not args.no_cccd:
        lines.extend(CCCD_LINES)
    lines.extend(PREAMBLE)
    lines.extend(gated_lines(writes))

    Path(args.out).write_text("\n".join(lines) + "\n", encoding="utf-8")

    markers = sum(1 for w in writes if is_marker(w))
    print(f"wrote {args.out}: {len(writes)} data writes, {wire_bytes(writes)}B on air, "
          f"{markers} windows")
    print(f"container {len(container)}B, stream {len(container) + len(TRAILER)}B, "
          f"{window_count(len(container) + len(TRAILER))} windows, "
          f"{len(gated_lines(writes))} transfer lines after gating")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
