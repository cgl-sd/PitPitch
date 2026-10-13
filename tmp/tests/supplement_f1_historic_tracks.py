#!/usr/bin/env python3
"""Stage eleven historical F1 layout bindings from hash-pinned licensed SVGs.

Uses only original route paths: no tracing, point interpolation or invented path.
Python standard library + curl; caches originals/evidence by default. --refresh
refetches evidence and SVGs, and stops if a source SVG changed for manual review.
Never edits the production catalog. Source pages, upstream credit, licenses,
original bytes and hashes remain alongside transparent display-ready derivatives.
"""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / 'tmp/validation/f1-track-completion/historical-tracks'
ASSETS = ROOT / 'app/src/main/assets/f1-media'
SVG = 'http://www.w3.org/2000/svg'
ET.register_namespace('', SVG)
BINDINGS = {
    'layout/2020/f1-2020-9': 'mugello',
    'layout/2020/f1-2020-10': 'sochi',
    'layout/2020/f1-2020-11': 'nurburgring',
    'layout/2020/f1-2020-12': 'portimao',
    'layout/2020/f1-2020-14': 'istanbul',
    'layout/2020/f1-2020-16': 'sakhir-outer',
    'layout/2021/f1-2021-3': 'portimao',
    'layout/2021/f1-2021-7': 'ricard',
    'layout/2021/f1-2021-15': 'sochi',
    'layout/2021/f1-2021-16': 'istanbul',
    'layout/2022/f1-2022-12': 'ricard',
}
SOURCES = json.loads(r'''{
  "mugello": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/1/1a/Mugello_Racing_Circuit_track_map.svg",
    "page": "https://commons.wikimedia.org/wiki/File:Mugello_Racing_Circuit_track_map.svg",
    "credit": "Will Pittenger; names corrected by Restu20",
    "license": "CC-BY-SA-3.0",
    "licenseUrl": "https://creativecommons.org/licenses/by-sa/3.0/",
    "sourceSeason": 2010,
    "sourceSha256": "889149921093db8621397a12fd42cc152fd6c5e8282f3e8d3b854ac134c6ec8f",
    "sourceFetchedUtc": "2026-10-12T12:54:56.489768+00:00",
    "pathIds": [
      "path2419"
    ],
    "historicalApplicability": "Full 5.245 km Mugello race loop, not the pit/paddock shortcut. Official 2020 Tuscany detailed map is the historical comparator.",
    "historicalEvidenceUrls": []
  },
  "sochi": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/5/57/F1_circuits_2014-2018_-_Sochi_Autodrom_%28version_2%29.svg",
    "page": "https://commons.wikimedia.org/wiki/File:F1_circuits_2014-2018_-_Sochi_Autodrom_(version_2).svg",
    "credit": "Firkin / Openclipart",
    "license": "CC0-1.0",
    "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
    "sourceSeason": 2017,
    "sourceSha256": "33ad3311edb988733ac08e450cec0a9ce60bea47b5daeaf281e8fcafd0a23b1f",
    "sourceFetchedUtc": "2026-10-12T12:54:56.490007+00:00",
    "pathIds": [
      "path4330"
    ],
    "historicalApplicability": "Full 5.848 km GP loop from the final 2014-2018 F1 circuit drawing; matches 2020/2021 official Russia maps including the 18-corner shape. Not the earlier 2013 19-corner project drawing nor the short Sirius layout introduced after November 2023.",
    "historicalEvidenceUrls": [
      "https://en.wikipedia.org/wiki/Sochi_Autodrom"
    ],
    "sourceFile": "sochi-gp-original.svg",
    "sourcePageFile": "sochi-gp-source-page.html"
  },
  "nurburgring": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/7/76/N%C3%BCrburgring_-_Grand-Prix-Strecke.svg",
    "page": "https://commons.wikimedia.org/wiki/File:N%C3%BCrburgring_-_Grand-Prix-Strecke.svg",
    "credit": "Will Pittenger; GP geometry correction by Sentoan; typography by DooFi",
    "license": "Public domain",
    "licenseUrl": "https://commons.wikimedia.org/wiki/Template:PD-self",
    "sourceSeason": 2013,
    "sourceSha256": "6bc686225537b8386f3f9993fe234c82e502b64739609f4cc157b54c507a622c",
    "sourceFetchedUtc": "2026-10-12T12:54:56.490518+00:00",
    "pathIds": [
      "path3128"
    ],
    "historicalApplicability": "Visible GP-Strecke path3128 from the July 2013 correction (file history: Real and correct GP layout), including Mercedes Arena and tight NGK chicane. Hidden earlier path3197 is explicitly excluded. 2020 Eifel GP uses this 5.148 km GP route, not Nordschleife.",
    "historicalEvidenceUrls": []
  },
  "portimao": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/0/0a/Aut%C3%B3dromo_do_Algarve_F1_Sectors.svg",
    "page": "https://commons.wikimedia.org/wiki/File:Aut%C3%B3dromo_do_Algarve_F1_Sectors.svg",
    "credit": "GabrielStella, Gpmat and Sentoan",
    "license": "CC-BY-SA-4.0",
    "licenseUrl": "https://creativecommons.org/licenses/by-sa/4.0/",
    "sourceSeason": 2021,
    "sourceSha256": "e58fff9484bd3194a6a93c7afd41db640d5a63ad49d5b86ab4fc3399df457ef2",
    "sourceFetchedUtc": "2026-10-12T12:54:56.490824+00:00",
    "pathIds": [],
    "historicalApplicability": "Source explicitly describes F1 layout, 15 turns. The complete race loop is the union of the 2011-character black path and separate 236-character black F1 Turn1 leg, preserving both original transforms. They overlap on the start/finish straight and approach to Turn2. Omit gray 223-character alternative Turn1 hairpin and other bypasses; matches 4.653 km official 2020/2021 map, not MotoGP alternative.",
    "historicalEvidenceUrls": [
      "https://commons.wikimedia.org/wiki/File:Aut%C3%B3dromo_do_Algarve_alt.svg",
      "https://commons.wikimedia.org/wiki/File:Aut%C3%B3dromo_de_Algarve.svg"
    ]
  },
  "ricard": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/f/f2/Le_Castellet_circuit_map_Formula_One_2019_and_2021_with_corner_names_English_19_07_2021.svg",
    "page": "https://commons.wikimedia.org/wiki/File:Le_Castellet_circuit_map_Formula_One_2019_and_2021_with_corner_names_English_19_07_2021.svg",
    "credit": "HumanBodyPiloter5",
    "license": "CC0-1.0",
    "licenseUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
    "sourceSeason": 2021,
    "sourceSha256": "df9a7f80b307a93e9a7a4cdfe567882aa668a899012b6ae84f5026bd87f31035",
    "sourceFetchedUtc": "2026-10-12T12:54:56.491249+00:00",
    "pathIds": [
      "path828"
    ],
    "historicalApplicability": "File explicitly identifies Formula One 2019/2021 route. Select the black filled race-route ribbon path828, retaining Mistral/Chicane Nord (turns 8/9) and V2 Verrerie S, omit blue pit lane. RacingCircuits identifies F1 as 1C with V2 Verrerie S (1C-V2); official 2021 and 2022 pages/maps use 5.842 km chicane route.",
    "historicalEvidenceUrls": [
      "https://www.racingcircuits.info/europe/france/paul-ricard.html"
    ]
  },
  "istanbul": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/5/5d/Istanbul_park.svg",
    "page": "https://commons.wikimedia.org/wiki/File:Istanbul_park.svg",
    "credit": "Will Pittenger",
    "license": "CC-BY-SA-3.0",
    "licenseUrl": "https://creativecommons.org/licenses/by-sa/3.0/",
    "sourceSeason": 2008,
    "sourceSha256": "3c350c99aeba811d389a0673f688b5295d1eb5f155b3b743891cae8c8b656f97",
    "sourceFetchedUtc": "2026-10-12T12:54:56.491635+00:00",
    "pathIds": [
      "path3833"
    ],
    "historicalApplicability": "Full 5.338 km 14-turn Istanbul Park F1 loop; select black path3833 and omit gray possible shortcut/pit route and sector markings. Official 2020/2021 Turkey detailed maps are historical comparators.",
    "historicalEvidenceUrls": []
  },
  "sakhir-outer": {
    "url": "https://upload.wikimedia.org/wikipedia/commons/6/61/Bahrain_International_Circuit--Outer_Circuit.svg",
    "page": "https://commons.wikimedia.org/wiki/File:Bahrain_International_Circuit--Outer_Circuit.svg",
    "credit": "Will Pittenger; metadata cleanup by Cherkash",
    "license": "CC-BY-SA-3.0",
    "licenseUrl": "https://creativecommons.org/licenses/by-sa/3.0/",
    "sourceSeason": 2017,
    "sourceSha256": "4c7e08bafdb93b408559a79ce44fe1f650e97c6d0e317b0a20cddbe88511904a",
    "sourceFetchedUtc": "2026-10-12T12:54:56.491937+00:00",
    "pathIds": [
      "path4777"
    ],
    "historicalApplicability": "Select path4777, the only 15-unit black-stroked race-loop use in the source, not gray path4003 (regular GP infield). Official F1 announcement explicitly assigns the 11-turn 3.543 km Outer Track to 2020 round16 Sakhir, contrasting round15 regular 5.412 km Bahrain GP.",
    "historicalEvidenceUrls": [
      "https://www.formula1.com/en/latest/article/f1-to-use-bahrains-high-speed-outer-track-for-sakhir-grand-prix.6niLbb2btc0IrscR1BMpx8"
    ]
  }
}''')
CHANGES = ('Isolate original race-route geometry; omit annotations, pit lanes, '
           'alternative routes, sectors, flags, backgrounds and white halo. '
           'Black paint, rounded stroke and proportional padded SVG viewport; '
           'original path data and transforms are unchanged.')


def sha(body):
    return hashlib.sha256(body).hexdigest()


def fetch(url, destination, refresh=False):
    if refresh or not destination.exists():
        body = subprocess.check_output([
            'curl', '--fail', '--silent', '--show-error', '--location',
            '--max-time', '60', url])
        destination.write_bytes(body)
    return destination.read_bytes()


def select_paths(root, circuit, source):
    paths = [n for n in root.iter() if n.tag == '{%s}path' % SVG]
    if source['pathIds']:
        selected = [n for n in paths if n.get('id') in source['pathIds']]
        assert len(selected) == len(source['pathIds']), circuit
    else:
        # The Algarve source splits its GP route across two black paths:
        # the long loop and the separate F1 Turn1 leg. They intentionally
        # overlap on the start/finish straight and approach to Turn2.
        # The neighboring gray 223-character path is the alternate hairpin.
        assert circuit == 'portimao'
        selected = [n for n in paths if n.get('stroke') == '#000'
                    and len(n.get('d', '')) in (2011, 236)]
        assert len(selected) == 2, circuit
    return selected


def outline(circuit, source, original):
    root = ET.fromstring(original)
    parents = {child: node for node in root.iter() for child in node}
    if 'viewBox' in root.attrib:
        x, y, width, height = map(float, root.attrib['viewBox'].split())
    else:
        x = y = 0
        width, height = float(root.attrib['width']), float(root.attrib['height'])
    padding = max(width, height) * 0.025
    w, h = width + 2 * padding, height + 2 * padding
    result = ET.Element('{%s}svg' % SVG, {
        'viewBox': f'{x-padding:g} {y-padding:g} {w:g} {h:g}',
        'width': str(math.ceil(w)), 'height': str(math.ceil(h)),
        'preserveAspectRatio': 'xMidYMid meet', 'version': '1.1'})
    metadata = {k: source[k] for k in ('url', 'page', 'credit', 'license',
                'licenseUrl', 'sourceSha256', 'historicalApplicability')}
    metadata['changes'] = CHANGES
    ET.SubElement(result, '{%s}metadata' % SVG).text = json.dumps(
        metadata, ensure_ascii=False, sort_keys=True)
    for path in select_paths(root, circuit, source):
        ancestors, parent = [], parents.get(path)
        while parent is not None and parent is not root:
            ancestors.append(parent)
            parent = parents.get(parent)
        target = result
        for ancestor in reversed(ancestors):
            if ancestor.get('transform'):
                target = ET.SubElement(target, '{%s}g' % SVG,
                                       {'transform': ancestor.get('transform')})
        attributes = {'d': path.attrib['d']}
        if path.get('transform'):
            attributes['transform'] = path.get('transform')
        if circuit == 'ricard':
            # This is the authentic filled race-road ribbon (two contours),
            # not the blue pit lane. Keep its exact boundaries and winding.
            attributes.update(fill='#000000', stroke='none')
        else:
            attributes.update(fill='none', stroke='#000000',
                              **{'stroke-width': f'{max(width,height)*0.010:g}',
                                 'stroke-linecap': 'round',
                                 'stroke-linejoin': 'round'})
        ET.SubElement(target, '{%s}path' % SVG, attributes)
    return ET.tostring(result, encoding='utf-8', xml_declaration=True) + b'\n', math.ceil(w), math.ceil(h)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh', action='store_true')
    args = parser.parse_args()
    directory = STAGE / 'sources'
    directory.mkdir(parents=True, exist_ok=True)
    references = json.loads((ASSETS / 'layout-references.json').read_text())
    archive = ROOT / 'app/src/main/assets/sports'
    archive_catalog = json.loads((archive / 'catalog.json').read_text())
    races = {}
    for year in sorted({int(key.split('/')[1]) for key in BINDINGS}):
        entry = archive_catalog[f'https://api.jolpi.ca/ergast/f1/{year}.json?limit=100']
        document = json.loads((archive / entry['file']).read_text())
        for race in document['MRData']['RaceTable']['Races']:
            key = f'layout/{year}/f1-{year}-{race["round"]}'
            races[key] = dict(season=year, round=int(race['round']),
                              circuitId=race['Circuit']['circuitId'], name=race['raceName'])
    catalog, provenance, derivatives = {}, {}, {}
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for circuit, source in SOURCES.items():
        filename = source.get('sourceFile', f'{circuit}-original.svg')
        original = fetch(source['url'], directory / filename, args.refresh)
        assert sha(original) == source['sourceSha256'], ('Source changed', circuit)
        page_filename = source.get('sourcePageFile', f'{circuit}-source-page.html')
        page = fetch(source['page'], directory / page_filename, args.refresh)
        evidence = []
        for index, url in enumerate(source['historicalEvidenceUrls']):
            file = f'{circuit}-historical-evidence-{index}.html'
            body = fetch(url, directory / file, args.refresh)
            evidence.append(dict(url=url, file=f'sources/{file}', sha256=sha(body)))
        body, width, height = outline(circuit, source, original)
        file = f'outline-historic-{circuit}.svg'
        (STAGE / file).write_bytes(body)
        derivatives[circuit] = dict(file=file, width=width, height=height, sha256=sha(body))
        provenance[circuit] = dict(source, originalFile=f'sources/{filename}',
            sourcePageFile=f'sources/{page_filename}',
            sourcePageSha256=sha(page), sourceEvidence=evidence, changes=CHANGES,
            derivative=derivatives[circuit])
    for key, circuit in BINDINGS.items():
        race = races[key]
        reference = references[key]
        year, entity = key.split('/')[1:]
        season, round_ = int(year), int(entity.rsplit('-', 1)[1])
        expected_circuit = 'bahrain' if circuit == 'sakhir-outer' else circuit
        assert (race['season'], race['round'], race['circuitId']) == (season, round_, expected_circuit)
        assert (reference['season'], reference['round'], reference['entity'], reference['circuitId']) == (season, round_, entity, expected_circuit)
        ref_file = directory / reference['file']
        ref_body = fetch(reference['url'], ref_file, args.refresh)
        assert sha(ref_body) == reference['sha256'], ('Official reference changed', key)
        page_file = directory / f'{entity}-official-page.html'
        page_body = fetch(reference['page'], page_file, args.refresh)
        source = SOURCES[circuit]
        catalog[key] = dict(role='layout', entity=entity, season=season, round=round_,
            circuitId=expected_circuit, raceName=race['name'],
            **derivatives[circuit], url=source['url'], page=reference['page'],
            credit=source['credit'], license=source['license'],
            licenseUrl=source['licenseUrl'], sourceSeason=source['sourceSeason'],
            sourceSha256=source['sourceSha256'], sourceFetchedUtc=source['sourceFetchedUtc'],
            sourcePage=source['page'], sourcePageSha256=provenance[circuit]['sourcePageSha256'],
            sourceFile=provenance[circuit]['originalFile'],
            historicalApplicability=source['historicalApplicability'],
            historicalEvidenceUrls=source['historicalEvidenceUrls'] + [reference['page']],
            historicalEvidence=provenance[circuit]['sourceEvidence'],
            referenceLayoutUrl=reference['url'], referenceLayoutSha256=reference['sha256'],
            referenceLayoutFile=f'sources/{reference["file"]}',
            officialPageSha256=sha(page_body), officialPageFile=f'sources/{page_file.name}',
            changes=CHANGES, style='licensed-authentic-black-outline', fetchedUtc=now)
    from f1_outline_style import normalize_catalog
    catalog = normalize_catalog(STAGE, catalog)
    (STAGE / 'catalog-additions.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    (STAGE / 'provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n')
    print(f'Staged {len(catalog)} historical bindings from {len(derivatives)} authentic SVG routes')


if __name__ == '__main__':
    main()
