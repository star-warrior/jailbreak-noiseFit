# test_replay3.ps1 - replay with NoiseFit running (like Phase 1)
param([string]$AdbPath = ".\platform-tools\adb.exe")

$adb = $AdbPath
if (-not (Test-Path $adb)) { Write-Host "adb not found"; exit 1 }

Write-Host "1) Launch uploader..."
& $adb shell am start -n com.jay.wfup/.MainActivity 2>$null
Start-Sleep 3

Write-Host "2) Type MAC in input field..."
& $adb shell input tap 100 650 2>$null
Start-Sleep 1
& $adb shell input text "DB:81:C2:15:9F:33" 2>$null
Start-Sleep 1
& $adb shell input tap 540 620 2>$null
Start-Sleep 5

Write-Host "3) Hit REPLAY..."
& $adb shell input tap 540 780 2>$null
Start-Sleep 5

Write-Host "4) Check logcat..."
& $adb logcat -d -s WFUP:I 2>$null