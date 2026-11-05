# Stage 3 Gate 0 — Live SMB Storage Proof (Evidence Record)

Date: 2026-05-11 · Machine: Windows dev box (bobbyp330) · Worktree: C:\SmartTube-stage3 @ feature/stage3-archive-catalogue
Isolated test directory: \\192.168.1.1\volume(sda1)\_stage3_gate0 (created for this probe; production media untouched)

## Test scripts
- local-testing/gate0/smb_probe.ps1 — write/replace/rotate probe (Windows SMB client)
- local-testing/gate0/smb_probe_versioned.ps1 — versioned-snapshot publish probe
- local-testing/gate0/Gate0LiveProbe.java — production SmbjArchiveClient fixture read (standalone JVM)
- local-testing/gate0/Gate0ShareProbe.java / Gate0CredProbe.java — smbj diagnostics

## Results

### T1 — SMB share writable from this machine — PASS
Set-Content to \\192.168.1.1\volume(sda1)\_stage3_gate0\write_test.txt, content read back correctly ("probe-write-ok").

### T2 — Atomic rename-over-open (single writer) — PASS with caveat
[System.IO.File]::Replace(tmp, target, backup) over the SMB share succeeded; target content swapped from
{"catalogueVersion":1} to {"catalogueVersion":2}. NOTE: File.Replace with null backup fails on this path
("The path is not of a legal form."); a real backup path is required. File.Move does NOT overwrite
("Cannot create a file when that file already exists").

### T3 — Rename-over-open under concurrent readers — FAIL (design-invalidating)
200 rotations while a reader hammered the target for 3 s: 9,034 reads, **1,811 bad**. Replace repeatedly
failed with "The process cannot access the file because it is being used by another process" / "Unable to
remove the file to be replaced" while readers held the file. Conclusion: the Archer SMB server does not
provide POSIX-style atomic rename-over-open under contention. A simple "write temp, rename over library.json"
publish is NOT safe on this device. (Earlier run with 18,282 reads/0 bad was invalid — the Move call was
failing every iteration and the file never changed.)

### T4 — Versioned-snapshot publish scheme — PASS
Scheme: immutable snapshots catalogue.v{N}.json + pointer.json {"current":N}; readers validate JSON and
fall back to last-known-good on any failure. 300 rapid rotations under a 5 s concurrent reader loop:
- publisher: 278/300 first-try pointer publishes; 22 needed retry (bounded retry succeeded every time)
- reader: ok=3,114 fellBack=506 bad=0 — zero unrecoverable reads; every fallback served a valid previous snapshot.

### T5 — Production SmbjArchiveClient (smbj 0.14.0) reading live fixtures — NOT RUN (blocked)
Standalone JVM probe compiled against production sources (SmbjArchiveClient, LocalArchiveIndexCore, gson 2.10.1).
Connection + session setup OK (negotiated dialect SMB_2_0_2), but tree connect to volume(sda1) fails for all
smbj credential variants:
- guest()/guest/"" and guest/guest: TreeConnect TIMEOUT (8 s, no response)
- anonymous() and "": STATUS_ACCESS_DENIED (0xc0000022)
Windows meanwhile accesses the share using a stored credential (cmdkey /list: Domain:target=192.168.1.1,
User: admin). The password is not available to this session and was not extracted. Item 2 (client reads
library.json/fixture) can be proven once valid SMB credentials are supplied for the probe, or proven on-device.

### T6 — Pi access method to the router SMB share — NOT RUN (blocked)
No Pi SSH credentials or mount documentation exists in docs/ or local-testing/. Item 1 (how the Pi mounts the
share, whether it can write catalogue files) requires Pi access details.

### T7 — Archive-relative path safety — PASS (static)
LocalArchiveIndex.requireSafeRelativePath is already used by SmbjArchiveClient.smbPath() for every list/open
call (no "..", no absolute paths, no NUL); compile check passed. Runtime fixture read-through pending T5.

## Design consequences
1. Publish = write immutable snapshot file, flush, THEN update pointer file with bounded retry (sharing
   violations are expected and normal). Never replace a snapshot in place.
2. Consumer must: parse pointer, load snapshot, validate JSON + schema, keep last valid catalogue on any
   failure (defer refresh). Monotonic generation check.
3. Windows-only File.Replace semantics do NOT carry over to the Pi publisher; the Pi must use its own
   retrying write+rename via its SMB client, proven separately (T6).

## Verdict
Gate 0 remains **NOT GO** until T5 and T6 are demonstrated. T1–T4 and T7 are complete and recorded.
