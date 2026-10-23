"""Explicit preparation of provider team colors. Never runs from Gradle or the app."""
import json, hashlib, datetime, urllib.request, concurrent.futures, re
from pathlib import Path
root=Path('shared/assets'); catalog=json.loads((root/'sports/catalog.json').read_text()); teams={}
def walk(v,league):
 if isinstance(v,dict):
  team=v.get('team')
  if isinstance(team,dict) and team.get('id') and team.get('displayName'):
   teams.setdefault(str(team['id']),{'originalName':team['displayName'],'league':league})
  for child in v.values():walk(child,league)
 elif isinstance(v,list):
  for child in v:walk(child,league)
for url,meta in catalog.items():
 if '/soccer/' in url:walk(json.loads((root/'sports'/meta['file']).read_text()),url.split('/soccer/',1)[1].split('/',1)[0])
cache=Path('tmp/validation/team-colors');cache.mkdir(parents=True,exist_ok=True)
def get(entry):
 id,original=entry;url=f"https://site.api.espn.com/apis/site/v2/sports/soccer/{original['league']}/teams/{id}"
 path=cache/f'{id}.json';stamp=cache/f'{id}.time'
 try:
  if path.exists() and stamp.exists():body=path.read_bytes();time=stamp.read_text()
  else:
   with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'PitPitch packaged branding preparation/0.7'}),timeout=12) as response:body=response.read()
   time=datetime.datetime.now(datetime.timezone.utc).isoformat();path.write_bytes(body);stamp.write_text(time)
  team=json.loads(body)['team'];assert str(team['id'])==id
  color=team.get('color','');alternate=team.get('alternateColor','')
  if not re.fullmatch('[0-9A-Fa-f]{6}',color):return None
  return str(1000000+int(id)),{'color':color.upper(),'alternateColor':alternate.upper() if re.fullmatch('[0-9A-Fa-f]{6}',alternate) else None,
   'source':url,'fetchedUtc':time,'sha256':hashlib.sha256(body).hexdigest(),'originalName':original['originalName'],'providerName':team.get('displayName')}
 except Exception as error:return {'id':id,'error':str(error)}
output={};errors=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for index,result in enumerate(pool.map(get,teams.items())):
  if isinstance(result,tuple):output[result[0]]=result[1]
  elif result:errors.append(result)
  if (index+1)%30==0:print('Checked',index+1,'/',len(teams),'colors',len(output),flush=True)
(root/'football-team-colors.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
(cache/'errors.json').write_text(json.dumps(errors,ensure_ascii=False,indent=2)+'\n')
print('Prepared',len(output),'/',len(teams),'identity-checked team colors; errors',len(errors),flush=True)
