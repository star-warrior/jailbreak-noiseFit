# test_replay2.ps1 - improved BLE replay test
param([string]$AdbPath = ".\platform-tools\adb.exe")

$adb = $AdbPath
if (-not (Test-Path $adb)) { Write-Host "adb not found"; exit 1 }

Write-Host "1) Disable NoiseFit..."
& $adb shell am force-stop com.noisefit 2>$null
& $adb shell pm disable-user --user 0 com.noisefit 2>$null
& $adb logcat -c 2>$null

Write-Host "2) Launch uploader..."
& $adb shell am start -n com.jay.wfup/.MainActivity 2>$null
Start-Sleep 3

Write-Host "3) Focus MAC input and type MAC..."
# Focus the app window first
& $adb shell input keyevent 26 2>$null  # KEYCODE_BACK
Start-Sleep 1
# Tap the MAC edit field to give it focus (approximate - left side of screen)
& $adb shell input tap 100 650 2>$null
Start-Sleep 1
# Type the MAC
& $adb shell input text "DB:81:C2:15:9F:33" 2>$null
Start-Sleep 1
# Tap CONNECT BY MAC button
& $adb shell input tap 540 620 2>$null
Start-Sleep 5

Write-Host "4) Check connection status..."
$log = & $adb logcat -d -s WFUP:I 2>$null
Write-Host "Log: $log"

Write-Host "5) Hit REPLAY..."
& $adb shell input tap 540 780 2>$null
Start-Sleep 5

Write-Host "6) Check result..."
$log2 = & $adb logcat -d -s WFUP:I 2>$null
Write-Host "Final log: $log2"