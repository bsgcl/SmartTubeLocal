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
  - publication is all-or-nothing: library and EVERY channel file are written
    first; if any write fails the new revision directory is removed and the
    previous valid pointer/revision stays advertised. The pointer is written
    last, so readers never see a half-published revision.
  - the current revision (per the pointer) is never garbage-collected.

Incremental scanning (watch mode):
  - sidecar metadata is cached in <root>/._catalogue_meta_cache.json, outside
    the published catalogue. A sidecar is re-read only when its size or mtime
    changes; media/thumbnail siblings are re-stat'ed every scan so deleted or
    replaced media is detected without re-reading the 720 KB sidecars.
  - a catalogue revision is published only when content actually changed.
  - recovery: if the cache file is missing, corrupt or schema-mismatched, the
    next scan is a full scan; deleting the cache file forces one manually.

Usage:
  metube_catalogue.py --root /mnt/smarttube-archive/YouTube-Local --once
  metube_catalogue.py --root /mnt/smarttube-archive/YouTube-Local --watch --interval 60

No database, no permanent service. Deploy as a systemd timer/service only with
operator approval.
"""

import argparse
import hashlib
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
META_CACHE_NAME = "._catalogue_meta_cache.json"
META_CACHE_SCHEMA = 2


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


def remove_tree(path):
    """Best-effort recursive delete (used to discard unpublishable revisions)."""
    for dirpath, _, files in os.walk(path, topdown=False):
        for name in files:
            try:
                os.remove(os.path.join(dirpath, name))
            except OSError:
                pass
        try:
            os.rmdir(dirpath)
        except OSError:
            pass


def rel_dirname(rel_path):
    directory = os.path.dirname(rel_path).replace(os.sep, "/")
    return "" if directory == "." else directory


def scan_sidecar(root, rel_sidecar):
    """Parse one .info.json sidecar. Return a video entry dict or None
    (malformed/unsafe sidecar). Media/thumbnail paths are filled in later by
    refresh_media_fields()."""
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
    return {
        "videoId": video_id,
        "channelId": channel_id,
        "title": title,
        "duration": duration,
        "mediaPath": None,
        "mediaLength": -1,
        "thumbPath": None,
    }


def refresh_media_fields(root, entry):
    """Stat the media/thumbnail siblings of a sidecar (cheap; no content read)
    and update the entry. Returns True when the media artwork/location changed
    compared with the previous scan."""
    base = entry["_base"]
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
    changed = (entry.get("mediaPath") != media_path
               or entry.get("mediaLength") != media_length
               or entry.get("thumbPath") != thumb_path)
    entry["mediaPath"] = media_path
    entry["mediaLength"] = media_length
    entry["thumbPath"] = thumb_path
    return changed


def load_meta_cache(path):
    """Load the producer metadata cache. Returns the cache doc or None when
    missing/invalid (a None result forces a full scan: the recovery path)."""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            cache = json.load(fh)
    except (OSError, ValueError):
        return None
    if not isinstance(cache, dict) or cache.get("schema") != META_CACHE_SCHEMA:
        return None
    if not isinstance(cache.get("entries"), dict):
        return None
    return cache


def scan(root, meta_cache_path):
    """Walk the tree and group entries by channel. Bounded and defensive.

    Sidecars whose size+mtime match the metadata cache are NOT re-read; their
    cached metadata is reused. Media/thumbnail siblings are always re-stat'ed
    so deleted or replaced media is detected. Deleted sidecars drop out of the
    catalogue automatically (the catalogue is rebuilt from what exists).

    Returns (channels, total, stats, cache_doc_or_None)."""
    cache = load_meta_cache(meta_cache_path)
    if cache is None and os.path.exists(meta_cache_path):
        sys.stderr.write("metadata cache invalid; performing full scan\n")
    cache_entries = cache["entries"] if cache else {}
    channels = {}
    kept = set()
    total = 0
    sidecars_read = 0
    reused = 0
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
                break
            rel_sidecar = safe_relative_path(
                f"{rel_dir}/{name}" if rel_dir else name)
            if not rel_sidecar:
                continue
            try:
                st = os.stat(os.path.join(root, rel_sidecar))
            except OSError:
                continue
            cached = cache_entries.get(rel_sidecar)
            if (isinstance(cached, dict) and cached.get("_size") == st.st_size
                    and cached.get("_mtime") == st.st_mtime
                    and canonical_id(str(cached.get("videoId", "")))):
                entry = dict(cached)
                reused += 1
            else:
                entry = scan_sidecar(root, rel_sidecar)
                sidecars_read += 1
                if entry is None:
                    cache_entries.pop(rel_sidecar, None)
                    continue
            entry["_size"] = st.st_size
            entry["_mtime"] = st.st_mtime
            entry["_relDir"] = rel_dirname(rel_sidecar)
            entry["_base"] = rel_sidecar[:-len(SIDECAR_SUFFIX)]
            refresh_media_fields(root, entry)
            kept.add(rel_sidecar)
            total += 1
            channels.setdefault(entry["channelId"], []).append(entry)
        if len(channels) > MAX_CHANNELS:
            sys.stderr.write("channel limit exceeded\n")
            break
    for videos in channels.values():
        videos.sort(key=lambda e: (e["title"], e["videoId"]))  # stable ordering
    removed = sum(1 for key in cache_entries if key not in kept)
    stats = {"sidecarsRead": sidecars_read, "reused": reused,
             "total": total, "deleted": removed}
    return channels, total, stats, cache


def content_signature(channels):
    """Stable content fingerprint of the catalogue payload (revision and
    timestamps excluded). Used to skip publishing unchanged catalogues."""
    payload = []
    for channel_id in sorted(channels):
        videos = channels[channel_id]
        channel_title = videos[0]["_relDir"] or channel_id
        payload.append({
            "channelId": channel_id,
            "title": channel_title,
            "videoCount": len(videos),
            "videos": [{
                "videoId": e["videoId"],
                "title": e["title"],
                "duration": e["duration"],
                "mediaPath": e["mediaPath"],
                "mediaLength": e["mediaLength"],
                "thumbPath": e["thumbPath"],
            } for e in videos],
        })
    blob = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def save_meta_cache(path, channels, signature):
    """Persist the metadata cache (outside the published catalogue). Entries
    are stored as-is minus the per-walk working fields."""
    entries = {}
    for videos in channels.values():
        for e in videos:
            key = e["_base"] + SIDECAR_SUFFIX
            stored = dict(e)
            stored.pop("_relDir", None)
            entries[key] = stored
    doc = {"schema": META_CACHE_SCHEMA, "signature": signature, "entries": entries}
    return atomic_write(path, json.dumps(doc, ensure_ascii=False,
                                         separators=(",", ":")))


def read_current_revision(cat_dir):
    """Read the advertised revision. Returns (revision, ok). When a pointer
    file exists but cannot be parsed, publication is refused: guessing
    revision 1 could overwrite the currently advertised revision."""
    pointer_path = os.path.join(cat_dir, "pointer.json")
    if not os.path.exists(pointer_path):
        return 0, True  # first-ever publication
    try:
        with open(pointer_path, "r", encoding="utf-8") as fh:
            revision = int(json.load(fh).get("revision", 0))
        if revision < 0:
            return None, False
        return revision, True
    except (OSError, ValueError):
        return None, False


def publish(root, channels, total):
    """Publish a complete new revision, all-or-nothing.

    Every required channel file is mandatory: if the library or ANY channel
    write fails, the new revision directory is removed and the previous
    pointer (and revision) stays valid and advertised. A pointer write
    failure also aborts without advertising. Garbage collection never removes
    the revision the pointer currently advertises."""
    cat_dir = os.path.join(root, "_catalogue")
    os.makedirs(cat_dir, exist_ok=True)

    current, ok = read_current_revision(cat_dir)
    if not ok:
        sys.stderr.write("existing pointer unreadable; refusing to publish "
                         "to avoid unsafe revision reuse\n")
        return False
    new_revision = current + 1
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
        channel_docs.append((channel_id, {
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

    def abort(reason):
        remove_tree(version_dir)  # discard the partial revision
        sys.stderr.write(f"publication aborted ({reason}); "
                         f"previous revision {current} preserved\n")
        return False

    os.makedirs(os.path.join(version_dir, "channels"), exist_ok=True)
    remove_tree(version_dir)  # discard any stale files from a reused number
    os.makedirs(os.path.join(version_dir, "channels"), exist_ok=True)
    if not atomic_write(os.path.join(version_dir, "library.json"),
                        json.dumps(library, ensure_ascii=False,
                                   separators=(",", ":"))):
        return abort("library write failed")
    for channel_id, doc in channel_docs:
        target = os.path.join(version_dir, "channels", f"{channel_id}.json")
        if not atomic_write(target, json.dumps(doc, ensure_ascii=False,
                                               separators=(",", ":"))):
            return abort(f"channel write failed: {channel_id}")
    fsync_dir(version_dir)
    fsync_dir(os.path.join(version_dir, "channels"))

    pointer = json.dumps({"formatVersion": FORMAT_VERSION,
                          "revision": new_revision})
    if not atomic_write(os.path.join(cat_dir, "pointer.json"), pointer):
        return abort("pointer write failed")
    fsync_dir(cat_dir)

    # Retain only the newest KEEP_VERSIONS revisions, plus the advertised one.
    try:
        versions = sorted(
            (d for d in os.listdir(cat_dir) if re.match(r"^v\d+$", d)),
            key=lambda d: int(d[1:]))
        keep = {int(d[1:]) for d in versions[-KEEP_VERSIONS:]}
        keep.add(current)
        for old in versions:
            if int(old[1:]) in keep:
                continue
            remove_tree(os.path.join(cat_dir, old))
    except OSError:
        pass
    print(f"published revision {new_revision}: {total} videos, "
          f"{len(channels)} channels")
    return True


def run_once(root, publish_always=False):
    """One scan + conditional publish. Returns True when a revision was
    published (or already up to date and nothing needed doing)."""
    meta_cache_path = os.path.join(root, META_CACHE_NAME)
    channels, total, stats, cache = scan(root, meta_cache_path)
    signature = content_signature(channels)
    previous_signature = cache.get("signature") if cache else None
    saved = False
    if total == 0 and previous_signature is None:
        print("no valid sidecars found; nothing published")
        return True
    if signature == previous_signature and not publish_always:
        print(f"no catalogue changes; skipping publish "
              f"(scanned {total}, sidecars read {stats['sidecarsRead']}, "
              f"reused {stats['reused']}, deleted {stats['deleted']})")
        saved = save_meta_cache(meta_cache_path, channels, signature)
        return True
    published = False
    if total:
        published = publish(root, channels, total)
    else:
        print("no valid sidecars found; nothing published")
    if published:
        saved = save_meta_cache(meta_cache_path, channels, signature)
    elif cache is not None or previous_signature is not None:
        # Publication failed: keep the (possibly refreshed) metadata cache for
        # reuse, but keep the OLD signature so the next run retries publishing.
        saved = save_meta_cache(meta_cache_path, channels, previous_signature)
    if not saved:
        # A failed cache save is safe: the next run simply does a full scan.
        sys.stderr.write("metadata cache save failed; next scan will be full\n")
    if published:
        print(f"sidecars read {stats['sidecarsRead']}, reused "
              f"{stats['reused']}, deleted {stats['deleted']}")
    return published


def main():
    parser = argparse.ArgumentParser(description="SmartTube Local catalogue producer")
    parser.add_argument("--root", required=True)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--interval", type=int, default=60)
    parser.add_argument("--force", action="store_true",
                        help="publish even when content is unchanged")
    args = parser.parse_args()
    root = args.root
    if not os.path.isdir(root):
        sys.stderr.write(f"root not found: {root}\n")
        return 2
    while True:
        run_once(root, publish_always=args.force)
        if not args.watch:
            break
        time.sleep(max(10, args.interval))
    return 0


if __name__ == "__main__":
    sys.exit(main())
