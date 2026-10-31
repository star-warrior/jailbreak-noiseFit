param([string]$log = "btsnoop_hci.log")
$ErrorActionPreference = "Stop"
$bytes = [IO.File]::ReadAllBytes((Join-Path $PWD $log))
function BE([byte[]]$b,[int]$o){ [uint32]($b[$o]*16777216+$b[$o+1]*65536+$b[$o+2]*256+$b[$o+3]) }
function U16([byte[]]$b,[int]$o){ $b[$o]+256*$b[$o+1] }
function HexOf([byte[]]$b,[int]$from,[int]$to){ ($b[$from..$to] | ForEach-Object { $_.ToString("X2") }) -join "" }

$ops = New-Object System.Collections.ArrayList
$i=16; $n=0
while($i+24 -le $bytes.Length){
  $incl = BE $bytes ($i+4); $flags = BE $bytes ($i+8); $i += 24
  if($incl -lt 1 -or ($i+$incl) -gt $bytes.Length){ break }
  $d = New-Object byte[] $incl; [Array]::Copy($bytes,$i,$d,0,$incl); $i += $incl; $n++
  if($d[0] -ne 0x02 -or $d.Length -lt 10){ continue }
  $cid = $d[7] + 256*$d[8]
  if($cid -ne 4){ continue }
  $op = $d[9]
  if($op -ne 0x52 -and $op -ne 0x12){ continue }
  $att = $d[9..($d.Length-1)]
  [void]$ops.Add([pscustomobject]@{ n=$n; op=$op; att=$att })
}

$out = New-Object System.Collections.Generic.List[string]
$out.Add("# replay commands for uploader: W <handle-hex> <value-hex>")
$out.Add("# CCCD enable")
$out.Add("W 22 0100")
$out.Add("W 25 0100")
$out.Add("W 28 0100")
$out.Add("W 2B 0100")
$out.Add("W 2E 0100")
$out.Add("# data session (verbatim from capture + synthesized final chunk)")

# only writes to 0x2A
$seqExpected = 1
$stream = New-Object System.Collections.Generic.List[byte]
$chunkIdx = 0
foreach($o in $ops){
  if($o.op -ne 0x52){ continue }
  $b = $o.att
  if($b.Length -lt 5){ continue }
  $h = U16 $b 1
  if($h -ne 0x2A){ continue }
  $data = $b[3..($b.Length-1)]
  $hex = HexOf $data 0 ($data.Length-1)
  $out.Add(("W 2A " + $hex))
  if($data.Length -ge 2){
    $seq = U16 $data 0
    # file-continuity tracking (scratch)
    if($seq -ne 0){
      $hdrLen = 2
      if($seq -eq 1 -and $data.Length -ge 4 -and $data[2] -eq 0x0E -and $data[3] -eq 0x00){ $hdrLen = 4 + (U16 $data 2) }
      if($seq -eq $seqExpected -or $seq -eq 1){ $seqExpected = if($seq -eq 1){ 2 } else { $seq+1 } }
      for($k=$hdrLen; $k -lt $data.Length; $k++){ [void]$stream.Add($data[$k]) }
      $chunkIdx++
    }
  }
}
# count check
Write-Output ("stream so far: " + $stream.Count + "  writes: " + $chunkIdx)

# synthesize final chunk: remaining file bytes + trailer 78668428
$wf = [IO.File]::ReadAllBytes((Join-Path $PWD "watchface.zip"))
$miss = New-Object System.Collections.Generic.List[byte]
for($k = $stream.Count; $k -lt $wf.Length; $k++){ [void]$miss.Add($wf[$k]) }
$miss.Add(0x78); $miss.Add(0x66); $miss.Add(0x84); $miss.Add(0x28)
$finalSeq = ($chunkIdx % 22) + 1   # next seq after last window's chunks (=3 when capture cut at 2)
$payload = New-Object System.Collections.Generic.List[byte]
[void]$payload.Add([byte]($finalSeq -band 0xFF)); [void]$payload.Add([byte](($finalSeq -shr 8) -band 0xFF))
foreach($b2 in $miss){ [void]$payload.Add($b2) }
$out.Add("# synthesized final chunk (seq $finalSeq, payload $($payload.Count-2) B = rest of file + trailer)")
$out.Add(("W 2A " + (HexOf $payload.ToArray() 0 ($payload.Count-1))))
Write-Output ("missing file bytes closed: " + $miss.Count)

[IO.File]::WriteAllLines((Join-Path $PWD "replay.txt"), $out)
Write-Output ("replay lines: " + $out.Count)
