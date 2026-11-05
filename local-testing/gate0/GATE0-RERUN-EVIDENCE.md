# Gate 0 Evidence — Stage 3 Scalable Archive Catalogue

Run date: 2026-11-05 (rerun after premature GO correction)
Worktree: `C:\SmartTube-stage3`, branch `feature/stage3-archive-catalogue`

## Credentials handling

- Router SMB password and Pi SSH password were supplied by the operator in chat and
  used **only** via environment variables (`GATE0_PW`, `PI_PW`) in transient probe
  processes. They are not stored in any script, source file, git object, or report.
- `pi_ssh.py` and `Gate0AdminProbe.java` read passwords exclusively from env vars.

## T1 — Pi SMB mount and permissions — PASS

Command (via paramiko SSH, `pi_ssh.py run`):

    mount | grep -iE 'cifs|sda1|volume'; cat /etc/fstab; ls /etc/systemd/system/

Result:

    //192.168.1.1/volume(sda1) on /mnt/smarttube-archive type cifs
      (rw,nosuid,nodev,noexec,relatime,vers=2.0,cache=strict,username=admin,
       uid=1000,forceuid,gid=1000,forcegid,file_mode=0664,dir_mode=0775,
       soft,...,rsize=65536,wsize=65536,bsize=1048576,echo_interval=5,actimeo=1)

- Confirmed: the archive is on the **router-attached drive**, accessed by the Pi
  over **CIFS (SMB2.0)** as user `admin` — not a Pi-local filesystem.
- Mounted at `/mnt/smarttube-archive`, mounted by `mnt-smarttube-archive.mount`
  (systemd). Read-write, files 0664 / dirs 0775 owned by dietpi.

## T2 — Pi can create and read isolated catalogue files — PASS

Script: `pi_publish_test.sh` (uploaded via base64-over-exec; SFTP subsystem is
disabled on the Pi — `EOF during negotiation` — so base64 transfer was used).

    == T2: basic create/read ==
    {"fmt":1,"rev":0}
    create+read: 0

## T3 — Safe publication through the Pi's CIFS mount — PASS

Test: 300 sequential publishes (`write tmp -> sync -> mv -f tmp pointer.json`,
each revision also copied to an immutable `catalogue.v{N}.json` snapshot) while a
concurrent reader loop read and JSON-validated `pointer.json` for 12 seconds.

    READER: reads=44 good=44 bad=0 fallback_ok=0
    WRITER: revs=300 mvfail=0 retries=0

- `mv -f` (CIFS rename-over-existing) succeeded 300/300 with **zero retries** and
  the concurrent reader saw **zero invalid reads**.
- Contrast with the earlier Windows-side test: direct replacement there produced
  1,811/9,034 bad reads under aggressive concurrent open. The difference is
  reader behaviour (long-held opens vs short validated reads); the versioned
  snapshot scheme is retained because it removes the sharing-window entirely and
  enables reader-side fallback regardless of client platform.

## T4 — Publication failure and recovery — PASS

    == T5: malformed pointer preserves last valid snapshot ==
    OK: malformed pointer rejected; last valid snapshot=catalogue.v300.json rev=300

A malformed `pointer.json` was published; validation rejected it and the last
valid snapshot (`catalogue.v300.json`, rev=300) remained available. This matches
the consumer design: parse failure -> keep last valid cached catalogue.

## T5 — Production SmbjArchiveClient reads fixtures — PASS

Fixtures written to `YouTube-Local/_stage3_gate/fixtures/` (library.json 147 B,
channel_UC123.json 364 B, UTF-8 **without BOM** — a first run failed only because
PowerShell `Set-Content -Encoding UTF8` emits a BOM; fixtures rewritten BOM-less).

Command: `compile_admin.bat` (javac + java with the bundled JDK 17, classpath =
gradle-cached smbj 0.14.0 + bouncycastle + slf4j, android.jar for org.json;
sources compiled from the production tree: `SmbjArchiveClient.java`,
`LocalArchiveIndex.java`).

    connect OK (user=admin)
    READ OK  YouTube-Local/_stage3_gate/fixtures/library.json  bytes=142
    READ OK  YouTube-Local/_stage3_gate/fixtures/channel_UC123.json  bytes=359
    T5 PASS: production SmbjArchiveClient read both fixtures

Note: the earlier BLOCKED result was an authentication problem (guest/anonymous
rejected or timing out at tree-connect). With the operator-supplied `admin`
credentials the production client connects and reads. On the TV the app must be
configured with the same admin credentials (or the share must permit the
configured TV credentials) — to be confirmed at the device gate.

## T6/T7 — Version rejection and path safety — PASS

    == T6: unsupported catalogue version rejection ==
    OK: version 99 rejected (supported: [1])

    == T7: unsafe relative path rejection ==
    REJECT '../etc/passwd'
    ACCEPT 'Channel [UC123]/video.mp4'
    REJECT '/abs/path'
    REJECT 'a\\b'
    ACCEPT 'ok/dir/file.mp4'
    REJECT '..'

(T7 mirrors the policy enforced by `LocalArchiveIndex.requireSafeRelativePath`,
which gates every `list`/`openRead` in `SmbjArchiveClient`.)

## Prior evidence retained (Windows side, commit 1aaddba)

- Direct replacement under concurrent reads: FAIL (1,811/9,034 bad reads).
- Versioned snapshots + pointer with fallback: PASS (3,114 good, 506 fallbacks,
  0 unrecoverable; 22/300 pointer updates needed retry).

## Gate 0 verdict: GO (server/storage layer)

All mandatory Gate 0 items demonstrated against the real router share and the
real Pi. Remaining NOT RUN items belong to later gates, not Gate 0:
- TCL on-device consumer proofs (Gate 4).
- Pi producer deployment (requires operator approval).
- Samsung device ADB status (to be established; if Tizen, TCL is the test device).

## Design decision (evidence-backed)

Immutable versioned snapshots (`catalogue.v{N}.json`, per-channel files) +
small `pointer.json` published by the Pi with `sync` before rename and bounded
retry; TV consumer validates JSON and format version, falls back to the last
valid cached catalogue on any failure. No in-place replacement, no database.
