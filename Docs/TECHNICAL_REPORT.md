# Technical report — NoiseFit Halo watch faces, reverse engineered

**Scope.** This document records how the NoiseFit Halo watch-face container and
its Bluetooth LE transfer were reverse engineered, what was measured, and how each
claim is supported. The authoritative byte layout is
[`PROTOCOL.md`](PROTOCOL.md); this report explains the evidence and the method.

**Result.** A photo can be converted to a native face and uploaded from an Android
phone, offline, without the vendor compiler or a paid app flow. Transport and
container are reproduced exactly. Rendering is correct for colours, orientation
and a live clock. One cosmetic issue remains: the glyph cells carry a black
backing, visible as boxes over a photo.

**Status vocabulary.** *Verified* = reproduced on hardware or from vendor code.
*Observed* = seen once and consistent with everything else. *Assumed* = a
hypothesis, not yet tested.

---

## 1. The problem

The official app offers custom photo faces behind a paid flow and compiles the
face with a closed native library (`.so`). The library needs a package directory
of JSON, fonts and previews that the app assembles at runtime; calling it directly
crashes without that environment. Patching and re-signing the vendor APK is not a
workable distribution route.

The alternative is to understand the two artifacts:

1. the **face container** the watch stores and renders;
2. the **BLE transfer** that installs it.

Both had to be solved without the compiler.

---

## 2. Method

The work followed a strict evidence ladder. Every rung was completed on hardware
before the next was attempted.

1. **Capture a known-good transfer.** Bluetooth HCI snoop log while the official
   app installed a face, plus `adb logcat` for the human-readable story.
2. **Replay it verbatim.** A text replay script (`W <handle> <hex>`,
   `R <handle> <pattern>`) reproduced the official transfer byte for byte. This
   proved the capture was complete and the framing understood.
3. **Rebuild the container.** Decompress the vendor face, reproduce every header
   field, then recompress and compare sizes and bytes.
4. **Mutate one region at a time.** Replace a single region of the uncompressed
   blob with a recognisable pattern, upload, and observe what changes on the
   watch. This maps the blob without guessing.
5. **Change one variable at a time.** Byte order, then geometry, then clock bar,
   then glyph cells. Each experiment changed exactly one thing.
6. **Keep the decoder in the loop.** The app decompresses its own container and
   compares every byte before sending. A build that fails self-check is never
   uploaded.

Tools: Wireshark (btsnoop/ATT), jadx (APK), Python 3 with Pillow, PowerShell,
Android Studio, and a BLE client in plain Java.

---

## 3. Capture and framing

### 3.1 GATT layout

The vendor face service is a custom 128-bit service:

```
wire UUID bytes : FB 34 9B 7F 80 00 00 80 00 10 00 00 00 6F 18 xx
host order      : 16186Fxx-0000-1000-8000-00807F9B34FB
```

| Handle | Characteristic | Role |
| --- | --- | --- |
| `0x0021` | `16186F01` | command channel A |
| `0x0024` | `16186F02` | command channel B |
| `0x0027` | `16186F03` | unused |
| `0x002A` | `16186F04` | data stream |
| `0x002D` | `16186F05` | side channel |

All five have notify + write-without-response (`0x14`). The transfer uses MTU
247, so each write carries 177 bytes.

### 3.2 Command session

Before the file, the watch must be told to expect a face. The sequence is a
session start, a command, an ack, then a credit exchange on the other channel:

```
W 24 00 00 00 00 01 00
R 24 00 00 01 01 00 00
W 24 01 00 08 53 3A 0D 22 0B 0A 03 39 31 36 10 32 18 32 20 01
R 24 00 00 01 00 00 00
R 21 00 00 00 00 01 00
W 21 00 00 01 01 00 00
R 21 01 00 08 53 3A ...
W 21 00 00 01 00 00 00
```

`53 3A` is "transfer this face". Its protobuf field 1 is the face id as a string
(`"916"`, the id of the reference face), fields 2 and 3 are `50`, field 4 is `1`.
The reply body is `53 3A 04 2A 02 08 00`, status `00`.

Skipping this session, or leaving the official app connected, makes the watch
reject every `0x2A` write with `00 00 01 02 00 00`. This was the single biggest
time sink in the project: the data framing was correct long before the session
was understood.

### 3.3 Stream and windows

The data stream is:

```
prefix(22) ‖ container ‖ CRC32(prefix ‖ container)(4, little endian)
```

`prefix` = `00 10` + 16 zero bytes + container length as `u32le`. The trailing
CRC32 was recovered from `com.zhapp.ble.manager.ThemeManager.initUpload`: it is a
standard zlib CRC32, not the vendor S-box used inside the container. This closed
the last "unexplained constant" in the protocol.

The stream is split into windows of at most 22 chunks. Each window starts with a
6-byte marker and ends when the watch sends a done notification:

```
marker  : 00 00 00 00 <chunkCount u16le>
chunk 1 : <seq u16le> 0E 00 <winIdx u16le> <12-byte field> [meta] <data>
chunk N : <seq u16le> <data>
```

Window 1 is special: the 12-byte field is constant
`00 10 00 00 00 00 00 00 00 00 00 00`, followed by a 10-byte meta containing the
container length. Later windows use their own first 12 payload bytes as the field,
then re-emit them. Payload per window is 3824 bytes for window 1 and 3846 bytes
afterwards. The advertised piece count is
`ceil((containerLength + 26) / 3846)`.

Flow control, measured from the accepted capture:

```
marker      → wait for 00 00 01 01 00 00 on 0x2A   (~80 ms)
all chunks  → wait for 00 00 01 00 00 00 on 0x2A   (~180–480 ms)
```

One 22-chunk window takes about 0.56 s. Flooding without waiting produces the
`01 02` rejection pattern.

**Evidence.** `ReplayBuilder.java` regenerates the entire accepted transfer — all
302 data writes — byte for byte. The reconstructed stream is 50 287 bytes for a
50 283-byte container, which matches prefix + container + CRC32 exactly.

---

## 4. Container

### 4.1 Layout

```
0     4   magic "AF AF AF 02"
4     4   u32le uncompressed size        754368
8     4   u32le vendor checksum of blob
12    4   u32le compressed payload size
16    4   u32le vendor checksum of payload
20    4   u32le reserved
24    2   u16le block count              185
26    2n  u16le block entries            0x8000 | compressed size
26+2n     payload                        n LZO1X blocks of 4096 bytes
```

The header is **dynamic**: `26 + 2 * blockCount`. The reference face has 185
blocks, so the header is 396 bytes; `396 + 49 887 = 50 283`, the real file size.
Assuming a fixed 396-byte header works only for blobs of that exact size, which is
why early synthetic faces were rejected.

### 4.2 Compression

Each 4096-byte block is compressed independently with **LZO1X**. The vendor uses
`lzo1x_1_compress` from LZO 2.x. The repo contains both a Java (`Lzo1x.java`) and a
Python (`lzo1x.py`) codec; both round-trip the vendor's own streams and produce
slightly smaller output on the reference blob.

Early work assumed FastLZ because the compressed blocks begin with similar-looking
tokens. FastLZ decompression produced garbage on real faces; the LZO1X decoder did
not. That was the decisive test.

### 4.3 Vendor checksums

Fields 8 and 16 are not a standard CRC. From
`com.zhapp.ble.utils.lzo.LzoCrcUtils`:

```java
long crc = 0xFFFFFFFFL;
for (byte b : data)
    crc = table[((int) crc ^ b) & 0xFF] ^ ((crc >> 8) & 0xFFFFFF);
return littleEndianBytes(~crc);
```

`table` is a 256-entry vendor S-box (`table[0] == 0x60454269`, not zero). No
standard CRC parameter set matches it, which is why brute-forcing CRC variants
failed. The implementation is `LzoCrc32.java` / `lzo_crc32.py`.

Field 8 covers the uncompressed blob, field 16 covers the compressed payload.
`LzoUtils.mergeDialFile([B)[B` returns exactly this container; the 4-byte tail is
added later by the BLE layer.

A caveat worth recording: the cloud copy of the reference face carries
`76329039` / `4B97A128`, while the function above yields `9EA8CB0B` / `57317C02`.
The vendor server therefore uses a different path. The app's own DIY uploads use
the function and are accepted, so the app form is the one implemented here.

---

## 5. Uncompressed blob

### 5.1 Regions

Reference face A, 754 368 bytes. All pixel data is **RGB565 big endian**, stride
`width * 2`.

| Range | Size | Content |
| --- | --- | --- |
| `0 .. 514` | 514 | config header, widget table at `0x180` |
| `514 .. 192714` | 192 200 | preview 310×310 |
| `192714 .. 320056` | 127 342 | clock-bar background 466×136 + 590-byte tail |
| `320056 .. 627616` | 307 560 | artwork 466×330 |
| `627616 .. 754368` | 126 752 | glyph sprite sheets |

Each region was proven independently by replacing it with a grid or solid pattern
and observing the watch:

- preview only → no visible change; it is the face-list thumbnail.
- clock-bar block → the bar lights up; digits stay crisp, so the bar is a stored
  image, not a firmware rectangle.
- artwork block → only the lower 330 rows change; the clock keeps running.
- atlas block → digits become solid cells; keeping the atlas stock keeps the time
  live.

### 5.2 Screen composition

```
rows   0 .. 135   ← mid block  466×136
rows 136 .. 465   ← artwork    466×330
all rows          ← sprite sheets blitted on top
```

`136 + 330 = 466`, so a full-bleed 466×466 photo splits at row 136 with no scaling
and no displacement. This was confirmed on hardware after an earlier version
stretched one region and produced a visible seam.

### 5.3 Pixel byte order

RGB565, high byte first. A greyscale ramp uploaded in both orders settled it: big
endian produces a smooth ramp, little endian produces obviously wrong colours. The
result is consistent with the vendor code and with every subsequent face.

### 5.4 Widget table

The config header contains a pointer at `0x0062` (u16 BE) = `0x0180` (384). At
that offset is a table of 13-byte records, terminated by a zero record:

```
u16 x
u16 y
u16 w
u16 h
u8  kind
u32 sheet offset
```

Face A decodes to:

| rect (x, y, w, h) | kind | sheet offset | bytes |
| --- | --- | --- | --- |
| 204, 23, 60, 30 | 4 (date) | 627648 | 14 400 = 4 cells |
| 86, 69, 60, 90 | 10 (digit) | 642048 | 108 000 = 10 cells |
| 154, 69, 60, 90 | 10 | 642048 | shared |
| 222, 69, 24, 90 | 1 (colon) | 750048 | 4 320 = 1 cell |
| 254, 69, 60, 90 | 10 | 642048 | shared |
| 322, 69, 60, 90 | 10 | 642048 | shared |

The arithmetic tiles the atlas with no gaps:

```
digits : 10 × 60 × 90 × 2 = 108 000
date   :  4 × 60 × 30 × 2 =  14 400
colon  :  1 × 24 × 90 × 2 =   4 320
```

Face B (cyan hours, white minutes) has the same six records with different
geometry (y = 64, h = 100, w = 70) and **two** digit sheet offsets. Colour is
baked into the pixels, not stored in the record. That is the key insight for
customisation: to change the clock colour you regenerate the sheet, and to change
the background behind the clock you must change what the sheet contains or what
the firmware fills.

The vendor APK documents the intended schema in
`assets/dial/style/OSW-802N/template1..4.json`:

```json
{ "widget": "textbox", "data": "time_hour24", "view": [x, y, w, h],
  "font_color": "...", "font_bkcolor": "...", "font_type": "...",
  "font_size": 90, "align": "...", "alpha": 255, "zorder": 1 }
```

So positions, sizes, colours and backgrounds are config in the vendor pipeline.
The binary form of those fields is only partially mapped; `font_bkcolor` has no
confirmed offset yet.

---

## 6. Building a face offline

The shipped app (`android/`) performs the whole pipeline:

1. pick a photo;
2. crop with a square viewport the size of the round screen (drag + pinch), so
   nothing is squashed;
3. compose a 466×466 RGB565 big-endian screen;
4. render the 310×310 preview from the same screen;
5. splice rows 0–135 into the mid block and 136–465 into the artwork;
6. leave the config header and sprite sheets stock;
7. split into 4096-byte blocks, LZO1X each, build the dynamic header, compute both
   vendor checksums;
8. decompress the result and compare every byte (self-check);
9. frame with `ReplayBuilder` and write over BLE with flow control.

The build and self-check take well under a second on a mid-range phone. The
transfer is dominated by the watch: roughly 0.56 s per 3846-byte window.

Container sizes: a typical photo face is 100–200 kB after compression; a noisy
random-pixel face can exceed 400 kB. The app keeps a 400 000-byte sanity gate with
an explicit override.

---

## 7. What went wrong, and what it taught

| Assumption | Reality | Lesson |
| --- | --- | --- |
| Compression is FastLZ | LZO1X | Round-trip the vendor stream, don't pattern-match magic bytes |
| Header is fixed 396 bytes | `26 + 2n` | Read block count first, then derive offsets |
| Trailer is an unexplained constant | `CRC32(prefix ‖ container)` | Find the code path that builds it |
| Data framing alone is enough | `53 3A` session is mandatory | Replay the *whole* accepted sequence, not just the bulk writes |
| Pixels are little endian | Big endian | Greyscale ramp, one variable at a time |
| One 466×466 image fills the blob | Screen is two stacked blocks | Map regions with targeted probes |
| Atlas drives the clock background | Atlas holds sprites; bar is a stored image | Change one region; observe |
| A matching header proves a good face | It proves acceptance only | Only a photo on the watch proves rendering |

The most expensive mistakes were the ones that produced *plausible* output: a
header that parsed, a stream that decompressed to the right length, a face that
uploaded without error but rendered wrongly.

---

## 8. Reproducibility

Everything needed to repeat the result is in this repository:

- `android/` — complete app, no external dependencies, builds with JDK 17 + SDK 34.
- `builder/` — Python reference implementation of the same container.
- `watchfaces/` — pre-generated containers for transport bring-up.
- `Docs/btsnoop_hci.log` — HCI capture of an accepted transfer.
- `Docs/wf_transcript.txt` — write-by-write transcript.
- `Docs/PROTOCOL.md` — byte layout.
- `profiles/halo.json` — every model-specific value with a status field.

Checks a reviewer can run:

```bash
python builder/verify_build.py builder/../../watchfaces/face_solid.lz
```

and, with a watch, replay `face_solid.lz` first. A flat colour on the watch proves
transport, container framing and flow control before any image code is involved.

---

## 9. Remaining work

1. **Clock cell backgrounds.** The glyph sheets store a black backing. Either the
   firmware blits cells opaque (fix: per-position sheets with the photo painted
   into each cell) or it fills the widget rect from config (fix: the binary
   `font_bkcolor` field). The `PLATE PROBE` build paints the sheet backgrounds in
   colour to distinguish the two. Outcome pending.
2. **Widget record fields.** Only `x, y, w, h, kind, sheet` are mapped. Sizes,
   colours, alpha and z-order for non-time widgets are unknown.
3. **Metadata widgets.** Steps, battery, heart rate and weekday need extra sheets
   in a larger atlas. The container type field (`0214` vs `0203`) may gate it;
   untested whether the Halo accepts a larger layout.
4. **Other models.** The protocol shape is likely shared across the vendor's
   Veryfit-based line, but handles, sizes, magic and layout must be re-measured
   per model. A profile template and procedure are in
   `skills/jailbreak-noiseFit/skill.md`.

---

## 10. Ethics and scope

This is interoperability work on hardware we own, using artwork we own or have
licensed. It is not affiliated with Noise. Vendor assets are analysed, not
redistributed for use. Published captures are scrubbed of MAC addresses, account
ids and tokens. A malformed face can leave a watch showing a broken face; recovery
is normally a re-sync through the official app or a factory reset, and every test
device was recoverable.
