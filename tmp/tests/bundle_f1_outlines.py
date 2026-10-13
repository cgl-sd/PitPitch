#!/usr/bin/env python3
"""Package reviewed official and historical SVG outlines with exact race bindings.

Run to stage candidates, then compare each shape with its source before --apply.
Reviewed historical supplements stay pinned to their licensed original geometry.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/f1-media'
STAGE = ROOT / 'tmp/validation/f1-outline'
REFERENCE = ASSETS / 'layout-references.json'

def stage(item, tokens, supplements):
    key, reference = item
    if key in supplements:
        record = supplements[key]
        body = (ASSETS / record['file']).read_bytes()
        assert hashlib.sha256(body).hexdigest() == record['sha256']
        (STAGE / record['file']).write_bytes(body)
        return key, record
    year, circuit = reference['season'], reference['circuitId']
    # These old variants differ from the available current official outline.
    if year == 2020 and (circuit in ('catalunya', 'yas_marina') or reference['round'] == 16):
        raise ValueError('Different historical layout; no matching official outline')
    token = tokens.get(circuit)
    if token is None:
        raise ValueError('No reviewed official SVG source for this circuit')
    source_year = 2026 if year == 2026 else 2025
    url = f'https://media.formula1.com/image/upload/v1740000001/common/f1/{source_year}/track/{source_year}track{token}blackoutline.svg'
    cached = STAGE / f'outline-{source_year}-{token}.svg'
    if cached.exists():
        body = cached.read_bytes()
    else:
        body = urllib.request.urlopen(url, timeout=25).read()
        cached.write_bytes(body)
    svg = ET.fromstring(body)
    assert svg.tag.endswith('svg')
    assert not any(n.tag.split('}')[-1] in ('text', 'image', 'script') for n in svg.iter())
    assert any(n.tag.endswith('path') for n in svg.iter())
    view = [float(x) for x in svg.attrib['viewBox'].split()]
    record = dict(reference, file=cached.name, url=url, width=int(view[2]), height=int(view[3]),
        sha256=hashlib.sha256(body).hexdigest(), sourceSeason=source_year,
        style='official-black-outline', referenceLayoutUrl=reference['url'],
        referenceLayoutSha256=reference['sha256'],
        fetchedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    return key, record

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apply', action='store_true')
    args = p.parse_args()
    STAGE.mkdir(parents=True, exist_ok=True)
    catalog = json.loads((ASSETS / 'catalog.json').read_text())
    if not REFERENCE.exists():
        REFERENCE.write_text(json.dumps({k: r for k, r in catalog.items() if r['role'] == 'layout'}, indent=2) + '\n')
    references = json.loads(REFERENCE.read_text())
    supplements = {key: record for key, record in catalog.items()
                   if record['role'] == 'layout' and record.get('supplemental')}
    references.update(supplements)
    if args.apply:
        accepted = json.loads((STAGE / 'catalog.json').read_text())
        for key in list(catalog):
            if catalog[key]['role'] == 'layout':
                del catalog[key]
        for key, r in accepted.items():
            body = (STAGE / r['file']).read_bytes()
            assert hashlib.sha256(body).hexdigest() == r['sha256']
            assert references[key]['circuitId'] == r['circuitId']
            shutil.copy2(STAGE / r['file'], ASSETS / r['file'])
            catalog[key] = r
        (ASSETS / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
        print('Applied', len(accepted), 'reviewed SVG bindings')
        return
    # Derive track filenames from existing identity-verified official diagrams.
    # Historical-only calendar circuits, verified against their 2025 SVGs.
    tokens = {'bahrain': 'sakhir', 'imola': 'imola', 'jeddah': 'jeddah'}
    for r in references.values():
        if r['season'] == 2026:
            tokens[r['circuitId']] = re.search(r'2026track(.+)detailed', r['url'])[1]
    results, errors = {}, {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        jobs = {pool.submit(stage, item, tokens, supplements): item[0] for item in references.items()}
        for job in concurrent.futures.as_completed(jobs):
            key = jobs[job]
            try:
                key, record = job.result()
                results[key] = record
            except Exception as e:
                errors[key] = str(e)
    (STAGE / 'catalog.json').write_text(json.dumps(results, indent=2) + '\n')
    (STAGE / 'errors.json').write_text(json.dumps(errors, indent=2) + '\n')
    print('Staged', len(results), 'bindings; unavailable:', len(errors))

if __name__ == '__main__':
    main()
