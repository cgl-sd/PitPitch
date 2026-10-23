#!/usr/bin/env python3
"""Stage official high-resolution portraits and season-specific side-view cars; apply reviewed records explicitly."""
from pathlib import Path
import argparse, concurrent.futures, datetime, hashlib, io, json, re, shutil, urllib.request
from PIL import Image, ImageDraw
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'shared/assets/f1-media'
STAGE = ROOT / 'tmp/validation/ui-polish/media'
parser = argparse.ArgumentParser(); parser.add_argument('--apply', action='store_true'); args = parser.parse_args()
STAGE.mkdir(parents=True, exist_ok=True)
catalog = json.loads((ASSETS / 'catalog.json').read_text())
existing = json.loads((STAGE/'staged.json').read_text()) if (STAGE/'staged.json').exists() else {}
def fetch(item):
    key, record = item
    if key in existing: return key, existing[key]
    url = record['url']
    if record['role'] == 'driver':
        if key in ('driver/2021/mazepin','driver/2021/kubica'):
            photo_year = 2019 if record['entity'] == 'kubica' else 2021
            url = f"https://media.formula1.com/image/upload/c_fit,w_640/q_95/content/dam/fom-website/drivers/{photo_year}Drivers/{record['entity']}.png"
        url = url.replace('w_160', 'w_640').replace('h_240', 'h_1600').replace('w_240','w_960').replace('q_auto','q_95')
    else:
        year = record['season']; team = record['entity'].replace('_','')
        team = {'red_bull':'redbullracing','sauber':'kicksauber'}.get(record['entity'],team)
        if year >= 2024:
            url = f'https://media.formula1.com/image/upload/c_fit,w_960/q_95/common/f1/{year}/{team}/{year}{team}carright.webp'
        else:
            logo = catalog.get(f"logo/{year}/{record['entity']}", {})
            slug = logo.get('url','').rsplit('/',1)[-1].removesuffix('-logo.png')
            url = f'https://media.formula1.com/image/upload/c_fit,w_960/q_95/content/dam/fom-website/teams/{year}/{slug}.png'
    try:
        body = urllib.request.urlopen(url,timeout=25).read()
        im = Image.open(io.BytesIO(body)); im.load()
        if record['role']=='car':
            assert im.mode=='RGBA' and im.getextrema()[3][0] == 0, 'not transparent'
            assert im.width/im.height > 2.4, 'not side view'
        else: assert im.height >= 400 and im.width >= 160, 'insufficient portrait resolution'
        name = record['file']; target=STAGE/name
        # Store the genuine decoded source at lossless WebP quality, without invented detail.
        im.save(target,format='WEBP',lossless=True)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        return key, dict(record, url=url, width=im.width, height=im.height, sha256=digest,
            previousUrl=record['url'], variant='official-hires' if record['role']=='driver' else 'official-season-side-view',
            modification='Official source decoded to lossless WebP; no generated content',
            fetchedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    except Exception as e:
        print('FAILED',key,url,str(e),flush=True);return None
if args.apply:
    records=json.loads((STAGE/'staged.json').read_text())
    expected = {k for k,r in catalog.items() if r['role']=='driver' or (r['role']=='car' and r['season']<=2024)}
    assert expected <= records.keys(), f'Missing staged bindings: {expected - records.keys()}'
    for key,r in records.items():
        source=STAGE/r['file'];assert hashlib.sha256(source.read_bytes()).hexdigest()==r['sha256']
        shutil.copy2(source,ASSETS/r['file']);catalog[key]=r
    (ASSETS/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
    print('Applied',len(records),'reviewed bindings')
else:
    items=[(k,r) for k,r in catalog.items() if r['role']=='driver' or (r['role']=='car' and r['season']<=2024)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool: records=dict(r for r in pool.map(fetch,items) if r)
    (STAGE/'staged.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
    for role in ['driver','car']:
        for year in range(2020,2027):
            entries=[(k,r) for k,r in records.items() if r['role']==role and r['season']==year]
            if not entries:continue
            sheet=Image.new('RGB',(800,((len(entries)+4)//5)*145),'#e8edf2'); draw=ImageDraw.Draw(sheet)
            for n,(k,r) in enumerate(entries):
                im=Image.open(STAGE/r['file']).convert('RGBA')
                if role=='driver' and im.height/im.width>2: im=im.crop((0,0,im.width,min(im.height,im.width*1.2)))
                im.thumbnail((150,112));x=(n%5)*160;y=(n//5)*145
                sheet.paste(im,(x+(160-im.width)//2,y),im);draw.text((x+2,y+115),k.split('/')[-1],fill='black')
            sheet.save(STAGE/f'review-{role}-{year}.jpg')
    print('Staged',len(records),'of',len(items),'bindings',flush=True)
