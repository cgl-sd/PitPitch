"""Verify packaged source integrity, seasonal scope, fixture IDs, roster fields and F1 pagination."""
import collections, hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]/'app/src/main/assets/sports'
C=json.loads((ROOT/'catalog.json').read_text())
def load(url):return json.loads((ROOT/C[url]['file']).read_text())
def groups(d):
 rows=list(d.get('standings',{}).get('entries',[]))
 for child in d.get('children',[]):rows.extend(groups(child))
 return rows
report={'football':[],'f1':[],'resources':len(C),'portraits':0,'playersWithGoals':0,'emptyRosters':[],'wrongRosterSeason':[]}
for url,entry in C.items():
 body=(ROOT/entry['file']).read_bytes();assert hashlib.sha256(body).hexdigest()==entry['sha256'],url
 if '/roster?' in url:
  d=json.loads(body);expected=int(url.split('season=')[1])
  if d.get('season',{}).get('year')!=expected:report['wrongRosterSeason'].append(url);continue
  if not d.get('athletes'):report['emptyRosters'].append(url)
  for p in d.get('athletes',[]):
   report['portraits']+=bool(p.get('headshot',{}).get('href'))
   report['playersWithGoals']+=any(s.get('name')=='totalGoals' for c in p.get('statistics',{}).get('splits',{}).get('categories',[]) for s in c.get('stats',[]))
leagues=['eng.1','esp.1','ita.1','ger.1','fra.1','uefa.champions','uefa.europa']
for y in range(2020,2027):
 for l in leagues:
  events={}
  for cy in [y,y+1]:
   d=load(f'https://site.api.espn.com/apis/site/v2/sports/soccer/{l}/scoreboard?dates={cy}&limit=1000')
   assert len(d['events'])<1000,'Possible upstream truncation'
   events.update({e['id']:e for e in d['events'] if e['season']['year']==y})
  table=load(f'https://site.web.api.espn.com/apis/v2/sports/soccer/{l}/standings?season={y}')
  assert table['season']['year']==y
  assert events and groups(table),(l,y)
  report['football'].append({'competition':l,'season':y,'fixtures':len(events),'completed':sum(e['competitions'][0]['status']['type']['completed'] for e in events.values()),'tableTeams':len(groups(table))})
for y in range(2020,2027):
 races=load(f'https://api.jolpi.ca/ergast/f1/{y}.json?limit=100')['MRData']['RaceTable']['Races']
 first=load(f'https://api.jolpi.ca/ergast/f1/{y}/results.json?limit=100&offset=0')['MRData'];total=int(first['total']);actual=0;published=0
 for r in races:
  url=f'https://api.jolpi.ca/ergast/f1/{y}/{r["round"]}/results.json?limit=100'
  if url not in C:continue
  results=load(url)['MRData']['RaceTable']['Races'][0]['Results'];assert len({x['Driver']['driverId'] for x in results})==len(results)
  actual+=len(results);published+=1
 assert actual==total,(y,actual,total)
 report['f1'].append({'season':y,'scheduledRaces':len(races),'publishedRaces':published,'results':actual})
report['footballFixtureTotal']=sum(r['fixtures'] for r in report['football']);report['rosterResources']=sum('/roster?' in u for u in C)
output=pathlib.Path(__file__).resolve().parents[1]/'validation/archive-verification.json';output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in report.items() if k not in ['football','f1','emptyRosters','wrongRosterSeason']},ensure_ascii=False));print('Empty rosters',len(report['emptyRosters']),'Wrong-season rosters',len(report['wrongRosterSeason']));print(report['f1'])
