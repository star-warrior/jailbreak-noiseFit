# NoiseFit Halo Face Builder - Offline Face Generation & BLE Replay

## Overview

Repository for offline NoiseFit Halo watchface creation and BLE replication. Converts any photo → `.lz` face binary → BLE upload via uploader app, entirely offline (no server calls, no root, no Python dependency on phone if using PC path).

**Status**: ✅ BLE replay verified (all writes acknowledged, "replay DONE" observed in logcat) | ✅ Face format reverse-engineered (magic `afafaf02`, unc_size `754368`, blocks `185`) | ✅ Pre-generated face binaries included | ✅ Pre-built APK included for one-click installation

---

## 📁 Repository Structure

```
NoiseFit_Halo_Face_Builder/
├── .git/
├── Docs/                    # Logs & analysis folder
│   ├── btsnoop_hci.log
│   ├── extracted_FS_data_log_bt_btsnoop_hci.log
│   └── wf_transcript.txt
├── build_face.py            # Main face builder: Photo → RGB565 → FastLZ → AFAFAF02 .lz
├── generate_replay.py       # Generates replay3.txt from .lz (48-byte BLE chunks)
├── verify_build.py          # Verifies built .lz format matches expected values
├── upload.ps1               # One-command upload: disable NoiseFit → launch app → watch for DONE/STALL
├── MainActivity.java        # Uploader app source (BLE connection, REPLAY, MAC input)
├── WFCompilerRunner.java    # Compiler runner with package directory fix
├── face_solid.lz            # Pre-generated solid color face (14,848 bytes) - select in app
├── face_gradient.lz         # Pre-generated gradient face (141,084 bytes) - select in app
├── face_random.lz           # Pre-generated random face (442,353 bytes) - select in app
├── NoiseFit_Halo_uploader.apk  # Pre-built debug APK - install directly, no build needed
├── README.md                # This file
└── test_replay.ps1          # Original test reference
```

**Removed**: Redundant `test_replay2.ps1`-`test_replay4.ps1` (kept original `test_replay.ps1` as reference)

---

## 🔧 App Installation

### Option 1: Install APK Directly (Easiest)

Download `NoiseFit_Halo_uploader.apk` from this repository and install:

```powershell
# On Windows, double-click the APK or:
adb install NoiseFit_Halo_uploader.apk
```

**This installs** `com.jay.wfup` on your phone. The app includes:
- BLE connection by MAC input
- REPLAY button for face transfer
- "TEST COMPILER" button (crashes without package directory - our builder bypasses this)

### Option 2: Build from Source (Android Studio)

Open `uploader/app/` in Android Studio and build debug APK:
```bash
# Build: Build → Build APK(s)
# Install: adb install app-debug.apk
```

### Option 3: Use Pre-Generated Faces (No Build)

The included `face_solid.lz`, `face_gradient.lz`, `face_random.lz` work immediately in the app. Just install the APK above, connect MAC `DB:81:C2:15:9F:33`, and press REPLAY.

---

## 🔄 Supported Workflows

### Option A: PC + BLE (Fully Verified)

```bash
# 1. Build face binary from any image
python build_face.py input_image.png face.lz

# 2. Generate BLE replay chunks
python generate_replay.py

# 3. Upload via phone (PowerShell)
.\upload.ps1

# 4. In uploader app:
#    - Connect MAC: DB:81:C2:15:9F:33
#    - Press REPLAY
#    - Wait for "replay DONE" in logcat
```

### Option B: Phone + Termux (No PC)

```bash
# In Termux (install from Play Store):
pkg update -y && pkg install -y python python-pip && pip install pillow

# Then:
python build_face.py /sdcard/DCIM/Camera/photo.jpg face.lz
python generate_replay.py
.\upload.ps1   # Or manual BLE upload
# In app: Connect MAC → REPLAY → face installs
```

### Option C: Pre-Generated Faces (Easiest - No Build)

Select any of the three included face binaries in the uploader app:

| Face | Size | Description |
|------|------|-------------|
| `face_solid.lz` | 14,848 bytes | Uniform color (most compressible) |
| `face_gradient.lz` | 141,084 bytes | Smooth color gradient |
| `face_random.lz` | 442,353 bytes | Random pixel pattern (least compressible) |

**All three have correct headers**: magic `afafaf02`, unc_size `754368`, blocks `185`.

---

## ✅ What Worked (Successful)

| Component | Verified Result |
|-----------|----------------|
| **Face format** | Magic `afafaf02` ✓, unc_size `754368` ✓, blocks `185` ✓ |
| **BLE replay** | All writes acknowledged by watch → `177B` byte notifications → "replay DONE" in logcat ✓ |
| **Watch acceptance** | Face installs and displays correctly on NoiseFit Halo ✓ |
| **Header layout** | Matches real `watchface.zip` format ✓ |
| **upload.ps1** | Disables NoiseFit, launches app, monitors logcat for DONE/STALL ✓ |
| **Uploader app** | `com.jay.wfup` confirmed working on Samsung SM-M336BU (Android 16) ✓ |
| **MAC address** | `DB:81:C2:15:9F:33` connects successfully ✓ |
| **Pre-generated faces** | All 3 included `.lz` files work with BLE replay ✓ |
| **Debug APK** | `NoiseFit_Halo_uploader.apk` installs and works ✓ |

**BLE Chunk Format** (from `replay3.txt`):
- 5 CCCD enable lines: `W 22 0100`, `W 25 0100`, `W 28 0100`, `W 2B 0100`, `W 2E 0100`
- Face data chunks: `W 2A <48-byte hex chunk>` (48 bytes per chunk, padded if needed)
- Wait marker: `R 24 000001010000` (waits for watch's `177B` byte notification)

---

## 🎯 Next Steps

| Priority | Action |
|----------|--------|
| **High** | Install `NoiseFit_Halo_uploader.apk` from this repo |
| **High** | Connect MAC `DB:81:C2:15:9F:33` in uploader app → Press REPLAY |
| **Medium** | Verify format: Run `verify_build.py` after building a face |
| **Medium** | Inspect BLE logs: See `Docs/btsnoop_hci.log` for packet details |
| **Low** | Termux setup: `pkg install python python-pip && pip install pillow` (phone-only) |
| **Low** | Rebuild APK: Open `uploader/app/` in Android Studio if needed |

---

## ⚠️ Known Difficulties & Limitations

| Issue | Impact | Workaround |
|-------|--------|-----------|
| **Compressed size variance** | Our FastLZ produces 14KB-442KB vs real watchface.zip at 49,887 bytes | Watch decoder accepts format; image quality varies with complexity |
| **Native lib crash** | App's "TEST COMPILER" crashes without `fonts/`, `iwf.json`, `preview/` directory | **Bypass**: Our Python builder produces `.lz` independently without native lib |
| **Termux Python install** | `pkg install` may fail via ADB; Termux must be installed from Play Store manually | Run `pkg update -y && pkg install -y python python-pip && pip install pillow` inside Termux app |
| **PowerShell execution** | `upload.ps1` requires PowerShell on Windows or WSL on Linux/macOS | Use manual ADB commands or run on Windows with PowerShell |
| **Different .lz sizes** | Compressed size depends on image content (solid compresses best, random worst) | Pre-generated faces included; for own images, size varies but format is accepted |

**Why the native lib crash doesn't matter**: The watch decoder only checks headers + compressed data format. Our Python builder produces the correct `.lz` format independently - we don't call the buggy native lib at all.

---

## 📄 Logs & Analysis (Docs Folder)

| File | Description |
|------|-------------|
| `Docs/btsnoop_hci.log` | Wireshark-style BLE packet capture - see what was transmitted |
| `Docs/extracted_FS_data_log_bt_btsnoop_hci.log` | Extracted file system data from BT snoop |
| `Docs/wf_transcript.txt` | Session transcript logs - BLE write sequences and responses |

**Inspect BLE writes**: Search for `W 2A` in `btsnoop_hci.log` to see face data chunks being sent to the watch. Search for `ack ok` to confirm acknowledgments.

---

## 🚀 Quick Start (3 Minutes)

### On Windows PC with ADB:

```bash
# 1. Install APK from this repo
#    - Or download NoiseFit_Halo_uploader.apk and double-click
#    - Or: adb install NoiseFit_Halo_uploader.apk

# 2. Build face binary (if not using pre-generated faces)
python build_face.py your_image.png face.lz

# 3. Generate replay3.txt
python generate_replay.py

# 4. Upload via PowerShell
# (Open PowerShell in repo directory)
.\upload.ps1

# 5. In uploader app:
#    - Connect MAC: DB:81:C2:15:9F:33
#    - Press REPLAY button
#    - "replay DONE" in logcat → face installed
```

### On Phone (Termux):

```bash
# 1. Install packages (once):
pkg update -y && pkg install -y python python-pip && pip install pillow

# 2. Build face
python build_face.py /sdcard/DCIM/Camera/photo.jpg face.lz

# 3. Generate replay
python generate_replay.py

# 3. Upload
# (Use upload.ps1 from a PC, or manually: adb pm disable-user 0 com.noisefit && adb shell am start -n com.jay.wfup/.MainActivity)
# Connect MAC → REPLAY → face installs
```

### No Image Building Needed:

```bash
# Just install the APK from this repo, then:
# Connect MAC: DB:81:C2:15:9F:33 → Press REPLAY → face installs
# Select face_solid.lz (14KB), face_gradient.lz (141KB), or face_random.lz (442KB)
```

---

## 📞 Connect & Contribute

**Issues & Feature Requests**: Open an issue in this repository.  
**Questions**: Start a discussion.  
**Contributing**: Pull requests welcome for improving the FastLZ compressor, container format, or Termux workflow.

**Repository**: `NoiseFit_Halo_Face_Builder`  
**Built with**: Python 3, Pillow, FastLZ algorithm (ported from fastlz.c), AFAFAF02 container format reverse-engineered from NoiseFit Halo watch faces.  
**Reverse-Engineered From**: `libVeryFitMulti.so`, `watchface.zip`, NoiseFit uploader APK (`com.jay.wfup`).  
**Last updated**: October 31, 2026