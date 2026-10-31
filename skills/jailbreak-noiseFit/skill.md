---
name: jailbreak-noisefit
description: Use this skill when a user wants to upload custom watchfaces to any NoiseFit smartwatch (Halo is the verified reference model; other editions are supported through per-model profiles) without the companion app's paywalled flow. Also use it to set up the NoiseFit Face Builder repo, or to reproduce the approach for another smartwatch or app, meaning capturing and reverse-engineering the BLE face-transfer protocol, discovering a model's face container and display parameters, building a face converter (image to vendor binary), and writing an Android uploader that replays the transfer offline. Covers tooling, BLE capture and analysis, per-model profile creation, FastLZ and RGB565 handling, CRC checks, the uploader app, a validation ladder, pitfalls, and safety boundaries. Trigger on mentions of NoiseFit, any Noise watch model, Veryfit, watchface upload, custom dial, BLE replay, btsnoop, HCI snoop, GATT write replay, AFAFAF02, or bypassing a companion-app paywall for the user's own device.
---

# BLE Watchface Uploader: Reverse-Engineer, Build, and Replay

Reference repository: https://github.com/star-warrior/jailbreak-noiseFit

This skill helps a user send custom watchfaces to **any NoiseFit watch they own**, and more generally to any smartwatch whose faces can only be sent through a gated companion app. It does this by reverse-engineering the face container and the BLE transfer, building a converter, and replaying the transfer offline.

The reference implementation was built and verified on the **NoiseFit Halo**. Every other model is treated as **unverified until its own capture says otherwise**. Model-specific values (screen size, pixel format, container header, BLE handles, chunk size, flow-control pattern) live in a **model profile**, never in code constants. See section 3A.

Read the whole skill before acting. The order of work matters, and several steps exist because earlier attempts failed.

## 0. Ground rules

### Scope and safety

Proceed only when all of these hold:

- The user owns the watch, or has the owner's permission.
- The artwork is the user's own or properly licensed.
- The goal is interoperability and personal use.

Do not help with:

- Accessing other people's accounts or vendor servers beyond what the user is entitled to.
- Extracting paid assets for redistribution.
- Attacking, bricking, or interfering with devices the user does not own.

Say plainly that the project is unaffiliated with the vendor and that terms of service may apply to the user's situation. Do not give legal conclusions.

### Honesty about status

For the NoiseFit Halo: the BLE transport is verified, the container header is verified, and arbitrary image conversion is only partial.

For every other NoiseFit model: nothing is verified. Treat Halo values as hypotheses to test, never as facts. Never present a converter as complete for a model until the user has seen their own image render correctly on that model's watch. Use the words "verified", "observed" and "assumed" deliberately, and record which one applies to each profile field.

### Risk to the device

An experimental face can leave the watch showing garbage or refusing syncs. Before the first upload, make sure the user knows the recovery path (re-sync via the official app, or factory reset) and has no unsaved data on the watch.

## 1. Information to collect first

Ask for these (one or two at a time, not an interrogation):

1. Watch model and companion app name and package ID.
2. Display resolution and shape (the reference watch, the Halo, is 466x466, round-ish AMOLED).
3. Whether the user can run the official app at all and whether they can transfer a face once (needed for capture).
4. Whether they have a sample face file from the vendor (a zip or binary with a face inside).
5. Host OS (the reference tooling is PowerShell and ADB friendly) and whether they have an Android phone with developer options.
6. Their comfort level: reading logs, Python, Android Studio.
7. The exact NoiseFit model and firmware version (shown in the companion app's device screen).
8. The companion app package name and version, since different NoiseFit apps and versions may use different flows.
9. Whether the user can read the watch's Bluetooth name and MAC, and whether a BLE scanner like nRF Connect is available.

Then pick a plan:

| Situation                                         | Plan                                                                                                                                            |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| NoiseFit Halo                                     | Use the existing `halo` profile. Follow the repo setup, validate transport with a pre-generated face, then work on conversion                   |
| Another NoiseFit model                            | Create a new profile (section 3A). Start from the Halo profile as a hypothesis, then capture a transfer from that model and correct every field |
| Another vendor using the same Veryfit-style stack | Same as above; expect the magic, header and handles to differ and verify each one                                                               |
| Unrelated vendor                                  | Run the full workflow from section 3 from scratch                                                                                               |

## 2. Tooling

| Purpose                 | Tool                                                  | Notes                                                                                                                                   |
| ----------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Phone control and logs  | ADB (platform-tools)                                  | `adb logcat`, `adb install`, `adb shell`                                                                                                |
| BLE capture             | Android "Bluetooth HCI snoop log" (Developer options) | Produces btsnoop_hci.log; pull via bug report or `adb pull` (path varies by device)                                                     |
| Capture analysis        | Wireshark                                             | Filters: `btatt`, `btatt.opcode == 0x52` (Write Command), `btatt.opcode == 0x12` (Write Request), `btatt.opcode == 0x1b` (Notification) |
| APK inspection          | jadx (or similar)                                     | Find BLE UUIDs, command builders, the face-compiler call site                                                                           |
| Native library analysis | Ghidra or IDA                                         | Only when the compiler lives in a .so; expect to find format constants and file-layout code                                             |
| Converter               | Python 3.9+, Pillow                                   | Image resize, RGB565 packing                                                                                                            |
| Uploader app            | Android Studio, Java or Kotlin                        | BLE GATT client                                                                                                                         |
| Manual BLE poking       | nRF Connect (phone app)                               | Quick way to read characteristics, dump the GATT table, and test writes                                                                 |
| Optional                | Termux                                                | Run Python on the phone; install manually, prefer the F-Droid build                                                                     |

Install check:

```bash
python --version
pip install pillow
adb version
adb devices
```

## 3. Workflow

The phases are ordered so each one removes a class of unknowns before the next begins.

### Phase A: Capture a known-good transfer

1. On the phone, enable Developer options, then "Enable Bluetooth HCI snoop log". Toggle Bluetooth off and on so logging starts clean.
2. Use the official app to send exactly one face to the watch. If that requires a paid or account-bound step the user is entitled to, do that; do not help circumvent entitlements the user does not hold.
3. Disable snooping, pull the log. Typical paths: `/sdcard/btsnoop_hci.log`, `/data/misc/bluetooth/logs/btsnoop_hci.log` (needs root), or via a bug report zip (`adb bugreport`) under `FS/data/misc/bluetooth/logs/`. The reference project used an extracted FS dump of the bug report.
4. Also record `adb logcat` during the transfer. Many companion apps log their own progress, which gives the human-readable story that matches the packets.

Record the exact face used, ideally with a copy of the source asset.

### Phase B: Read the capture

In Wireshark:

1. Filter to the watch's address: `bluetooth.addr == AA:BB:CC:DD:EE:FF`.
2. Filter ATT: `btatt`. Sort by time.
3. Identify the stages:
   - Service discovery and CCCD enables (writes of `0100` to descriptor handles). The Halo used handles 0x22, 0x25, 0x28, 0x2B, 0x2E.
   - A start or header command.
   - A long run of identical-length Write Commands (no response) carrying payload. The Halo used 48-byte chunks to handle 0x2A.
   - Periodic notifications from the watch that gate the next batch. The Halo sent 177-byte notifications on 0x2A.
   - A final commit or completion exchange.
4. Export the payload bytes of the chunk run and concatenate them. This should equal the face file (possibly with a small wrapper).

Cross-check: the concatenated payload length should match the length of the vendor's face file plus any framing. If not, you are missing chunks or there is per-chunk framing; look for a repeating prefix byte or counter in each chunk.

### Phase C: Understand the chunking and flow control

Characterise the protocol before writing code:

- Chunk size and whether the last chunk is padded.
- Whether chunks carry a sequence number or offset.
- Where the watch pauses the sender (the notifications). Write a table: after chunk N, wait for notification X.
- Write type: Write Command (no response) versus Write Request (with response). The Halo used WRITE_TYPE_NO_RESPONSE.
- MTU: if chunks exceed ATT_MTU - 3 they will be truncated. A 48-byte chunk fits the default 23-byte MTU only if MTU was raised first; check the capture for an MTU exchange and replicate it (`requestMtu`).
- Any bonding or encryption. If the capture shows the link was encrypted, the uploader must use the same bonded device.

Turn this into a replay script. The reference project's format is deliberately simple text:

```
W 22 0100          # write CCCD enable at handle 0x22
W 25 0100
W 28 0100
W 2B 0100
W 2E 0100
W 2A <48-byte hex> # data chunk, no response
W 2A <48-byte hex>
R 24 000001010000  # wait until the watch notifies the expected pattern
...
```

`W` writes, `R` waits. Keep the format human-readable so failures can be traced by line number. The handles, chunk size and wait pattern above are the Halo's; another model gets its own values from its own capture.

### Phase D: Replay the captured transfer verbatim

Before converting anything, replay the exact captured payload from the official app:

1. Build the replay script from the captured bytes.
2. Send it with the uploader (section 5).
3. Confirm the watch shows the same face the official app installed.

If this fails, the problem is in the transport (MTU, flow control, bonding, the official app still holding the connection), not in conversion. Fix it here. This step is the single most valuable one in the whole workflow.

### Phase E: Learn the face container

Obtain a real face file (from the capture or from a vendor-provided face archive). Inspect it:

```bash
xxd face.lz | head -n 40
python - <<'EOF'
import struct, sys
d = open("face.lz","rb").read()
print("len", len(d))
print("magic", d[:4].hex())
# try reading little-endian 32-bit words from the header
for i in range(0, 64, 4):
    print(i, struct.unpack_from("<I", d, i)[0], d[i:i+4].hex())
EOF
```

The NoiseFit Halo reference values are shown below. For any other model, treat them as hypotheses and read the real values from that model's own face file (section 3A).

| Field             | Halo value                                             |
| ----------------- | ------------------------------------------------------ |
| Magic             | `afafaf02`                                             |
| Uncompressed size | 754368 (466 x 466 x 2)                                 |
| Block count       | 185                                                    |
| Address table     | 185 entries x 2 bytes (offsets into the payload)       |
| Header size       | 396 bytes observed (not fully mapped)                  |
| Payload           | FastLZ-compressed RGB565 pixel data, split into blocks |
| Trailer           | 4-byte CRC-32/MPEG-2                                   |

Method for discovering fields:

1. Find the width x height x bytes-per-pixel number in the header. That anchors the uncompressed size.
2. Find a small integer that equals the number of address-table entries. The table is a monotonically increasing list of offsets; check that the last offset is near the file length.
3. Decompress the payload blocks and check that the total is the uncompressed size.
4. Work out the trailer: compute candidate checksums over candidate ranges and compare with the last 4 bytes.

CRC-32/MPEG-2 reference implementation (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final XOR):

```python
def crc32_mpeg2(data: bytes) -> int:
    crc = 0xFFFFFFFF
    for b in data:
        crc ^= b << 24
        for _ in range(8):
            if crc & 0x80000000:
                crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF
            else:
                crc = (crc << 1) & 0xFFFFFFFF
    return crc
```

Also try the common variants (reflected CRC-32, CRC-32/BZIP2, CRC-32/CKSUM) if MPEG-2 does not match. Test the checksum over the payload only, over header plus payload, and with different byte orders for storing the result. Do not assume.

Open questions to settle by experiment on any new watch:

- RGB565 byte order (little or big endian) and whether red and blue are swapped.
- Whether the image is stored row-major, column-major, or in tiles.
- FastLZ level (1 or 2) and block size; whether blocks are compressed independently.
- Whether the header contains fields beyond size and block count (flags, preview offsets, checksums of the header).

### Phase F: Build the converter

Pipeline: resize, convert to RGB565, split into blocks, compress each block, assemble header plus address table plus payload, append the CRC. Every model-specific number comes from the profile (section 3A).

RGB565 packing, driven by the profile:

```python
from PIL import Image
import json

def load_profile(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

def to_rgb565(img: Image.Image, profile: dict, little_endian: bool = True) -> bytes:
    w = profile["display"]["width"]
    h = profile["display"]["height"]
    img = img.convert("RGB").resize((w, h), Image.LANCZOS)
    out = bytearray()
    for r, g, b in img.getdata():
        v = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
        out += v.to_bytes(2, "little" if little_endian else "big")
    expected = profile["container"]["uncompressed_size"]
    assert len(out) == expected, f"pixel buffer {len(out)} != profile size {expected}"
    return bytes(out)
```

The assertion is intentional: if a new model's profile has the wrong resolution or pixel format, the build fails immediately instead of producing a face that the watch silently rejects or draws incorrectly.

Notes:

- If the watch is round, check whether the vendor masks outside the circle and whether the corners must be black. For square or rounded-rectangle models, check whether the vendor expects rounded corners to be pre-masked. Take this from the vendor's own face file, not from assumption.
- Keep block boundaries consistent with the vendor's. The reference converter's output sizes (14 KB to 442 KB) versus the vendor's 49,887 bytes show that its block or match strategy does not yet match; a smaller, matching output is the signal that the layout is right.
- FastLZ: port the reference algorithm exactly (level 1 or 2). A different compressor can still decode correctly, but if the watch's decoder is strict about the opcode stream you will see corruption. Test with synthetic data first.

Verification helpers (build these early):

- A `verify_build.py` that checks magic, uncompressed size, block count, address table monotonicity, and CRC, all against the profile.
- A decompressor that round-trips your own output back to the original pixels. If you cannot round-trip, do not upload.
- A preview renderer that draws the decoded RGB565 to PNG, so you can inspect output without the watch.

### Phase G: Generate the replay script from the face

```python
def make_replay(lz_bytes: bytes, profile: dict) -> str:
    ble = profile["ble"]
    chunk = ble["chunk_bytes"]
    lines = [f"W {h} 0100" for h in ble["cccd_handles"]]
    data_handle = ble["data_handle"]
    for i in range(0, len(lz_bytes), chunk):
        part = lz_bytes[i:i + chunk].ljust(chunk, b"\x00")
        lines.append(f"W {data_handle} {part.hex()}")
        # insert wait markers exactly where this model's capture shows the watch gating
    return "\n".join(lines) + "\n"
```

Do not hard-code wait positions or wait patterns; derive them from this model's capture table (Phase C) and store them in the profile. The Halo's wait marker was `R 24 000001010000`, satisfied by a 177-byte notification; another model may use a different handle, pattern or length.

Also check whether the capture contains per-transfer metadata (face size, a face ID, a name, a CRC) sent in a start command. A new image has a new size and CRC, so those fields must be regenerated, not copied.

## 3A. Model profiles

All model-specific values go in a profile file, one per watch model, for example `main/profiles/halo.json`. The builder, the replay generator and the verifier read the profile; none of them should hard-code Halo values.

### Profile template

```json
{
  "model": "NoiseFit Halo",
  "profile_status": "verified-transport, partial-conversion",
  "display": {
    "width": 466,
    "height": 466,
    "shape": "round",
    "pixel_format": "RGB565",
    "bytes_per_pixel": 2,
    "byte_order": "unconfirmed"
  },
  "container": {
    "magic": "afafaf02",
    "uncompressed_size": 754368,
    "block_count": 185,
    "address_table_entry_bytes": 2,
    "header_size": 396,
    "compression": "FastLZ",
    "fastlz_level": "unconfirmed",
    "trailer": "CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no xorout)"
  },
  "ble": {
    "cccd_handles": ["22", "25", "28", "2B", "2E"],
    "data_handle": "2A",
    "write_type": "NO_RESPONSE",
    "chunk_bytes": 48,
    "wait_handle": "24",
    "wait_pattern": "000001010000",
    "gating_notification_bytes": 177,
    "mtu_request": "check capture"
  },
  "evidence": {
    "capture_file": "Docs/btsnoop_hci.log",
    "reference_face_bytes": 49887
  }
}
```

Mark every field with how you know it. Use `verified` (checked against a real capture or file from this model), `observed` (seen once), `assumed` (copied from another model) or `unconfirmed`. A new profile should start with every value `assumed` and move each one up the ladder as evidence arrives. One practical way is a parallel `"field_status"` object in the same file that maps each field name to its status.

### Discovering a new model's profile

Work through these in order. Each one replaces an assumption with a measurement.

1. **Identify the device.** Record model name, firmware version, BLE advertised name, MAC (the user's own), and the companion app package and version.
2. **Dump the GATT table.** Use nRF Connect (or `adb logcat` output from the companion app) to list services, characteristics and their handles and UUIDs. Compare with the Halo's handle layout. Handles can differ between models and firmware; UUIDs are usually more stable, so record both.
3. **Capture a real face transfer** from the official app (Phase A and B). This is the ground truth for handles, chunk size, flow control and the start and end commands.
4. **Find the display parameters.**
   - Read the resolution from the companion app's face editor, the face package's config (the Halo's package uses an `iwf.json`), or the decompiled app.
   - Cross-check with the face file: the uncompressed size in the header should equal width x height x bytes per pixel. If it does not, your pixel format or resolution is wrong.
   - Note the screen shape. Round, square and rounded-rectangle screens may mask or crop differently.
5. **Extract a real face** from the capture or from the vendor's face package, and map its header (Phase E). Compare the magic, header size, block count, address-table layout and trailer with the Halo profile. Treat any difference as a finding to record, not as noise.
6. **Run the validation ladder** (section 4) with the new profile. Do not skip rung 1, replaying the captured official transfer byte for byte.
7. **Mark the profile status** honestly, for example `transport-verified, conversion-untested`.

### What typically changes between models (hypotheses, not facts)

| Item                              | Why it may differ                                  | How to check                                        |
| --------------------------------- | -------------------------------------------------- | --------------------------------------------------- |
| Resolution and shape              | Different panels                                   | Face editor, face package config, header size field |
| Uncompressed size and block count | They follow from resolution and the block strategy | Read them from a real face for that model           |
| Magic and header layout           | Different firmware or container revision           | Hex-dump a real face and compare                    |
| BLE handles and UUIDs             | Different GATT table per model or firmware         | nRF Connect dump and the capture                    |
| Chunk size and MTU                | Different MTU negotiation                          | MTU exchange in the capture                         |
| Flow-control pattern              | Different ack and notification scheme              | Notification bytes in the capture                   |
| Pixel format and byte order       | Different display controller                       | Corner-marker test                                  |
| Face slot, naming and metadata    | Different start command                            | Start command in the capture                        |

### Contributing a profile

When a profile reaches at least `transport-verified`, add it to the repo as `main/profiles/<model>.json`, add a row to the compatibility matrix below, and include the capture or a redacted transfer table. Remove or mask the user's MAC address, account IDs and tokens first. Pull requests and issues go to https://github.com/star-warrior/jailbreak-noiseFit.

### Compatibility matrix

| Model                 | Profile     | Transport | Conversion | Notes                                              |
| --------------------- | ----------- | --------- | ---------- | -------------------------------------------------- |
| NoiseFit Halo         | `halo.json` | Verified  | Partial    | Reference model, tested on one watch and one phone |
| Other NoiseFit models | none yet    | Unknown   | Unknown    | Use this section to create a profile               |

## 4. Validation ladder

Move up one rung at a time. Stop and debug when a rung fails.

1. Replay the captured official transfer byte for byte. Watch shows the original face.
2. Replay a synthetic solid-color face (very compressible, tiny). Watch shows a flat color.
3. Replay a gradient. Check that orientation and color channels are correct.
4. Replay a test pattern with distinct corners (red top-left, green top-right, blue bottom-left, white bottom-right) plus text. This reveals flips, rotations, channel swaps, and byte-order mistakes.
5. Replay a random-pixel face. This stresses the compressor with incompressible data.
6. Replay a real photo.
7. Replay a real photo with a typical UI overlay if the vendor supports overlays.

Use a checkerboard or a corner-marker pattern; it is far easier to diagnose than a photo.

The pre-generated faces in `main/watchfaces/` are Halo-specific (size, resolution and header). Do not use them to test another model.

## 5. Building the uploader app

Minimum features: connect by MAC, request MTU, enable notifications, run the replay script with flow control, log everything.

Key implementation points (Android BLE):

- Load handles, chunk size, write type and wait patterns from the model profile (or the generated replay script), not from constants in the app. The same app should serve every supported model.
- Permissions:
  - Android 12 and newer: `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` at runtime.
  - Older: `ACCESS_FINE_LOCATION` for scanning.
- Connect with `device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`. Get the device from `BluetoothAdapter.getRemoteDevice(mac)`.
- After `onConnectionStateChange` reports connected, call `requestMtu(...)` if the capture showed an MTU exchange, then `discoverServices()`.
- Enable notifications: `setCharacteristicNotification(ch, true)` and write `ENABLE_NOTIFICATION_VALUE` to the CCCD descriptor. Descriptor writes are asynchronous; wait for `onDescriptorWrite` before the next one. Android drops overlapping GATT operations, so serialise everything through a queue.
- Data chunks: `ch.setWriteType(WRITE_TYPE_NO_RESPONSE)`, `ch.setValue(bytes)`, `gatt.writeCharacteristic(ch)`. Without a response there is no callback to pace you, so rely on `onCharacteristicWrite` where the platform provides it, or a small inter-write delay, and always honour the watch's gating notifications.
- Wait markers: block the replay thread on a latch that `onCharacteristicChanged` releases when the expected notification pattern arrives. Add a timeout and log STALL when it fires.
- Logging: print a line for every step with the index (`#12 wait 2A <- ...`, `ack ok ...`, `replay DONE`). The reference project's logcat markers are `replay start`, `#X wait ...`, `ack ok ...`, `> 2a 177B`, `replay DONE`.
- Handles versus UUIDs: handles can change between models, firmware versions and reconnects. Store UUIDs in the profile as the primary key and keep handles as a debugging aid. Resolve handles at runtime from the discovered services where possible.

Helper script pattern (PowerShell, as in the reference). The reference implementation of this pattern is `main/scripts/upload.ps1`; read it before writing a new one.

```powershell
adb shell pm disable-user --user 0 <official.app.package>   # prevent a second BLE central
adb shell am start -n <your.app.package>/.MainActivity
adb logcat -c
adb logcat | Select-String "replay|ack ok|STALL"
```

Re-enable the official app afterwards (`adb shell pm enable <official.app.package>`).

## 6. If the vendor's compiler is a native library

If the companion app compiles faces with a `.so`, you may be tempted to call it. The reference project's experience:

- Calling the compiler directly crashed with a null dereference when its expected package directory (config JSON, `fonts/`, `preview/`) was missing.
- Patching and re-signing the APK is not a workable distribution route.
- Reproducing the output format in Python was cheaper and safer than repairing the compiler.

Use the native library only as an oracle: if you can run it in a controlled environment with the right directory, feed it test images and compare its output with your converter's. That gives you ground truth for block layout and compression choices without needing to ship it.

Static analysis targets in Ghidra or IDA: functions named like `make_compression_file`, `makeFileCompression`, bitmap or font tools, and any code writing a 4-byte magic.

## 7. Pitfalls checklist

- The official app still holds the BLE connection. Force-stop or disable it.
- Skipping the MTU request, so chunks are silently truncated.
- Not serialising GATT operations on Android.
- Ignoring watch-side gating notifications and flooding the watch.
- Copying size or CRC fields from the captured transfer into a different face.
- Assuming the CRC variant. Test several.
- Assuming RGB565 byte order or channel order. Test with corner markers.
- Trusting a small header match (magic, size, block count) as proof the converter is correct. It proves acceptance of the header, not correct rendering.
- Assuming another NoiseFit model shares the Halo's resolution, container header or BLE handles. Verify every one against that model's own capture.
- Reusing the Halo's pre-generated faces on another model. Their size, resolution and header are Halo-specific and may be rejected or drawn wrongly.
- Putting model constants in code instead of a profile, which makes the next model a rewrite.
- Claiming a model is "supported" after transport works but before an image renders correctly.
- Using the vendor's MAC from someone else's logs. Always use the user's own device address.
- Leaving personal identifiers (MAC addresses, account IDs, tokens) in published logs or git history. Scrub captures before publishing.
- Uploading experimental faces to a watch the user cannot easily reset.

## 8. Troubleshooting

| Symptom                                       | Likely cause                                       | Action                                                                                                              |
| --------------------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Cannot connect                                | Official app connected, wrong MAC, watch asleep    | Disable official app, verify MAC, wake the watch                                                                    |
| Connects, replay stalls early                 | Missing notification enable or MTU request         | Compare your first 20 operations with the capture                                                                   |
| Stalls mid-transfer                           | Gating notification not matched                    | Log the exact notification bytes; check the wait pattern and timeout                                                |
| Replay DONE but old face shown                | Commit step missing or CRC rejected                | Compare the final exchange with the capture; check CRC variant and range                                            |
| Face shows but colors wrong                   | RGB565 byte or channel order                       | Run corner-marker test; flip endianness or swap R and B                                                             |
| Face shows but shifted, skewed or garbled     | Wrong block layout, stride, or compressor mismatch | Round-trip decompress your own file; compare block table with a vendor face                                         |
| Face is correct but very large                | Compressor or block strategy differs               | Compare your sizes with the vendor's; study their match strategy                                                    |
| Works for solid, fails for photos             | Compressor edge cases                              | Fuzz the compressor with random data and round-trip                                                                 |
| Face rejected on a non-Halo model             | Header, resolution or magic differs                | Extract a real face for that model and compare it field by field with your profile                                  |
| Face drawn at wrong scale, cropped or offset  | Wrong resolution or shape in the profile           | Re-read the display size from the app or face package; confirm uncompressed size = width x height x bytes per pixel |
| CCCD writes or data writes fail with an error | Handles differ on this model                       | Dump the GATT table with nRF Connect and update the profile                                                         |

## 9. Deliverables to produce for a new port

1. A capture log and a short table of the transfer stages.
2. A replay script that reproduces the official transfer byte for byte.
3. A model profile (`main/profiles/<model>.json`) with every field marked verified, observed, assumed or unconfirmed.
4. A format notes file: magic, sizes, header map, checksum variant, pixel order, compression details.
5. A converter with a verifier, a round-trip decompressor, and a preview renderer.
6. An uploader app (or reuse the reference one) with a serialised GATT queue and loggable output.
7. A README and approach report that separate verified claims from assumptions.

## 10. Working with the reference repository

Repository: https://github.com/star-warrior/jailbreak-noiseFit

Repository map:

```
Docs/                      Approaches report, HCI capture, extracted data, transcript
main/builder/              build_face.py, generate_replay.py, verify_build.py
main/profiles/             One JSON profile per watch model (halo.json is the reference)
main/scripts/              upload.ps1, test_replay.ps1, dump_replay.ps1
main/watchfaces/           face_solid.lz, face_gradient.lz, face_random.lz (Halo-specific)
main/MainActivity.java     Uploader app activity
main/WFCompilerRunner.java Native compiler wrapper
skills/jailbreak-noiseFit/ This skill
llms.txt                   Context file for AI assistants
NoiseFit_Halo_uploader.apk Pre-built debug APK
project.toml               Project metadata
```

For a supported model (run from the repo root, with `<model>` such as `halo`):

```bash
git clone https://github.com/star-warrior/jailbreak-noiseFit.git
cd jailbreak-noiseFit
pip install pillow
adb install NoiseFit_Halo_uploader.apk
python main/builder/build_face.py input.png face.lz --profile main/profiles/<model>.json
python main/builder/verify_build.py face.lz --profile main/profiles/<model>.json
python main/builder/generate_replay.py face.lz --profile main/profiles/<model>.json
.\main\scripts\upload.ps1
```

If the user's model has no profile yet, go to section 3A before building anything. If the user's checkout predates profile support and the scripts do not accept `--profile`, the first task is to move their hard-coded constants (resolution, uncompressed size, block count, magic, handles, chunk size) into `main/profiles/halo.json` and make the three builder scripts read from it.

Then in the app: enter the user's own watch MAC, press REPLAY, watch logcat for `replay DONE`.

For the Halo, start with `main/watchfaces/face_solid.lz` to prove transport, then try the user's image. For any other model, start with rung 1 of the validation ladder instead. If the image renders wrongly, move to the validation ladder in section 4 and the troubleshooting table in section 8. The main open problem in the reference project is matching the official compiler's block layout and compression so arbitrary images render faithfully and compress to a comparable size; contributions there are the most valuable.

## 11. How to talk to the user

- Be concrete: give the next command or the next thing to look at, not a lecture.
- Say what you are confident about and what you are guessing.
- Always say which model a statement is verified on. "Verified" without a model name is not allowed.
- When a step fails, ask for the specific log lines or the Wireshark rows around the failure.
- Remind the user to scrub MAC addresses and tokens from anything they publish.
- Keep expectations realistic: transport can often be working in an afternoon; faithful image conversion for a new model or vendor can take much longer.
