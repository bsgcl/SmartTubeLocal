# Gate 0 Probe 4b: versioned-snapshot publish scheme under concurrent readers
$ErrorActionPreference = 'Continue'
$t = '\\192.168.1.1\volume(sda1)\_stage3_gate0'

# Snapshots are immutable once written. Pointer switches between them.
$s1 = Join-Path $t 'catalogue.v1.json'
$s2 = Join-Path $t 'catalogue.v2.json'
Set-Content -LiteralPath $s1 -Value '{"gen":1,"videos":["a","b"]}'
Set-Content -LiteralPath $s2 -Value '{"gen":2,"videos":["a","b","c"]}'

# reader: reads pointer, then snapshot; validates JSON; on any failure falls back to last-known-good
$job = Start-Job -ScriptBlock {
    param($t)
    $lastGood = '{"gen":0}'
    $ok = 0; $fellBack = 0; $bad = 0
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 5000) {
        try {
            $ptr = [System.IO.File]::ReadAllText((Join-Path $t 'pointer.json'))
            $gen = [int]($ptr | ConvertFrom-Json).current
            $snap = [System.IO.File]::ReadAllText((Join-Path $t ('catalogue.v' + $gen + '.json')))
            $null = ($snap | ConvertFrom-Json)   # must parse
            $lastGood = $snap
            $ok++
        } catch {
            # pointer or snapshot read/parse failed -> fall back to last valid
            try { $null = ($lastGood | ConvertFrom-Json); $fellBack++ } catch { $bad++ }
        }
    }
    "ok=$ok fellBack=$fellBack bad=$bad"
} -ArgumentList $t

# publisher: rotates snapshots rapidly, pointer update via Replace with retry
$ptr = Join-Path $t 'pointer.json'
Set-Content -LiteralPath $ptr -Value '{"current":1}'
$ptrTmp = Join-Path $t 'pointer.json.tmp'
$bak = Join-Path $t 'pointer.bak'
$publishFails = 0; $published = 0
for ($i = 0; $i -lt 300; $i++) {
    $g = if ($i % 2 -eq 0) { 2 } else { 1 }
    Set-Content -LiteralPath $ptrTmp -Value ('{"current":' + $g + '}')
    $done = $false
    for ($r = 0; $r -lt 5 -and -not $done; $r++) {
        try { [System.IO.File]::Replace($ptrTmp, $ptr, $bak); $done = $true; $published++ }
        catch { Start-Sleep -Milliseconds 10 }
    }
    if (-not $done) { $publishFails++ }
}
Write-Host "PUBLISHER: published=$published publishFails=$publishFails"
Wait-Job $job | Out-Null
Receive-Job $job
Remove-Job $job
Write-Host "PROBE4B-DONE"
