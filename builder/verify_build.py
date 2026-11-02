#!/usr/bin/env python3
"""Check a face container before it goes to the watch.

Verifies the container structure, both vendor checksums, and that every block
decodes back to the exact blob. With ``--base`` it also confirms that only the
two image regions differ from the reference blob.

Usage:
    python verify_build.py face.lz
    python verify_build.py face.lz --base ../assets/blob.bin
"""

import argparse
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_face  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description="Verify a NoiseFit Halo face container.")
    ap.add_argument("face", help="container to check")
    ap.add_argument("--base", help="reference blob to diff against (optional)")
    args = ap.parse_args(argv)

    container = Path(args.face).read_bytes()
    problems = []

    print(f"file        {args.face}")
    print(f"size        {len(container)}B")

    try:
        blob = build_face.unpack(container)
    except ValueError as e:
        print(f"FAIL        {e}")
        return 1

    unc, crc8, comp, crc16, reserved = struct.unpack_from("<IIIII", container, 4)
    count = struct.unpack_from("<H", container, 24)[0]

    print(f"magic       {container[:4].hex().upper()}")
    print(f"uncompressed {unc}B")
    print(f"payload     {comp}B")
    print(f"blocks      {count}")
    print(f"field8      {crc8:08X}  (blob checksum, verified)")
    print(f"field16     {crc16:08X}  (payload checksum, verified)")
    print(f"reserved    {reserved}")

    sizes = [struct.unpack_from("<H", container, 26 + i * 2)[0] & 0x7FFF for i in range(count)]
    print(f"block sizes min {min(sizes)}B, max {max(sizes)}B, sum {sum(sizes)}B")

    if args.base:
        base = Path(args.base).read_bytes()
        if len(base) != build_face.BLOB_SIZE:
            problems.append(f"reference blob is {len(base)}B, expected {build_face.BLOB_SIZE}B")
        else:
            bg_same = blob[build_face.OFF_BG:build_face.OFF_BG + build_face.LEN_BG] == \
                base[build_face.OFF_BG:build_face.OFF_BG + build_face.LEN_BG]
            pv_same = blob[build_face.OFF_PREVIEW:build_face.OFF_PREVIEW + build_face.LEN_PREVIEW] == \
                base[build_face.OFF_PREVIEW:build_face.OFF_PREVIEW + build_face.LEN_PREVIEW]
            head_same = blob[:build_face.OFF_PREVIEW] == base[:build_face.OFF_PREVIEW]
            mid_same = blob[build_face.OFF_PREVIEW + build_face.LEN_PREVIEW:build_face.OFF_BG] == \
                base[build_face.OFF_PREVIEW + build_face.LEN_PREVIEW:build_face.OFF_BG]

            print()
            print(f"config region (0..514)        {'unchanged' if head_same else 'CHANGED'}")
            print(f"preview  (514..192714)        {'unchanged' if pv_same else 'replaced'}")
            print(f"padding  (192714..320056)     {'unchanged' if mid_same else 'CHANGED'}")
            print(f"background (320056..754368)   {'unchanged' if bg_same else 'replaced'}")

            if not head_same:
                problems.append("firmware config region differs from the reference blob")
            if not mid_same:
                problems.append("padding region differs from the reference blob")

    print()
    if problems:
        for p in problems:
            print(f"FAIL  {p}")
        return 1
    print("OK: structure, both checksums and full decode round-trip verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
