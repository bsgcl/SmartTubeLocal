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
PLAYLIST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,128}$")
SIDECAR_SUFFIX = ".info.json"
MEDIA_CANDIDATES = [".mp4", ".mkv", ".webm", ".mov", ".m4v"]
THUMB_CANDIDATES = [".webp", ".jpg", ".jpeg", ".png"]
MAX_VIDEOS = 5000
MAX_CHANNELS = 500
MAX_PLAYLISTS = 500
MAX_PLAYLIST_ENTRIES = 5000
MAX_TOTAL_PLAYLIST_SOURCE_ENTRIES = 50000
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


def canonical_playlist_id(value):
    return value if isinstance(value, str) and PLAYLIST_ID_RE.fullmatch(value) else None


def channel_display_title(rel_dir, channel_id):
    """Remove the bracketed canonical ID from a channel folder display name."""
    name = (rel_dir or "").replace("\\", "/").rsplit("/", 1)[-1].strip()
    suffix = " [" + channel_id + "]"
    if name.endswith(suffix):
        name = name[:-len(suffix)].rstrip()
    return name or channel_id


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
    pending_entries = {}
    kept = set()
    total = 0
    sidecars_read = 0
    reused = 0
    # Collect directory read failures: an unreachable/unreadable tree must
    # never be mistaken for an empty archive.
    scan_errors = []

    def on_walk_error(name):
        scan_errors.append(name)

    for dirpath, dirnames, filenames in os.walk(root, onerror=on_walk_error):
        # Ignore our isolated Gate 0 fixtures; they are not household videos.
        dirnames[:] = [d for d in dirnames if d not in
                       ("_catalogue", "_catalogue-source", "_stage3_gate")]
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
            # A feed-level .info.json has no playable media. yt-dlp also writes
            # video metadata before the final MP4 move. Exclude both until the
            # matching complete media file exists, without caching the entry.
            if not entry["mediaPath"] or entry["mediaLength"] <= 0:
                # Keep compact metadata for unchanged, unfinished and feed-only
                # sidecars. Do not reread their potentially large JSON each scan.
                pending_entries[rel_sidecar] = entry
                kept.add(rel_sidecar)
                continue
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
             "total": total, "deleted": removed, "errors": len(scan_errors)}
    if scan_errors:
        sys.stderr.write(f"WARNING: {len(scan_errors)} directory read "
                         f"failure(s); scan may be incomplete\n")
    return channels, total, stats, cache, pending_entries


def load_playlist_definitions(path):
    """Load the manually refreshed source playlist inventory, if configured.

    This file is deliberately separate from the live archive scan: normal
    MeTube events reconcile known playlist membership without contacting
    YouTube. A malformed configured file aborts publication instead of
    silently erasing a previously published playlist index.
    """
    if not path or not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8") as fh:
        root = json.load(fh)
    if not isinstance(root, dict) or root.get("schemaVersion") != 1:
        raise ValueError("playlist definitions schemaVersion must be 1")
    channel_id = canonical_id(root.get("sourceChannelId"))
    channel_title = root.get("sourceChannelTitle", channel_id)
    playlists = root.get("playlists")
    if (not channel_id or not isinstance(channel_title, str) or not channel_title.strip()
            or len(channel_title) > 200 or not isinstance(playlists, list)
            or len(playlists) > MAX_PLAYLISTS):
        raise ValueError("invalid playlist definitions header")
    result = []
    seen = set()
    total_entries = 0
    for raw in playlists:
        if not isinstance(raw, dict):
            raise ValueError("playlist definition must be an object")
        playlist_id = canonical_playlist_id(raw.get("playlistId"))
        title = raw.get("title")
        ids = raw.get("videoIds")
        item_channel = canonical_id(raw.get("channelId"))
        source_url = raw.get("sourceUrl")
        source_count = raw.get("sourceVideoCount")
        if (not playlist_id or playlist_id in seen or not isinstance(title, str)
                or not title.strip() or len(title) > 300 or item_channel != channel_id
                or not isinstance(source_url, str) or len(source_url) > 2048
                or not source_url.startswith("https://www.youtube.com/playlist?")
                or not isinstance(ids, list) or not isinstance(source_count, int)
                or source_count < len(ids) or source_count > MAX_TOTAL_PLAYLIST_SOURCE_ENTRIES):
            raise ValueError("invalid playlist definition record")
        if any(not canonical_id(video_id) for video_id in ids):
            raise ValueError("invalid video ID in playlist definition")
        total_entries += len(ids)
        if total_entries > MAX_TOTAL_PLAYLIST_SOURCE_ENTRIES:
            raise ValueError("total source playlist entry limit exceeded")
        seen.add(playlist_id)
        result.append({"playlistId": playlist_id, "title": title.strip(),
                       "channelId": item_channel, "sourceUrl": source_url,
                       "channelTitle": channel_title.strip(),
                       "sourceVideoCount": source_count, "videoIds": list(ids)})
    return result


def build_playlist_documents(definitions, channels):
    """Reconcile ordered source IDs against playable archive media IDs."""
    available = {
        entry["videoId"]
        for videos in channels.values()
        for entry in videos
        if safe_relative_path(entry.get("mediaPath")) and entry.get("mediaLength", -1) > 0
    }
    summaries = []
    details = []
    for definition in definitions:
        source_ids = definition["videoIds"]
        local_ids = [video_id for video_id in source_ids if video_id in available]
        if len(local_ids) > MAX_PLAYLIST_ENTRIES:
            raise ValueError("local playlist entry limit exceeded")
        missing_count = len(source_ids) - len(local_ids)
        summary = {
            "playlistId": definition["playlistId"],
            "title": definition["title"],
            "channelId": definition["channelId"],
            "channelTitle": definition["channelTitle"],
            "videoCount": len(local_ids),
            "sourceVideoCount": definition["sourceVideoCount"],
            "missingVideoCount": max(0, definition["sourceVideoCount"] - len(local_ids)),
            "thumbnailVideoId": local_ids[0] if local_ids else None,
        }
        detail = {
            "formatVersion": FORMAT_VERSION,
            "playlistId": definition["playlistId"],
            "title": definition["title"],
            "channelId": definition["channelId"],
            "channelTitle": definition["channelTitle"],
            "sourceVideoCount": definition["sourceVideoCount"],
            "missingVideoCount": max(0, definition["sourceVideoCount"] - len(local_ids)),
            "thumbnailVideoId": local_ids[0] if local_ids else None,
            "videoIds": local_ids,
        }
        summaries.append(summary)
        details.append((definition["playlistId"], detail))
    return summaries, details


def published_playlist_index_is_nonempty(root):
    """Detect a missing source file that would otherwise erase a valid index."""
    cat_dir = os.path.join(root, "_catalogue")
    revision, ok = read_current_revision(cat_dir)
    if not ok or not revision:
        return False
    path = os.path.join(cat_dir, f"v{revision}", "playlists.json")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            document = json.load(fh)
        return isinstance(document, dict) and bool(document.get("playlists"))
    except (OSError, ValueError):
        return False


def content_signature(channels, playlist_details=None):
    """Stable content fingerprint of the catalogue payload (revision and
    timestamps excluded). Used to skip publishing unchanged catalogues."""
    payload = []
    for channel_id in sorted(channels):
        videos = channels[channel_id]
        channel_title = channel_display_title(videos[0]["_relDir"], channel_id)
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
    playlist_payload = [doc for _, doc in (playlist_details or [])]
    blob = json.dumps({"channels": payload, "playlists": playlist_payload},
                      ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def save_meta_cache(path, channels, signature, pending_entries=None):
    """Persist the metadata cache (outside the published catalogue). Entries
    are stored as-is minus the per-walk working fields."""
    entries = {}
    for videos in channels.values():
        for e in videos:
            key = e["_base"] + SIDECAR_SUFFIX
            stored = dict(e)
            stored.pop("_relDir", None)
            entries[key] = stored
    if pending_entries:
        entries.update(pending_entries)
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


def publish(root, channels, total, playlist_summaries=None, playlist_details=None):
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
    playlist_summaries = playlist_summaries or []
    playlist_details = playlist_details or []
    for channel_id in sorted(channels):
        videos = channels[channel_id]
        channel_title = channel_display_title(videos[0]["_relDir"], channel_id)
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
    playlists_index = {
        "formatVersion": FORMAT_VERSION,
        "revision": new_revision,
        "playlists": playlist_summaries,
    }

    def abort(reason):
        remove_tree(version_dir)  # discard the partial revision
        sys.stderr.write(f"publication aborted ({reason}); "
                         f"previous revision {current} preserved\n")
        return False

    os.makedirs(os.path.join(version_dir, "channels"), exist_ok=True)
    remove_tree(version_dir)  # discard any stale files from a reused number
    os.makedirs(os.path.join(version_dir, "channels"), exist_ok=True)
    os.makedirs(os.path.join(version_dir, "playlists"), exist_ok=True)
    if not atomic_write(os.path.join(version_dir, "library.json"),
                        json.dumps(library, ensure_ascii=False,
                                   separators=(",", ":"))):
        return abort("library write failed")
    for channel_id, doc in channel_docs:
        target = os.path.join(version_dir, "channels", f"{channel_id}.json")
        if not atomic_write(target, json.dumps(doc, ensure_ascii=False,
                                               separators=(",", ":"))):
            return abort(f"channel write failed: {channel_id}")
    if not atomic_write(os.path.join(version_dir, "playlists.json"),
                        json.dumps(playlists_index, ensure_ascii=False,
                                   separators=(",", ":"))):
        return abort("playlists index write failed")
    for playlist_id, doc in playlist_details:
        doc = dict(doc)
        doc["revision"] = new_revision
        target = os.path.join(version_dir, "playlists", f"{playlist_id}.json")
        if not atomic_write(target, json.dumps(doc, ensure_ascii=False,
                                                separators=(",", ":"))):
            return abort(f"playlist write failed: {playlist_id}")
    fsync_dir(version_dir)
    fsync_dir(os.path.join(version_dir, "channels"))
    fsync_dir(os.path.join(version_dir, "playlists"))

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


def run_once(root, publish_always=False, playlist_definitions_path=None):
    """One scan + conditional publish. Returns True when a revision was
    published (or already up to date and nothing needed doing)."""
    meta_cache_path = os.path.join(root, META_CACHE_NAME)
    if (playlist_definitions_path and not os.path.isfile(playlist_definitions_path)
            and published_playlist_index_is_nonempty(root)):
        sys.stderr.write("playlist definitions are missing while a published index exists; "
                         "publication aborted to preserve it\n")
        return False
    try:
        definitions = load_playlist_definitions(playlist_definitions_path)
    except (OSError, ValueError) as error:
        sys.stderr.write(f"playlist definitions invalid; publication aborted: {error}\n")
        return False
    channels, total, stats, cache, pending_entries = scan(root, meta_cache_path)
    playlist_summaries, playlist_details = build_playlist_documents(definitions, channels)
    signature = content_signature(channels, playlist_details)
    previous_signature = cache.get("signature") if cache else None
    saved = False
    # An incomplete scan must never look like an unchanged or empty archive.
    if stats.get("errors"):
        sys.stderr.write(f"scan incomplete ({stats['errors']} read failure"
                         "s); publication aborted; previous catalogue "
                         "preserved\n")
        return False
    if signature == previous_signature and not publish_always:
        print(f"no catalogue changes; skipping publish "
              f"(scanned {total}, sidecars read {stats['sidecarsRead']}, "
              f"reused {stats['reused']}, deleted {stats['deleted']})")
        saved = save_meta_cache(meta_cache_path, channels, signature, pending_entries)
        return True
    # Publish a valid empty catalogue for a genuinely empty initial archive.
    # The same code publishes an empty revision after the final video vanishes.
    # All directory-read failures are rejected above, before this decision.
    published = publish(root, channels, total, playlist_summaries, playlist_details)
    if published:
        saved = save_meta_cache(meta_cache_path, channels, signature, pending_entries)
    elif cache is not None or previous_signature is not None:
        # Publication failed: keep the (possibly refreshed) metadata cache for
        # reuse, but keep the OLD signature so the next run retries publishing.
        saved = save_meta_cache(meta_cache_path, channels, previous_signature, pending_entries)
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
    parser.add_argument("--playlist-definitions", default=None,
                        help="manually refreshed source definitions (default: ROOT/_catalogue-source/playlist-definitions.json)")
    args = parser.parse_args()
    root = args.root
    if not os.path.isdir(root):
        sys.stderr.write(f"root not found: {root}\n")
        return 2
    playlist_definitions_path = args.playlist_definitions or os.path.join(
        root, "_catalogue-source", "playlist-definitions.json")
    while True:
        success = run_once(root, publish_always=args.force,
                           playlist_definitions_path=playlist_definitions_path)
        if not args.watch:
            return 0 if success else 1
        time.sleep(max(10, args.interval))


if __name__ == "__main__":
    sys.exit(main())
