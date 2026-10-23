#!/usr/bin/env python3
"""Refresh factual public-source snapshots. Resumable, bounded concurrency, no API secrets."""
import argparse, concurrent.futures, datetime, hashlib, json, pathlib, threading, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[2]/'shared/assets/sports'
ROOT.mkdir(parents=True,exist_ok=True)
LOCK=threading.RLock()
RATE=threading.Lock()
NEXT_REQUEST=0.0
CATALOG=json.loads((ROOT/'catalog.json').read_text()) if (ROOT/'catalog.json').exists() else {}
FAILURES=[]
REFRESH=False
LEAGUES=['eng.1','esp.1','ita.1','ger.1','fra.1','uefa.champions','uefa.europa']
SITE='https://site.api.espn.com/apis/site/v2/sports/soccer/'
WEB='https://site.web.api.espn.com/apis/v2/sports/soccer/'
F1='https://api.jolpi.ca/ergast/f1/'
def keep(d,keys):return {k:d[k] for k in keys if k in d}
def team(t):return keep(t,['id','displayName','shortDisplayName','abbreviation','logo','logos'])
def slim(d,kind):
 if kind=='schedule':
  events=[]
  for e in d['events']:
   if e.get('season',{}).get('year',0)<2020:continue
   x=keep(e,['id','date','season','status']); comps=[]
   for c in e.get('competitions',[]):
    cc=keep(c,['date','timeValid','status','venue','leg','series','altGameNote','notes'])
    cc['competitors']=[dict(keep(t,['id','homeAway','score','winner']),team=team(t['team'])) for t in c['competitors']]
    comps.append(cc)
   x['competitions']=comps;events.append(x)
  return {'events':events}
 if kind=='standings':
  def group(g):
   out=keep(g,['name','season','id'])
   if 'standings' in g:out['standings']={'entries':[{**keep(e,['note','clincher']), 'team':team(e['team']),'stats':[keep(s,['name','value','displayValue']) for s in e['stats']]} for e in g['standings'].get('entries',[])]}
   if 'children' in g:out['children']=[group(c) for c in g['children']]
   return out
  return group(d)
 if kind=='roster':
  athletes=[]
  for a in d.get('athletes',[]):
   x=keep(a,['id','displayName','jersey','position','dateOfBirth','citizenship','status','headshot'])
   categories=a.get('statistics',{}).get('splits',{}).get('categories',[])
   if categories:x['statistics']={'splits':{'categories':[{'stats':[keep(s,['name','value','displayValue']) for s in c.get('stats',[]) if s['name'] in ['appearances','totalGoals','goalAssists']]} for c in categories]}}
   athletes.append(x)
  # ESPN roster coach metadata is demonstrably stale across seasons; do not ingest it as head coach.
  return {'season':d.get('season',{}),'athletes':athletes,'coachVerification':'Unavailable: supplier coach metadata is not season-verified'}
 return d

def fetch(url,kind='raw'):
 with LOCK:entry=CATALOG.get(url)
 path=ROOT/(hashlib.sha256(url.encode()).hexdigest()+'.json')
 if not entry and path.exists():
  body=path.read_text();entry={'file':path.name,'fetchedUtc':datetime.datetime.fromtimestamp(path.stat().st_mtime,datetime.timezone.utc).isoformat(),'sha256':hashlib.sha256(body.encode()).hexdigest()}
  with LOCK:CATALOG[url]=entry
 if not REFRESH and entry and ROOT.joinpath(entry['file']).exists():
  try:
   body=ROOT.joinpath(entry['file']).read_bytes()
   if hashlib.sha256(body).hexdigest()==entry['sha256']:return json.loads(body)
  except (ValueError, OSError):pass
 global NEXT_REQUEST
 for attempt in range(3):
  try:
   with RATE:
    delay=max(0,NEXT_REQUEST-time.monotonic());time.sleep(delay);NEXT_REQUEST=time.monotonic()+.34
   req=urllib.request.Request(url,headers={'User-Agent':'PitPitch data archive/0.6','Accept':'application/json'})
   with urllib.request.urlopen(req,timeout=35) as response:d=json.load(response)
   data=slim(d,kind);name=hashlib.sha256(url.encode()).hexdigest()+'.json';body=json.dumps(data,ensure_ascii=False,separators=(',',':'))
   ROOT.joinpath(name).write_text(body)
   with LOCK:
    CATALOG[url]={'file':name,'fetchedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sha256':hashlib.sha256(body.encode()).hexdigest(),'portraitSchema':kind=='roster'}
    if len(CATALOG)%100==0:save();print('Progress',len(CATALOG),flush=True)
   return data
  except Exception as e:
   if attempt<2:time.sleep(1+attempt*2)
   else:
    with LOCK:FAILURES.append({'url':url,'error':str(e)})
    print('UNAVAILABLE',url,str(e),flush=True);return None

def parallel(jobs,workers=3):
 with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:list(pool.map(lambda j:fetch(*j),jobs))
 save()
def save():
 with LOCK:
  snapshot=dict(CATALOG);failures=list(FAILURES)
 ROOT.joinpath('catalog.json').write_text(json.dumps(snapshot,ensure_ascii=False,indent=2))
 ROOT.joinpath('coverage.json').write_text(json.dumps({'generatedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'resources':len(snapshot),'unavailable':failures},ensure_ascii=False,indent=2))
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--start',type=int,default=2020);ap.add_argument('--end',type=int,default=datetime.date.today().year);ap.add_argument('--rosters',action='store_true');ap.add_argument('--refresh',action='store_true');args=ap.parse_args();
 global REFRESH
 REFRESH=args.refresh
 if args.start<2020 or args.end<args.start:ap.error("Archive years must start in 2020 or later")
 years=range(args.start,args.end+1)
 jobs=[(f'{SITE}{l}/scoreboard?dates={y}&limit=1000','schedule') for l in LEAGUES for y in range(args.start,args.end+2)]
 jobs += [(f'{WEB}{l}/standings?season={y}','standings') for l in LEAGUES for y in years]
 parallel(jobs);print('Football schedules/tables',len(CATALOG),flush=True)
 if args.rosters:
  jobs=[];seen=set()
  for l in LEAGUES:
   for y in years:
    clubs=set()
    for cy in [y,y+1]:
     d=fetch(f'{SITE}{l}/scoreboard?dates={cy}&limit=1000','schedule') or {}
     for e in d.get('events',[]):
      if e.get('season',{}).get('year')==y:
       clubs.update(t['team']['id'] for c in e['competitions'] for t in c['competitors'])
    for club in sorted(clubs):
     if (l,y,club) not in seen:
      jobs.append((f'{SITE}{l}/teams/{club}/roster?season={y}','roster'));seen.add((l,y,club))
  print('Roster requests',len(jobs),flush=True);parallel(jobs,6);print('Rosters finished',len(CATALOG),flush=True)
 # Keep Jolpica well below its documented 4/s limit. Archive every published result via pagination.
 for y in years:
  for resource in [f'{y}.json?limit=100',f'{y}/driverstandings.json?limit=100',f'{y}/constructorstandings.json?limit=100']:
   fetch(F1+resource);time.sleep(.3)
  first=fetch(F1+f'{y}/results.json?limit=100&offset=0')
  if first:
   total=int(first['MRData']['total']);races={}
   for offset in range(0,total,100):
    page=first if offset==0 else fetch(F1+f'{y}/results.json?limit=100&offset={offset}')
    if not page:continue
    for race in page['MRData']['RaceTable']['Races']:
     key=race['round']
     if key not in races:races[key]=dict(race,Results=[])
     races[key]['Results'].extend(race.get('Results',[]))
    time.sleep(.3)
   # Only publish per-round records when all pages loaded and no driver duplicates exist.
   if sum(len(r['Results']) for r in races.values())==total:
    for rd,race in races.items():
     if len({r['Driver']['driverId'] for r in race['Results']})!=len(race['Results']):raise ValueError('Duplicate F1 result')
     body={'MRData':{'RaceTable':{'season':str(y),'Races':[race]}}};url=F1+f'{y}/{rd}/results.json?limit=100';name=hashlib.sha256(url.encode()).hexdigest()+'.json';serialized=json.dumps(body,separators=(',',':'));ROOT.joinpath(name).write_text(serialized);CATALOG[url]={'file':name,'fetchedUtc':CATALOG[F1+f'{y}/results.json?limit=100&offset=0']['fetchedUtc'],'sha256':hashlib.sha256(serialized.encode()).hexdigest()}
  save();print('F1 season',y,'resources',len(CATALOG),flush=True)
 save();print('DONE',len(CATALOG),'unavailable',len(FAILURES),flush=True)
if __name__=='__main__':main()
