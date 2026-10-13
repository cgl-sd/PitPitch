"""Audit season bounds and absence of retired assets; preserve supported source records."""
import argparse,json,pathlib,re,urllib.parse,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[2];ASSETS=ROOT/'app/src/main/assets';SPORTS=ASSETS/'sports'
catalog=json.loads((SPORTS/'catalog.json').read_text())
for url,entry in catalog.items():
 query=urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
 y=next((int(query[k][0]) for k in ['season','dates'] if k in query),None)
 if y is None:
  m=re.search(r'/ergast/f1/(\d{4})(?:/|\.json)',url);y=int(m[1]) if m else None
 assert y is not None and y>=2020,url
 data=json.loads((SPORTS/entry['file']).read_text())
 if '/scoreboard?' in url:assert all(e['season']['year']>=2020 for e in data['events'])
 if 'season' in data:assert data['season']['year']>=2020
 assert data.get('MRData',{}).get('RaceTable',{}).get('season','2020')>='2020'
 assert data.get('MRData',{}).get('StandingsTable',{}).get('season','2020')>='2020'
assert {p.name for p in SPORTS.glob('*.json')}=={v['file'] for v in catalog.values()}|{'catalog.json','coverage.json'}
assert {p.stem for p in (ASSETS/'rounds').glob('[0-9]*.json')}=={str(y) for y in range(2020,2027)}
badges=json.loads((ASSETS/'team-badges/catalog.json').read_text())
assert {p.name for p in (ASSETS/'team-badges').iterdir()}=={v['file'] for v in badges.values()}|{'catalog.json'}
backup=ROOT/'tmp/validation/archive-before-2020-scope'
if (backup/'sports-catalog.json').exists():
 previous=json.loads((backup/'sports-catalog.json').read_text())
 for url,entry in catalog.items():
  original=backup/entry['file']
  if not original.exists():continue
  old=json.loads(original.read_text());new=json.loads((SPORTS/entry['file']).read_text())
  if '/scoreboard?' in url:
   old['events']=[e for e in old['events'] if e['season']['year']>=2020];assert old==new,url
  else:assert old==new,url
print(f'PASS: {len(catalog)} source resources and {len(badges)} crests are scoped to 2020 onward; no orphan retired assets; supported source records unchanged.')

parser=argparse.ArgumentParser();parser.add_argument('--apk',type=pathlib.Path);args=parser.parse_args()
if args.apk:
 with zipfile.ZipFile(args.apk) as apk:
  for directory in ['sports','rounds','team-badges']:
   expected={f'assets/{directory}/{p.name}' for p in (ASSETS/directory).iterdir()}
   actual={n for n in apk.namelist() if n.startswith(f'assets/{directory}/') and not n.endswith('/')}
   assert actual==expected,(directory,actual-expected,expected-actual)
   for path in (ASSETS/directory).iterdir():assert apk.read(f'assets/{directory}/{path.name}')==path.read_bytes()
 print('PASS: APK contains exactly the scoped source/round/crest assets, with no retired extra files.')
