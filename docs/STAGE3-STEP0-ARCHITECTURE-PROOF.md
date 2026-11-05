# Stage 3 — Step 0 Architecture Proof (GO/NO GO)

Status: **GO — static Pi catalogue**
Date: recorded on `feature/stage3-archive-catalogue` from baseline `bb4bb9f` (SmartTube Local 32.62)
Worktree: `C:\SmartTube-stage3` (new; `C:\SmartTube` and all other worktrees untouched)

## 1. Verified baseline

- `git cat-file -t bb4bb9f` → commit. `origin/master` head = `bb4bb9f` "fix(update): advertise SmartTube LOCAL 32.62"; release tag points at `6a69ad7`.
- `C:\SmartTube` worktree is on `fix/local-subscription-write-regression` at `467be26` (32.57), behind 17 — untouched per ticket.
- Worktree list confirmed: 5 existing worktrees preserved.

## 2. Current scanner evidence (code inspection, `LocalArchiveRepository.java`)

```
MAX_DEPTH = 4
MAX_ENTRIES = 512          // counts files AND directories
MAX_SIDECARS = 128
MAX_METADATA_BYTES = 1024 * 1024
MAX_ARTWORK_ITEMS = 64
MAX_SCAN_MILLIS = 60_000
```

- Entry point: `scanNow()` → BFS over `SmbjArchiveClient.list()` per directory, then `readSmallFile()` per `.info.json`, then `LocalArchiveIndexCore.build()` matching media/artwork by filename `contains(videoId)`.
- Thumbnail flow: on-demand SMB fetch of artwork into `LocalArchiveArtworkCache`, with retry backoff; failure paths keep existing cache.
- Item identity: canonical YouTube `videoId` (regex `[A-Za-z0-9_-]{6,32}`) + `channel_id` from sidecar; channels grouped by channel.
- Playback route: `LocalArchivePlaybackManager` → SMB `openRead` → `LoopbackHttpStream` → ExoPlayer. Unchanged by this design.
- Profile gate: `LocalArchiveSettings.isLibraryEnabledForActiveProfile()` checked in browse, loadChannelVideos, thumbnail, and playback paths.

## 3. Live Pi/SMB storage evidence (read-only probe, this machine)

- `\\192.168.1.1\volume(sda1)\YouTube-Local` reachable.
- Layout: `Channel Name [UC...]\Title [videoId].{info.json,mp4,webp}` — standard yt-dlp output.
- Current archive: 2 channels, 9 files (3 videos, complete triplets). Small today; the design target is 1,500+.
- **Key measurement:** one real sidecar (`Khan Academy ... [TBNCqRCsSvY].info.json`) is **736,157 bytes** (~720 KB), fields confirmed: `id`, `channel_id`, `channel`, `uploader`, `title`, `duration`.
- Scale arithmetic: 1,500 videos × ~720 KB ≈ **1.07 GB of sidecar bytes over SMB per full scan**, plus ~1,500+ directory listings. At the current 60 s deadline and 128-sidecar cap the scan would abort long before completion. Raising caps would only trade a timeout for an unusable multi-minute startup.

## 4. Alternatives compared

| Option | Verdict | Reason |
|---|---|---|
| A. Raise scan limits (TV-side scan) | **Rejected** | Explicitly disallowed; ~1 GB SMB scan per startup is not scalable, blocks the UI thread window, hits the 60 s deadline. |
| B. TV-side cached index (scan once, cache) | **Rejected** | First scan is still unbounded (~1 GB); cache invalidation forces re-scan; each new download invalidates. Still a database-equivalent burden on the TV. |
| C. Hybrid (TV cache + incremental SMB diff) | **Rejected for now** | Requires the TV to enumerate directories to diff — same unbounded enumeration, just amortised. Complexity not justified by evidence. |
| D. **Static Pi catalogue + lazy channel files (chosen)** | **GO** | TV reads one small `library.json` (one SMB read) plus one channel file per viewed channel. Producer runs on the Pi where the files already live. No new server, no database. |

## 5. Gate 0 sub-proof answers

1. **SMB read of catalogue through existing client** — `SmbjArchiveClient.readSmallFile` streams any small file; a catalogue of 1,500 video summaries (~10 bytes/entry metadata-rich ≈ 150–400 KB) fits well under `MAX_METADATA_BYTES` (1 MB) and a single read call. To be exercised in JVM tests with a synthetic catalogue fixture (Gate 2).
2. **Complete-snapshot publish (no partial reads)** — Producer writes `library.json.tmp` (or `library.<rev>.json`) on the Pi's local filesystem, `fsync`s, then `rename()`s over the target. POSIX rename on the same filesystem is atomic; SMB2 clients observe either the old or the new complete file — never a partial write. Consumer-side defence-in-depth: catalogue carries a `revision` and a `sha256` of the channel index; any parse/ checksum failure ⇒ reject and keep last valid cache.
3. **Failed read must not destroy TV cache** — consumer parses new catalogue fully into memory before swapping the in-memory index; any exception leaves the previous cache intact. Existing artwork cache already follows this pattern.
4. **Path safety** — all media/artwork paths in the catalogue are archive-relative and pass the existing `LocalArchiveIndexCore.requireSafeRelativePath` (rejects absolute, drive-colon, `..`, empty segments). The consumer never opens anything outside `archiveFolder` (SMB client resolves relative to the configured root).
5. **TCL ARMv7 / Android API compatibility** — consumer uses Gson (already a dependency, used in `LocalArchiveIndexCore`), `org.json` is not needed for the catalogue, no new Android APIs; producer is pure Python/JVM on the Pi, not an Android concern. ARMv7 build uses the established JDK 17 toolchain.

## 6. Chosen design (summary)

- **Producer (Pi):** Python script scanning the MeTube/yt-dlp tree, emitting `smarttube-catalogue/library.json` (format version, revision, generated timestamp, channel summaries) and `smarttube-catalogue/channels/<sha(channelId)>.json` (video summaries). Atomic publish via temp+rename. Triggered by a post-download hook and/or cron. Only yt-dlp `.info.json` is the source of truth; malformed sidecars are skipped with a count, never aborting the catalogue.
- **Consumer (TV):** replaces `scanNow()`'s full sweep with: read `library.json` → update index; per-channel `loadChannelVideos` reads only that channel's JSON file on demand; last-valid catalogue cached on device; explicit refresh from Local Library settings; errors keep last cache and surface a user-safe message.
- **Media/artwork/playback** paths unchanged; `SmbjArchiveClient` gains no write operations.

## 7. NOT RUN / deferred

- On-device TCL ARMv7 proofs (Gates 2–4 device portions): **NOT RUN** at Step 0; scheduled for the implementation test phase.
- Producer run on the Pi itself: code + tests land in this branch; deployment to the Pi requires separate approval per ticket.

## 8. Decision

**GO.** Static Pi-produced catalogue + lazy per-channel loading + last-valid TV cache. No database, no persistent server, no unbounded scan. Implementation proceeds on this branch under the ticket's gates.
