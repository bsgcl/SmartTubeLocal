#!/usr/bin/env python3
"""Backfill bounded SponsorBlock timing records into the active archive revision.

This runs on an operator-controlled producer with WAN access. The TV reads only
the resulting revision files over the existing SMB archive share.
"""
import argparse
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

LEDGER_PATH = Path(__file__).resolve().parent / "sponsorblock-failures.json"
MAX_LEDGER_BYTES = 32 * 1024 * 1024

API = "https://sponsor.ajay.app/api/skipSegments"
CATEGORIES = ("sponsor", "selfpromo", "interaction", "intro", "outro",
              "preview", "music_offtopic", "poi_highlight", "filler")
ACTIONS = {"skip", "mute"}
MAX_SEGMENTS = 256
MAX_BYTES = 256 * 1024
VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
CATEGORY_SET = frozenset(CATEGORIES)


class SponsorBlockRequestError(RuntimeError):
    pass


def atomic_json(path, document):
    payload = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_BYTES:
        raise ValueError("SponsorBlock record exceeds 256 KiB")
    parent = os.path.dirname(path)
    os.makedirs(parent, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".sponsor-", suffix=".tmp", dir=parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def fetch_result(video_id, duration_seconds, timeout, opener=None):
    if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
        raise ValueError("video ID must be a canonical 11-character YouTube ID")
    if (isinstance(duration_seconds, bool) or not isinstance(duration_seconds, (int, float)) or
            not math.isfinite(duration_seconds) or duration_seconds <= 0):
        raise ValueError("video duration must be finite and positive")
    if not CATEGORY_SET or any(not isinstance(category, str) for category in CATEGORIES):
        raise ValueError("SponsorBlock categories are invalid")
    query = [("videoID", video_id),
             ("categories", json.dumps(list(CATEGORIES), separators=(",", ":")))]
    request = urllib.request.Request(API + "?" + urllib.parse.urlencode(query),
                                     headers={"User-Agent": "SmartTube-Local-SponsorCache/1.0"})
    opener = opener or urllib.request.urlopen
    try:
        with opener(request, timeout=timeout) as response:
            raw = response.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return [], True
        if error.code == 400:
            raise SponsorBlockRequestError("SponsorBlock rejected the request (HTTP 400)") from error
        else:
            raise SponsorBlockRequestError(f"SponsorBlock request failed (HTTP {error.code})") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise SponsorBlockRequestError(f"SponsorBlock network request failed: {error}") from error
    segments, rejected = parse_response_report(raw, video_id, duration_seconds)
    if rejected:
        print(f"WARN {video_id}: omitted {rejected} invalid-bound SponsorBlock segment(s)")
    return segments, False


def parse_response(raw, video_id, duration_seconds):
    return parse_response_report(raw, video_id, duration_seconds)[0]


def parse_response_report(raw, video_id, duration_seconds):
    if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
        raise ValueError("video ID must be a canonical 11-character YouTube ID")
    if len(raw) > MAX_BYTES:
        raise ValueError("SponsorBlock API response exceeds 256 KiB")
    response = json.loads(raw.decode("utf-8"))
    if not isinstance(response, list) or len(response) > MAX_SEGMENTS:
        raise ValueError("SponsorBlock API returned an invalid segment list")
    segments = []
    rejected_bounds = 0
    for segment in response:
        if not isinstance(segment, dict):
            raise ValueError("SponsorBlock API returned a malformed segment")
        span = segment.get("segment")
        category = segment.get("category")
        action = segment.get("actionType")
        api_duration = segment.get("videoDuration")
        if (not isinstance(span, list) or len(span) != 2 or
                any(isinstance(value, bool) or not isinstance(value, (int, float)) or
                    not math.isfinite(value) for value in span) or
                isinstance(api_duration, bool) or not isinstance(api_duration, (int, float)) or
                not math.isfinite(api_duration) or
                not isinstance(category, str) or category not in CATEGORY_SET or
                not isinstance(action, str) or action not in ACTIONS or
                (segment.get("videoID") is not None and segment.get("videoID") != video_id) or
                (api_duration > 0 and abs(api_duration - duration_seconds) > 2.0)):
            raise ValueError(f"SponsorBlock timing/schema does not match {video_id}")
        if span[0] < 0 or span[1] <= span[0] or span[1] > duration_seconds:
            rejected_bounds += 1
            continue
        segments.append({"startMs": round(span[0] * 1000), "endMs": round(span[1] * 1000),
                         "category": category, "action": action})
    if response and not segments and rejected_bounds:
        raise ValueError(f"SponsorBlock returned no valid segment timings for {video_id}")
    return segments, rejected_bounds


def fetch(video_id, duration_seconds, timeout, opener=None):
    return fetch_result(video_id, duration_seconds, timeout, opener)[0]


def select_videos(videos, video_id=None, limit=0):
    if video_id is not None:
        if not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id):
            raise ValueError("video ID must be a canonical 11-character YouTube ID")
        matches = [video for video in videos if video.get("videoId") == video_id]
        if len(matches) != 1:
            raise ValueError(f"expected one catalogue entry for {video_id}, found {len(matches)}")
        return matches
    return videos[:limit] if limit > 0 else videos


def media_mtime_for(video, media_stat):
    catalogue_mtime = video.get("mediaMtimeNs")
    if catalogue_mtime is None:
        return media_stat.st_mtime_ns
    if (isinstance(catalogue_mtime, bool) or not isinstance(catalogue_mtime, int) or
            catalogue_mtime <= 0 or catalogue_mtime != media_stat.st_mtime_ns):
        raise ValueError("local media changed since catalogue publication")
    return catalogue_mtime


def active_revision(catalogue):
    with open(os.path.join(catalogue, "pointer.json"), encoding="utf-8") as stream:
        pointer = json.load(stream)
    revision = pointer.get("revision")
    if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
        raise ValueError("active catalogue pointer is invalid")
    return revision


def require_active_revision(catalogue, expected_revision):
    if active_revision(catalogue) != expected_revision:
        raise ValueError("active catalogue revision changed during cache generation; record not written")


def save_failure_ledger(failures):
    """Atomically persist failure details beside this script, not in the archive."""
    document = {"formatVersion": 1, "failures": failures}
    payload = json.dumps(document, ensure_ascii=False, sort_keys=True, indent=2).encode("utf-8")
    if len(payload) > MAX_LEDGER_BYTES:
        raise ValueError("SponsorBlock failure ledger exceeds 32 MiB")
    directory = str(LEDGER_PATH.parent)
    fd, temporary = tempfile.mkstemp(prefix=".sponsorblock-failures-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, LEDGER_PATH)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def load_failure_ledger():
    if not LEDGER_PATH.exists():
        save_failure_ledger({})
        print(f"Created failure ledger: {LEDGER_PATH}", flush=True)
        return {}
    if LEDGER_PATH.stat().st_size > MAX_LEDGER_BYTES:
        raise ValueError("SponsorBlock failure ledger exceeds 32 MiB")
    with LEDGER_PATH.open(encoding="utf-8") as stream:
        document = json.load(stream)
    if (not isinstance(document, dict) or document.get("formatVersion") != 1 or
            not isinstance(document.get("failures"), dict)):
        raise ValueError("SponsorBlock failure ledger is invalid; refusing to overwrite it")
    failures = document["failures"]
    if any(not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id) or
           not isinstance(entry, dict) or not isinstance(entry.get("reason"), str)
           for video_id, entry in failures.items()):
        raise ValueError("SponsorBlock failure ledger entries are invalid; refusing to overwrite it")
    return failures


def media_identity(video, media_stat):
    return {"mediaPath": video["mediaPath"], "mediaLength": media_stat.st_size,
            "mediaMtimeNs": media_stat.st_mtime_ns,
            "durationMs": round(float(video["duration"]) * 1000)}


def failure_matches(entry, identity):
    return isinstance(entry, dict) and all(entry.get(key) == value
                                           for key, value in identity.items())


def record_failure(failures, video_id, identity, error):
    failures[video_id] = dict(identity, reason=str(error)[:400],
                              recordedAt=datetime.now(timezone.utc).isoformat())
    save_failure_ledger(failures)


def process_video(video, index, total, args, catalogue, revision, output, failures):
    """Return cached, blocked, saved, empty or failed; never alter archive media."""
    video_id = video["videoId"]
    label = f"[{index}/{total}] {video_id}"
    if not VIDEO_ID_RE.fullmatch(video_id):
        print(f"ERROR {label}: invalid video ID", flush=True)
        return "failed"
    relative_media = video.get("mediaPath")
    if (not isinstance(relative_media, str) or
            relative_media.startswith(("/", "\\")) or ".." in relative_media.split("/")):
        print(f"ERROR {label}: unsafe media path", flush=True)
        return "failed"
    media_path = os.path.realpath(os.path.join(args.archive_root, *relative_media.split("/")))
    if os.path.commonpath((os.path.realpath(args.archive_root), media_path)) != os.path.realpath(args.archive_root):
        print(f"ERROR {label}: media path escapes archive root", flush=True)
        return "failed"

    try:
        media_stat = os.stat(media_path)
    except OSError as error:
        print(f"ERROR {label}: media unavailable ({error}); will retry next run", flush=True)
        return "failed"
    if media_stat.st_size != video.get("mediaLength"):
        print(f"ERROR {label}: file size differs from catalogue; will retry next run", flush=True)
        return "failed"
    try:
        media_mtime_ns = media_mtime_for(video, media_stat)
    except ValueError as error:
        print(f"ERROR {label}: {error}; will retry after catalogue refresh", flush=True)
        return "failed"

    identity = media_identity(video, media_stat)
    path = os.path.join(output, video_id + ".json")
    old_record = None
    if os.path.isfile(path) and not args.force:
        try:
            if os.path.getsize(path) > MAX_BYTES:
                raise ValueError("existing cache record exceeds 256 KiB")
            with open(path, encoding="utf-8") as stream:
                old_record = json.load(stream)
        except (OSError, ValueError):
            pass
    if valid_record(old_record, dict(video, mediaMtimeNs=media_mtime_ns), revision) and not args.force:
        if video_id in failures:
            del failures[video_id]
            save_failure_ledger(failures)
        if args.verbose:
            print(f"CACHED {label}: valid record reused", flush=True)
        return "cached"

    if failure_matches(failures.get(video_id), identity) and not args.retry_failures:
        if args.verbose:
            print(f"BLOCKED {label}: {failures[video_id]['reason']}", flush=True)
        return "blocked"

    print(f"CHECK {label}: verifying local MP4 duration", flush=True)
    try:
        probe = subprocess.run([args.ffprobe, "-v", "error", "-show_entries", "format=duration",
                                "-of", "default=noprint_wrappers=1:nokey=1", media_path],
                               capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"ERROR {label}: ffprobe unavailable ({error}); will retry", flush=True)
        return "failed"
    try:
        if probe.returncode != 0:
            raise ValueError(f"ffprobe cannot verify media duration: {probe.stderr.strip()}")
        media_duration = float(probe.stdout.strip())
        if not math.isfinite(media_duration) or media_duration <= 0 or abs(media_duration - float(video["duration"])) > 1.5:
            raise ValueError("local media duration differs from source timeline")
    except ValueError as error:
        record_failure(failures, video_id, identity, error)
        print(f"BLOCKED {label}: {error}; recorded in failure ledger", flush=True)
        return "failed"

    print(f"FETCH {label}: requesting SponsorBlock segments", flush=True)
    try:
        segments, not_found = fetch_result(video_id, float(video["duration"]), args.timeout)
    except ValueError as error:
        record_failure(failures, video_id, identity, error)
        print(f"BLOCKED {label}: {error}; recorded in failure ledger", flush=True)
        return "failed"
    except SponsorBlockRequestError as error:
        print(f"ERROR {label}: {error}; transient API failure not blocked", flush=True)
        return "failed"

    try:
        final_stat = os.stat(media_path)
        if (final_stat.st_size != media_stat.st_size or
                final_stat.st_mtime_ns != media_stat.st_mtime_ns):
            raise ValueError("local media changed during SponsorBlock request")
        record = {"formatVersion": 1, "revision": revision, "videoId": video_id,
                  "durationMs": round(float(video["duration"]) * 1000),
                  "mediaPath": relative_media, "mediaLength": video.get("mediaLength"),
                  "mediaMtimeNs": media_mtime_ns, "segments": segments}
        if not valid_record(record, dict(video, mediaMtimeNs=media_mtime_ns), revision):
            raise ValueError("generated SponsorBlock record failed validation")
        require_active_revision(catalogue, revision)
        atomic_json(path, record)
    except (OSError, ValueError) as error:
        print(f"ERROR {label}: {error}; cache not written", flush=True)
        return "failed"

    if video_id in failures:
        del failures[video_id]
        save_failure_ledger(failures)
    print(f"SAVED {label}: {len(segments)} segment(s)" +
          (" (none reported)" if not segments else ""), flush=True)
    if args.delay:
        time.sleep(args.delay)
    return "empty" if not_found or not segments else "saved"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive_root", help="local mount or producer path for the archive share")
    parser.add_argument("--limit", type=int, default=0, help="maximum videos to inspect; 0 means all")
    parser.add_argument("--video-id", help="process exactly one canonical YouTube video ID")
    parser.add_argument("--output-dir", help="record directory; defaults to the active revision cache")
    parser.add_argument("--delay", type=float, default=0.25, help="seconds between API requests")
    parser.add_argument("--timeout", type=float, default=20)
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"),
                        help="ffprobe executable used to confirm untrimmed local duration")
    parser.add_argument("--force", action="store_true", help="refresh existing valid cache records")
    parser.add_argument("--retry-failures", action="store_true",
                        help="retry previously blocked videos without refreshing valid records")
    parser.add_argument("--verbose", action="store_true", help="show every reused/blocked record")
    args = parser.parse_args()

    if args.limit < 0 or not math.isfinite(args.delay) or args.delay < 0.25:
        raise SystemExit("--limit must be non-negative and --delay must be at least 0.25 seconds")
    if not math.isfinite(args.timeout) or not 1 <= args.timeout <= 120:
        raise SystemExit("--timeout must be between 1 and 120 seconds")
    if args.video_id and args.limit:
        raise SystemExit("--video-id cannot be combined with --limit")

    catalogue = os.path.join(args.archive_root, "_catalogue")
    try:
        revision = active_revision(catalogue)
        revision_dir = os.path.join(catalogue, f"v{revision}")
        with open(os.path.join(revision_dir, "library.json"), encoding="utf-8") as stream:
            library = json.load(stream)
        if library.get("revision") != revision:
            raise ValueError("library revision does not match pointer")
        failures = load_failure_ledger()
    except (OSError, ValueError) as error:
        raise SystemExit(f"Cannot start SponsorBlock refresh: {error}") from error
    videos = [video for channel in library.get("channels", [])
              for video in channel.get("videos", [])
              if isinstance(video.get("videoId"), str) and isinstance(video.get("duration"), (int, float))
              and video["duration"] > 0]
    try:
        videos = select_videos(videos, args.video_id, args.limit)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if videos and not args.ffprobe:
        raise SystemExit("ffprobe is required to verify local media duration before caching timestamps")

    output = args.output_dir or os.path.join(revision_dir, "sponsorblock")
    counts = {"cached": 0, "blocked": 0, "saved": 0, "empty": 0, "failed": 0}
    print(f"SponsorBlock: catalogue revision {revision}; {len(videos)} video(s) selected", flush=True)
    print(f"Failure ledger: {LEDGER_PATH} ({len(failures)} known failure(s))", flush=True)
    print(f"Record directory: {output}", flush=True)

    for index, video in enumerate(videos, 1):
        if active_revision(catalogue) != revision:
            raise SystemExit("STOP: catalogue revision changed; refresh will resume on next run")
        state = process_video(video, index, len(videos), args, catalogue, revision, output, failures)
        counts[state] += 1
        if state == "failed" and args.delay:
            time.sleep(args.delay)
        if index % 25 == 0 or index == len(videos):
            print(f"PROGRESS {index}/{len(videos)}: "
                  f"{counts['cached']} cached, {counts['blocked']} blocked, "
                  f"{counts['saved'] + counts['empty']} saved, {counts['failed']} errors",
                  flush=True)

    print(f"COMPLETE revision {revision}: "
          f"{counts['saved']} saved with segments, {counts['empty']} empty saved, "
          f"{counts['cached']} valid cached, {counts['blocked']} known failures skipped, "
          f"{counts['failed']} errors; ledger holds {len(failures)} failure(s); "
          "MP4 files unchanged", flush=True)


def valid_record(record, video, revision):
    if not isinstance(record, dict) or not isinstance(video, dict):
        return False
    if set(record) != {"formatVersion", "revision", "videoId", "durationMs", "mediaPath",
                       "mediaLength", "mediaMtimeNs", "segments"}:
        return False
    if len(json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > MAX_BYTES:
        return False
    video_id = video.get("videoId")
    duration = video.get("duration")
    media_length = video.get("mediaLength")
    media_mtime = video.get("mediaMtimeNs")
    if (not isinstance(video_id, str) or not VIDEO_ID_RE.fullmatch(video_id) or
            isinstance(duration, bool) or not isinstance(duration, (int, float)) or
            not math.isfinite(duration) or duration <= 0 or
            isinstance(media_length, bool) or not isinstance(media_length, int) or media_length <= 0 or
            isinstance(media_mtime, bool) or not isinstance(media_mtime, int) or media_mtime <= 0):
        return False
    if (isinstance(record.get("formatVersion"), bool) or not isinstance(record.get("formatVersion"), int) or
            record.get("formatVersion") != 1 or isinstance(record.get("revision"), bool) or
            not isinstance(record.get("revision"), int) or record.get("revision") != revision or
            record.get("videoId") != video_id or
            isinstance(record.get("durationMs"), bool) or
            not isinstance(record.get("durationMs"), int) or
            abs(record["durationMs"] - round(duration * 1000)) > 2_000 or
            record.get("mediaPath") != video.get("mediaPath") or
            record.get("mediaLength") != media_length or
            record.get("mediaMtimeNs") != media_mtime):
        return False
    segments = record.get("segments")
    if not isinstance(segments, list) or len(segments) > MAX_SEGMENTS:
        return False
    for segment in segments:
        if not isinstance(segment, dict):
            return False
        start, end = segment.get("startMs"), segment.get("endMs")
        category, action = segment.get("category"), segment.get("action")
        if (isinstance(start, bool) or not isinstance(start, int) or
                isinstance(end, bool) or not isinstance(end, int) or
                start < 0 or end <= start or end > round(duration * 1000) or
                not isinstance(category, str) or category not in CATEGORY_SET or
                not isinstance(action, str) or action not in ACTIONS):
            return False
    return True


if __name__ == "__main__":
    main()
