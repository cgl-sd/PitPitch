"""Archive complete, identity-checked Jolpica qualifying pages. Never fetch during Gradle."""
import datetime, hashlib, json, pathlib, time, urllib.request
ROOT=pathlib.Path('app/src/main/assets/sports')
CAT=json.loads((ROOT/'catalog.json').read_text())
BASE='https://api.jolpi.ca/ergast/f1/'
CACHE=pathlib.Path('tmp/validation/f1-complete/qualifying'); CACHE.mkdir(parents=True,exist_ok=True)
def fetch(url):
    file=CACHE/(hashlib.sha256(url.encode()).hexdigest()+'.json')
    meta=file.with_suffix('.time')
    if file.exists():return json.loads(file.read_text()),meta.read_text()
    for retry in range(4):
        try:
            body=urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'PitPitch archive/1.0'}),timeout=30).read()
            data=json.loads(body); stamp=datetime.datetime.now(datetime.timezone.utc).isoformat()
            file.write_bytes(body);meta.write_text(stamp);time.sleep(.4);return data,stamp
        except Exception:
            if retry==3:raise
            time.sleep(2**retry)
def save(url,body,stamp):
    raw=json.dumps(body,separators=(',',':')).encode(); name=hashlib.sha256(url.encode()).hexdigest()+'.json'
    (ROOT/name).write_bytes(raw); CAT[url]={'file':name,'fetchedUtc':stamp,'sha256':hashlib.sha256(raw).hexdigest()}
for year in range(2020,2027):
    calendar=json.loads((ROOT/CAT[BASE+f'{year}.json?limit=100']['file']).read_text())
    tracks={r['round']:r['Circuit']['circuitId'] for r in calendar['MRData']['RaceTable']['Races']}
    first,stamp=fetch(BASE+f'{year}/qualifying.json?limit=100&offset=0')
    total=int(first['MRData']['total']); races={}; actual=0
    for offset in range(0,total,100):
        page,ts=(first,stamp) if offset==0 else fetch(BASE+f'{year}/qualifying.json?limit=100&offset={offset}')
        stamp=max(stamp,ts)
        assert int(page['MRData']['total'])==total
        for race in page['MRData']['RaceTable']['Races']:
            assert int(race['season'])==year and tracks.get(race['round'])==race['Circuit']['circuitId']
            rd=race['round'];target=races.setdefault(rd,dict(race,QualifyingResults=[]))
            target['QualifyingResults'].extend(race['QualifyingResults']);actual+=len(race['QualifyingResults'])
    assert actual==total
    for rd,race in races.items():
        rows=race['QualifyingResults']; assert len({r['Driver']['driverId'] for r in rows})==len(rows)
        # Supplier ties/anomalies remain original; driver ID is identity, never rank.
        if len({r.get('position') for r in rows}) < len(rows): print('Supplier repeated qualifying position',year,rd,flush=True)
        save(BASE+f'{year}/{rd}/qualifying.json?limit=100',{'MRData':{'total':str(len(rows)),'RaceTable':{'season':str(year),'round':rd,'Races':[race]}}},stamp)
    (ROOT/'catalog.json').write_text(json.dumps(CAT,indent=2,ensure_ascii=False)+'\n')
    print(year,len(races),total,flush=True)
