# SmartTube Local Stage 1 validation

Date: 3 November 2026

## Scope and source history

Stage 1 adds separate Local Library channel rows, each labelled with `[LOCAL]`.
The original presentation and README commits are retained in the integration
history, followed by the extracted grouping helper, its tests, and the 32.58
version checkpoint.

The integration branch is `feature/local-library-roadmap`, based on GitHub
`origin/master` at `480e0eb`. Forgejo `master` at `cdbf6cc` is an ancestor of
that base. The branch retains the VirusTotal release-note gate from commit
`6123bf0`.

Relevant commits, in order:

- `28603bd` — original Local Library channel rows.
- `9300fdc` — original README update.
- `c970380` — extracted stable channel-row grouping into a helper.
- `f9475e5` — tests for grouping and stable row IDs.
- `34fae70` — version 32.58 / 2448.

`34fae70` is the Stage 2 source/version baseline. The validation report is a
documentation-only follow-up commit.

## Direct TCL observations

These observations were made on the installed 32.57 candidate, not on the new
32.58 APK:

- Kids Local Library displayed distinct `[LOCAL] Khan Academy` and
  `[LOCAL] TED-Ed` rows, with two Khan Academy cards and one TED-Ed card.
- Videos from both rows started through the existing SMB playback route. The
  app media session reported the expected titles and active playback.
- Pause, resume, and seeking worked during local playback.
- Kids had Local Library enabled and Guest had it disabled; the profile roster
  remained present after restarting the app.
- Online Home recommendations and thumbnails loaded.

## Bobby's manual confirmations

Bobby reports these additional checks on the 32.57 Stage 1 candidate:

- Archive refresh was run twice, with no duplicate channel rows.
- Online YouTube playback worked.
- Local SMB playback worked, including with WAN disconnected while LAN remained
  available.
- Natural video completion and audible audio were confirmed.
- Profile permissions behaved as expected.

These manual confirmations are recorded separately from the direct observations
above. The new 32.58 APK was not installed on a TV under this ticket.

## Automated validation

- Focused plain-JVM tests: **8 passed** — six
  `LocalArchiveChannelRowsTest` cases and two selected
  `LocalArchiveJvmTest` cases covering metadata validation and profile access.
- `:smarttubetv:assembleStbetaDebug`: **passed** with the specified P330 JDK 17
  and Android SDK.
- Robolectric-dependent tests were not run. The previously accepted Robolectric
  limitation remains; no Robolectric or JDK changes were made.

## Version 32.58 artifacts

Package identity: `org.smarttube.beta`  
Version: `32.58` / code `2448`  
Signing certificate SHA-256:
`ac03e19e95e101ce0fa22f641091b085f2ea23c703c0b395ed5f26569bb45741`

| Artifact | Size | SHA-256 | Verification |
| --- | ---: | --- | --- |
| `local-testing/releases/32.58/SmartTube_beta_32.58_armeabi-v7a.apk` | 34,660,460 bytes | `768706B724D9338D1F6C84175F965AD19952FB926FBFBAC8E58CE9C1C871F34D` | ARMv7; package/version and v1/v2 signature verified |
| `local-testing/releases/32.58/SmartTube_beta_32.58_universal.apk` | 48,540,087 bytes | `75507248908B43461751636183026F07B90E5604F9E0A6C74B530E970D381856` | ARM64 and ARMv7; package/version and v1/v2 signature verified |

Both APKs use the expected existing signing certificate. The 32.57 reference
APKs are preserved under `local-testing/releases/32.57/` with their original
checksums recorded in the 32.57 validation report.

## Stage 2 handoff

Start Stage 2 from `34fae70` on `feature/local-library-roadmap`. The final
report-only commit follows it. No Stage 2 implementation is included here.
