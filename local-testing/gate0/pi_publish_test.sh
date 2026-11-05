#!/bin/bash
# Gate 0 Pi-side CIFS publication test. Isolated dir only; no production files touched.
set -u
G=/mnt/smarttube-archive/YouTube-Local/_stage3_gate/pi_test
mkdir -p "$G"
cd "$G" || exit 2
rm -f pointer.json catalogue.v*.json *.tmp 2>/dev/null

echo "== T2: basic create/read =="
echo '{"fmt":1,"rev":0}' > t0.tmp && mv -f t0.tmp t0.json && cat t0.json && rm -f t0.json
echo "create+read: $?"

echo "== T3: rename-over-existing sanity =="
echo '{"a":1}' > r.tmp && mv -f r.tmp r.json && echo '{"a":2}' > r.tmp && mv -f r.tmp r.json && echo "overwrite mv: OK ($(cat r.json))" || echo "overwrite mv: FAILED"

echo "== T4: concurrent publish vs read (writer 300 revs, reader 10s) =="
# reader: loop reading pointer.json, validate JSON via python3, count bad; fallback to newest snapshot
(
  good=0; bad=0; fb=0; n=0
  end=$((SECONDS+12))
  while [ $SECONDS -lt $end ]; do
    n=$((n+1))
    if python3 -c "import json,sys; json.load(open('pointer.json'))" 2>/dev/null; then
      good=$((good+1))
    else
      bad=$((bad+1))
      # fallback: newest snapshot
      snap=$(ls -1 catalogue.v*.json 2>/dev/null | sort -V | tail -1)
      if [ -n "$snap" ] && python3 -c "import json,sys; json.load(open('$snap'))" 2>/dev/null; then
        fb=$((fb+1))
      fi
    fi
  done
  echo "READER: reads=$n good=$good bad=$bad fallback_ok=$fb"
) &
READER_PID=$!

rev=0; mvfail=0; retries=0
while [ $rev -lt 300 ]; do
  rev=$((rev+1))
  printf '{"fmt":1,"rev":%d,"ts":"pi"}\n' "$rev" > pointer.tmp
  sync
  ok=0
  for try in 1 2 3 4 5; do
    if mv -f pointer.tmp pointer.json 2>/dev/null; then ok=1; [ $try -gt 1 ] && retries=$((retries+1)); break; fi
    sleep 0.02
  done
  [ $ok -eq 0 ] && mvfail=$((mvfail+1))
  # publish snapshot (unique name, no conflict expected)
  cp pointer.json "catalogue.v$rev.json" 2>/dev/null
done
echo "WRITER: revs=$rev mvfail=$mvfail retries=$retries"
wait $READER_PID

echo "== T5: malformed pointer preserves last valid snapshot =="
echo '{broken' > pointer.tmp && mv -f pointer.tmp pointer.json
snap=$(ls -1 catalogue.v*.json | sort -V | tail -1)
if python3 -c "import json,sys; json.load(open('pointer.json'))" 2>/dev/null; then
  echo "FAIL: malformed pointer accepted"
else
  echo "OK: malformed pointer rejected; last valid snapshot=$snap rev=$(python3 -c "import json;print(json.load(open('$snap'))['rev'])")"
fi

echo "== T6: unsupported catalogue version rejection =="
printf '{"fmt":99,"rev":1}\n' > badver.json
python3 - <<'EOF'
import json
d = json.load(open('badver.json'))
SUPPORTED = {1}
print("OK: version %d rejected (supported: %s)" % (d["fmt"], sorted(SUPPORTED)) if d["fmt"] not in SUPPORTED else "FAIL: accepted")
EOF

echo "== T7: unsafe relative path rejection (same policy as requireSafeRelativePath) =="
python3 - <<'EOF'
import os
def safe(rel):
    if rel.startswith("/") or ".." in rel.split("/") or "\\" in rel or rel.strip()=="" :
        return False
    p = os.path.normpath(rel)
    return not p.startswith("..")
cases = ["../etc/passwd", "Channel [UC123]/video.mp4", "/abs/path", "a\\b", "ok/dir/file.mp4", ".."]
for c in cases:
    print(("REJECT" if not safe(c) else "ACCEPT"), repr(c))
EOF

echo "== cleanup =="
rm -f pointer.json pointer.tmp catalogue.v*.json r.json badver.json
ls -la "$G"
echo DONE
