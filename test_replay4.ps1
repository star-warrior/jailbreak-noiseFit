# test_replay4.ps1 - wait for replay DONE
param([string]$AdbPath = ".\platform-tools\adb.exe")

$adb = $AdbPath
if (-not (Test-Path $adb)) { Write-Host "adb not found"; exit 1 }

Write-Host "1) Launch uploader..."
& $adb shell am start -n com.jay.wfup/.MainActivity 2>$null
Start-Sleep 3

Write-Host "2) Connect by MAC..."
& $adb shell input tap 100 650 2>$null
Start-Sleep 1
& $adb shell input text "DB:81:C2:15:9F:33" 2>$null
Start-Sleep 1
& $adb shell input tap 540 620 2>$null
Start-Sleep 5

Write-Host "3) Hit REPLAY..."
& $adb shell input tap 540 780 2>$null

Write-Host "4) Wait for replay DONE or STALL (30s timeout)..."
$elapsed = 0
while ($elapsed -lt 30) {
    Start-Sleep 1
    $elapsed++
    $log = & $adb logcat -d -s WFUP:I 2>$null
    if ($log -match 'replay DONE') {
        Write-Host "replay DONE detected!"
        Write-Host "Face installed on watch!"
        & $adb shell pm enable com.noisefit 2>$null
        exit 0
    }
    if ($log -match 'STALL') {
        Write-Host "STALL detected - halting"
        & $adb shell pm enable com.noisefit 2>$null
        exit 1
    }
    # Show progress
    if ($elapsed % 5 -eq 0 -and $log) {
        Write-Host "Waiting... $elapsed s`
n$log.substring(0,200)"
    }
}
Write-Host "Timeout - no replay DONE or STALL after 30s"
& $adb shell pm enable com.noisefit 2>$null
exit 1