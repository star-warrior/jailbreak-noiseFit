# NoiseFit Halo Face Builder

Build custom watch faces for a **NoiseFit Halo** and install them over Bluetooth
LE — entirely offline, with no account, no server, no root, and no vendor compiler.

The whole pipeline runs on the phone: pick a photo, crop it the way the vendor app
does, and the app composes a native face container and uploads it using the exact
write sequence the official app uses.

> Independent research project. Not affiliated with, endorsed by, or supported by
> Noise. "NoiseFit" and "Halo" are trademarks of their respective owners.

---

## Status

| Area | State | Notes |
| ---- | ----- | ----- |
| BLE transport | **Verified** | Reproduces all 302 data writes of a real accepted transfer byte for byte |
| Container format | **Verified** | Dynamic `26 + 2n` header, two vendor checksums, LZO1X per 4096-byte block |
| Blob layout | **Verified** | Every image region confirmed on hardware with a targeted probe |
| Photo → face on phone | **Verified** | Photo renders with correct colours, correct orientation and a live clock |
| Full-bleed geometry | **Verified** | Photo is continuous across the clock bar / artwork seam, no scaling or offset |
| Square crop dialog | **Verified** | Drag and pinch, nothing squashed to fit |
| Clock cell backgrounds | **Fixed, watch check pending** | Each widget now gets its own glyph sheet with the photo painted into every cell, so the cells blit the photo instead of a black box |
| Other models | **Untested** | Only the Halo, on one phone, has been exercised |

Photo face size: the stock blob is 754 368 B and the per-widget-sheet build is
1 078 368 B before LZO1X. The app keeps a 2 000 000 B sanity gate with an explicit
override.

---

## What you get

**`android/`** — the complete Android app (Java, no dependencies):

- square crop dialog like the vendor custom-face step
- on-device face build: photo → RGB565 → splice into the reference blob → LZO1X →
  container with vendor checksums → self-check by decompressing and comparing
- BLE uploader: command session, windowed framing, per-window flow control
- live log of every step

**`builder/`** — PC reference implementation in Python: build a container, verify
it, generate a replay script. Useful for analysis; never required by the app.

**`watchfaces/`** — pre-generated test containers for bringing up the transport.

**`Docs/`** — protocol reference, engineering report, approach history, captures.

**`skills/`** — a procedural skill for porting the approach to another watch.

---

## Repository layout

```
NoiseFit_Halo_Face_Builder/
├── android/                        Android Studio / Gradle project
│   ├── app/src/main/java/com/jay/wfup/
│   │   ├── CropView.java           square crop viewport (drag + pinch)
│   │   ├── FacePipeline.java       photo -> composed 466x466 screen -> blob
│   │   ├── FaceBuilder.java        blob -> container (header, checksums)
│   │   ├── Lzo1x.java              LZO1X codec
│   │   ├── LzoCrc32.java           vendor S-box checksum
│   │   ├── ReplayBuilder.java      container -> BLE window/chunk framing
│   │   └── MainActivity.java       UI, BLE client, replay engine
│   └── app/src/main/assets/
│       ├── blob.bin                754368-byte reference blob
│       └── replay3.txt             captured replay script
├── builder/                        Python reference tools
│   ├── build_face.py
│   ├── generate_replay.py
│   ├── verify_build.py
│   ├── lzo1x.py
│   └── lzo_crc32.py
├── scripts/                        PowerShell helpers (upload, dump replay)
├── watchfaces/                     Pre-generated test containers
├── profiles/halo.json              Every model-specific value, with status
├── Docs/
│   ├── PROTOCOL.md                 Authoritative container + BLE reference
│   ├── TECHNICAL_REPORT.md         How it was solved, with evidence
│   ├── APPROACHES_REPORT.md        What was tried, what failed, what replaced it
│   ├── btsnoop_hci.log             HCI capture of a real accepted transfer
│   └── wf_transcript.txt           Write-by-write session transcript
├── skills/jailbreak-noiseFit/      Procedural skill for other models
├── NoiseFit_Halo_uploader.apk      Pre-built debug APK
├── README.md
├── setup.md
├── llms.txt
└── project.toml
```

---

## Quick start

### 1. Phone only (recommended)

1. Install `NoiseFit_Halo_uploader.apk` (`adb install NoiseFit_Halo_uploader.apk`),
   or build `android/` yourself — see [`setup.md`](setup.md).
2. Pair the watch with the official app once, note its Bluetooth MAC, then
   **force-stop the official app**. Only one app can hold the connection.
3. Open **WF Uploader**, paste the MAC, press **CONNECT BY MAC**, grant Bluetooth
   permissions, wait for `all cccd done`.
4. **PICK PHOTO** → drag/pinch in the square crop dialog → **USE CROP**.
   The square is the whole round screen, so nothing gets squashed.
5. **BUILD FACE (photo over whole screen)**.
6. **UPLOAD BUILT FACE**. Keep the app open until the log says `replay DONE`.

The watch keeps showing the new face after the app closes.

### 2. Build the app from source

```bash
cd android
gradle assembleDebug            # or: ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements and platform notes: [`setup.md`](setup.md).

### 3. PC for the container, phone for the upload

```bash
pip install pillow

python builder/build_face.py photo.jpg face.lz
python builder/verify_build.py face.lz
python builder/generate_replay.py face.lz android/app/src/main/assets/replay3.txt
```

Then rebuild/install the app and press **REPLAY**. The Python compressor produces
the same byte count as the Java one on the same blob.

---

## How it works

### Face container

```
0     4     magic "AF AF AF 02"
4     4     u32le uncompressed blob size         (754368 on the Halo)
8     4     u32le vendor checksum of the blob
12    4     u32le compressed payload size
16    4     u32le vendor checksum of the payload
20    4     u32le reserved
24    2     u16le block count
26    2*n   u16le per block: 0x8000 | compressed size
26+2n ...   n LZO1X blocks of 4096 bytes
```

The header is dynamic. The tail appended at transfer time is
`CRC32(prefix22 || container)`, little endian — solved, not a mystery constant.

### Uncompressed blob (754 368 B)

| range | content |
| --- | --- |
| `0 .. 514` | config header, including the widget table |
| `514 .. 192714` | preview 310×310, face-list thumbnail only |
| `192714 .. 320056` | clock-bar background, 466×136 image + 590-byte tail |
| `320056 .. 627616` | artwork 466×330, lower part of the round screen |
| `627616 .. 754368` | glyph sprite sheets (date, digits, colon, AM-PM) |

Pixels are **RGB565 big endian**, stride `width * 2`. Screen composition is 1:1:
rows 0–135 come from the mid block, rows 136–465 from the artwork, and the sprite
sheets are blitted on top by the firmware.

A full-bleed photo therefore splits at row 136: rows 0–135 go to the mid block,
rows 136–465 to the artwork. The config and the sprite sheets stay stock, which is
why the clock keeps working.

### Config header

The 514-byte config ends with a table of 13-byte widget records at offset `0x180`:

```
u16 x, u16 y, u16 w, u16 h, u8 kind, u32 sheet offset
```

`kind` 4 = date row, 10 = digit, 1 = colon. `sheet offset` points at a raw RGB565
sprite sheet in the atlas block: 10 digit cells of `w*h`, four date cells, one
colon cell. Colour is baked into the sheet; face B uses a second digit sheet so
hours and minutes can differ in colour and size.

Full details, byte ranges and the exact field arithmetic:
[`Docs/PROTOCOL.md`](Docs/PROTOCOL.md).

### BLE transfer

```
22-byte prefix || container || CRC32(prefix || container)
```

Then per window: a 6-byte marker, up to 22 chunks of 177 bytes, with a go-ahead
notification after the marker and a done notification after the last chunk. Before
any of it, an 18-byte `53 3A` command session must complete on handles 0x24/0x21,
otherwise the watch rejects every data write. All of it is in
[`Docs/PROTOCOL.md`](Docs/PROTOCOL.md).

---

## Requirements

- NoiseFit Halo (466×466, LZO1X, `afafaf02` containers). Other models are untested.
- Android phone with BLE, Android 8.0+ (`minSdk 26`, compiled against SDK 34).
- For building from source: JDK 17, Android SDK 34, Gradle 8.7+.
- Optional PC tooling: Python 3.9+ with Pillow.

No internet, server, account or root is used at runtime.

---

## Troubleshooting

**`blob.bin missing`** — the APK lacks the reference blob. Rebuild from `android/`.

**`blob.bin`/`self check failed`** — the compressor produced a stream the decoder
disagrees with. Nothing is sent. Report the log lines.

**Upload stalls** — keep the phone close, make sure the official app is
force-stopped, retry. Only one app can hold the BLE connection.

**Old face still shown after `replay DONE`** — the watch may apply the face a few
seconds after the transfer; wake it and wait. If it still refuses, re-check the
command session lines in the log.

**Wrong colours** — the blob stores RGB565 big endian. Little-endian output looks
like a channel swap. Use the app's own build path.

**Black boxes around the clock** — known open issue, see below.

**`verify_build.py` reports a changed config region** — wrong reference blob. Use
the 754368-byte one from `android/app/src/main/assets/blob.bin`.

---

## Open problems

1. **On-watch confirmation of per-widget sheets.** Sprite cells are blitted
   opaque; the builder now gives every widget its own sheet with the photo patch
   painted into each cell. The 1 078 368-byte blob packs and round-trips; the
   final watch check is pending.
2. **Widget record fields.** Only `x, y, w, h, kind, sheet` are decoded. Sizes,
   colours and background alpha for non-time widgets are still unknown.
3. **Metadata widgets.** Faces that show steps, battery, heart rate or weekday use
   extra sheets in a larger atlas. Layout is gated by the container type field
   (`0214` vs `0203`); untested whether the Halo accepts a bigger layout.
4. **One model, one phone.** Every other NoiseFit model needs its own capture.

Roadmap and evidence: [`Docs/TECHNICAL_REPORT.md`](Docs/TECHNICAL_REPORT.md).

---

## Contributing

Most valuable right now:

- test results on other NoiseFit models, with GATT dumps and face captures
- a photo face with the clock cell backgrounds eliminated
- captures of vendor faces that show steps/day/battery, so the widget table can be
  mapped further
- an LZO1X match finder that stays smaller than the vendor stream

Please scrub MAC addresses, account IDs and tokens from anything you publish.

---

## Disclaimer and licence

For interoperability with hardware you own, using artwork you own or have licensed.
A malformed face can leave the watch showing a broken face; recovery is normally a
re-sync through the official app or a factory reset. Test on hardware you can
recover. Provided as is, without warranty.

MIT — see [`LICENSE`](LICENSE).
