"""Build independent round enrichment. Does not edit ESPN snapshots.
Raw inputs live in ignored tmp/validation/rounds/raw; --fetch downloads public sources.
Matching is exact after an explicit club alias list, scoped to competition/season.
No rounds are inferred from dates, scores, or fixture order.
"""
import argparse, collections, datetime, hashlib, json, pathlib, re, unicodedata, urllib.request, urllib.error
ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = ROOT/'tmp/validation/rounds/raw'
OUT = ROOT/'shared/assets/rounds'
CODES={'eng.1':'en.1','esp.1':'es.1','ita.1':'it.1','ger.1':'de.1','fra.1':'fr.1','uefa.champions':'champions-league','uefa.europa':'europa-league'}
def norm(s):
 s=''.join(c for c in unicodedata.normalize('NFKD',s.lower()) if not unicodedata.combining(c))
 return ''.join(w for w in re.findall('[a-z0-9]+',s) if w not in {'fc','afc','cf','sc','ac','as','us','rc','cd','ssc','bc','fsv','vfb','vfl','sv','tsg'})
ALIASES={}
def aliases(canonical,*names):
 for name in names: ALIASES[norm(name)]=norm(canonical)
for line in '''Alavés|Deportivo Alavés
Almería|UD Almería
Atlético Madrid|Club Atlético de Madrid|Atletico de Madrid|Atletico Madrid
Celta Vigo|RC Celta|RC Celta de Vigo
Deportivo|Deportivo La Coruña|RC Deportivo La Coruña
Eibar|SD Eibar
Espanyol|Espanyol Barcelona|RCD Espanyol de Barcelona
Huesca|SD Huesca
Las Palmas|UD Las Palmas
Levante|Levante UD
Mallorca|RCD Mallorca
Osasuna|CA Osasuna
Racing Santander|Real Racing Club de Santander
Rayo Vallecano|Rayo Vallecano de Madrid
Real Betis|Real Betis Balompié
Real Sociedad|Real Sociedad de Fútbol
Benevento|Benevento Calcio
Bologna|Bologna FC 1909
Brescia|Brescia Calcio
Cagliari|Cagliari Calcio
Como|Como 1907
Fiorentina|ACF Fiorentina
Frosinone|Frosinone Calcio
Genoa|Genoa CFC
Internazionale|FC Internazionale Milano|Inter
Lazio|Lazio Roma|SS Lazio
Parma|Parma Calcio 1913
Pisa|AC Pisa 1909
SPAL|SPAL 2013 Ferrara
Salernitana|US Salernitana 1919
Sampdoria|UC Sampdoria
Sassuolo|Sassuolo Calcio|US Sassuolo Calcio
Spezia|Spezia Calcio
Udinese|Udinese Calcio
Bayer Leverkusen|Bayer 04 Leverkusen|Leverkusen
Bayern Munich|Bayern München|FC Bayern München|Bayern
Borussia Mönchengladbach|Bor. Mönchengladbach|Mönchengladbach|M'gladbach
FC Cologne|1. FC Köln
Hamburg SV|Hamburger SV
Hertha Berlin|Hertha BSC
Mainz|1. FSV Mainz 05
SV Elversberg|SV 07 Elversberg
SpVgg Greuther Fürth|SpVgg Greuther Fürth 1903
St Pauli|FC St. Pauli 1910
TSG Hoffenheim|1899 Hoffenheim|TSG 1899 Hoffenheim|Hoffenheim
VfL Bochum|VfL Bochum 1848
Angers|Angers SCO
Bordeaux|Girondins Bordeaux
Brest|Stade Brestois 29
Caen|SM Caen
Clermont Foot|Clermont Foot 63
Guingamp|EA Guingamp
Lens|Racing Club de Lens
Lille|Lille OSC
Lyon|Olympique Lyonnais
Marseille|Olympique Marseille|Olympique de Marseille
Montpellier|Montpellier HSC
Nice|OGC Nice
Nimes|Nîmes Olympique
Stade Rennais|Stade Rennais FC 1901|Rennes
Strasbourg|RC Strasbourg Alsace
Troyes|ES Troyes AC|ESTAC Troyes'''.splitlines():aliases(*line.split('|'))
for line in '''AEK Athens|AEK
Apoel Nicosia|APOEL
Ajax Amsterdam|Ajax
Atlético Madrid|Atleti|Atlético
Borussia Dortmund|B. Dortmund|Dortmund
Real Betis|Betis
Bodo/Glimt|Bodø/Glimt
SK Brann|Brann
Brighton & Hove Albion|Brighton
Brøndby IF|Brøndby
CFR Cluj-Napoca|CFR Cluj
CSKA Moscow|CSKA Moskva
NK Celje|Celje
F.C. København|Copenhagen
Red Star Belgrade|Crvena Zvezda
F91 Dudelange|Dudelange
IF Elfsborg|Elfsborg
Feyenoord Rotterdam|Feyenoord
Eintracht Frankfurt|Frankfurt
Dinamo Zagreb|GNK Dinamo
Racing Genk|Genk
KAA Gent|Gent
Hapoel Be'er|H. Beer-Sheva
HJK Helsinki|HJK
BK Häcken|Häcken
Jagiellonia Bialystok|Jagiellonia
LASK Linz|LASK
Lille|LOSC
Lech Poznan|Lech
Legia Warsaw|Legia
Leicester City|Leicester
RB Leipzig|Leipzig
Lillestrom|Lillestrøm
Lokomotiv Moscow|Lokomotiv Moskva
Ludogorets Razgrad|Ludogorets
Maccabi Haifa|M. Haifa
Maccabi Tel-Aviv|M. Tel-Aviv
Malmö FF|Malmö
Manchester United|Man United|Man Utd
Manchester City|Man. City
NK Maribor|Maribor
NEC Nijmegen|N.E.C.
Newcastle United|Newcastle
Nottingham Forest|Nott'm Forest
Omonia Nicosia|Omonia|Omonoia
PSV Eindhoven|PSV
Partizan Belgrade|Partizan
Viktoria Plzen|Plzen
FK Qarabag|Qarabag
Rigas Futbola Skola|RFS
Raków Czestochowa|Raków
Rapid Vienna|Rapid Wien
Slovan Bratislava|S. Bratislava
Sabah FK|Sabah
RB Salzburg|Salzburg
Schalke 04|Schalke
Shakhtar Donetsk|Shakhtar
Sheriff Tiraspol|Sheriff
Slavia Prague|Slavia Praha
Sparta Prague|Sparta Praha
Spartak Moscow|Spartak Moskva
Saint-Étienne|St-Étienne
SK Sturm Graz|Sturm|Sturm Graz
FK TSC|TSC
Tottenham Hotspur|Tottenham
1. FC Union Berlin|Union Berlin
Union St.-Gilloise|Union SG
Viking FK|Viking
Vitória de Guimaraes|Vitória SC
West Ham United|West Ham
Wolfsberger|Wolfsberg
Wolverhampton Wanderers|Wolves
Zenit St Petersburg|Zenit'''.splitlines():aliases(*line.split('|'))
def canonical(s,c):
 if c.startswith('uefa.') and norm(s)=='paris':return norm('Paris Saint-Germain')
 return ALIASES.get(norm(s),norm(s))
def source_url(y,c):
 if c.startswith('uefa.'):return f'https://fixturedownload.azurewebsites.net/feed/json/{CODES[c]}-{y}'
 return f'https://raw.githubusercontent.com/openfootball/football.json/master/{y}-{str(y+1)[-2:]}/{CODES[c]}.json'
def load_source(y,c):
 path=RAW/f"{y}-{CODES[c]}.json"
 if not path.exists():return [],None
 d=json.loads(path.read_text());rows=[]
 if c.startswith('uefa.'):
  for x in d:
   if x['RoundNumber'] > (8 if y>=2024 else 6):continue # KO has a phase, not a global matchday.
   rows.append({'home':x['HomeTeam'],'away':x['AwayTeam'],'round':x['RoundNumber'],'ref':str(x['MatchNumber']),'group':x.get('Group'),'date':x['DateUtc'][:10]})
 else:
  for i,x in enumerate(d['matches']):
   m=re.fullmatch(r'Matchday (\d+)',x['round'])
   if m:rows.append({'home':x['team1'],'away':x['team2'],'round':int(m[1]),'ref':str(i),'group':None,'date':x['date']})
 return rows,hashlib.sha256(path.read_bytes()).hexdigest()
def espn_events(y,c):
 cat=json.loads((ROOT/'shared/assets/sports/catalog.json').read_text());events={}
 for cy in [y,y+1]:
  key=f'https://site.api.espn.com/apis/site/v2/sports/soccer/{c}/scoreboard?dates={cy}&limit=1000'
  for e in json.loads((ROOT/'shared/assets/sports'/cat[key]['file']).read_text())['events']:
   if e['season']['year']==y:events[e['id']]=e
 return list(events.values())
def generate(write=True):
 report=[];unknown=collections.defaultdict(set);all_source={};total=0
 for y in range(2020,2027):
  results=[]
  for c in CODES:
   rows,digest=load_source(y,c);all_source[f'{y}/{c}']={'url':source_url(y,c),'sha256':digest,'rows':len(rows),'fetchedUtc':datetime.datetime.fromtimestamp((RAW/f'{y}-{CODES[c]}.json').stat().st_mtime,datetime.timezone.utc).isoformat() if digest else None}
   index=collections.defaultdict(list)
   for x in rows:index[canonical(x['home'],c),canonical(x['away'],c)].append(x)
   used=set();eligible=0;covered=0
   events=espn_events(y,c)
   identities=collections.defaultdict(set)
   for event in events:
    for team in event['competitions'][0]['competitors']:
     identities[canonical(team['team']['displayName'],c)].add(team['team']['id'])
   assert all(len(ids)==1 for ids in identities.values()),(y,c,'club alias collision')
   pairs=collections.defaultdict(list)
   for event in events:
    ts=event['competitions'][0]['competitors'];home=next(t['team']['id'] for t in ts if t['homeAway']=='home');away=next(t['team']['id'] for t in ts if t['homeAway']=='away')
    pairs[home,away].append(event)
   for e in events:
    g=e['competitions'][0];note=g.get('altGameNote','');stage=note.split(', ',1)[-1]
    if c.startswith('uefa.') and not(re.search(r'group [A-L]$',stage,re.I) or stage=='League Phase'):continue
    eligible+=1
    h=next(x['team'] for x in g['competitors'] if x['homeAway']=='home');a=next(x['team'] for x in g['competitors'] if x['homeAway']=='away')
    candidates=index[canonical(h['displayName'],c),canonical(a['displayName'],c)]
    if c.startswith('uefa.') and y<2024:candidates=[x for x in candidates if str(x['group']).lower()==('Group '+stage[-1]).lower()]
    if len(candidates)!=1:
     unknown[c].add((h['displayName'],a['displayName'],y));continue
    x=candidates[0]; ref=x['ref']
    duplicates=pairs[h['id'],a['id']]
    if len(duplicates)>1:
     # Relegation playoff can repeat a league pairing. Only the exact dated fixture is enriched.
     if e['date'][:10]!=x.get('date'):unknown[c].add((h['displayName'],a['displayName'],y));continue
    assert ref not in used,(y,c,ref)
    used.add(ref);covered+=1
    results.append({'id':f"fb-espn-{c}-{e['id']}",'competitionId':c,'season':y,'homeTeamId':int(h['id'])+1000000,'awayTeamId':int(a['id'])+1000000,'providerNote':note,'round':x['round'],'source':source_url(y,c),'sourceRef':ref})
   report.append({'season':y,'competition':c,'matched':covered,'eligible':eligible,'sourceRows':len(rows)})
  total+=len(results)
  if write:
   OUT.mkdir(parents=True,exist_ok=True);(OUT/f'{y}.json').write_text(json.dumps(results,ensure_ascii=False,separators=(',',':'))+'\n')
 for c,entries in unknown.items():
  print(c,'unmatched',len(entries));print(sorted(entries)[:80])
 print('TOTAL',total)
 if write:(OUT/'catalog.json').write_text(json.dumps({'sources':all_source,'coverage':report},ensure_ascii=False,indent=2)+'\n')
 print('\n'.join(f"{x['season']} {x['competition']} {x['matched']}/{x['eligible']}" for x in report))
 return report
def fetch():
 RAW.mkdir(parents=True,exist_ok=True)
 for y in range(2020,2027):
  for c in CODES:
   path=RAW/f"{y}-{CODES[c]}.json"
   if path.exists():continue
   try:
    body=urllib.request.urlopen(source_url(y,c),timeout=30).read();json.loads(body);path.write_bytes(body)
   except urllib.error.HTTPError as error:
    raise
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--fetch',action='store_true');args=parser.parse_args()
 if args.fetch:fetch()
 generate()
