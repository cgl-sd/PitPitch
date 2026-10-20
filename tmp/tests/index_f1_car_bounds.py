#!/usr/bin/env python3
"""Index existing car transparency bounds without modifying any image pixels."""
from pathlib import Path
import argparse
import json
from PIL import Image

root = Path(__file__).resolve().parents[2] / 'app/src/main/assets/f1-media'
parser = argparse.ArgumentParser()
parser.add_argument('--verify', action='store_true')
args = parser.parse_args()
catalog = json.loads((root / 'catalog.json').read_text())
count = 0
for key, record in catalog.items():
    if record['role'] != 'car':
        continue
    with Image.open(root / record['file']) as image:
        bounds = list(image.convert('RGBA').getchannel('A').getbbox())
    assert bounds[2] > bounds[0] and bounds[3] > bounds[1], key
    if args.verify:
        assert record.get('contentBounds') == bounds, key
    else:
        record['contentBounds'] = bounds
    count += 1
if not args.verify:
    (root / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
print(f'{"Verified" if args.verify else "Indexed"} {count} original car transparency bounds')
