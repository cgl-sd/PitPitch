#!/usr/bin/env python3
"""Compare built native bundles to the unchanged shared manifest; never fetch data."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'shared/assets'


def digest(stream):
    result = hashlib.sha256()
    for block in iter(lambda: stream.read(65536), b''):
        result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-apk', type=Path)
    parser.add_argument('--ios-app', type=Path)
    args = parser.parse_args()
    manifest = json.loads((ASSETS / 'local-backend-manifest.json').read_text())
    expected = dict(manifest['files'])
    revision = hashlib.sha256(json.dumps(expected, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if revision != manifest['revision'] or (ASSETS / 'local-backend-revision.txt').read_text().strip() != revision:
        raise SystemExit('Shared manifest revision mismatch')
    for name in ('local-backend-manifest.json', 'local-backend-revision.txt'):
        with (ASSETS / name).open('rb') as stream:
            expected[name] = digest(stream)
    for relative, sha in expected.items():
        with (ASSETS / relative).open('rb') as stream:
            if digest(stream) != sha:
                raise SystemExit('Changed shared input: ' + relative)
    derived_root = ROOT / 'ios/PitPitch/Resources/MediaDerived'
    derived = json.loads((derived_root / 'derived-index.json').read_text())
    media = json.loads((ASSETS / 'f1-media/catalog.json').read_text())
    required = {row['file'] for row in media.values() if row['file'].endswith('.svg')}
    if set(derived) != required:
        raise SystemExit('iOS derived SVG coverage mismatch')
    for original, row in derived.items():
        with (ASSETS / 'f1-media' / original).open('rb') as stream:
            if digest(stream) != row['sourceSHA256']:
                raise SystemExit('Stale iOS SVG derivation: ' + original)
        with (derived_root / row['file']).open('rb') as stream:
            if digest(stream) != row['sha256']:
                raise SystemExit('Changed iOS track image: ' + row['file'])
    print('iOS derived tracks: %d original/output hash pairs verified' % len(derived))
    if args.android_apk:
        with zipfile.ZipFile(args.android_apk) as apk:
            for relative, sha in expected.items():
                with apk.open('assets/' + relative) as stream:
                    if digest(stream) != sha:
                        raise SystemExit('Android packaged input mismatch: ' + relative)
        print('Android APK: %d shared inputs verified' % len(expected))
    if args.ios_app:
        for relative, sha in expected.items():
            with (args.ios_app / 'assets' / relative).open('rb') as stream:
                if digest(stream) != sha:
                    raise SystemExit('iOS packaged input mismatch: ' + relative)
        for row in derived.values():
            with (args.ios_app / 'MediaDerived' / row['file']).open('rb') as stream:
                if digest(stream) != row['sha256']:
                    raise SystemExit('iOS packaged derived image mismatch: ' + row['file'])
        branding = ROOT / 'shared/branding/drawable-nodpi'
        for source in branding.glob('*.png'):
            with source.open('rb') as stream:
                sha = digest(stream)
            with (args.ios_app / 'branding/drawable-nodpi' / source.name).open('rb') as stream:
                if digest(stream) != sha:
                    raise SystemExit('iOS packaged brand image mismatch: ' + source.name)
        print('iOS app: %d shared inputs, derived tracks and original branding verified' % len(expected))
    print('Shared revision: ' + revision)


if __name__ == '__main__':
    main()
