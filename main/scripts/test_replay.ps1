# test_replay.ps1 - test BLE replay with our generated face
param([string]$AdbPath = ".\platform-tools\adb.exe")

$adb = $AdbPath
if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb"; exit 1 }

Write-Host "1) stopping + disabling NoiseFit..."
& $adb shell am force-stop com.noisefit | Out-Null
& $adb shell pm disable-user --user 0 com.noisefit | Out-Null
& $adb logcat -c | Out-Null

Write-Host "2) launching uploader..."
& $adb shell am start -n com.jay.wfup/.MainActivity | Out-Null

Write-Host "3) connecting by MAC..."
# Enter MAC text
& $adb shell input text "DB:81:C2:15:9F:33"
Start-Sleep 1
# Tap CONNECT BY MAC button (approximate coordinates)
& $adb shell input tap 540 620
Start-Sleep 3
# Check if connected by looking at logcat
$log = & $adb logcat -d -s WFUP:I
Write-Host "Log output: $log"

Write-Host "4) hitting REPLAY..."
& $adb shell input tap 540 800
Start-Sleep 5
Write-Host "Done - check logcat for 'replay DONE'"