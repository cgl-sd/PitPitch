#!/usr/bin/env python3
"""Stage official circuit diagrams from each archived race's exact season page.
Review staged diagrams before --apply. No inferred URLs or cross-season fallback.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import urllib.request
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/f1-media'
STAGE = ROOT / 'tmp/validation/f1-media-review/layouts'
# Explicit archive identity to official race-page mapping, not fuzzy name search.
SLUGS = dict(red_bull_ring='austria', hungaroring='hungary', silverstone='great-britain',
    catalunya='spain', madring='spain', spa='belgium', monza='italy', mugello='tuscany',
    sochi='russia', nurburgring='germany', portimao='portugal', imola='emiliaromagna',
    istanbul='turkey', bahrain='bahrain', yas_marina='united-arab-emirates', monaco='monaco',
    baku='azerbaijan', ricard='france', zandvoort='netherlands', americas='united-states',
    rodriguez='mexico', interlagos='brazil', losail='qatar', jeddah='saudi-arabia',
    albert_park='australia', miami='miami', villeneuve='canada', marina_bay='singapore',
    suzuka='japan', vegas='las-vegas', shanghai='china', sepang='bahrain')
SPECIAL = {'Styrian Grand Prix': 'styria', '70th Anniversary Grand Prix': '70th-anniversary',
           'Sakhir Grand Prix': 'sakhir'}
# The legacy URLs are mutable and reused across historical pages. Visual review
# found a pre-2021 Catalunya/Yas Marina layout and a post-2023 Singapore layout.
# Exclude conflicting years instead of treating page presence as season proof.
# Changes: formula1.com/en/latest/article/revealed-the-track-changes-aimed-at-improving-overtaking-at-abu-dhabis-yas.rejS8Xxbgoom5yPUInsek
# Catalunya: corp.formula1.com/formula-1-aws-gran-premio-de-espana-2023-changes-its-track-configuration/
UNVERIFIED_LEGACY = {'catalunya': set(range(2021, 2026)),
                     'yas_marina': set(range(2021, 2026)), 'marina_bay': {2022}}

class Images(HTMLParser):
    def __init__(self):
        super().__init__()
        self.images = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        src = a.get('src', '')
        if tag == 'img' and src.startswith('https://media.formula1.com/') and (
                '/track/' in src or '/Circuit%20maps%2016x9/' in src):
            self.images.append(a)

def races():
    archive = ROOT / 'app/src/main/assets/sports'
    catalog = json.loads((archive / 'catalog.json').read_text())
    for year in range(2020, datetime.date.today().year + 1):
        record = catalog.get(f'https://api.jolpi.ca/ergast/f1/{year}.json?limit=100')
        if record:
            yield from json.loads((archive / record['file']).read_text())['MRData']['RaceTable']['Races']

def fetch(race):
    year, round_ = int(race['season']), int(race['round'])
    circuit = race['Circuit']['circuitId']
    slug = SPECIAL.get(race['raceName'], SLUGS[circuit])
    if circuit == 'catalunya' and year == 2026:
        slug = 'barcelona-catalunya'
    if circuit == 'imola' and year == 2020:
        slug = 'emilia-romagna'
    page = f'https://www.formula1.com/en/racing/{year}/{slug}'
    with urllib.request.urlopen(page, timeout=25) as r:
        assert r.url == page, ('Unexpected redirect', r.url)
        html = r.read()
    parser = Images()
    parser.feed(html.decode())
    assert len(parser.images) == 1, ('Missing/ambiguous official diagram', page)
    source = parser.images[0]
    source_year = re.search(r'/common/f1/(\d{4})/track/', source['src'])
    assert not source_year or int(source_year[1]) == year, 'Different map season'
    assert source_year or year not in UNVERIFIED_LEGACY.get(circuit, set()), 'Legacy map conflicts with historical layout'
    url = re.sub(r'/image/upload/[^/]+/', '/image/upload/c_fit,w_1024/', source['src'])
    assert 'd_common:' not in url
    with urllib.request.urlopen(url, timeout=25) as r:
        body = r.read()
    im = Image.open(io.BytesIO(body))
    assert im.format == 'WEBP' and im.width >= 600
    entity = f'f1-{year}-{round_}'
    file = f'layout-{year}-{round_}-{circuit}.webp'
    (STAGE / file).write_bytes(body)
    (STAGE / f'{entity}.html').write_bytes(html)
    return f'layout/{year}/{entity}', dict(role='layout', entity=entity, circuitId=circuit,
        season=year, round=round_, raceName=race['raceName'], file=file, url=url, page=page,
        sourceImageUrl=source['src'], sourceImageAlt=source.get('alt', ''),
        fetchedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        sourcePageSha256=hashlib.sha256(html).hexdigest(), sha256=hashlib.sha256(body).hexdigest(),
        width=im.width, height=im.height, credit='Formula 1 / respective rights holders')

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apply', action='store_true')
    args = p.parse_args()
    STAGE.mkdir(parents=True, exist_ok=True)
    if args.apply:
        catalog = json.loads((ASSETS / 'catalog.json').read_text())
        assert not any(r.get('style') == 'official-black-outline' for r in catalog.values()), 'Use bundle_f1_outlines.py for the current plain-outline UI; do not restore annotated diagrams'
        candidates = json.loads((STAGE / 'catalog.json').read_text())
        for key, record in candidates.items():
            assert '/common/f1/' in record['url'] or record['season'] not in UNVERIFIED_LEGACY.get(record['circuitId'], set())
            body = (STAGE / record['file']).read_bytes()
            assert hashlib.sha256(body).hexdigest() == record['sha256']
            (ASSETS / record['file']).write_bytes(body)
            catalog[key] = record
        (ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
        print('Applied', len(candidates), 'reviewed official diagrams')
        return
    results, errors = {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        jobs = {pool.submit(fetch, race): race for race in races()}
        for job in concurrent.futures.as_completed(jobs):
            race = jobs[job]
            key = f"f1-{race['season']}-{race['round']}"
            try:
                key, record = job.result()
                results[key] = record
            except Exception as e:
                errors[key] = str(e)
    (STAGE / 'catalog.json').write_text(json.dumps(results, indent=2) + '\n')
    (STAGE / 'errors.json').write_text(json.dumps(errors, indent=2) + '\n')
    print('Staged', len(results), 'diagrams; errors:', errors)

if __name__ == '__main__':
    main()
