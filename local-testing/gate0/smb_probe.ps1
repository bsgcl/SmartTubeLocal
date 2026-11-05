# Gate 0 Probe 1: SMB write + rename semantics (isolated dir)
$ErrorActionPreference = 'Continue'
$t = '\\192.168.1.1\volume(sda1)\_stage3_gate0'
New-Item -ItemType Directory -Force -Path $t | Out-Null

$w = Join-Path $t 'write_test.txt'
Set-Content -LiteralPath $w -Value 'probe-write-ok'
Write-Host ("WRITE  : " + (Test-Path -LiteralPath $w) + " content=" + (Get-Content -LiteralPath $w))

# Probe 2: atomic-ish publish = write temp, flush, rename over existing target
$target = Join-Path $t 'publish.json'
Set-Content -LiteralPath $target -Value '{"catalogueVersion":1,"status":"original"}'

# write temp file
$tmp = Join-Path $t 'publish.json.tmp'
Set-Content -LiteralPath $tmp -Value '{"catalogueVersion":2,"status":"replacement"}'
$fs = [System.IO.File]::Open($tmp, 'Open', 'Read', 'ReadWrite')
$null = $fs.ReadByte(); $fs.Flush($true); $fs.Close()   # flush-to-disk attempt

# rename over existing: use File.Replace (overwrite primitive)
try {
    $backup = Join-Path $t 'publish.json.bak'
    [System.IO.File]::Replace($tmp, $target, $backup)
    Write-Host "REPLACE : replaced-over-existing OK"
    Write-Host ("TARGET after replace: " + (Get-Content -LiteralPath $target))
    Remove-Item -LiteralPath $backup -Force
} catch {
    Write-Host ("REPLACE : FAILED " + $_.Exception.Message)
}

# Probe 3: concurrent read during rename rotation (readers hammering while publisher rotates)
$target2 = Join-Path $t 'rotate.json'
Set-Content -LiteralPath $target2 -Value '{"gen":1}'
$job = Start-Job -ScriptBlock {
    param($p)
    $bad = 0; $reads = 0
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt 3000) {
        try {
            $txt = [System.IO.File]::ReadAllText($p)
            $reads++
            if ($txt -notmatch '"gen":"?(1|2)"?') { $bad++ }
        } catch { $bad++ }
    }
    "reads=$reads bad=$bad"
} -ArgumentList $target2

for ($i = 1; $i -le 200; $i++) {
    $g = if ($i % 2 -eq 0) { 2 } else { 1 }
    $tt = Join-Path $t 'rotate.tmp'
    Set-Content -LiteralPath $tt -Value ('{"gen":' + $g + '}')
    $bk = Join-Path $t 'rotate.bak'
    try { [System.IO.File]::Replace($tt, $target2, $bk) } catch { Write-Host ("ROTATE-ERR once: " + $_.Exception.Message) }
}
Wait-Job $job | Out-Null
Receive-Job $job
Remove-Job $job
Write-Host "PROBE-DONE"
