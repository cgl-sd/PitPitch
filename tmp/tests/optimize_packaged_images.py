#!/usr/bin/env python3
"""Offline, reversible image encoding; keep dimensions, alpha, bindings and source times.

Run --apply once, then --verify. Originals and pixel comparison report stay ignored
under tmp/validation/apk-size. Never re-encode an already optimized derivative.
"""
import argparse
import hashlib
import io
import json
import shutil
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets'
BACKUP = ROOT / 'tmp/validation/apk-size/originals'
ENCODING = 'webp-full-resolution-v1'

def digest(body):
    return hashlib.sha256(body).hexdigest()

def verify(folder, catalog):
    for key, record in catalog.items():
        path = ASSETS / folder / record['file']
        assert path.parent == ASSETS / folder
        assert digest(path.read_bytes()) == record['sha256'], key
        if path.suffix != '.svg':
            with Image.open(path) as image:
                assert image.size == (record['width'], record['height']), key

def deduplicate(catalog):
    """Share only byte-identical raster files for the same role and entity.

    Each season retains its original binding, provenance and source timestamp.
    Circuit outlines/attribution references are intentionally excluded.
    """
    canonical = {}
    removed = set()
    for record in catalog.values():
        if record['role'] not in ('driver', 'car', 'logo'):
            continue
        identity = (record['role'], record['entity'], record['sha256'])
        name = record['file']
        shared = canonical.setdefault(identity, name)
        if shared != name:
            assert (ASSETS / 'f1-media' / shared).read_bytes() == (ASSETS / 'f1-media' / name).read_bytes()
            record['file'] = shared
            removed.add(name)
    used = {record['file'] for record in catalog.values()}
    saved = 0
    for name in removed - used:
        path = ASSETS / 'f1-media' / name
        backup = BACKUP / 'duplicates' / name
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, backup)
        saved += path.stat().st_size
        path.unlink()
    print(f'Shared {len(removed - used)} byte-identical files; saved {saved/1048576:.2f} MiB')

def apply(folder, catalog):
    rows = []
    # Each physical image is processed once, even when several seasons share it.
    files = {}
    for record in catalog.values():
        if folder == 'team-badges' or record.get('role') in ('driver', 'car'):
            files.setdefault(record['file'], []).append(record)
    def encode(item):
        filename, records = item
        record = records[0]
        if record.get('encoding', {}).get('id') == ENCODING:
            return None
        path = ASSETS / folder / filename
        original = path.read_bytes()
        image = Image.open(io.BytesIO(original)).convert('RGBA')
        lossless = folder == 'team-badges'
        quality = 100 if lossless else 94
        while True:
            output = io.BytesIO()
            image.save(output, 'WEBP', lossless=lossless, quality=quality, method=6, exact=True)
            encoded = output.getvalue()
            decoded = Image.open(io.BytesIO(encoded)).convert('RGBA')
            assert decoded.size == image.size
            assert decoded.getchannel('A').tobytes() == image.getchannel('A').tobytes(), filename
            # Invisible RGB is irrelevant; compare both black and white composites.
            errors = []
            for background in ('black', 'white'):
                base = Image.new('RGBA', image.size, background)
                before = Image.alpha_composite(base, image).convert('RGB')
                after = Image.alpha_composite(base, decoded).convert('RGB')
                errors.append(max(ImageStat.Stat(ImageChops.difference(before, after)).rms))
            if lossless:
                assert max(errors) == 0, filename
                break
            if max(errors) < 4:
                break
            # Preserve difficult high-contrast originals with lossless encoding.
            lossless = True
            quality = 100
        if len(encoded) >= len(original):
            return None
        staged = ROOT / 'tmp/validation/apk-size/staged' / folder / Path(filename).with_suffix('.webp')
        staged.parent.mkdir(parents=True, exist_ok=True)
        staged.write_bytes(encoded)
        print(f'Staged {folder}/{filename}: {len(original)} -> {len(encoded)}', flush=True)
        return filename, records, original, encoded, lossless, quality, max(errors)

    # Finish all encodes and quality checks before replacing catalog-bound inputs.
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = [result for result in pool.map(encode, files.items()) if result]
    for filename, records, original, encoded, lossless, quality, error in results:
        path = ASSETS / folder / filename
        backup = BACKUP / folder / filename
        backup.parent.mkdir(parents=True, exist_ok=True)
        if not backup.exists():
            shutil.copy2(path, backup)
        assert digest(backup.read_bytes()) == digest(original)
        target = path.with_suffix('.webp')
        target.write_bytes(encoded)
        for row in records:
            row['file'] = target.name
            row['sha256'] = digest(encoded)
            row['encoding'] = dict(id=ENCODING, sourceSha256=digest(original),
                lossless=lossless, quality=quality,
                dimensionsUnchanged=True, alphaUnchanged=True)
            if row.get('modification'):
                row['encoding']['sourceModification'] = row['modification']
            row['modification'] = ('Lossless WebP distribution derivative' if lossless else
                'WebP Q94 distribution derivative') + '; original dimensions and alpha preserved; no generated content'
        if target != path:
            path.unlink()  # Recoverable original remains in the local backup and Git.
        rows.append(dict(file=f'{folder}/{filename}', before=len(original), after=len(encoded),
                         maxCompositeRms=error))
    return rows

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--deduplicate', action='store_true')
    args = parser.parse_args()
    assert sum((args.apply, args.verify, args.deduplicate)) == 1, 'Choose one operation'
    rows = []
    for folder in ('f1-media', 'team-badges'):
        path = ASSETS / folder / 'catalog.json'
        catalog = json.loads(path.read_text())
        verify(folder, catalog)
        if args.apply:
            rows.extend(apply(folder, catalog))
        if args.deduplicate and folder == 'f1-media':
            deduplicate(catalog)
        if args.apply or args.deduplicate:
            path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
            verify(folder, catalog)
    if args.apply:
        report = ROOT / 'tmp/validation/apk-size/image-report.json'
        report.write_text(json.dumps(rows, indent=2) + '\n')
        print(f'Optimized {len(rows)} images; saved {sum(r["before"]-r["after"] for r in rows)/1048576:.2f} MiB')
    else:
        print('All image hashes and original dimensions verified')
