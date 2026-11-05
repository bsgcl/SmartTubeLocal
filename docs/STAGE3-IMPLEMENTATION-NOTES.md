# Stage 3 Implementation Notes — Scalable Archive Catalogue

Branch `feature/stage3-archive-catalogue`, worktree `C:\SmartTube-stage3`.
Gate 0: GO (see `local-testing/gate0/GATE0-RERUN-EVIDENCE.md`).

## Architecture (evidence-backed)

Pi-produced versioned catalogue on the router SMB share, consumed by the TV
through the existing read-only smbj stack. No database, no media server.

    <archiveFolder>/_catalogue/pointer.json               {"formatVersion":1,"revision":N}
    <archiveFolder>/_catalogue/v{N}/library.json          channel summaries + compact video entries
    <archiveFolder>/_catalogue/v{N}/channels/{id}.json    full per-channel entries

- **Producer** (`pi-catalogue/metube_catalogue.py`, Python stdlib only):
  scans yt-dlp `.info.json` sidecars, validates IDs and paths (same policy as
  the consumer), skips malformed sidecars, writes each revision to
  `_catalogue/v{N}` (temp file + fsync + rename with bounded retry), publishes
  `pointer.json` **last**, retains the newest 2 revisions. Run modes: `--once`,
  `--watch --interval N`. Not installed on the Pi (needs operator approval);
  suggested unit file is included in comments in the script header.
- **Consumer** (`ArchiveCatalogue`, `ArchiveCatalogueClient`): startup reads
  pointer + library only (2 small SMB reads; the 1,500-video sidecar scan that
  previously hit the 60 s/128-file limits is gone). The last valid library is
  cached in app files storage; any failed or malformed refresh preserves it.
  Per-channel files are fetched lazily when the user enters a channel
  (`loadChannelVideos`), merged into the item index, and cached on device;
  failures fall back to the cached catalogue.
- **Preserved unchanged:** `SmbjArchiveClient` (read-only), the
  `SMB -> LoopbackHttpStream -> ExoPlayer` playback route, profile gating
  (`isLibraryEnabledForActiveProfile` on browse/thumbnails/channel/playback),
  artwork caching, and all online YouTube features.

## Design decision to review

`library.json` contains compact video entries **including** media/thumb paths
(~400 KB at 1,500 videos), not just channel summaries. Reason: the existing
browse UX renders the flat local-library list and LOCAL badges at startup, and
`attachLocalCopy` runs on the main thread (no SMB allowed there). Carrying the
compact entries in the library file keeps behaviour identical while removing
the sidecar scan. Per-channel JSON files still exist and are loaded lazily on
channel access as required.

## Explicit refresh

Local Library settings gained "Refresh catalogue from SMB". "Test and scan
archive" now also refreshes the catalogue. A failed refresh keeps the previous
valid catalogue (in-memory + on-device cache).

## Test status

- JVM unit tests (`ArchiveCatalogueTest`): PASS — 1,500-video synthetic
  catalogue (grouping, identity, stable ordering, artwork/media paths),
  pointer/channel round trip, unsupported version, duplicate ID, invalid ID,
  unsafe path (entry kept, media dropped), malformed entry tolerance,
  channel identity mismatch.
- Live cross-check (`Gate0CatalogueProbe`, production smbj client against the
  real share and a catalogue produced by the real Pi script): PASS.
- ARMv7 APK build: PASS (`assembleStbetaDebug`).
- NOT RUN (pending operator approval for APK install): TCL on-device tests —
  startup time/SMB-read measurement, playback/seek/pause/resume/completion,
  profiles/subscriptions/playlists/online regression, offline-WAN playback.
- NOT RUN: Samsung device (ADB status unconfirmed; if Tizen, TCL is the test
  device). Producer deployment to the Pi (requires approval).

## Deployment

Not deployed. Nothing installed, no Pi service changed, no release published.
