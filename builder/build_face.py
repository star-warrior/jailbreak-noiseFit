#!/usr/bin/env python3
"""Photo -> NoiseFit Halo watch-face container, entirely offline.

This is the PC reference implementation of the pipeline that the Android app runs
on the phone (``uploader/app/src/main/java/com/jay/wfup/FacePipeline.java`` and
``FaceBuilder.java``). Same input, same bytes out.

Format, measured from a real accepted face and from the vendor code:

    container = 396-byte header + 185 LZO1X blocks of 4096 bytes

    header:
      0   4     magic "AF AF AF 02"
      4   4     u32le uncompressed size (754368)
      8   4     u32le vendor checksum of the uncompressed blob
      12  4     u32le compressed payload size
      16  4     u32le vendor checksum of the compressed payload
      20  4     u32le reserved (0)
      24  2     u16le block count (185)
      26  370   u16le per block: 0x8000 | compressed size

The uncompressed blob is the reference blob with exactly two regions replaced:

    514    .. 192714   preview 310x310 RGB565 LE   (192200 B)
    320056 .. 754368   background 466x466 RGB565 LE (434312 B)

Everything else (firmware config, fonts, layout) is carried over untouched, which
is why a reference blob is required.

Usage:
    python build_face.py photo.jpg face.lz
    python build_face.py photo.jpg face.lz --bits 4
    python build_face.py photo.jpg face.lz --ladder     # print all rung sizes
"""

import argparse
import struct
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("pip install pillow")
    raise SystemExit(1)

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lzo1x  # noqa: E402
import lzo_crc32  # noqa: E402

BLOB_SIZE = 754368
BLOCK = 4096
BLOCK_COUNT = (BLOB_SIZE + BLOCK - 1) // BLOCK
HEADER_SIZE = 396

OFF_PREVIEW = 514
PREVIEW_W, PREVIEW_H = 310, 310
LEN_PREVIEW = PREVIEW_W * PREVIEW_H * 2

OFF_BG = 320056
BG_W, BG_H = 466, 466
LEN_BG = BG_W * BG_H * 2

LADDER = (8, 6, 5, 4, 3, 2)

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_BASE = REPO_ROOT / "uploader" / "app" / "src" / "main" / "assets" / "blob.bin"


# ---------------------------------------------------------------------------
# image -> pixels
# ---------------------------------------------------------------------------

def centre_crop(im):
    w, h = im.size
    s = min(w, h)
    left = (w - s) // 2
    top = (h - s) // 2
    return im.crop((left, top, left + s, top + s))


def to_rgb565(im, w, h):
    """Centre-crop, resize and encode as RGB565 little endian."""
    px = centre_crop(im).convert("RGB").resize((w, h), Image.BILINEAR).load()
    out = bytearray(w * h * 2)
    i = 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            out[i] = v & 0xFF
            out[i + 1] = (v >> 8) & 0xFF
            i += 2
    return bytes(out)


def quantize(im, bits):
    """Reduce each channel to ``bits`` bits, no dithering (dither costs bytes)."""
    levels = (1 << bits) - 1
    rgb = centre_crop(im).convert("RGB")
    px = rgb.load()
    out = Image.new("RGB", rgb.size)
    op = out.load()
    lut = [((v * levels // 255) * 255 // levels) for v in range(256)]
    for y in range(rgb.size[1]):
        for x in range(rgb.size[0]):
            r, g, b = px[x, y]
            op[x, y] = (lut[r], lut[g], lut[b])
    return out


# ---------------------------------------------------------------------------
# blob / container
# ---------------------------------------------------------------------------

def load_base(path):
    data = Path(path).read_bytes()
    if len(data) != BLOB_SIZE:
        raise SystemExit(f"reference blob is {len(data)}B, expected {BLOB_SIZE}B: {path}")
    return data


def splice(base, bg565, prev565):
    if len(bg565) != LEN_BG:
        raise SystemExit(f"background is {len(bg565)}B, expected {LEN_BG}B")
    if len(prev565) != LEN_PREVIEW:
        raise SystemExit(f"preview is {len(prev565)}B, expected {LEN_PREVIEW}B")
    blob = bytearray(base)
    blob[OFF_BG:OFF_BG + LEN_BG] = bg565
    blob[OFF_PREVIEW:OFF_PREVIEW + LEN_PREVIEW] = prev565
    return bytes(blob)


def pack(blob):
    """Compress the blob and wrap it in the afafaf02 container."""
    if len(blob) != BLOB_SIZE:
        raise SystemExit(f"blob is {len(blob)}B, expected {BLOB_SIZE}B")

    payload = bytearray()
    sizes = []
    for off in range(0, BLOB_SIZE, BLOCK):
        chunk = lzo1x.compress(blob[off:off + BLOCK])
        if len(chunk) >= 0x8000:
            raise SystemExit(f"block at {off} compressed to {len(chunk)}B, exceeds 0x7fff")
        sizes.append(len(chunk))
        payload += chunk

    header = bytearray(HEADER_SIZE)
    header[0:4] = b"\xAF\xAF\xAF\x02"
    struct.pack_into("<I", header, 4, BLOB_SIZE)
    struct.pack_into("<I", header, 8, lzo_crc32.compute(blob))
    struct.pack_into("<I", header, 12, len(payload))
    struct.pack_into("<I", header, 16, lzo_crc32.compute(payload))
    struct.pack_into("<I", header, 20, 0)
    struct.pack_into("<H", header, 24, BLOCK_COUNT)
    for i, n in enumerate(sizes):
        struct.pack_into("<H", header, 26 + i * 2, 0x8000 | n)

    return bytes(header) + bytes(payload)


def unpack(container):
    """Decode a container back to the uncompressed blob and verify its structure."""
    if container[:4] != b"\xAF\xAF\xAF\x02":
        raise ValueError("bad magic")
    unc, crc8, comp, crc16, reserved = struct.unpack_from("<IIIII", container, 4)
    count = struct.unpack_from("<H", container, 24)[0]
    if unc != BLOB_SIZE:
        raise ValueError(f"uncompressed size {unc} != {BLOB_SIZE}")
    if count != BLOCK_COUNT:
        raise ValueError(f"block count {count} != {BLOCK_COUNT}")
    if reserved != 0:
        raise ValueError(f"reserved field {reserved} != 0")
    if HEADER_SIZE + comp != len(container):
        raise ValueError(f"payload size {comp} != file {len(container) - HEADER_SIZE}")

    payload = container[HEADER_SIZE:]
    if lzo_crc32.compute(payload) != crc16:
        raise ValueError("payload checksum mismatch (field 16)")

    blob = bytearray()
    pos = 0
    for i in range(count):
        size = struct.unpack_from("<H", container, 26 + i * 2)[0] & 0x7FFF
        expect = min(BLOCK, BLOB_SIZE - i * BLOCK)
        block = lzo1x.decompress(payload[pos:pos + size])
        if len(block) != expect:
            raise ValueError(f"block {i} decoded {len(block)}B, expected {expect}B")
        blob += block
        pos += size
    if pos != comp:
        raise ValueError(f"block table covers {pos}B of {comp}B payload")

    blob = bytes(blob)
    if lzo_crc32.compute(blob) != crc8:
        raise ValueError("blob checksum mismatch (field 8)")
    return blob


# ---------------------------------------------------------------------------
# pipeline
# ---------------------------------------------------------------------------

def build(im, base, bits=8):
    """Return (container, blob) for one quality rung."""
    src = im if bits >= 8 else quantize(im, bits)
    bg = to_rgb565(src, BG_W, BG_H)
    pv = to_rgb565(im, PREVIEW_W, PREVIEW_H)
    blob = splice(base, bg, pv)
    return pack(blob), blob


def main(argv=None):
    ap = argparse.ArgumentParser(description="Build a NoiseFit Halo face container offline.")
    ap.add_argument("image", help="input photo (any format Pillow reads)")
    ap.add_argument("out", help="output .lz container")
    ap.add_argument("--base", default=str(DEFAULT_BASE),
                    help=f"reference 754368-byte blob (default: {DEFAULT_BASE})")
    ap.add_argument("--bits", type=int, default=8, choices=list(LADDER),
                    help="bits per channel for the background (default 8)")
    ap.add_argument("--ladder", action="store_true",
                    help="build every rung and print the sizes instead of writing one file")
    args = ap.parse_args(argv)

    base = load_base(args.base)
    im = Image.open(args.image)

    if args.ladder:
        print(f"{'bits':>4}  {'container':>9}")
        for bits in LADDER:
            container, _ = build(im, base, bits)
            print(f"{bits:>4}  {len(container):>9}")
        return 0

    container, blob = build(im, base, args.bits)
    Path(args.out).write_bytes(container)

    print(f"wrote {args.out}: {len(container)}B (payload {len(container) - HEADER_SIZE}B)")
    print(f"field8  {struct.unpack_from('<I', container, 8)[0]:08X}  blob checksum")
    print(f"field16 {struct.unpack_from('<I', container, 16)[0]:08X}  payload checksum")

    back = unpack(container)
    if back != blob:
        raise SystemExit("SELF CHECK FAILED: container does not decode to the intended blob")
    print(f"self check OK: {len(back)}B round-trip identical")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
