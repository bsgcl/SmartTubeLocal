#!/usr/bin/env python3
"""Focused producer checks for the source playlist catalogue documents."""
import json
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "pi-catalogue"))
import metube_catalogue as producer  # noqa: E402


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS", message)


def add_video(root, channel_id, video_id, title):
    folder = os.path.join(root, "LearningMovements [" + channel_id + "]")
    os.makedirs(folder, exist_ok=True)
    base = os.path.join(folder, title + " [" + video_id + "]")
    with open(base + ".info.json", "w", encoding="utf-8") as fh:
        json.dump({"id": video_id, "channel_id": channel_id,
                   "title": title, "duration": 60}, fh)
    with open(base + ".mp4", "wb") as fh:
        fh.write(b"video-bytes")


def definitions(path, playlist_records):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump({"schemaVersion": 1, "sourceChannelId": "UC" + "A" * 22,
                   "sourceUrl": "https://www.youtube.com/@learningmovements/playlists",
                   "playlists": playlist_records}, fh)


def playlist(playlist_id, title, video_ids, source_count=None):
    return {"playlistId": playlist_id, "title": title,
            "channelId": "UC" + "A" * 22,
            "sourceUrl": "https://www.youtube.com/playlist?list=" + playlist_id,
            "sourceVideoCount": len(video_ids) if source_count is None else source_count,
            "videoIds": video_ids}


def read_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def main():
    temp = tempfile.mkdtemp(prefix="playlist-catalogue-")
    try:
        root = os.path.join(temp, "archive")
        os.makedirs(root)
        cfg = os.path.join(temp, "playlist-definitions.json")
        channel_id = "UC" + "A" * 22
        ids = ["video00001", "video00002", "video00003"]
        for i, video_id in enumerate(ids):
            add_video(root, channel_id, video_id, "Video " + str(i + 1))
        records = [
            playlist("PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV", "Source One - LearningMovements",
                     [ids[0], ids[1], ids[0], "missing0001"], 4),
            playlist("PLtest000002", "Source Two - LearningMovements",
                     [ids[1], ids[2]]),
            playlist("PLtest000003", "Empty Source - LearningMovements", []),
        ]
        definitions(cfg, records)
        check(producer.run_once(root, playlist_definitions_path=cfg),
              "playlist catalogue publishes on first reconciliation")
        base = os.path.join(root, "_catalogue", "v1")
        index = read_json(os.path.join(base, "playlists.json"))
        check(index["revision"] == 1 and len(index["playlists"]) == 3,
              "playlist identities and source titles are retained")
        one = read_json(os.path.join(base, "playlists", "PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV.json"))
        two = read_json(os.path.join(base, "playlists", "PLtest000002.json"))
        empty = read_json(os.path.join(base, "playlists", "PLtest000003.json"))
        check(one["videoIds"] == [ids[0], ids[1], ids[0]],
              "ordered IDs and repeated source entries are preserved")
        check(one["missingVideoCount"] == 1 and "missing0001" not in one["videoIds"],
              "missing archive videos are counted and excluded from playback")
        check(ids[1] in one["videoIds"] and ids[1] in two["videoIds"],
              "one archived video can belong to multiple playlists")
        check(empty["videoIds"] == [] and index["playlists"][2]["videoCount"] == 0,
              "empty source playlists remain valid and have no playback entries")
        check(producer.run_once(root, playlist_definitions_path=cfg)
              and producer.read_current_revision(os.path.join(root, "_catalogue")) == (1, True),
              "unchanged source and archive do not publish a new revision")

        records[1] = playlist("PLtest000002", "Source Two - LearningMovements",
                              [ids[2], ids[1]])
        definitions(cfg, records)
        check(producer.run_once(root, playlist_definitions_path=cfg)
              and producer.read_current_revision(os.path.join(root, "_catalogue")) == (2, True),
              "source playlist order changes publish a new revision")
        reordered = read_json(os.path.join(root, "_catalogue", "v2",
                                            "playlists", "PLtest000002.json"))
        check(reordered["videoIds"] == [ids[2], ids[1]],
              "source order update is preserved exactly")

        # Membership changes and local media deletion both reconcile on refresh.
        os.remove(os.path.join(root, "LearningMovements [" + channel_id + "]",
                               "Video 1 [" + ids[0] + "].mp4"))
        records[0] = playlist("PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV", "Source One - LearningMovements",
                              [ids[0], ids[1], ids[0], "missing0001"], 4)
        definitions(cfg, records)
        check(producer.run_once(root, playlist_definitions_path=cfg),
              "local media deletion causes a new playlist revision")
        after_delete = read_json(os.path.join(root, "_catalogue", "v3",
                                               "playlists", "PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV.json"))
        check(after_delete["videoIds"] == [ids[1]] and after_delete["missingVideoCount"] == 3,
              "deleted local videos cannot remain as stale playlist entries")

        # An invalid source refresh must not replace the last valid pointer.
        definitions(cfg, [playlist("bad/id", "Malformed", [])])
        check(not producer.run_once(root, playlist_definitions_path=cfg),
              "invalid playlist metadata aborts publication")
        check(producer.read_current_revision(os.path.join(root, "_catalogue")) == (3, True),
              "invalid metadata preserves the previously advertised revision")

        # A missing definitions file after first publication must not silently
        # erase the last valid playlist index during a scheduled scan.
        os.remove(cfg)
        check(not producer.run_once(root, playlist_definitions_path=cfg),
              "missing playlist definitions abort publication when an index exists")
        check(producer.read_current_revision(os.path.join(root, "_catalogue")) == (3, True),
              "missing definitions preserve the previously advertised revision")
        definitions(cfg, [playlist("PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV", "Source One - LearningMovements",
                                  [ids[1]])])

        # A detail-file write failure must not publish a partial revision.
        definitions(cfg, [playlist("PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV", "Source One - LearningMovements",
                                  [ids[1]])])
        original_atomic_write = producer.atomic_write

        def fail_playlist_detail(path, text):
            if path.endswith(os.path.join("playlists", "PLb61ADzf2-R2cwR-4ucpZKPARz5YeM5XV.json")):
                return False
            return original_atomic_write(path, text)

        producer.atomic_write = fail_playlist_detail
        try:
            check(not producer.run_once(root, playlist_definitions_path=cfg),
                  "interrupted playlist publication is rejected")
        finally:
            producer.atomic_write = original_atomic_write
        check(producer.read_current_revision(os.path.join(root, "_catalogue")) == (3, True),
              "failed playlist publication keeps the last valid pointer")
        print("\n14/14 playlist producer checks passed")
    finally:
        shutil.rmtree(temp, ignore_errors=True)


if __name__ == "__main__":
    main()
