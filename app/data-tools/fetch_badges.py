#!/usr/bin/env python3
"""Bundle team crest images referenced by the archive into app assets. Resumable, rate-limited, no API secrets."""
import argparse, datetime, json, pathlib, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]/'src/main/assets'
SPORTS=ROOT/'sports';OUT=ROOT/'badges';OUT.mkdir(parents=True,exist_ok=True)
cat=json.loads((SPORTS/'catalog.json').read_text())
ap=argparse.ArgumentParser();ap.add_argument('--force',action='store_true');args=ap.parse_args()

pairs={}
for url,entry in cat.items():
    try:d=json.loads((SPORTS/entry['file']).read_text())
    except Exception:continue
    teams=[]
    if '/scoreboard' in url:
        for e in d.get('events',[]):
            for c in e.get('competitions',[]):teams += [x.get('team',{}) for x in c.get('competitors',[])]
    elif '/standings' in url:
        def walk(g):
            if 'standings' in g:teams.extend(e['team'] for e in g['standings'].get('entries',[]))
            for ch in g.get('children',[]):walk(ch)
        walk(d)
    else:continue
    for t in teams:
        tid=t.get('id');href=t.get('logo')
        if tid and href:pairs[str(tid)]=href
print('unique teams with a badge url:',len(pairs),flush=True)
downloaded=failed=0
for tid,href in sorted(pairs.items(),key=lambda kv:int(kv[0])):
    ext='.svg' if '.svg' in href else '.png'
    target=OUT/f'{tid}{ext}'
    if not args.force and target.exists() and target.stat().st_size>0:continue
    try:
        req=urllib.request.Request(href,headers={'User-Agent':'PitPitch data archive/0.6','Accept':'image/*'})
        with urllib.request.urlopen(req,timeout=35) as r:body=r.read()
        if len(body)<100:raise ValueError('suspiciously small body')
        target.write_bytes(body);downloaded+=1;time.sleep(.3)
    except Exception as e:
        failed+=1;print('FAIL',tid,href,e,flush=True)
manifest={'generatedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'teams':len(pairs),'stored':len([p for p in OUT.iterdir() if p.suffix in ('.png','.svg')]),
          'failed':failed,'sources':pairs}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=1))
print(f'downloaded now {downloaded}, failed {failed}, stored {manifest["stored"]}',flush=True)
