#!/usr/bin/env python3
"""Stage native official logo variants, review, then --apply; never recolor logos."""
from pathlib import Path
import argparse, concurrent.futures, datetime, hashlib, json, shutil, urllib.request
from PIL import Image
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/f1-media'
STAGE = ROOT / 'tmp/validation/f1-teams'
p = argparse.ArgumentParser(); p.add_argument('--apply', action='store_true'); args = p.parse_args()
STAGE.mkdir(parents=True, exist_ok=True)
catalog = json.loads((ASSETS / 'catalog.json').read_text())
def stage(item):
    key, record = item
    url = record['url'].replace('w_128', 'w_256').replace('logowhite', 'logo')
    name = record['file'].replace('.webp', '-color.webp')
    body = urllib.request.urlopen(url, timeout=25).read()
    target = STAGE / name; target.write_bytes(body)
    with Image.open(target) as im:
        w, h = im.size
    return key, dict(record, file=name, url=url, width=w, height=h,
        sha256=hashlib.sha256(body).hexdigest(), variant='official-original-color',
        previousMonochromeUrl=record['url'], fetchedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
if not args.apply:
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        records = dict(pool.map(stage, [(k,r) for k,r in catalog.items() if r['role']=='logo' and 'logowhite' in r['url']]))
    (STAGE / 'reviewed-color-catalog.json').write_text(json.dumps(records, indent=2)+'\n')
    print('Staged',len(records),'native logos; review every team/year before --apply')
else:
    records = json.loads((STAGE / 'reviewed-color-catalog.json').read_text())
    for key, r in records.items():
        body = (STAGE / r['file']).read_bytes()
        assert hashlib.sha256(body).hexdigest()==r['sha256']
        old = ASSETS / catalog[key]['file']
        shutil.copy2(STAGE / r['file'], ASSETS / r['file'])
        if old.name != r['file']:
            (STAGE / 'retired-logos').mkdir(exist_ok=True)
            shutil.copy2(old, STAGE / 'retired-logos' / old.name); old.unlink()
        catalog[key] = r
    (ASSETS / 'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
    print('Applied',len(records),'reviewed native logos')
