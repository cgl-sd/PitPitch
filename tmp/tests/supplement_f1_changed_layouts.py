#!/usr/bin/env python3
"""Stage six licensed authentic layout variants and the 13 exact missing bindings.

Requires svgpathtools (pip install svgpathtools). Never modifies production assets
or catalogs. Exact source hashes pin the reviewed geometry; upstream changes fail
closed. Originals and licensing/change-history HTML remain in the staging folder.
"""
import datetime
import hashlib
import json
import math
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

from svgpathtools import parse_path
from f1_outline_style import normalize_catalog

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / 'tmp/validation/f1-track-completion/changed-layouts'
CC3 = 'https://creativecommons.org/licenses/by-sa/3.0/'
CC4 = 'https://creativecommons.org/licenses/by-sa/4.0/'
COMMONS = 'https://commons.wikimedia.org/wiki/File:'
UPLOAD = 'https://upload.wikimedia.org/wikipedia/commons/'
EVIDENCE = {
    'catalunya-turn10-2021': 'https://www.formula1.com/en/latest/article/circuit-de-barcelona-catalunya-complete-remodelling-of-turn-10.giY8HxbKVmtub2ZjyvI1B',
    'catalunya-chicane-2023': 'https://www.formula1.com/en/latest/article/circuit-de-barcelona-catalunya-to-feature-new-configuration-for-2023-spanish.6ZQgemg7eOlAJbBtu56PHE',
    'yas-redesign-2021': 'https://www.formula1.com/en/latest/article/revealed-the-track-changes-aimed-at-improving-overtaking-at-abu-dhabis-yas.rejS8Xxbgoom5yPUInsek',
    'singapore-redesign-2023': 'https://www.formula1.com/en/latest/article/singapore-grand-prix-set-to-feature-revised-track-layout-in-2023.6KsrU6hQw3hxrVJ6zKeJ74',
    'catalunya-history': 'https://en.wikipedia.org/wiki/Circuit_de_Barcelona-Catalunya',
    'yas-history': 'https://en.wikipedia.org/wiki/Yas_Marina_Circuit',
}
SOURCES = {
    'catalunya-2020': dict(title='Formula1_Circuit_Catalunya.svg', remote='9/9c/Formula1_Circuit_Catalunya.svg',
        sha256='8a0884bdfbccc70a37667ad6e1a08b0a4a1f5929391f791506fb063125d1cbbd',
        sourceSeason=2017, revision='2017-05-18T01:11:23Z', creator='Will Pittenger; Sentoan (2017 revision)', license='CC-BY-SA-3.0', licenseUrl=CC3,
        selector={'id': 'path3965'}, applicableSeasons=[2020],
        geometry='2007–2020 F1 Grand Prix route: acute Turn 10 and final-sector chicane. Commons caption explicitly identifies 2007–2020; F1 documents Turn 10 replacement only in 2021.',
        evidence=['catalunya-turn10-2021', 'catalunya-chicane-2023', 'catalunya-history']),
    'catalunya-2021': dict(title='Formula1_Circuit_Catalunya_2021.svg', remote='2/26/Formula1_Circuit_Catalunya_2021.svg',
        sha256='4b2afb8e4d0bbba9a792243287371a5dc54c72fcf8be38cea52153894134e2c4',
        sourceSeason=2021, revision='2021-04-21T23:28:45Z', creator='GabrielStella; based on Will Pittenger / Sentoan', license='CC-BY-SA-3.0', licenseUrl=CC3,
        selector={'dLength': 8197}, applicableSeasons=[2021, 2022],
        geometry='2021–2022 F1 Grand Prix route: remodelled arcing Turn 10, retained Turns 14–15 chicane. 2021 Commons drawing and F1 Turn 10 announcement identify the new corner; F1 chicane removal announcement dates the next route change to 2023.',
        evidence=['catalunya-turn10-2021', 'catalunya-chicane-2023', 'catalunya-history']),
    'catalunya-2023': dict(title='2023_F1_CourseLayout_Spain.svg', remote='2/25/2023_F1_CourseLayout_Spain.svg',
        sha256='08e92c70d1f3955600a287a8a7566c83aabe887cc3d1bec37189a62c12989c81',
        sourceSeason=2023, revision='2023-05-30T08:13:59Z', creator='ごひょううべこ', license='CC-BY-SA-4.0', licenseUrl=CC4,
        selector={'groupId': 'コース'}, applicableSeasons=[2023, 2024, 2025],
        geometry='2023 onward 4.657 km F1 route: remodelled Turn 10, final chicane bypassed, 14 turns. F1 dates chicane removal to 2023; circuit history describes this as the current route, and the source page explicitly lists use by the 2023, 2024 and 2025 Spanish Grands Prix. No modern 2026 asset is substituted.',
        evidence=['catalunya-turn10-2021', 'catalunya-chicane-2023', 'catalunya-history']),
    'yas-2020': dict(title='Circuit_Yas-Island.svg', remote='d/dc/Circuit_Yas-Island.svg',
        sha256='45fdbc5ddc5aa0b23476bdcb7cfb1eedcb89c4474d6a336b4f59cd42ebd53a2b',
        sourceSeason=2017, revision='2017-11-27T04:39:18Z', creator='Arz; Sentoan; Cherkash (cleanup)', license='CC-BY-SA-3.0', licenseUrl=CC3,
        selector={'id': 'path25'}, applicableSeasons=[2020],
        geometry='Pre-2021 21-turn Yas Marina F1 route: north-hairpin chicane, four sharp south-marina corners and tight hotel section. The 2017 source predates the works; F1 June 2021 announcement explicitly describes these as the old route replaced for the 2021 race. Commons caption end-year 2021 is not treated as proof of 2021 race applicability.',
        evidence=['yas-redesign-2021', 'yas-history']),
    'yas-2021': dict(title='2022_F1_CourseLayout_Abu_Dhabi.svg', remote='2/24/2022_F1_CourseLayout_Abu_Dhabi.svg',
        sha256='a44c69641de8d19a1deaaae20e52d4b9e75d8b231f62a1b22ce8b111ad075293',
        sourceSeason=2022, revision='2023-05-26T01:55:44Z', creator='ごひょううべこ', license='CC-BY-SA-4.0', licenseUrl=CC4,
        selector={'groupId': 'コース'}, applicableSeasons=[2021, 2022, 2023, 2024, 2025],
        geometry='Post-2021 5.281 km, 16-turn F1 route: north chicane eliminated, sweeping banked south-marina turn and opened hotel corners. F1 documents first use for the 2021 Abu Dhabi GP; history describes the 2021 redesign as the current route. Licensed 2022 drawing includes these three changes, and source-page usage identifies 2022–2025 Abu Dhabi GPs. May 2023 source revision fixes broken drawing parts, not a new race route.',
        evidence=['yas-redesign-2021', 'yas-history']),
    'singapore-2022': dict(title='2022_F1_CourseLayout_Singapore.svg', remote='2/24/2022_F1_CourseLayout_Singapore.svg',
        sha256='2da0f14a452f924a7f1caec4cf60bcae2f6687f3fc7a926e21441b73faa63372',
        sourceSeason=2022, revision='2022-09-13T02:17:24Z', creator='ごひょううべこ', license='CC-BY-SA-4.0', licenseUrl=CC4,
        selector={'groupId': 'コース'}, applicableSeasons=[2022],
        geometry='Exact 2022 Marina Bay F1 route with the old Bay Grandstand Turns 16–19 section, 23 corners (not 21), 5.063 km. September 2022 source is contemporaneous. F1 October 2022 announcement explicitly dates replacement of these four corners with a straight and reduction from 23 to 19 corners to 2023. This is not the older 2015 source or the modern 2023+ route.',
        evidence=['singapore-redesign-2023']),
}
BINDINGS = {
    'catalunya': [(2020, 6), (2021, 4), (2022, 6), (2023, 7), (2024, 10), (2025, 9)],
    'yas_marina': [(2020, 17), (2021, 22), (2022, 22), (2023, 22), (2024, 24), (2025, 24)],
    'marina_bay': [(2022, 17)],
}
NAMES = {'catalunya': 'Spanish Grand Prix', 'yas_marina': 'Abu Dhabi Grand Prix', 'marina_bay': 'Singapore Grand Prix'}
SLUGS = {'catalunya': 'spain', 'yas_marina': 'united-arab-emirates', 'marina_bay': 'singapore'}


def digest(body):
    return hashlib.sha256(body).hexdigest()


def resource(path, url, expected=None):
    if not path.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(request, timeout=45) as response:
            path.write_bytes(response.read())
    body = path.read_bytes()
    if expected and digest(body) != expected:
        raise ValueError(f'Unreviewed upstream source change: {url}')
    return body


def select_path(root, selector):
    if 'groupId' in selector:
        group = next(n for n in root.iter() if n.attrib.get('id') == selector['groupId'])
        candidates = [n for n in group if n.tag.endswith('path')]
    elif 'id' in selector:
        candidates = [n for n in root.iter() if n.attrib.get('id') == selector['id']]
    else:
        candidates = [n for n in root.iter() if n.tag.endswith('path') and len(n.attrib.get('d', '')) == selector['dLength']]
    if len(candidates) != 1:
        raise ValueError('Pinned source no longer has exactly one reviewed route path')
    return candidates[0].attrib['d']


def derivative(name, source, original):
    # Retain the exact closed route centerline, not annotations, alternate routes
    # or pit lanes. Cropping discards only empty margin; it does not distort shape.
    d = select_path(ET.fromstring(original), source['selector'])
    path = parse_path(d)
    if not path.isclosed():
        raise ValueError('Authentic circuit route must be closed')
    x0, x1, y0, y1 = path.bbox()
    span = max(x1 - x0, y1 - y0)
    stroke, padding = span * 0.012, span * 0.035
    w, h = x1 - x0 + 2 * padding, y1 - y0 + 2 * padding
    width, height = 1024, math.ceil(1024 * h / w)
    root = ET.Element('svg', xmlns='http://www.w3.org/2000/svg', width=str(width), height=str(height),
                      viewBox=f'{x0-padding:.6f} {y0-padding:.6f} {w:.6f} {h:.6f}')
    ET.SubElement(root, 'title').text = name + ' authentic F1 circuit outline'
    ET.SubElement(root, 'desc').text = (f"Source: {COMMONS}{source['title']}; creator: {source['creator']}; "
        f"license: {source['license']} ({source['licenseUrl']}). PitPitch adaptation: isolate exact original route path, "
        'omit all labels, numbers, pit lanes, sector colours and background, recolour black and crop empty margin. '
        f"Derivative distributed under {source['license']}. Original SHA-256: {source['sha256']}.")
    ET.SubElement(root, 'path', d=d, fill='none', stroke='#000000', **{
        'stroke-width': f'{stroke:.6f}', 'stroke-linecap': 'round', 'stroke-linejoin': 'round'})
    body = ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'
    filename = f'outline-historical-{name}.svg'
    (STAGE / filename).write_bytes(body)
    return filename, width, height, digest(body), digest(d.encode())


def main():
    (STAGE / 'sources').mkdir(parents=True, exist_ok=True)
    (STAGE / 'evidence').mkdir(exist_ok=True)
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    evidence = {}
    for name, url in EVIDENCE.items():
        file = f'evidence/{name}.html'
        body = resource(STAGE / file, url)
        evidence[name] = dict(url=url, file=file, sha256=digest(body), fetchedUtc=now)
    assets = {}
    for name, source in SOURCES.items():
        url, page = UPLOAD + source['remote'], COMMONS + source['title']
        original_file = f'sources/{name}.svg'
        body = resource(STAGE / original_file, url + '?download=1', source['sha256'])
        page_file = f'evidence/{name}-license.html'
        page_body = resource(STAGE / page_file, page)
        file, width, height, sha256, path_hash = derivative(name, source, body)
        assets[name] = dict(file=file, width=width, height=height, sha256=sha256, url=url, page=page,
            sourceSeason=source['sourceSeason'], sourceRevisionUtc=source['revision'],
            sourceFile=original_file, sourceSha256=source['sha256'], sourcePageFile=page_file,
            sourcePageSha256=digest(page_body), sourcePathSelector=source['selector'], sourcePathSha256=path_hash,
            credit=source['creator'] + ' / Wikimedia Commons; outline adaptation by PitPitch',
            license=source['license'], licenseUrl=source['licenseUrl'], derivativeLicense=source['license'],
            modifications='Exact original route path isolated; labels, numbers, pit lanes, alternative routes, sector colours and background omitted; uniform black stroke; empty margins cropped proportionally.',
            style='licensed-historical-black-outline', applicableSeasons=source['applicableSeasons'],
            historicalLayoutEvidence=source['geometry'], geometryEvidence=[evidence[k] for k in source['evidence']],
            fetchedUtc=now)
    records = {}
    for circuit, races in BINDINGS.items():
        for season, round_ in races:
            if circuit == 'catalunya':
                variant = 'catalunya-' + str(2020 if season == 2020 else 2021 if season <= 2022 else 2023)
            elif circuit == 'yas_marina':
                variant = 'yas-' + str(2020 if season == 2020 else 2021)
            else:
                variant = 'singapore-2022'
            entity = f'f1-{season}-{round_}'
            records[f'layout/{season}/{entity}'] = dict(assets[variant], role='layout', entity=entity,
                season=season, round=round_, circuitId=circuit, raceName=NAMES[circuit],
                racePage=f'https://www.formula1.com/en/racing/{season}/{SLUGS[circuit]}', historicalVariant=variant)
    records = normalize_catalog(STAGE, records)
    (STAGE / 'catalog-additions.json').write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n')
    (STAGE / 'geometry-evidence.json').write_text(json.dumps(dict(sources=SOURCES, evidence=evidence), ensure_ascii=False, indent=2) + '\n')
    print(f'Staged {len(records)} bindings using {len(assets)} licensed authentic layout variants in {STAGE}')


if __name__ == '__main__':
    main()
