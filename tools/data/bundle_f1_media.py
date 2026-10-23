#!/usr/bin/env python3
"""Verify or restore the reviewed F1 media manifest, without modifying sports JSON.

Run with --download only to restore missing/corrupt files from their recorded HTTPS
URLs. A changed upstream image is rejected until it has been manually reviewed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2] / 'shared/assets/f1-media'
parser = argparse.ArgumentParser()
parser.add_argument('--download', action='store_true')
args = parser.parse_args()
catalog = json.loads((root / 'catalog.json').read_text())
for key, record in catalog.items():
    path = root / record['file']
    assert path.parent == root, key
    expected = record['sha256']
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        if not args.download:
            raise SystemExit(f'Missing or changed asset: {key}')
        assert record['url'].startswith('https://media.formula1.com/')
        assert 'd_common:' not in record['url'], 'CDN fallback images are not real photos'
        with urllib.request.urlopen(record['url'], timeout=30) as response:
            body = response.read()
        if hashlib.sha256(body).hexdigest() != expected:
            raise SystemExit(f'Upstream image changed; review before replacing: {key}')
        path.write_bytes(body)
    assert record['entity'] == key.split('/')[-1], key
    if path.suffix == '.svg':
        svg = ET.fromstring(path.read_bytes())
        assert not any(n.tag.split('}')[-1] in ('text', 'image', 'script') for n in svg.iter()), key
        assert any(n.tag.endswith('path') for n in svg.iter()), key
    if record['role'] in ('car', 'logo', 'layout'):
        assert record['season'] == int(key.split('/')[1]), key
    if record['role'] == 'layout':
        assert record['entity'] == f'f1-{record["season"]}-{record["round"]}', key
        assert record['circuitId'], key
print(f'Validated {len(catalog)} bindings, {len({r["file"] for r in catalog.values()})} packaged images')
