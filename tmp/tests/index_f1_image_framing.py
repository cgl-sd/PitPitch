#!/usr/bin/env python3
"""Index visible logo bounds and reviewed, offline-detected face geometry.

No bitmap pixels, factual IDs, seasons, source URLs or acquisition times change.
Generate face observations with detect_portrait_faces.swift, then review the
contact sheets before applying them. --verify needs only the packaged catalog.
"""
import argparse
import json
from pathlib import Path
from PIL import Image
from collections import deque

ROOT = Path(__file__).resolve().parents[2]
MEDIA = ROOT / 'shared/assets/f1-media'
def logo_bounds(image):
    # Ignore only near-white canvas connected to the outside; enclosed white
    # brand details remain part of the mark. Source pixels are never rewritten.
    pixels = image.load()
    width, height = image.size
    def canvas(x, y):
        r, g, b, alpha = pixels[x, y]
        return alpha < 8 or min(r, g, b) >= 245
    outside = set()
    queue = deque((x, y) for x in range(width) for y in (0, height - 1))
    queue.extend((x, y) for y in range(height) for x in (0, width - 1))
    while queue:
        x, y = queue.popleft()
        if (x, y) in outside or not canvas(x, y):
            continue
        outside.add((x, y))
        queue.extend((xx, yy) for xx, yy in ((x-1,y),(x+1,y),(x,y-1),(x,y+1))
                     if 0 <= xx < width and 0 <= yy < height and (xx, yy) not in outside)
    foreground = [(x, y) for y in range(height) for x in range(width)
                  if pixels[x, y][3] >= 8 and (x, y) not in outside]
    assert foreground
    return [min(x for x,y in foreground), min(y for x,y in foreground),
            max(x for x,y in foreground)+1, max(y for x,y in foreground)+1]

parser = argparse.ArgumentParser()
parser.add_argument('--faces', type=Path)
parser.add_argument('--verify', action='store_true')
args = parser.parse_args()
assert bool(args.faces) != args.verify
catalog = json.loads((MEDIA / 'catalog.json').read_text())
faces = json.loads(args.faces.read_text()) if args.faces else {}
count = {'logo': 0, 'driver': 0}
for key, record in catalog.items():
    role = record['role']
    if role not in count:
        continue
    image = Image.open(MEDIA / record['file']).convert('RGBA')
    if role == 'logo':
        bounds = logo_bounds(image)
        if args.verify:
            assert record['contentBounds'] == bounds, key
        else:
            record['contentBounds'] = bounds
    elif args.verify:
        bounds = record['faceBounds']
        assert record['faceGeometrySource'] == 'Apple Vision face rectangles revision 3; visually reviewed'
        assert record['faceGeometrySha256'] == record['sha256'], key
    else:
        observations = faces[record['file']]
        assert len(observations) == 1, key
        box = observations[0]
        bounds = [round(box[0] * image.width, 3), round(box[1] * image.height, 3),
                  round(box[2] * image.width, 3), round(box[3] * image.height, 3)]
        record['faceBounds'] = bounds
        record['faceGeometrySha256'] = record['sha256']
        record['faceGeometrySource'] = 'Apple Vision face rectangles revision 3; visually reviewed'
    assert 0 <= bounds[0] < bounds[2] <= image.width, key
    assert 0 <= bounds[1] < bounds[3] <= image.height, key
    count[role] += 1
if not args.verify:
    (MEDIA / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
print('Verified' if args.verify else 'Indexed', count)
