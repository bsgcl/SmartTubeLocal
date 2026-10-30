# Sol handoff: SmartTube LOCAL playback and stream cleanup

Date: 31 October 2026 (Australia/Perth)

## Goal

Continue diagnosing the TCL playback problems in the local SMB archive:

- Local videos produce audio but no visible picture.
- Player controls can be focused, but seeking appears ineffective.
- Verify that the temporary loopback SMB-to-HTTP stream closes after playback ends or the player exits.

The user authorized direct LAN/ADB access and testing the archive videos. The TCL is muted. Do not use Remote Desktop Commander. Preserve the installed `org.smarttube.beta` app and its data; no uninstall, data clear, commit, push, or publication.

## Confirmed download format

The three `.mp4` files in `\\192.168.1.1\volume(sda1)\YouTube-Local` were inspected as MP4 containers, not just by reading downloader sidecars. Each contains `vide` and `soun` tracks with `avc1` and `mp4a` sample entries. Sidecars report H.264 Main at 854x480 and AAC audio. A missing video track or unusual MeTube codec is unlikely.

During the earlier local playback session, TCL logs showed ExoPlayer finding `video/avc` at 854x480 and the AAC audio track, two track groups, and `STATE_READY`. The captured media codec metrics showed AAC decoder sessions, but no clear AVC decoder startup or first-frame event. This points toward the app/TV render or decoder path; it does not yet identify the cause.

## Stream cleanup: corrected finding, still needs a valid lifecycle test

An earlier report treated `127.0.0.1:12854` as the local SMB playback listener. That attribution was wrong. `/proc/net/tcp` reports that listener as UID 10052, and `pm list packages -U` maps UID 10052 to Google `com.google.android.apps.mediashell`. The listener survived force-stopping SmartTube, confirming it is not evidence of a SmartTube stream leak.

SmartTube is UID 10084. After force-stopping it, `/proc/net/tcp` and `/proc/net/tcp6` showed zero sockets owned by UID 10084. SmartTube was then relaunched with `monkey`; app data was preserved.

I opened the navigation rail and the browse UI showed three `LOCAL` badges on Khan Academy cards. A coordinate tap did not open playback; pressing DPAD_CENTER opened `PlaybackActivity`, but there was no `LISTEN` socket owned by UID 10084 and only one UID 10084 TCP socket in `CLOSE_WAIT` to loopback. Because the navigation did not clearly confirm the Local Library section and the expected local listener never appeared, treat that playback as an unconfirmed/likely ordinary recommendation, not a valid local stream test. Pressing Back returned to Browse; logs show `releasePlayer` and `Engine released`, and UID 10084 had zero TCP sockets afterward. This confirms player release leaves no SmartTube-owned sockets for that unconfirmed playback, but it still does not verify cleanup of an actual SMB loopback stream.

The only listener on port 12854 remained UID 10052 (`com.google.android.apps.mediashell`). It is not a SmartTube leak. The app is currently at `BrowseActivity` and no playback is active.

Relevant implementation:

- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/local/LoopbackHttpStream.java:271` — `close()` closes the listener, active sockets, executor, and SMB client.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/local/LocalArchivePlaybackManager.java:30` — owns one stream and calls `close()` in `closeActive()`.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/BrowsePresenter.java:447` — activates the stream when local playback starts.
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/VideoLoaderController.java:113` — closes the active stream on engine release; also closes it on transitions.
- `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/PlaybackFragment.java:371` — engine release path; check whether back/finish can skip or defer it.

### Required cleanup reproduction

1. Navigate explicitly to the SmartTube Local Library section and confirm its section heading, then confirm a LOCAL archive card before opening it. The home Recommended row can also show LOCAL badges, so a badge alone does not prove the Local Library route.
2. While the LOCAL video is playing, read `/proc/net/tcp` and `/proc/net/tcp6`; identify the `LISTEN` socket owned by UID 10084 and record only its local loopback port/state.
3. Exit playback with Back/Stop and verify that the UID 10084 listener and accepted sockets disappear. Also inspect whether audio/background playback is intentionally retained.
4. If a listener remains while UID 10084 is running, inspect logs and manager references. If force-stop is needed to prove ownership, verify no app data is cleared and relaunch afterward.
5. Do not use the Google MediaShell port 12854 as a proxy for SmartTube cleanup.

## Seek and video investigation

- Local playback is passed to `ExoMediaSourceFactory.fromUrlList()` as a token URL with no file extension. `buildMediaSource()` infers `TYPE_OTHER` and uses `ExtractorMediaSource`; the TV did identify H.264/AAC tracks.
- `LoopbackHttpStream` advertises `Accept-Ranges: bytes`, returns `206`/`Content-Range`, and reads from the requested SMB byte offset. Verify real range requests and response bytes during a seek; unit tests so far did not establish device playback seek correctness.
- `LocalArchiveRepository.toVideo()` at line 251 does not copy `LocalArchiveItem.durationSeconds` into `Video.durationMs`. Check if the TV seek UI uses the `Video` metadata or ExoPlayer’s actual duration; add duration propagation only if it affects the control path.
- `PlaybackFragment.createPlayerGlue()` enables seeking (`setSeekEnabled(true)`).
- `PlayerUIController.handleLeftRightSkip()` at line 799 can route left/right to previous/next when quick-skip is enabled. `VideoLoaderController.loadPrevious()` returns for local items, while next may finish local playback. Verify the user's active quick-skip behavior and distinguish DPAD seeking from seek-bar controls.
- An earlier DPAD-left probe was inconclusive because TCL `dumpsys media_session` returned a stale position timestamp. Do not treat that as proof that seeking failed or succeeded.

## Working tree and device state

- Repository: `C:\SmartTube`, branch `codex/local-smb-playback-proof`.
- The working tree already contains multiple modified and untracked local-SMB changes. No files were changed in this cleanup check; do not reset or clean the tree.
- Installed device package: `org.smarttube.beta`, previously identified as version 32.56/code 2446. This cleanup check did not install or rebuild anything.
- ADB target: `192.168.1.100:5555`, TCL product `C06_2K_GB`.
- SmartTube was force-stopped only to distinguish the listener owner, then relaunched. No app data was cleared. The current foreground activity is Browse; the archive stream itself is not being played.
- No tests were run in this cleanup check.

## Next result to provide

First establish whether a listener owned by UID 10084 appears only for a confirmed LOCAL video and whether it disappears after player exit. Then continue diagnosing the missing video frame and seek behavior, with automated logs and device observations kept separate. Do not claim the cleanup lifecycle is fixed or verified until the local playback reproduction above succeeds.
