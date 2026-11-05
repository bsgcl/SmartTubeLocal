#!/usr/bin/env python3
"""SmartTube Local Stage 3 catalogue producer.

Scans a yt-dlp media tree (Channel [UCid]/Title [videoId].info.json) and
publishes a versioned catalogue to the SMB share:

  <root>/_catalogue/pointer.json               {"formatVersion":1,"revision":N}
  <root>/_catalogue/v{N}/library.json          channel summaries + compact video entries
  <root>/_catalogue/v{N}/channels/{id}.json    full entries for one channel

Safety rules (mirrors the TV consumer):
  - formatVersion always 1; consumers reject anything else.
  - video/channel IDs must be canonical ([A-Za-z0-9_-]{6,32}).
  - every published path is checked against the same safe-relative-path policy
    (no absolute paths, no backslashes, no "..", non-empty).
  - malformed .info.json sidecars are skipped; valid entries survive.
  - publication: write to temp files, fsync, rename with bounded retry; the
    pointer is published last so readers never see a half-published revision.

Usage:
  metube_catalogue.py --root /mnt/smarttube-archive/YouTube-Local --once
  metube_catalogue.py --root /mnt/smarttube-archive/YouTube-Local --watch --interval 60

No database, no permanent service. Deploy as a systemd timer/service only with
operator approval.
"""

import argparse
import json
import os
import re
import sys
import time

FORMAT_VERSION = 1
VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,32}$")
SIDECAR_SUFFIX = ".info.json"
MEDIA_CANDIDATES = [".mp4", ".mkv", ".webm", ".mov", ".m4v"]
THUMB_CANDIDATES = [".webp", ".jpg", ".jpeg", ".png"]
MAX_VIDEOS = 5000
MAX_CHANNELS = 500
RETRY_LIMIT = 5
RETRY_SLEEP = 0.05
KEEP_VERSIONS = 2


def safe_relative_path(path):
    """Reject anything the TV consumer would reject."""
    if not path or path.startswith("/") or "\\" in path:
        return None
    parts = path.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return None
    return path


def canonical_id(value):
    return value if value and VIDEO_ID_RE.match(value) else None


def fsync_dir(path):
    try:
        fd = os.open(path, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError:
        pass  # CIFS dirs may not support fsync; file fsync already done.


def atomic_write(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
    for attempt in range(1, RETRY_LIMIT + 1):
        try:
            os.replace(tmp, path)
            return True
        except OSError:
            if attempt == RETRY_LIMIT:
                return False
            time.sleep(RETRY_SLEEP)
    return False


def scan_sidecar(root, rel_sidecar):
    """Return a video entry dict or None (malformed/unsafe sidecar)."""
    base = rel_sidecar[: -len(SIDECAR_SUFFIX)]
    try:
        with open(os.path.join(root, rel_sidecar), "r", encoding="utf-8", errors="replace") as fh:
            meta = json.load(fh)
    except (OSError, ValueError):
        return None
    video_id = canonical_id(str(meta.get("id", "")))
    if not video_id:
        return None
    channel_id = canonical_id(str(meta.get("channel_id", "")))
    if not channel_id:
        return None
    title = str(meta.get("title") or video_id)[:300]
    duration = meta.get("duration")
    duration = int(duration) if isinstance(duration, (int, float)) else -1

    media_path = None
    media_length = -1
    for ext in MEDIA_CANDIDATES:
        candidate = base + ext
        if os.path.exists(os.path.join(root, candidate)):
            media_path = candidate
            try:
                media_length = os.path.getsize(os.path.join(root, candidate))
            except OSError:
                media_length = -1
            break
    thumb_path = None
    for ext in THUMB_CANDIDATES:
        candidate = base + ext
        if os.path.exists(os.path.join(root, candidate)):
            thumb_path = candidate
            break
    if media_path and not safe_relative_path(media_path):
        media_path = None
        media_length = -1
    if thumb_path and not safe_relative_path(thumb_path):
        thumb_path = None

    return {
        "videoId": video_id,
        "channelId": channel_id,
        "title": title,
        "duration": duration,
        "mediaPath": media_path,
        "mediaLength": media_length,
        "thumbPath": thumb_path,
        "_relDir": rel_dirname(rel_sidecar),
    }


def rel_dirname(rel_path):
    directory = os.path.dirname(rel_path).replace(os.sep, "/")
    return "" if directory == "." else directory


def scan(root):
    """Walk the tree; group entries by channel. Bounded and defensive."""
    channels = {}
    total = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "_catalogue"]
        rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
        if rel_dir == ".":
            rel_dir = ""
        for name in filenames:
            if not name.lower().endswith(SIDECAR_SUFFIX):
                continue
            if total >= MAX_VIDEOS:
                sys.stderr.write("video limit reached; stopping scan\n")
                return channels, total
            rel_sidecar = safe_relative_path(
                f"{rel_dir}/{name}" if rel_dir else name)
            if not rel_sidecar:
                continue
            entry = scan_sidecar(root, rel_sidecar)
            if entry is None:
                continue
            total += 1
            channels.setdefault(entry["channelId"], []).append(entry)
        if len(channels) > MAX_CHANNELS:
            sys.stderr.write("channel limit exceeded\n")
            break
    for videos in channels.values():
        videos.sort(key=lambda e: (e["title"], e["videoId"]))  # stable ordering
    return channels, total


def publish(root, channels, total):
    cat_dir = os.path.join(root, "_catalogue")
    os.makedirs(cat_dir, exist_ok=True)

    revision = 0
    try:
        with open(os.path.join(cat_dir, "pointer.json"), "r", encoding="utf-8") as fh:
            revision = int(json.load(fh).get("revision", 0))
    except (OSError, ValueError):
        revision = 0
    new_revision = revision + 1
    version_dir = os.path.join(cat_dir, f"v{new_revision}")

    channel_docs = []
    library_channels = []
    for channel_id in sorted(channels):
        videos = channels[channel_id]
        channel_title = videos[0]["_relDir"] or channel_id
        entries = [{
            "videoId": e["videoId"],
            "title": e["title"],
            "duration": e["duration"],
            "mediaPath": e["mediaPath"],
            "mediaLength": e["mediaLength"],
            "thumbPath": e["thumbPath"],
        } for e in videos]
        library_channels.append({
            "channelId": channel_id,
            "title": channel_title,
            "videoCount": len(entries),
            "videos": entries,
        })
        channel_docs.append((channel_id, channel_title, {
            "formatVersion": FORMAT_VERSION,
            "channelId": channel_id,
            "title": channel_title,
            "videos": entries,
        }))

    library = {
        "formatVersion": FORMAT_VERSION,
        "revision": new_revision,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "channels": library_channels,
    }

    os.makedirs(os.path.join(version_dir, "channels"), exist_ok=True)
    if not atomic_write(os.path.join(version_dir, "library.json"),
                        json.dumps(library, ensure_ascii=False, separators=(",", ":"))):
        sys.stderr.write("library publish failed; keeping previous revision\n")
        return False
    for channel_id, _, doc in channel_docs:
        target = os.path.join(version_dir, "channels", f"{channel_id}.json")
        if not atomic_write(target, json.dumps(doc, ensure_ascii=False,
                                               separators=(",", ":"))):
            sys.stderr.write(f"channel publish failed: {channel_id}\n")
    fsync_dir(version_dir)
    fsync_dir(os.path.join(version_dir, "channels"))

    pointer = json.dumps({"formatVersion": FORMAT_VERSION, "revision": new_revision})
    if not atomic_write(os.path.join(cat_dir, "pointer.json"), pointer):
        sys.stderr.write("pointer publish failed; keeping previous revision\n")
        return False
    fsync_dir(cat_dir)

    # Retain only the newest KEEP_VERSIONS revisions.
    try:
        versions = sorted(
            (d for d in os.listdir(cat_dir) if re.match(r"^v\d+$", d)),
            key=lambda d: int(d[1:]))
        for old in versions[:-KEEP_VERSIONS]:
            target = os.path.join(cat_dir, old)
            for dirpath, _, files in os.walk(target, topdown=False):
                for name in files:
                    try:
                        os.remove(os.path.join(dirpath, name))
                    except OSError:
                        pass
                try:
                    os.rmdir(dirpath)
                except OSError:
                    pass
    except OSError:
        pass
    print(f"published revision {new_revision}: {total} videos, "
          f"{len(channels)} channels")
    return True


def main():
    parser = argparse.ArgumentParser(description="SmartTube Local catalogue producer")
    parser.add_argument("--root", required=True)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=int, default=60)
    args = parser.parse_args()
    root = args.root
    if not os.path.isdir(root):
        sys.stderr.write(f"root not found: {root}\n")
        return 2
    while True:
        channels, total = scan(root)
        if total:
            publish(root, channels, total)
        else:
            print("no valid sidecars found; nothing published")
        if not args.watch:
            break
        time.sleep(max(10, args.interval))
    return 0


if __name__ == "__main__":
    sys.exit(main())
