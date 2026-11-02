<div align="center">

# Approach Report

**How the NoiseFit Halo face transfer was investigated: the routes that failed, the
measurements that replaced them, and the working pipeline.**

![Reverse Engineering](https://img.shields.io/badge/REVERSE%20ENGINEERING-1F2937?style=for-the-badge)
![Android](https://img.shields.io/badge/ANDROID-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Bluetooth LE](https://img.shields.io/badge/BLUETOOTH%20LE-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white)
![Python](https://img.shields.io/badge/PYTHON-3776AB?style=for-the-badge&logo=python&logoColor=white)

</div>

> **Reading note.** This is a historical and methodological record. Several early
> claims were later disproved: FastLZ (actually LZO1X), 48-byte chunks (actually
> 177), a fixed 396-byte header (actually `26 + 2n`), an unexplained CRC trailer
> (actually `CRC32(prefix22 ‖ container)`), little-endian pixels (actually big
> endian), and "image conversion partially solved" (now verified, with one known
> cosmetic issue). Superseded passages are marked. For the measured format see
> [`PROTOCOL.md`](PROTOCOL.md); for the evidence-based report see
> [`TECHNICAL_REPORT.md`](TECHNICAL_REPORT.md).

---

## Contents

1. [Summary](#1-summary)
2. [Goal and constraints](#2-goal-and-constraints)
3. [Phase 1 — The official app](#3-phase-1--the-official-app)
4. [Phase 2 — Routes that failed](#4-phase-2--routes-that-failed)
5. [Phase 3 — Cracking the transfer](#5-phase-3--cracking-the-transfer)
6. [Phase 4 — Cracking the container](#6-phase-4--cracking-the-container)
7. [Phase 5 — Mapping the blob](#7-phase-5--mapping-the-blob)
8. [Phase 6 — The phone-only pipeline](#8-phase-6--the-phone-only-pipeline)
9. [Results](#9-results)
10. [What is still open](#10-what-is-still-open)
11. [Lessons](#11-lessons)

---

## 1. Summary

The official app gates custom photo faces behind a paid flow and compiles them
with a closed native library. Instead of fighting the compiler, we captured a real
accepted transfer, replayed it, then rebuilt the container from first principles.

What now works:

- byte-exact BLE replay, including the mandatory command session and per-window
  flow control;
- independent rebuild of the `afafaf02` container with LZO1X and the vendor
  checksum;
- a mapped 754 368-byte blob, proven region by region on hardware;
- a phone-only pipeline: photo → crop → face → upload, offline.

What does not: the glyph cells still carry a black backing that shows as boxes
over a photo, and the widget table is only partially decoded.

---

## 2. Goal and constraints

**Goal.** Turn any photo into a watch face and install it over BLE, entirely from
the phone, offline.

Constraints that shaped every decision:

- no account, server or internet at runtime;
- no root;
- no vendor compiler;
- reuse the existing BLE path, not a new pairing scheme;
- test on hardware we can recover;
- be explicit about what is verified and what is assumed.

---

## 3. Phase 1 — The official app

### What was done

Decompiled `noisefit_base.apk` with jadx, read the face-transfer classes, and
captured a real transfer with the Android Bluetooth HCI snoop log plus `adb
logcat`.

### What was found

The relevant code lives under `com.zhapp.ble`:

- `ThemeManager` builds the transfer buffer, sets the piece count and appends the
  CRC32 tail.
- `LzoUtils` assembles the container.
- `LzoCrcUtils` computes the two vendor checksums.
- The native face compiler is a `.so` invoked with a package directory of JSON,
  fonts and previews.

The APK also contains the intended widget schema under
`assets/dial/style/OSW-802N/template1..4.json` — `textbox` widgets with
`font_color`, `font_bkcolor`, `font_size`, `align`, `alpha` and `zorder`.

### Outcome

The app depends on a native compiler at runtime for *authoring*, but the resulting
file is a plain container that the BLE layer merely frames. If the container could
be produced independently, the compiler could be bypassed entirely.

---

## 4. Phase 2 — Routes that failed

### 4.1 Calling the native library directly

**Attempt.** Run the vendor `.so` from a small `app_process` harness
(`WFCompilerRunner`, `Ctx`, `Protocol`) with a generated input blob.

**Result.** Null dereference. The compiler expects a package directory — config
JSON, `fonts/`, `preview/` — that only the app assembles. Reconstructing that
directory is possible but fragile and phone-specific.

**Lesson.** A native authoring library is a poor dependency when the output format
can be reproduced. The library was kept only as an oracle for comparison.

### 4.2 Patching and re-signing the APK

**Attempt.** Remove the paywall check and re-sign.

**Result.** Rejected as a distribution route: signature changes break updates,
redistribution is legally murky, and the result is still tied to the vendor
pipeline.

### 4.3 A Termux-only workflow

**Attempt.** Run the Python builder on the phone.

**Result.** Workable in principle, but a second runtime to install and maintain.
The final app embeds the same logic in Java instead, so the phone needs nothing
beyond the APK.

### 4.4 FastLZ

**Attempt.** Early blocks looked like FastLZ, so a FastLZ decoder and encoder were
written. *(Superseded.)*

**Result.** FastLZ decompression produced garbage on real faces. LZO1X round-tripped
the vendor stream exactly. The mistake came from pattern-matching token bytes
instead of testing a decoder against real output.

### 4.5 Fixed-size container header

**Attempt.** Assume the 396-byte header observed on the reference face is
constant. *(Superseded.)*

**Result.** Faces with a different block count were rejected. The header is
`26 + 2 * blockCount`; 396 bytes is just 185 blocks.

### 4.6 Sending data without the command session

**Attempt.** Write the framed stream to `0x2A` immediately after enabling
notifications.

**Result.** Every write answered `00 00 01 02 00 00`. The watch never entered
transfer mode. The missing piece was the `53 3A` face-transfer command on
`0x24`/`0x21` and its credit exchange.

---

## 5. Phase 3 — Cracking the transfer

### The capture

`Docs/btsnoop_hci.log` plus `Docs/wf_transcript.txt` record one accepted transfer
write by write. Filtering ATT in Wireshark gives a clean story:

1. CCCD enables on `0x22 0x25 0x28 0x2B 0x2E`;
2. the command session on `0x24`/`0x21`;
3. windows on `0x2A`, each a 6-byte marker, up to 22 chunks of 177 bytes, and two
   notifications per window;
4. the transfer ends without a separate commit command.

### Replay

A deliberately simple text replay format was adopted:

```
W <handle> <hex>
R <handle> <expected notification hex>
```

It replays one line at a time and logs every step. The first complete replay
produced the same face on the watch. Later, `ReplayBuilder` regenerated all 302
data writes of the capture identically — proof that the framing was understood,
not merely copied.

### The tail

The 4-byte tail was the last unknown. It is
`CRC32(prefix22 ‖ container)`, little endian, built in
`ThemeManager.initUpload`. Two different real faces matched the formula, which
settled it.

*(Superseded: earlier notes called this an unexplained constant and tried several
CRC variants by brute force. The answer was in the code path, not in the bytes.)*

---

## 6. Phase 4 — Cracking the container

### Decompression

The payload is independent LZO1X blocks of 4096 bytes. Decoding the vendor face
produced a 754 368-byte blob whose regions looked like images.

### Rebuild

`FaceBuilder` reproduces the header, compresses each block, writes the two vendor
checksums and self-checks by decompressing its own output. The first accepted DIY
face was a solid colour built this way.

### Checksums

Fields 8 and 16 are not a polynomial CRC. They are a table-driven checksum with a
256-entry vendor S-box (`table[0] == 0x60454269`). Standard CRC parameter searches
never match it. The implementation was lifted from `LzoCrcUtils` and verified
against real containers.

### Byte order

*(Superseded: pixels were initially assumed little endian.)* A greyscale ramp
uploaded in both orders proved big endian: high byte first. Little endian looks
like a channel swap, not a byte swap, which is why it was misdiagnosed at first.

---

## 7. Phase 5 — Mapping the blob

The blob was mapped by mutation, one region at a time. Replace a region with a
grid, upload, observe.

| Region | Probe result | Conclusion |
| --- | --- | --- |
| `514 .. 192714` | no visible change | face-list preview only |
| `192714 .. 320056` | clock bar lights up, digits crisp | stored bar image |
| `320056 .. 627616` | lower 330 rows change | artwork block |
| `627616 .. 754368` | digits become solid cells | glyph sprite sheets |

This produced the screen model used by the app: rows 0–135 from the mid block,
rows 136–465 from the artwork, sprites on top. A full-bleed photo therefore splits
at row 136 with no scaling.

The widget table at `0x180` was decoded from the config header: 13-byte records of
`x, y, w, h, kind, sheet`. The sheet arithmetic tiles the atlas exactly, and a
second vendor face with cyan hours and white minutes proved colour is baked into
separate sheets rather than stored per widget.

*(Superseded: an earlier version stretched a single 466×466 image into one region,
which produced a visible seam. The seam disappeared once the two-block split was
applied.)*

---

## 8. Phase 6 — The phone-only pipeline

The final app does everything on the phone:

1. pick a photo;
2. crop in a square viewport the size of the round screen (drag + pinch);
3. compose 466×466 RGB565 big-endian;
4. regenerate the preview;
5. splice into the blob at row 136;
6. LZO1X, header, checksums;
7. self-check by decompressing and comparing every byte;
8. frame and upload with flow control.

No server, no Python, no root. The PC Python tools remain for analysis and for
generating replay scripts, and produce the same byte count as the Java path on the
same blob.

---

## 9. Results

| Area | State |
| --- | --- |
| BLE framing | Verified byte for byte |
| Command session | Verified |
| Flow control | Verified |
| Container header/checksums | Verified |
| Blob layout | Verified per region |
| RGB565 byte order | Verified |
| Photo face | Verified: colours, orientation, live clock |
| Full-bleed geometry | Verified |
| Crop dialog | Verified |
| Clock cell backgrounds | Open |
| Metadata widgets | Open |
| Other models | Untested |

The first photo face rendered correctly on hardware, with the clock live and the
image continuous across the bar/artwork seam. That is the point at which the
project moved from "transport works" to "the pipeline works".

---

## 10. What is still open

1. **Black cell backgrounds.** The sprite cells store a black backing. Two
   candidate causes: the firmware blits cells opaque, or it fills the widget rect
   from a config field. The `PLATE PROBE` build paints sheet backgrounds in
   colour to distinguish them. If opaque, the fix is per-position sheets with the
   photo painted into each cell (about 324 kB more blob); if config, the fix is
   the binary `font_bkcolor` field.
2. **Widget record fields.** Only geometry, kind and sheet offset are mapped.
3. **Metadata widgets.** Steps, battery, heart rate and weekday require a larger
   atlas and possibly a different container type (`0214` vs `0203`).
4. **Other models.** The protocol shape is likely shared across the vendor's
   Veryfit-based line, but every value must be re-measured per model.

---

## 11. Lessons

- **Replay the whole accepted sequence.** The bulk writes were correct for a long
  time; the missing command session made them useless.
- **Test decoders against real output.** FastLZ looked plausible and was wrong.
- **Read the code path, don't brute-force the bytes.** The CRC tail and the vendor
  S-box were both sitting in the APK.
- **Change one variable per experiment.** Byte order, geometry and region mapping
  each took one clean test once isolated.
- **A header match is not a rendering proof.** Only the watch proves the face.
- **Keep a self-check in the build.** Decompress and compare before sending; it
  turns a watch-side mystery into a local error.
- **Write down status honestly.** "Verified on the Halo" and "assumed from the
  Halo" are different claims, and confusing them wastes time on new models.
