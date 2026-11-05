# SmartTube Local playlist catalogue

The playlist index is an optional extension to the versioned archive catalogue.
Channel-only catalogues remain valid; readers treat a missing playlists.json
as an empty playlist collection.

## Published files

For revision N, the producer writes all files below before atomically
advancing _catalogue/pointer.json:

    _catalogue/
      pointer.json
      vN/
        library.json
        channels/{channelId}.json
        playlists.json
        playlists/{playlistId}.json

playlists.json is a bounded summary index. It stores the canonical playlist
and channel IDs, source title, local video count, original source count, count
of source entries without local media, and a local thumbnail video ID.
Each playlist detail stores the same identity plus ordered videoIds.
Repeated source entries and membership in multiple playlists are retained.
The producer only publishes IDs with an existing non-empty media file.
No video, thumbnail, or full yt-dlp metadata is copied into playlist files.

Playlist index and detail documents carry the same revision as library.json.
The TV validates the identity and revision and caches each file under a
revision-specific name. A changed pointer cannot reuse a stale playlist detail.

## Source refresh

MeTube completion events and the six-hour timer only reconcile
_catalogue-source/playlist-definitions.json with the archive. They never contact YouTube.
This keeps routine catalogue refreshes independent of WAN availability.

To refresh source membership deliberately:

1. Install yt-dlp on the development machine.
2. Run python refresh_playlist_definitions.py --output playlist-definitions.candidate.json.
   This is metadata-only and does not download media.
3. Review the candidate's playlist IDs, exact titles, entry counts, order,
   unavailable source positions, and differences from the active definitions.
4. Replace the active definitions only after that review, then copy them to
   YouTube-Local/_catalogue-source/playlist-definitions.json during an idle
   MeTube queue. This source file is outside the published revision tree.
5. Run the one-shot catalogue service and verify the new pointer and files.

Keep the candidate and source inventory as dated evidence. Never replace the
active definitions with an empty or partial result from a failed source read.
Users with a static SMB catalogue can publish the same versioned JSON layout
without running MeTube; the TV only reads the SMB files.

## Recovery

The producer publishes into a new vN directory, validates writes, and updates
the pointer last. A failed write leaves the previous pointer and revision
advertised. Restore by republishing the previous valid version or restoring its
pointer after confirming all referenced files remain present. Do not delete
the current or previous valid revision during recovery.
