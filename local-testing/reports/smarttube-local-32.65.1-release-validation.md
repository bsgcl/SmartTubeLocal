# SmartTube LOCAL 32.65.1 Release Validation

Date: 2026-11-06 (Australia/Perth)
Branch: `fix/saved-youtube-playlist-resolution`
Base: `1f420975957bbd7e1d1c01a8b9c0916bf6150cfb`

## Result

**PASS.** Final 32.65.1 source and APKs passed the local build and regression gates. The user confirms all Samsung acceptance checks passed on RC2, including offline `[LOCAL]` SMB playback with WAN disconnected and restored. That manual offline result is user-confirmed; this agent did not disconnect WAN.

## Automated checks

- `VideoActionPresenterRouteTest`: 6 passed.
- `BrowseService2WrapperSavedPlaylistTest`: 2 passed.
- `ArchiveCatalogueTest`: 8 passed.
- `ArchiveCatalogueClientCacheTest`: 10 passed.
- Total: 26 passed, 0 failed.
- `git diff --check`: passed.

Test logs: `smarttube-local-32.65.1-final-common-tests.log` and `smarttube-local-32.65.1-final-youtubeapi-tests.log`.

## Samsung acceptance

The user confirms saved YouTube playlists open their video lists and play videos; direct YouTube playback works; `[LOCAL]` playlists and channels work; and local SMB playback works with WAN disconnected. The app was updated in place to RC2 during validation, without clearing app data. The installed candidate was `32.65.1-rc2` / code 2457. The user reports WAN was restored after the offline test.

Earlier device evidence from the RC2 run is retained under `local-testing/evidence/`. The YouTube hardware video surface appeared black in screenshots, while the media session, decoder and advancing buffer showed active playback.

## Final stable artifacts

- Package: `org.smarttube.beta`.
- Version: `32.65.1`, versionCode `2458`.
- Signing certificate SHA-256: `ac03e19e95e101ce0fa22f641091b085f2ea23c703c0b395ed5f26569bb45741`.
- Universal APK: `local-testing/releases/smarttube-local-32.65.1/SmartTube_beta_32.65.1_universal.apk`.
  SHA-256: `8b4acb84aefcfcf83f11e5e50014643721a4d0118f7f4f53288321ef5abe18d7`.
- ARMv7 APK: `local-testing/releases/smarttube-local-32.65.1/SmartTube_beta_32.65.1_armeabi-v7a.apk`.
  SHA-256: `935e9607d775b1bb6d2ff8e919f67c1f1330d02716e56d3ee328fa07ad413dd8`.
- Checksums: `local-testing/releases/smarttube-local-32.65.1/SHA256SUMS`.
- Build log: `smarttube-local-32.65.1-final-build.log`.

## Update path

The app update URL still points to `raw.githubusercontent.com/bsgcl/SmartTubeLocal/master/update/smarttube_local_beta2.json`, not upstream SmartTube. The existing manifest lacked the 32.65.1 entry, so the release metadata now advertises code 2458 and the new universal release asset URL. The update mechanism itself is unchanged.

## Remote history

After fetching both remotes, Forgejo `master` (`480e0ebd1335defbf1f6a98a3144fce6e9257931`) was an ancestor of GitHub `master` (`1f420975957bbd7e1d1c01a8b9c0916bf6150cfb`): Forgejo had no unique commits and GitHub was 24 commits ahead. The histories did not diverge; no force push is needed.
