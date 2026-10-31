# upload.ps1 - one command per watchface upload.
# Disables NoiseFit (prevents BLE write interleave), launches uploader APK,
# watches logcat until replay DONE (auto re-enables NoiseFit), or STALL (stays disabled).
# Ctrl+C mid-transfer: NoiseFit stays disabled on purpose (re-enable manually, see output).
param([string]$AdbPath = ".\platform-tools\adb.exe")

$adb = $AdbPath
if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb"; exit 1 }

Write-Host "1) stopping + disabling NoiseFit..."
& $adb shell am force-stop com.noisefit | Out-Null
& $adb shell pm disable-user --user 0 com.noisefit
& $adb logcat -c | Out-Null
& $adb shell am start -n com.jay.wfup/.MainActivity | Out-Null
Write-Host "2) uploader launched. On the phone: CONNECT BY MAC -> wait 'all cccd done' -> REPLAY."
Write-Host "   watching logcat for WFUP..."
$running = $false
$prevLine = ""
try {
  while ($true) {
    Start-Sleep -Seconds 2
    $l = (& $adb logcat -d -s WFUP 2>$null) -join "`n"
    $lines = $l -split "`r?`n" | Where-Object { $_.Trim() -ne "" }
    $last = $lines | Select-Object -Last 1
    if ($last -and $last -ne $prevLine) { $prevLine = $last; Write-Host "   $last" }
    if ($l -match 'replay start') { $running = $true }
    if ($l -match 'replay DONE') {
      Write-Host "3) replay DONE - face installed."
      & $adb shell pm enable com.noisefit | Out-Null
      Write-Host "   NoiseFit re-enabled. All done."
      return
    }
    if ($l -match 'STALL') {
      $stall = $lines | Select-String 'STALL' | Select-Object -Last 1
      Write-Host "!! STALL halting replay:"
      Write-Host "   $stall"
      Write-Host "   NoiseFit left DISABLED for debugging. Re-enable when finished:"
      Write-Host "   adb shell pm enable com.noisefit"
      return
    }
  }
}
finally {
  if (-not $running) {
    & $adb shell pm enable com.noisefit | Out-Null
    Write-Host "aborted before replay start: NoiseFit re-enabled."
  } else {
    Write-Host "monitor exited; replay may still be running on the phone. NoiseFit NOT re-enabled to avoid collision."
    Write-Host "After transfer finishes: adb shell pm enable com.noisefit"
  }
}
