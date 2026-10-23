"""Offline identity, stage, range and coverage checks against unchanged ESPN assets."""
import collections,json,pathlib,runpy
ROOT=pathlib.Path(__file__).resolve().parents[2]
build=runpy.run_path(str(ROOT/'tmp/tests/build_football_rounds.py'))
assets=ROOT/'shared/assets/rounds';catalog=json.loads((assets/'catalog.json').read_text());total=0
for y in range(2020,2027):
 rows=json.loads((assets/f'{y}.json').read_text());assert len(rows)==len({r['id'] for r in rows})
 for c in build['CODES']:
  events={f"fb-espn-{c}-{e['id']}":e for e in build['espn_events'](y,c)}
  selected=[r for r in rows if r['competitionId']==c]
  for row in selected:
   event=events[row['id']];game=event['competitions'][0];teams={x['homeAway']:int(x['team']['id'])+1000000 for x in game['competitors']}
   assert row['season']==y and row['homeTeamId']==teams['home'] and row['awayTeamId']==teams['away']
   assert row['providerNote']==game.get('altGameNote','')
   assert 1<=row['round']<=((8 if y>=2024 else 6) if c.startswith('uefa.') else (34 if c=='ger.1' or c=='fra.1' and y>=2023 else 38))
  cov=next(x for x in catalog['coverage'] if x['competition']==c and x['season']==y)
  assert len(selected)==cov['matched']
  if y==2026:assert cov['matched']==cov['eligible'],(y,c,cov)
 total+=len(rows)
print(f'PASS: {total} supplementary round records, all 7 current-season competitions complete; ESPN IDs and stage notes verified.')
