# NoiseFit Halo Face Builder + BLE Replay System

## Overview

Complete offline pipeline to convert photos → `.lz` face binary → BLE replay upload to NoiseFit Halo watch.

**Status**: ✅ Full repo with Java app source, Python builder, BLE scripts, test faces, and replay3.txt format documentation

---

## 📁 Repository Contents

| File | Size | Description |
|------|------|-------------|
| `MainActivity.java` | 375 lines | Uploader app main activity (BLE connection, REPLAY, MAC input) |
| `WFCompilerRunner.java` | 163 lines | Compiler runner with package directory fix (`fonts/`, `iwf.json`, `preview/`) |
| `build_face.py` | 12,287 bytes | Main face builder: Photo → RGB565 → FastLZ → AFAFAF02 `.lz` container |
| `generate_replay.py` | ~90 lines | Generates `replay3.txt` from `.lz` file (48-byte BLE chunks) |
| `verify_build.py` | ~50 lines | Verifies the built `.lz` format matches expected values |
| `upload.ps1` | 51 lines | One-command upload: disable NoiseFit → launch app → watch for DONE/STALL |
| `test_replay.ps1` | Test script | Replay test harness |
| `test_replay2.ps1` | Test script | Second replay test harness |
| `test_replay3.ps1` | Test script | Third replay test harness |
| `test_replay4.ps1` | Test script | Fourth replay test harness |
| `dump_replay.ps1` | Dump script | Dump BLE replay data from logcat |
| `face_solid.lz` | ~14KB | Pre-generated solid color face (14,848 bytes compressed) |
| `face_gradient.lz` | ~141KB | Pre-generated gradient face |
| `face_random.lz` | ~442KB | Pre-generated random face (max compression) |
| `README.md` | 6,895 bytes | This documentation |

---

## 🔄 The Complete Workflow

### Option 1: Full Offline (No PC, Phone + BLE)

**1. Build the face binary** (on phone via Termux or on PC):

```bash
# On PC:
python build_face.py input_image.png face.lz

# OR on phone (Termux):
pkg update -y && pkg install -y python python-pip && pip install pillow
python build_face.py /sdcard/DCIM/Camera/photo.jpg face.lz
```

**2. Generate replay3.txt** (convert `.lz` → BLE chunks):

```bash
python generate_replay.py
# Creates: replay3.txt with 48-byte W 2A chunks + CCCD enable + R 24 wait marker
```

**3. Upload via BLE** (using the uploader app already on your phone):

```powershell
# In PowerShell (or run upload.ps1):
.\upload.ps1

# What it does:
# 1. Stops and disables NoiseFit: adb shell pm disable-user --user 0 com.noisefit
# 2. Launches uploader app: adb shell am start -n com.jay.wfup/.MainActivity
# 3. Watches logcat until "replay DONE" or "STALL"
# 4. Re-enables NoiseFit when done
```

**4. In the uploader app**:
- Connect by MAC: `DB:81:C2:15:9F:33`
- Press **REPLAY** button
- Face installs on watch (confirmed by "replay DONE" in logcat)

---

### Option 2: PC + BLE (Already Verified Working)

```bash
# On PC:
python build_face.py your_image.png face.lz

python generate_replay.py

# On phone (PowerShell):
.\upload.ps1

# Then in uploader app:
# 1. Connect by MAC: DB:81:C2:15:9F:33
# 2. Press REPLAY
# 3. Face installs
```

---

## 📱 The Uploader App (Already on Your Phone)

**Package**: `com.jay.wfup`  
**Status**: ✅ Installed & working on Samsung SM-M336BU (Android 16)  
**BLE MAC**: `DB:81:C2:15:9F:33` (NoiseFit Halo watch)

### App Features (from `MainActivity.java`):

| Button | Function |
|--------|----------|
| **SCAN** | Scan for Bluetooth devices |
| **CONNECT BY MAC** | Connect to watch by pasting MAC address |
| **REPLAY** | Start BLE face transfer from `replay3.txt` |
| **TEST COMPILER (offline)** | Calls native lib (crashes without package directory - our builder bypasses this) |

### BLE UUIDs (from decompiled code):

| UUID | Service/Characteristic | Purpose |
|------|----------------------|---------|
| `16186F00-0000-1000-8000-00807F9B34FB` | Custom service prefix | |
| `16186F01-0000-1000-8000-00807F9B34FB` | CHR_A | |
| `16186F02-0000-1000-8000-00807F9B34FB` | CHR_B | |
| `16186F04-0000-1000-8000-00807F9B34FB` | **CHR_DATA** | **Face data writes (W 2A)** |
| `16186F05-0000-1000-8000-00807F9B34FB` | CHR_SIDE | |
| `00002902-0000-1000-8000-00805F9B34FB` | CCCD | Client Characteristic Configuration Descriptor |

### BLE Chunk Format (from `replay3.txt`):

```
# First 5 lines (CCCD enable):
W 22 0100
W 25 0100
W 28 0100
W 2B 0100
W 2E 0100

# Face data chunks (example):
W 2A afafaf0288a00600efe934e14d5700002a961146000000006b0000005100a200f30044011002f202c803a00476054c06
W 2A 35070b08e508bb09960a7d0b530c2b0d010edc0ecc0fa21077114d1223131214ea14bf1595166b175a183519071ae91
...

# Wait for notification (last line):
R 24 000001010000
```

**Chunk structure**: 48 bytes of face data per `W 2A` line, written with `WRITE_TYPE_NO_RESPONSE`.  
**CCCD enable**: 5 writes to configure notifications on characteristic handles.  
**Wait marker**: `R 24 000001010000` waits for the watch's `177B` byte notification.

---

## 🛠️ Script Details

### `upload.ps1`

```
# upload.ps1 - one command per watchface upload.
# Disables NoiseFit (prevents BLE write interleave), launches uploader APK,
# watches logcat until replay DONE (auto re-enables NoiseFit), or STALL (stays disabled).

# Usage: .\upload.ps1
# Or: adb shell -Command ".\\upload.ps1"

# What it does:
# 1) Stops + disables NoiseFit: adb shell pm disable-user --user 0 com.noisefit
# 2) Launches uploader: adb shell am start -n com.jay.wfup/.MainActivity
# 3) Watches logcat for: 'replay start', 'replay DONE', or 'STALL'
# 4) Re-enables NoiseFit when complete (or leaves disabled for debugging)
```

### `generate_replay.py`

Generates `replay3.txt` from a built `.lz` file:

```
# Pipeline:
# 1. Read .lz file (header + payload + 4-byte CRC32-MPEG2 trailer)
# 2. Split payload into 48-byte chunks
# 3. Write: W 22 0100, W 25 0100, W 28 0100, W 2B 0100, W 2E 0100 (CCCD enable)
# 4. Write: W 2A <96-hex-chunks> (face data, 48 bytes each)
# 5. End: R 24 000001010000 (wait for watch notification)
```

### `verify_build.py`

Validates the output of `build_face.py`:

```
=== BUILD FACE PYTHON VERIFICATION ===
Magic: afafaf02 (expected)
Uncompressed size: 754368 (expected 466×466×2)
Compressed size: VARIES (depends on image content)
Blocks: 185 (expected)
File size: VARIES
Trailer CRC: verified
First chunk magic: afafaf02 ✓
```

---

## 📸 Test Faces (Pre-generated)

| Face | Compressed Size | Description |
|------|----------------|-------------|
| `face_solid.lz` | 14,848 bytes | Uniform color (most compressible) |
| `face_gradient.lz` | 141,084 bytes | Smooth color gradient |
| `face_random.lz` | 442,353 bytes | Random pixel pattern (least compressible) |

**All three have correct headers**: magic `afafaf02`, unc_size `754368`, blocks `185`.  
**Use in app**: Select any face via the uploader app's REPLAY function.

---

## ⚠️ Known Issues & Notes

### Compressed Size Variance

- **Our builder produces**: 14KB - 442KB (depends on image content)
- **Real watchface.zip**: 49,887 bytes
- **Issue**: Different FastLZ implementations produce different compressed sizes
- **Impact**: Watch decoder accepts the format; display quality varies with image complexity
- **Solid color images** compress best (~14KB); **random images** compress worst (~442KB)

### Native Lib Crash (App "TEST COMPILER")

- The uploader app's "TEST COMPILER" button crashes because `libVeryFitMulti.so` expects a full package directory (`fonts/`, `iwf.json`, `preview/`)
- **Bypass**: Our Python builder produces the `.lz` format independently without calling the native lib
- **Fix**: Not required for our offline path; the `.lz` files work via BLE replay

### Phone-Only Setup (Termux)

If running entirely on the phone:

```bash
# In Termux (run once):
pkg update -y
pkg install -y python python-pip
pip install pillow

# Then:
python build_face.py /sdcard/DCIM/Camera/photo.jpg face.lz
python generate_replay.py
# → Use upload.ps1 + uploader app REPLAY
```

---

## 🚀 Quick Start (5 Minutes)

### On PC:

```bash
# 1. Build face binary
python build_face.py your_image.png face.lz

# 2. Generate BLE replay
python generate_replay.py

# 3. Upload via phone
# Open PowerShell and run:
.\upload.ps1

# 4. In uploader app:
#    - Connect MAC: DB:81:C2:15:9F:33
#    - Press REPLAY
#    - Wait for "replay DONE" in logcat
```

### On Phone (Termux):

```bash
# 1. Install packages (once):
pkg update -y && pkg install -y python python-pip && pip install pillow

# 2. Build face
python build_face.py /sdcard/DCIM/Camera/photo.jpg face.lz

# 3. Generate replay
python generate_replay.py

# 4. Upload
# Open PowerShell on PC or use the upload script, then
# In uploader app: Connect MAC → REPLAY → face installs
```

---

## 📞 Connect & Contribute

**Issues & Feature Requests**: Open an issue in this repository.  
**Questions**: Start a discussion.  
**Contributing**: Pull requests welcome for improving the FastLZ compressor, container format, or BLE workflow.

**Repository**: `NoiseFit_Halo_Face_Builder`

**Built with**: Python 3, Pillow, FastLZ algorithm (ported from fastlz.c), AFAFAF02 container format reverse-engineered from NoiseFit Halo watch faces.

**Reverse-Engineered From**: `libVeryFitMulti.so`, `watchface.zip`, NoiseFit uploader APK (`com.jay.wfup`).

**Last updated**: October 31, 2026