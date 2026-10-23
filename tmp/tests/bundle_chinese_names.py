"""Fetch and verify an offline Chinese display catalog. Never writes ESPN archives.

Requires Python opencc (t2s). Run explicitly with --fetch; never called by Gradle.
Responses live under ignored tmp/validation/chinese-names/ for review.
--from-cache rebuilds from already reviewed responses without network requests.
"""
import sys,datetime,re
from opencc import OpenCC
converter=OpenCC("t2s")
if not any(arg in sys.argv for arg in ("--fetch","--from-cache")):
 raise SystemExit("Use --fetch or --from-cache; do not rebuild from missing caches.")
import json,urllib.request,urllib.parse,time
from pathlib import Path
root=Path('shared/assets/sports'); teams={};venues=set()
def walk(d):
 if isinstance(d,dict):
  t=d.get('team',{})
  if isinstance(t,dict) and t.get('id') and t.get('displayName'):teams.setdefault(str(t['id']),set()).add(t['displayName'])
  v=d.get('venue',{})
  if isinstance(v,dict) and v.get('fullName'):venues.add(v['fullName'])
  for x in d.values():walk(x)
 elif isinstance(d,list):
  for x in d:walk(x)
for u,m in json.loads((root/'catalog.json').read_text()).items():
 if '/soccer/' in u:walk(json.loads((root/m['file']).read_text()))

if "--fetch" in sys.argv:
 cache=Path('tmp/validation/chinese-names/wiki');cache.mkdir(exist_ok=True)
 def api(domain,params):
  for attempt in range(3):
   try:
    with urllib.request.urlopen(urllib.request.Request('https://'+domain+'/w/api.php?'+urllib.parse.urlencode(params|{'format':'json'}),headers={'User-Agent':'PitPitch offline display catalog/0.7'}),timeout=30) as r:return json.load(r)
   except urllib.error.HTTPError as e:
    if e.code != 429 or attempt == 2:raise
    print('Rate limited; backing off for 60 seconds',flush=True);time.sleep(60)
 canonical_lookup_titles=['Levante UD','Athletic Bilbao','SD Eibar','SD Huesca','RCD Mallorca','RCD Espanyol','UD Las Palmas','CD Leganés','Deportivo La Coruña','Genoa CFC','SS Lazio','US Lecce','US Cremonese','Hertha BSC','FC St. Pauli','Hamburger SV','Angers SCO','Lille OSC','RC Strasbourg Alsace','Nîmes Olympique','OGC Nice','RC Lens','Montpellier HSC','AS Saint-Étienne','Olympique de Marseille','ES Troyes AC','Sporting CP','PSV Eindhoven','AC Sparta Prague','ŠK Slovan Bratislava','SK Slavia Prague','Fenerbahçe S.K.','RSC Anderlecht','Riga FC','Lillestrøm SK','US Sassuolo Calcio','Stamford Bridge (stadium)','Villa Park (stadium)','King Power Stadium','Juventus Stadium','Santiago Bernabéu Stadium','Stadio Artemio Franchi','Stadio Arechi','Stade du Moustoir','Stadio Ennio Tardini','Stadio Renato Dall’Ara','Stadio Luigi Ferraris','Stadio Alberto Picco','Stadio Giovanni Zini']
 titles=sorted({t for t in venues|{t for names in teams.values() for n in names for t in [n,n+' F.C.',n+' FC',n+' CF',n+' Calcio','FC '+n]}|set(canonical_lookup_titles) if '|' not in t})
 ids=set()
 for i in range(0,len(titles),50):
  batch=titles[i:i+50];f=cache/('pages-'+str(i)+'.json')
  try:
   cached=json.loads(f.read_text()) if f.exists() else {}
   if cached.get('requestedTitles')==batch and 'query' in cached:d=cached
   else:
    d=api('en.wikipedia.org',{'action':'query','titles':'|'.join(batch),'redirects':1,'prop':'pageprops','ppprop':'wikibase_item'});assert 'query' in d,d;d['requestedTitles']=batch;f.write_text(json.dumps(d,ensure_ascii=False));time.sleep(6)
   ids.update(p['pageprops']['wikibase_item'] for p in d['query']['pages'].values() if p.get('pageprops',{}).get('wikibase_item'))
   print('Pages',min(i+50,len(titles)),'/',len(titles),'entities',len(ids),flush=True)
  except Exception as e: print('Pages failed',i,e,flush=True)
 ids=sorted(ids); entities={}
 for i in range(0,len(ids),45):
  batch=ids[i:i+45];f=cache/('entities-'+str(i)+'.json')
  try:
   cached=json.loads(f.read_text()) if f.exists() else {}
   if cached.get('requestedIds')==batch and 'entities' in cached:d=cached
   else:
    d=api('www.wikidata.org',{'action':'wbgetentities','ids':'|'.join(batch),'props':'labels|aliases|descriptions','languages':'en|zh-cn|zh-hans|zh'});assert 'entities' in d,d;d['requestedIds']=batch;f.write_text(json.dumps(d,ensure_ascii=False));time.sleep(6)
   entities.update(d['entities']);print('Entities',min(i+45,len(ids)),flush=True)
  except Exception as e:print('Entities failed',i,e,flush=True)

cache=Path('tmp/validation/chinese-names/wiki');linked={}; redirects={};pages={}
for p in sorted(cache.glob('pages-*.json')):
 d=json.loads(p.read_text()).get('query',{})
 for k in ['normalized','redirects']:
  for r in d.get(k,[]):redirects[r['from']]=r['to']
 for r in d.get('pages',{}).values():
  if r.get('pageprops',{}).get('wikibase_item'):pages[r['title']]=r['pageprops']['wikibase_item']
entities={}
for p in cache.glob('entities-*.json'):entities.update(json.loads(p.read_text()).get('entities',{}))
# Verified single-entity probes remain usable even when a batch service is unavailable.
for p in Path('tmp/validation/chinese-names').glob('entity-probe-*.json'):entities.update(json.loads(p.read_text()).get('entities',{}))
for p in Path('tmp/validation/chinese-names').glob('Q*.json'):entities.update(json.loads(p.read_text()).get('entities',{}))
if not entities:
 raise SystemExit('No verified entity responses; preserve the existing packaged catalog.')
def resolve(title):
 seen=set()
 while title in redirects and title not in seen:seen.add(title);title=redirects[title]
 return pages.get(title),title
def club_key(n):
 n=n.casefold().replace('.','').strip()
 n=re.sub(r'^(fc|cf|sc|ss|us|sd|rc|rcd|ud|cd|as|ac|sk|ogc|psv)\s+','',n)
 return re.sub(r'\s+(fc|cf|sc|calcio)$','',n)
def variants(n):return [n,n+' F.C.',n+' FC',n+' CF',n+' Calcio','FC '+n]
output={'generatedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'license':'CC0-1.0','source':'https://www.wikidata.org/wiki/Wikidata:Licensing','teams':{},'venues':{}};missing=[]
for kind,key,names in [('team',i,ns) for i,ns in teams.items()]+[('venue',n,{n}) for n in sorted(venues)]:
 candidates={}
 for n in names:
  for t in variants(n) if kind=='team' else [n]:
   qid,title=resolve(t)
   if qid:candidates[qid]=title
 # Previously searched results can provide exact aliases, but never accept a top search hit alone.
 if kind=='team' and (p:=Path('tmp/validation/chinese-names/search')/('team-'+key+'.json')).exists():
  for r in json.loads(p.read_text())['candidates']:
   e=entities.get(r['id'],{}); en=[e.get('labels',{}).get('en',{}).get('value','')]+[a['value'] for a in e.get('aliases',{}).get('en',[])]
   if any(n.casefold()==a.casefold() for n in names for a in en):candidates.setdefault(r['id'],None)
 for qid,e in entities.items():
  en_names=[e.get('labels',{}).get('en',{}).get('value','')]+[a['value'] for a in e.get('aliases',{}).get('en',[])]
  if any((club_key(n)==club_key(a) if kind=='team' else n.casefold()==a.casefold()) for n in names for a in en_names):candidates.setdefault(qid,None)
 found=[]
 for qid,title in candidates.items():
  e=entities.get(qid,{}); desc=e.get('descriptions',{}).get('en',{}).get('value','').lower()
  if kind=='team':
   zh_context=' '.join(v['value'] for lang,v in e.get('descriptions',{}).items() if lang.startswith('zh'))+' '+e.get('labels',{}).get('zh',{}).get('value','')
   if not (any(x in desc for x in ['football club','soccer club','football team','soccer team']) or ('sport' in desc and 'club' in desc and ('足球' in zh_context or any(resolve(n)[0]==qid for n in names))) or ('football' in desc and 'club' in desc)):continue
   identity_text=desc+' '+e.get('labels',{}).get('en',{}).get('value','').lower()+' '+(title or '').lower()
   if any(x in identity_text for x in ['women','female','femminile','w.f.c.','reserve','academy','under-','youth','junior']):continue
  elif not any(x in desc for x in ['stadium','football ground','sports venue','arena']):continue
  labels=e.get('labels',{});zh=next((labels[l]['value'] for l in ['zh-cn','zh-hans','zh'] if l in labels and re.search('[\u3400-\u9fff]',labels[l]['value'])),None)
  if zh:
   if kind=='team' and any(x in zh for x in ['女子','女足','青年','预备队','預備隊']):continue
   source_name=zh; zh=converter.convert(zh)
   if kind=='team':
    preferences={'Mainz':'美因茨05','Benfica':'本菲卡','Bayern Munich':'拜仁慕尼黑','Bayer Leverkusen':'勒沃库森','Wolverhampton Wanderers':'狼队','Brighton & Hove Albion':'布莱顿','Tottenham Hotspur':'热刺','Internazionale':'国际米兰','Aston Villa':'阿斯顿维拉','Manchester City':'曼城','Borussia Mönchengladbach':'门兴格拉德巴赫','1. FC Union Berlin':'柏林联合','Athletic Club':'毕尔巴鄂竞技','Paris Saint-Germain':'巴黎圣日耳曼','Borussia Dortmund':'多特蒙德','RB Leipzig':'莱比锡','FC Cologne':'科隆','FC Porto':'波尔图','AC Milan':'AC米兰'}
    zh_aliases={converter.convert(a['value']) for lang,rows in e.get('aliases',{}).items() if lang.startswith('zh') for a in rows}
    zh_aliases.update(converter.convert(v['value']) for lang,v in labels.items() if lang.startswith('zh'))
    zh_aliases.update(re.sub(r'(足球体育俱乐部|足球俱乐部|体育俱乐部|俱乐部|足球会)$','',a) for a in list(zh_aliases))
    preferred=next((preferences[n] for n in names if n in preferences and preferences[n] in zh_aliases),None)
    zh=preferred or re.sub(r'(足球体育俱乐部|足球俱乐部|体育俱乐部|俱乐部|足球会)$','',zh)
   en_names=[e.get('labels',{}).get('en',{}).get('value','')]+[a['value'] for a in e.get('aliases',{}).get('en',[])]
   rank=0 if any(resolve(n)[0]==qid for n in names) else 1 if any(n.casefold()==a.casefold() for n in names for a in en_names) else 2
   row={'identityMatchRank':rank,'name':zh,'sourceName':source_name,'conversion':'OpenCC t2s','originalNames':sorted(names),'source':'https://www.wikidata.org/wiki/'+qid}
   if title:row['identitySource']='https://en.wikipedia.org/wiki/'+urllib.parse.quote(title.replace(' ','_'))
   found.append(row)
 if found:
  best=min(row['identityMatchRank'] for row in found);found=[row for row in found if row['identityMatchRank']==best]
 if len(found)==1:output['teams' if kind=='team' else 'venues'][str(1000000+int(key)) if kind=='team' else key]=found[0]
 else:missing.append({'kind':kind,'key':key,'names':sorted(names)})
# The official Chinese club site uses this abbreviation; identity is still ESPN 360 / Q18656.
if '1000360' in output['teams']:output['teams']['1000360'].update(name='曼联',shortNameSource='https://www.manutd.com/zh/news/thursday-briefing-latest-man-utd-news-thursday-5-november-2020')
# Previously verified stadium identity and label.
if 'Old Trafford' in venues and 'Q83457' in entities:
 e=entities['Q83457']; label=e.get('labels',{}).get('zh-cn',{}).get('value')
 if label:output['venues']['Old Trafford']={'name':label,'originalNames':['Old Trafford'],'source':'https://www.wikidata.org/wiki/Q83457'}
# Explicit reviewed aliases are keyed by ESPN identity, never by a fuzzy display-name match.
for identity, review in json.loads(Path('tmp/tests/football_name_reviews.json').read_text()).items():
 expected_names = sorted(teams[str(int(identity) - 1000000)])
 assert expected_names == review['originalNames'], identity
 if identity not in output['teams']:
  output['teams'][identity] = {'identityMatchRank': 0, 'conversion': 'Reviewed simplified Chinese label/alias'}
 output['teams'][identity].update(review)
Path('shared/assets/football-chinese-names.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
missing = [row for row in missing if row['kind'] != 'team' or str(1000000 + int(row['key'])) not in output['teams']]
Path('tmp/validation/chinese-names/missing.json').write_text(json.dumps(missing,ensure_ascii=False,indent=2))
print('Matched',len(output['teams']),'/',len(teams),'teams;',len(output['venues']),'/',len(venues),'venue names')
