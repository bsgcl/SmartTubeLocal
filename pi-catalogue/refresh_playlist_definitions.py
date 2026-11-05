#!/usr/bin/env python3
"""Retrieve public playlist metadata only and write a reviewable candidate.

Requires yt-dlp on the development machine. It never downloads video files.
The output is a candidate so an operator can review title/ID/order changes
before replacing the producer's active playlist-definitions.json.
"""
import argparse
import datetime
import json
import os
import sys

try:
    import yt_dlp
except ImportError:
    print("Install yt-dlp on the development machine before refreshing.", file=sys.stderr)
    raise SystemExit(2)


SOURCE_URL = "https://www.youtube.com/@learningmovements/playlists"
OPTIONS = {
    "quiet": True,
    "no_warnings": True,
    "skip_download": True,
    "extract_flat": True,
    "ignoreerrors": True,
    "socket_timeout": 30,
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="playlist-definitions.candidate.json")
    args = parser.parse_args()

    with yt_dlp.YoutubeDL(OPTIONS) as ydl:
        channel = ydl.extract_info(SOURCE_URL, download=False)
        channel_id = channel.get("id") if channel else None
        channel_title = channel.get("channel") if channel else None
        entries = channel.get("entries") if channel else None
        if not channel_id or not channel_title or not entries:
            raise SystemExit("Playlist source was empty or unavailable; candidate not written.")

        result = {
            "schemaVersion": 1,
            "sourceChannelId": channel_id,
            "sourceChannelTitle": channel_title,
            "sourceUrl": SOURCE_URL,
            "retrievedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "playlists": [],
        }
        for card in entries:
            if not card or not card.get("id"):
                raise SystemExit("A playlist card was inaccessible; candidate not written.")
            playlist_id = card["id"]
            source_url = card.get("url") or (
                "https://www.youtube.com/playlist?list=" + playlist_id)
            detail = ydl.extract_info(source_url, download=False)
            if not detail:
                raise SystemExit(
                    f"Playlist {playlist_id} could not be read; candidate not written.")
            source_entries = detail.get("entries") or []
            video_ids = []
            unavailable = []
            for position, video in enumerate(source_entries, 1):
                if not video or not video.get("id"):
                    unavailable.append(position)
                else:
                    video_ids.append(video["id"])
            result["playlists"].append({
                "playlistId": playlist_id,
                "title": detail.get("title") or card.get("title"),
                "channelId": channel_id,
                "sourceUrl": source_url,
                "sourceVideoCount": len(source_entries),
                "unavailableSourcePositions": unavailable,
                "videoIds": video_ids,
            })

    output = os.path.abspath(args.output)
    parent = os.path.dirname(output)
    os.makedirs(parent, exist_ok=True)
    temporary = output + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(temporary, output)
    print(f"Wrote review candidate: {output}")
    print(f"Playlists: {len(result['playlists'])}; source entries: "
          f"{sum(item['sourceVideoCount'] for item in result['playlists'])}; "
          f"unavailable source entries: "
          f"{sum(len(item['unavailableSourcePositions']) for item in result['playlists'])}")
    print("No media files were downloaded.")


if __name__ == "__main__":
    main()
