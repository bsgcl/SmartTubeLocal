#!/usr/bin/env python3
"""Stage 3 corrective-ticket regression tests (Fix 1 + Fix 3).

Runs the catalogue producer in-process against synthetic archives. Prints
actual sidecar-read counts per scenario. Exit code 0 = all scenarios PASS.

Usage: python producer_regression_test.py
"""
import json
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "pi-catalogue"))
import metube_catalogue as prod  # noqa: E402

RESULTS = []
READS = {"n": 0}
_ORIGINAL_SCAN_SIDECAR = prod.scan_sidecar


def counting_scan_sidecar(root, rel):
    READS["n"] += 1
    return _ORIGINAL_SCAN_SIDECAR(root, rel)


prod.scan_sidecar = counting_scan_sidecar


def check(name, condition, detail=""):
    RESULTS.append((name, bool(condition), detail))
    print(("PASS" if condition else "FAIL") + f"  {name}" +
          (f"  [{detail}]" if detail else ""))


def make_video(root, channel, title, vid, media=True, thumb=True, bad=False):
    cdir = os.path.join(root, channel)
    os.makedirs(cdir, exist_ok=True)
    stem = os.path.join(cdir, f"{title} [{vid}]")
    if bad:
        with open(stem + ".info.json", "w", encoding="utf-8") as fh:
            fh.write("{not json")
        return
    meta = {"id": vid, "channel_id": channel.replace("Channel ", "UC"),
            "channel": channel, "title": title, "duration": 60}
    with open(stem + ".info.json", "w", encoding="utf-8") as fh:
        json.dump(meta, fh)
    if media:
        with open(stem + ".mp4", "wb") as fh:
            fh.write(b"M" * 1000)
    if thumb:
        with open(stem + ".webp", "wb") as fh:
            fh.write(b"T" * 10)


def setup_archive(base, count=1500):
    root = os.path.join(base, "archive")
    os.makedirs(root, exist_ok=True)
    per = 50
    for i in range(count):
        ch = i // per
        make_video(root, f"Channel UC{'A' * 22}{ch:02d}",
                   f"Video {i:05d}", f"v{i:08d}")
    return root


def pointer_rev(root):
    try:
        with open(os.path.join(root, "_catalogue", "pointer.json")) as fh:
            return json.load(fh)["revision"]
    except (OSError, ValueError, KeyError):
        return None


def run_once(root):
    READS["n"] = 0
    ok = prod.run_once(root)
    return ok, READS["n"]


def main():
    base = tempfile.mkdtemp(prefix="stage3-fix-")
    try:
        root = setup_archive(base)

        # --- Fix 3 scenario 1: first scan processes all sidecars
        ok, reads = run_once(root)
        check("F3 first scan reads all sidecars", reads == 1500, f"reads={reads}")
        check("F3 first scan publishes", ok and pointer_rev(root) == 1)

        # --- Fix 3 scenario 2: second scan unchanged
        ok, reads = run_once(root)
        check("F3 unchanged scan reopens zero sidecars", reads == 0, f"reads={reads}")
        check("F3 unchanged scan does not publish", pointer_rev(root) == 1)

        # --- Fix 3 scenario 3: one new video
        make_video(root, "Channel UC" + "A" * 22 + "00", "Video NEW", "vnew0001")
        ok, reads = run_once(root)
        check("F3 new video: exactly one sidecar read", reads == 1, f"reads={reads}")
        check("F3 new video published", pointer_rev(root) == 2)

        # --- Fix 3 scenario 4: one changed video (content + mtime)
        cdir = os.path.join(root, "Channel UC" + "A" * 22 + "00")
        sidecar = os.path.join(cdir, "Video 00000 [v00000000].info.json")
        with open(sidecar, "r", encoding="utf-8") as fh:
            meta = json.load(fh)
        meta["title"] = "Video 00000 RENAMED"  # real content change
        with open(sidecar, "w", encoding="utf-8") as fh:
            json.dump(meta, fh)
        os.utime(sidecar, (0, 0))
        ok, reads = run_once(root)
        check("F3 changed video: exactly one sidecar read", reads == 1, f"reads={reads}")
        check("F3 changed video published", pointer_rev(root) == 3)

        # --- Fix 3 scenario 5: one deleted sidecar
        os.remove(sidecar)
        ok, reads = run_once(root)
        check("F3 deleted video: zero sidecar reads", reads == 0, f"reads={reads}")
        lib = json.load(open(os.path.join(
            root, "_catalogue", "v4", "library.json"), encoding="utf-8"))
        count0 = [c for c in lib["channels"]
                  if c["channelId"].endswith("00")][0]["videoCount"]
        # channel 00 had 50 originals + vnew0001 = 51; minus the deleted one
        check("F3 deleted video removed from catalogue", count0 == 50, f"count={count0}")

        # --- Fix 3 scenario 6: deleted media file (sidecar unchanged)
        mp4 = os.path.join(cdir, "Video 00001 [v00000001].mp4")
        os.remove(mp4)
        ok, reads = run_once(root)
        check("F3 deleted media: zero sidecar reads", reads == 0, f"reads={reads}")
        lib = json.load(open(os.path.join(
            root, "_catalogue", "v5", "library.json"), encoding="utf-8"))
        entries = [c for c in lib["channels"]
                   if c["channelId"].endswith("00")][0]["videos"]
        orphan = [v for v in entries if not v["mediaPath"]]
        check("F3 deleted media reflected in catalogue", len(orphan) == 1,
              f"orphans={len(orphan)}")

        # --- Fix 3 scenario 7: invalid metadata cache -> full-scan recovery
        with open(os.path.join(root, prod.META_CACHE_NAME), "w") as fh:
            fh.write("{corrupt")
        ok, reads = run_once(root)
        check("F3 invalid cache triggers full scan", reads == 1500, f"reads={reads}")
        check("F3 recovered catalogue published", pointer_rev(root) == 6)

        # --- Fix 3 scenario 8: no content change -> no publish
        ok, reads = run_once(root)
        check("F3 post-recovery no-change scan: zero reads, no publish",
              reads == 0 and pointer_rev(root) == 6,
              f"reads={reads} rev={pointer_rev(root)}")

        # --- Fix 1 scenario A: channel write failure preserves previous rev
        orig_atomic = prod.atomic_write

        def failing_channel_write(path, text):
            if os.sep + "channels" + os.sep in path:
                return False  # simulate disk failure on a channel file
            return orig_atomic(path, text)

        make_video(root, "Channel UC" + "A" * 22 + "00", "Video NEW2", "vnew0002")
        prod.atomic_write = failing_channel_write
        ok, reads = run_once(root)
        prod.atomic_write = orig_atomic
        check("F1 channel write failure aborts publication", not ok)
        check("F1 previous revision preserved after channel failure",
              pointer_rev(root) == 6)
        version_dirs = [d for d in os.listdir(os.path.join(root, "_catalogue"))
                        if d.startswith("v")]
        check("F1 partial revision directory removed",
              "v7" not in version_dirs, f"dirs={sorted(version_dirs)}")

        # --- Fix 1 scenario B: pointer write failure preserves previous rev
        def failing_pointer_write(path, text):
            if path.endswith("pointer.json"):
                return False
            return orig_atomic(path, text)

        prod.atomic_write = failing_pointer_write
        ok, reads = run_once(root)
        prod.atomic_write = orig_atomic
        check("F1 pointer write failure aborts publication", not ok)
        check("F1 previous revision preserved after pointer failure",
              pointer_rev(root) == 6)

        # --- Fix 1 scenario C: later retry succeeds with complete revision
        ok, reads = run_once(root)
        check("F1 retry after failures publishes complete revision",
              ok and pointer_rev(root) == 7)
        v6 = os.path.join(root, "_catalogue", "v7")
        lib = json.load(open(os.path.join(v6, "library.json"), encoding="utf-8"))
        complete = all(os.path.isfile(os.path.join(
            v6, "channels", c["channelId"] + ".json"))
            for c in lib["channels"])
        check("F1 published revision has every channel file", complete)
        count0 = [c for c in lib["channels"]
                  if c["channelId"].endswith("00")][0]["videoCount"]
        check("F1 retry includes the pending new video", count0 == 51,
              f"count={count0}")

        # --- Fix 1 scenario D: unreadable pointer blocks unsafe revision reuse
        # (a content change is needed so a publication is actually attempted)
        make_video(root, "Channel UC" + "A" * 22 + "00", "Video NEW3", "vnew0003")
        with open(os.path.join(root, "_catalogue", "pointer.json"), "w") as fh:
            fh.write("{corrupt")
        ok, reads = run_once(root)
        check("F1 unreadable pointer refuses to publish", not ok)
        check("F1 catalogue untouched when pointer unreadable",
              os.path.isfile(os.path.join(root, "_catalogue", "v7",
                                          "library.json")))
        check("F1 pointer stays unparsable (not advanced)",
              pointer_rev(root) is None)

        # --- Fix 1 scenario E: GC never removes the advertised revision
        # point the pointer at an older existing revision, then publish
        with open(os.path.join(root, "_catalogue", "pointer.json"), "w") as fh:
            json.dump({"formatVersion": 1, "revision": 6}, fh)
        ok, reads = run_once(root)  # vnew0003 still pending -> publishes
        check("F1 publish over older advertised revision succeeds",
              ok and pointer_rev(root) == 7)
        check("F1 previously advertised revision preserved by GC",
              os.path.isfile(os.path.join(root, "_catalogue", "v6",
                                          "library.json")))

        failed = [r for r in RESULTS if not r[1]]
        print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} scenarios passed")
        return 1 if failed else 0
    finally:
        shutil.rmtree(base, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
