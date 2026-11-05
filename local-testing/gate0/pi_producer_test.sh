#!/bin/bash
set -u
R='/mnt/smarttube-archive/YouTube-Local/_stage3_gate/producer_test'
mkdir -p "$R/Channel One [UC1111111111]" "$R/Channel Two [UC2222222222]"
cd "$R" || exit 2
printf '%s' '{"id":"aaaaaaaaaaa","channel_id":"UC1111111111","title":"Video A","duration":120}' > 'Channel One [UC1111111111]/Video A [aaaaaaaaaaa].info.json'
printf '%s' '{"id":"bbbbbbbbbbb","channel_id":"UC1111111111","title":"Video B","duration":300}' > 'Channel One [UC1111111111]/Video B [bbbbbbbbbbb].info.json'
printf '%s' '{"id":"ccccccccccc","channel_id":"UC2222222222","title":"Video C","duration":45}' > 'Channel Two [UC2222222222]/Video C [ccccccccccc].info.json'
printf '%s' '{broken json' > 'Channel Two [UC2222222222]/Broken [ddddddddddd].info.json'
printf '%s' '{"id":"../evil","channel_id":"UC2222222222","title":"Evil"}' > 'Channel Two [UC2222222222]/Evil [eeeeeeeeeee].info.json'
echo "== running producer =="
python3 /tmp/metube_catalogue.py --root "$R" --once 2>&1
echo "== published files =="
find _catalogue -type f | sort
echo "== pointer =="
cat _catalogue/pointer.json; echo
echo "== library summary =="
python3 - <<'EOF'
import json
d = json.load(open('_catalogue/v1/library.json'))
print('rev', d['revision'])
for c in d['channels']:
    print('channel', c['channelId'], 'videos', c['videoCount'],
          [(v['videoId'], v['mediaPath'], v['thumbPath']) for v in c['videos']])
ch = json.load(open('_catalogue/v1/channels/UC1111111111.json'))
print('channel file ids:', [v['videoId'] for v in ch['videos']])
EOF
echo "== republish idempotency =="
python3 /tmp/metube_catalogue.py --root "$R" --once 2>&1
ls _catalogue
