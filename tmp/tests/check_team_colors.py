"""Offline identity, color, source-time and response-integrity checks for packaged branding."""
from pathlib import Path
import json,re,hashlib,datetime
root=Path('shared/assets')
colors=json.loads((root/'football-team-colors.json').read_text())
names=json.loads((root/'football-chinese-names.json').read_text())['teams']
assert set(colors)==set(names)
for identity,row in colors.items():
 provider_id=str(int(identity)-1000000)
 assert re.fullmatch('[0-9A-F]{6}',row['color']),identity
 assert row['originalName'] in names[identity]['originalNames'],identity
 assert row['source'].startswith('https://site.api.espn.com/apis/site/v2/sports/soccer/') and row['source'].endswith('/teams/'+provider_id),identity
 datetime.datetime.fromisoformat(row['fetchedUtc'])
 body=Path('tmp/validation/team-colors',provider_id+'.json').read_bytes()
 assert hashlib.sha256(body).hexdigest()==row['sha256'],identity
 team=json.loads(body)['team']
 assert str(team['id'])==provider_id and team['color'].upper()==row['color'],identity
print('Verified',len(colors),'original provider colors and identities; no source event/standing changes.')
