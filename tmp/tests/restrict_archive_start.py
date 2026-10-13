"""Restrict packaged seasons to 2020 onward, preserving records of supported seasons.
Removed and mixed-year original files are backed up under ignored tmp/validation.
"""
import datetime,hashlib,json,pathlib,re,shutil,urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[2];ASSETS=ROOT/'app/src/main/assets';SPORTS=ASSETS/'sports';START=2020
backup=ROOT/'tmp/validation/archive-before-2020-scope';backup.mkdir(parents=True,exist_ok=True)
def year(url):
 query=urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
 for key in ['season','dates']:
  if key in query:return int(query[key][0])
 m=re.search(r'/ergast/f1/(\d{4})(?:/|\.json)',url)
 return int(m[1]) if m else None
catalog=json.loads((SPORTS/'catalog.json').read_text());kept={};removed=[];edited=[]
if not (backup/'sports-catalog.json').exists():shutil.copy2(SPORTS/'catalog.json',backup/'sports-catalog.json')
for url,entry in catalog.items():
 path=SPORTS/entry['file']
 if year(url) is not None and year(url)<START:
  shutil.copy2(path,backup/path.name);removed.append(path);continue
 if '/scoreboard?' in url:
  data=json.loads(path.read_text());events=[e for e in data['events'] if e['season']['year']>=START]
  if len(events)!=len(data['events']):
   shutil.copy2(path,backup/path.name);edited.append({'url':url,'removedEvents':len(data['events'])-len(events)})
   data['events']=events;body=json.dumps(data,ensure_ascii=False,separators=(',',':')).encode();path.write_bytes(body);entry['sha256']=hashlib.sha256(body).hexdigest()
 kept[url]=entry
for path in removed:path.unlink()
(SPORTS/'catalog.json').write_text(json.dumps(kept,ensure_ascii=False,indent=2)+'\n')
coverage=json.loads((SPORTS/'coverage.json').read_text());coverage.update(resources=len(kept),archiveStartYear=START);(SPORTS/'coverage.json').write_text(json.dumps(coverage,ensure_ascii=False,indent=2)+'\n')
rounds=ASSETS/'rounds';catalog=json.loads((rounds/'catalog.json').read_text())
for f in rounds.glob('*.json'):
 if f.stem.isdigit() and int(f.stem)<START:shutil.copy2(f,backup/f'rounds-{f.name}');f.unlink()
catalog['coverage']=[x for x in catalog['coverage'] if x['season']>=START];catalog['sources']={k:v for k,v in catalog['sources'].items() if int(k.split('/')[0])>=START};catalog['archiveStartYear']=START
(rounds/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'resources':len(kept),'removedResources':len(removed),'mixedYearSnapshots':edited},ensure_ascii=False))
