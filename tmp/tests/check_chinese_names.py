"""Check exact provider identities and display-only localization, entirely offline."""
import json,re,hashlib
from pathlib import Path
root=Path('app/src/main/assets'); names=json.loads((root/'football-chinese-names.json').read_text()); teams={};venues=set()
def walk(v):
 if isinstance(v,dict):
  t=v.get('team',{})
  if isinstance(t,dict) and t.get('id') and t.get('displayName'):teams.setdefault(str(1000000+int(t['id'])),set()).add(t['displayName'])
  g=v.get('venue',{})
  if isinstance(g,dict) and g.get('fullName'):venues.add(g['fullName'])
  for x in v.values():walk(x)
 elif isinstance(v,list):
  for x in v:walk(x)
for u,m in json.loads((root/'sports/catalog.json').read_text()).items():
 if '/soccer/' not in u:continue
 blob=(root/'sports'/m['file']).read_bytes()
 assert hashlib.sha256(blob).hexdigest()==m['sha256'],m['file']
 walk(json.loads(blob))
for kind,entries in [('teams',names['teams']),('venues',names['venues'])]:
 for key,row in entries.items():
  assert re.search('[\u3400-\u9fff]',row['name']),key
  assert re.fullmatch('https://www.wikidata.org/wiki/Q[0-9]+',row['source']),key
  if kind=='teams':
   assert set(row['originalNames'])==teams[key],key
   assert not row['name'].endswith('俱乐部'),key
   assert not any(x in row['name'] for x in ['女子','女足','青年','预备队']),key
  else:assert key in venues and row['originalNames']==[key],key
assert names['teams']['1000360']['name']=='曼联'
assert names['venues']['Old Trafford']['name']=='老特拉福德球场'
print(f"Verified {len(names['teams'])}/{len(teams)} teams and {len(names['venues'])}/{len(venues)} venue names; original ESPN hashes unchanged.")

reviews=json.loads(Path('tmp/tests/football_name_reviews.json').read_text())
for identity, review in reviews.items():
 assert names['teams'][identity]['name']==review['name'],identity
 assert names['teams'][identity]['source']==review['source'],identity
 assert names['teams'][identity]['originalNames']==review['originalNames'],identity
assert names['teams']['1003263']['name']=='热那亚'
assert names['teams']['1000887']['name']=='雅典AEK'
assert names['teams']['1000887']['source']=='https://www.wikidata.org/wiki/Q201584'
assert not any('板球与' in row['name'] or '竞技联盟' in row['name'] or '体育联合会' in row['name'] for row in names['teams'].values())
print(f'Checked {len(reviews)} reviewed common-name overrides against exact ESPN IDs.')
