#!/usr/bin/env python3
"""Bundle ESPN position notes only when the complete table matches the existing archive."""
import concurrent.futures, datetime, hashlib, json, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2] / 'shared/assets'
CATALOG = json.loads((ROOT/'sports/catalog.json').read_text())
def fingerprint(entries):
    return sorted((str(e['team']['id']), sorted((s['name'], s.get('displayValue',str(s.get('value','')))) for s in e['stats'])) for e in entries)
def groups(root):
    out={}
    def walk(g):
        if g.get('standings',{}).get('entries'): out[str(g.get('id',g.get('name','')))]=g['standings']['entries']
        for c in g.get('children',[]): walk(c)
    walk(root)
    return out

def fetch(item):
    url,meta=item
    old=groups(json.loads((ROOT/'sports'/meta['file']).read_text()))
    try:
        req=urllib.request.Request(url,headers={'User-Agent':'PitPitch/0.7 archive position-note collection'})
        raw=urllib.request.urlopen(req,timeout=25).read(); current=groups(json.loads(raw))
        result={}
        for key,entries in old.items():
            if key not in current or fingerprint(entries)!=fingerprint(current[key]): continue
            result[key]={str(e['team']['id']):e['note']['description'] for e in current[key] if e.get('note',{}).get('description')}
        return url,{'fetchedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'archiveFile':meta['file'],
                    'archiveSha256':hashlib.sha256((ROOT/'sports'/meta['file']).read_bytes()).hexdigest(),'groups':result}
    except Exception as e: print('Unavailable:',url,type(e).__name__); return url,None
if __name__=='__main__':
    items=[(u,m) for u,m in CATALOG.items() if '/soccer/' in u and '/standings?season=' in u]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results={u:r for u,r in pool.map(fetch,items) if r is not None}
    (ROOT/'football-standing-notes.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
    print('Resources:',len(results),'matched tables:',sum(len(r['groups']) for r in results.values()))
