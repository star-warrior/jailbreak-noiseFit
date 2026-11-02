# Setup

Everything needed to build the Android app, run the PC reference tools, and put a
face on a NoiseFit Halo.

- [1. What you need](#1-what-you-need)
- [2. Install the prebuilt APK](#2-install-the-prebuilt-apk)
- [3. Build the Android app](#3-build-the-android-app)
- [4. Prepare the watch and the phone](#4-prepare-the-watch-and-the-phone)
- [5. Build and upload a face](#5-build-and-upload-a-face)
- [6. PC reference tools](#6-pc-reference-tools)
- [7. Verify the install](#7-verify-the-install)
- [8. Troubleshooting](#8-troubleshooting)

---

## 1. What you need

| Component | Minimum | Notes |
| --- | --- | --- |
| Phone | Android 8.0 (API 26), BLE | Tested on Samsung SM-M336BU, Android 16 |
| Watch | NoiseFit Halo | 466×466, container magic `afafaf02` |
| JDK | 17 | AGP 8.7 requires 17 |
| Android SDK | Platform 34, build-tools 34 | `ANDROID_HOME` must point at it |
| Gradle | 8.7 or newer | No wrapper is checked in; use a system Gradle or Android Studio |
| adb | platform-tools | For install and logs |
| Python | 3.9+ with Pillow | Only for the optional PC tools |

The reference build used:

```
JDK   17.0.20.1
SDK   platform 34
Gradle 8.9
Python 3.13 + Pillow
```

No AndroidX, no Kotlin, no external dependencies. The app is plain Java against
the platform APIs.

---

## 2. Install the prebuilt APK

```bash
adb devices                        # phone must be listed as "device"
adb install -r NoiseFit_Halo_uploader.apk
```

If the install is rejected because the app is already present with a different
signature:

```bash
adb uninstall com.jay.wfup
adb install NoiseFit_Halo_uploader.apk
```

The APK is a debug build, signed with the standard debug key. Version is shown in
the app log on every start (`WFUP build <tag>`).

---

## 3. Build the Android app

Set the environment, then build.

### Windows (PowerShell)

```powershell
$env:JAVA_HOME    = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"

cd android
gradle assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### macOS / Linux

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk

cd android
gradle assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`android/` is a normal Gradle project:

```
android/
├── build.gradle           AGP 8.7.3 plugin declaration
├── settings.gradle        repositories + include ':app'
├── gradle.properties
└── app/
    ├── build.gradle       applicationId com.jay.wfup, minSdk 26, target 34
    └── src/main/
```

Android Studio: *Open* → select `android/` → let it sync → Run.

### Notes

- There is no Gradle wrapper (`gradlew`) in the repo. Use a system Gradle 8.7+ or
  let Android Studio supply one (`gradle wrapper --gradle-version 8.9`).
- A first build needs network access for AGP and its dependencies. Subsequent
  builds work offline (`--offline`).
- `app/src/main/assets/blob.bin` (754368 B) and `replay3.txt` are required. Do not
  delete or regenerate them unless you know the reference blob is right.
- `libVeryFitMulti.so` is **not** shipped. It is only used by the abandoned native
  compiler harness (`WFCompilerRunner`, `Ctx`, `Protocol`), which the app does not
  invoke. The app builds and runs without it.

---

## 4. Prepare the watch and the phone

1. Pair the watch with the **official** NoiseFit app and let it do one normal
   transfer, so the watch saves the phone as a bonded central.
2. Note the watch's Bluetooth MAC address. In the app log it is the `mac connect`
   line; in Android settings it is under the paired device.
3. Force-stop the official app. On most phones:

   ```bash
   adb shell am force-stop <vendor.package>
   ```

   Or disable it temporarily:

   ```bash
   adb shell pm disable-user --user 0 <vendor.package>
   ```

   Only one BLE central can hold the face service. With the official app
   connected, the uploader connects but every data write is rejected.
4. Keep the watch awake and near the phone for the first test.

The uploader needs these runtime permissions, requested on first connect:

```
BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
```

---

## 5. Build and upload a face

1. Launch **WF Uploader**.
2. Paste the MAC and press **CONNECT BY MAC**. Wait for `all cccd done`.
3. **PICK PHOTO** → choose an image from the gallery.
4. The crop dialog opens automatically:
   - drag to move the photo
   - pinch to zoom
   - the square is exactly what the round watch shows, so nothing is squashed
   - **USE CROP** commits it; **CANCEL** discards
   - **RE-CROP PHOTO** reopens the same photo without picking again
5. **BUILD FACE (photo over whole screen)**. The log reports the container size
   and the self-check result.
6. **UPLOAD BUILT FACE**. Keep the app in the foreground until `replay DONE`.

A typical successful log:

```
WFUP build 2.5-probe
base blob 754368B
photo loaded 1600x1200
crop ready 466x466 at 1.00x zoom
full bleed: photo 466x466 across the whole screen, glyph atlas untouched
container 148231B, payload 147809B
selfcheck: OK
full bleed built in 412ms
replay start
replay DONE - keep the app open, watching for the watch verdict
```

A face is typically applied a few seconds after `replay DONE`. Wake the watch to
see it.

### If the upload is rejected

- Every data write answered with `00 00 01 02 00 00` → the command session did not
  run, or the official app still holds the link.
- Transfer stalls after a marker → the go-ahead notification was missed. Reconnect
  and retry; keep the phone close.
- `self check failed` → nothing was sent. The build is at fault, not the watch.

---

## 6. PC reference tools

Optional. The phone does everything; these are for analysis and for generating
replay scripts.

```bash
pip install pillow

# photo -> container (defaults to the reference blob in android/)
python builder/build_face.py photo.jpg face.lz

# structure, checksums, decode round-trip
python builder/verify_build.py face.lz

# container -> a replay script the app can load from assets/
python builder/generate_replay.py face.lz android/app/src/main/assets/replay3.txt
```

`build_face.py` accepts `--ladder` to print every quality rung, and reads the same
754368-byte reference blob.

Then rebuild the app, install, and press **REPLAY**.

PowerShell helper:

```powershell
.\scripts\upload.ps1
```

---

## 7. Verify the install

```bash
adb shell dumpsys package com.jay.wfup | grep versionName
adb logcat -c
adb logcat | grep -E "WFUP|replay|ack ok|STALL"
```

Expected on start:

```
WFUP build 2.5-probe
```

Expected on upload:

```
replay start
#12 wait 2A <- 000001010000
ack ok 000001010000
replay DONE
```

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `gradle` not found | No system Gradle | Install Gradle 8.7+, or open `android/` in Android Studio |
| `SDK location not found` | `ANDROID_HOME` unset | Export it, or add `sdk.dir` to `android/local.properties` |
| `Unsupported class file major version` | Wrong JDK | Use JDK 17 for Gradle |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Different signing key | `adb uninstall com.jay.wfup`, install again |
| App connects, all writes rejected | Official app still connected | Force-stop or disable it, reconnect |
| Stalls mid-transfer | Missed flow-control notification | Move closer, retry, keep the screen on |
| `blob.bin missing` | Asset removed from the build | Restore `android/app/src/main/assets/blob.bin` |
| Colours wrong after a PC build | Byte order | Blob pixels are RGB565 **big endian**; regenerate with the shipped tools |
| Face list thumbnail wrong | Preview region not rebuilt | Build inside the app; it generates the preview from the composed screen |
| `D`/`Undefined` from Pillow | Missing Pillow | `pip install pillow` |

Phone-specific: if `adb devices` drops the phone mid-transfer, the USB link is
power-saving. Keep the app in the foreground; the BLE link itself is independent of
USB.

---

## 9. Removing the app

```bash
adb uninstall com.jay.wfup
```

This does not restore the watch's original face. Use the official app to re-apply a
stock face, or factory reset the watch.
