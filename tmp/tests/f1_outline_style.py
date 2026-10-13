#!/usr/bin/env python3
"""Offline-only style normalization of licensed authentic F1 route geometry.

Requires svgpathtools and shapely. Produces filled even-odd contours, not masks,
white paint or a runtime-generated image. Original geometry/provenance remains
in the supplement source records and SVG metadata.
"""
import hashlib
import math
from pathlib import Path
import xml.etree.ElementTree as ET

from shapely.geometry import MultiLineString
from svgpathtools import Document

SVG = 'http://www.w3.org/2000/svg'
ET.register_namespace('', SVG)
STYLE = 'licensed-historical-double-outline'
MODIFICATION = ('Authentic source transforms applied; proportionally sampled '
                'route with subpixel curve tolerance; uniform black double '
                'contour with transparent center and background; no labels.')


def points_for(path, tolerance):
    points = []

    def chord_error(point, start, end):
        chord = end - start
        if abs(chord) == 0:
            return abs(point - start)
        ratio = max(0, min(1, ((point - start) * chord.conjugate()).real / abs(chord) ** 2))
        return abs(point - (start + ratio * chord))

    def sample(segment, start, end, depth=0):
        first, last = segment.point(start), segment.point(end)
        span = end - start
        error = max(chord_error(segment.point(start + span * fraction), first, last)
                    for fraction in (.25, .5, .75))
        if error <= tolerance:
            points.append((last.real, last.imag))
            return
        if depth >= 18:
            raise ValueError('Curve cannot be represented within reviewed tolerance')
        middle = (start + end) / 2
        sample(segment, start, middle, depth + 1)
        sample(segment, middle, end, depth + 1)

    first = path[0].start
    points.append((first.real, first.imag))
    for segment in path:
        sample(segment, 0, 1)
    if abs(path[0].start - path[-1].end) <= tolerance:
        points[-1] = points[0]
    return points


def normalize(body):
    original = ET.fromstring(body)
    paths = Document.from_svg_string(body.decode('utf-8')).paths()
    parts = [part for path in paths for part in path.continuous_subpaths()]
    if not parts:
        raise ValueError('Source has no authentic route')
    # A source road ribbon contains an outer and inner contour. Its outer
    # authentic road boundary is the representative route, not an alternate
    # racing configuration or an invented hand-traced path.
    def area(part):
        x0, x1, y0, y1 = part.bbox()
        points = points_for(part, max(x1 - x0, y1 - y0) * .00005)
        return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(points, points[1:]))) / 2
    routes = [max(parts, key=area)] if all(part.isclosed() for part in parts) else parts
    boxes = [route.bbox() for route in routes]
    x0, x1 = min(box[0] for box in boxes), max(box[1] for box in boxes)
    y0, y1 = min(box[2] for box in boxes), max(box[3] for box in boxes)
    span = max(x1 - x0, y1 - y0)
    if span <= 0:
        raise ValueError('Degenerate race route')
    line = MultiLineString([points_for(route, span * .00005) for route in routes])
    closure = line.buffer(span * .0001, resolution=12)
    loops = [closure] if closure.geom_type == 'Polygon' else list(closure.geoms)
    if not any(polygon.interiors for polygon in loops):
        raise ValueError('Authentic source legs do not form a complete race loop')
    outer = line.buffer(span * .032, resolution=12)
    inner = line.buffer(span * .007, resolution=12)
    contour = outer.difference(inner)
    if not contour.is_valid or contour.is_empty:
        raise ValueError('Invalid double contour')
    x0, y0, x1, y1 = contour.bounds
    padding = span * .025
    width, height = x1 - x0 + padding * 2, y1 - y0 + padding * 2
    root = ET.Element('{%s}svg' % SVG, {
        'viewBox': f'{x0-padding:.6f} {y0-padding:.6f} {width:.6f} {height:.6f}',
        'width': '1024', 'height': str(math.ceil(1024 * height / width)),
        'preserveAspectRatio': 'xMidYMid meet'})
    for child in original:
        if child.tag.rsplit('}', 1)[-1] in ('title', 'desc', 'metadata'):
            root.append(child)
    ET.SubElement(root, '{%s}desc' % SVG).text = MODIFICATION
    rings = []
    polygons = [contour] if contour.geom_type == 'Polygon' else list(contour.geoms)
    for polygon in polygons:
        for ring in [polygon.exterior, *polygon.interiors]:
            coordinates = list(ring.coords)
            rings.append('M' + ' L'.join(f'{x:.5f},{y:.5f}' for x, y in coordinates) + ' Z')
    ET.SubElement(root, '{%s}path' % SVG, {
        'd': ' '.join(rings), 'fill': '#15151E', 'fill-rule': 'evenodd'})
    result = ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n'
    return result, 1024, int(root.get('height'))


def normalize_catalog(directory, records):
    directory = Path(directory)
    files = {}
    for record in records.values():
        filename = record['file']
        if filename not in files:
            source = (directory / filename).read_bytes()
            source_hash = hashlib.sha256(source).hexdigest()
            if source_hash != record['sha256']:
                raise ValueError('Unreviewed staged derivative change: ' + filename)
            body, width, height = normalize(source)
            output = filename.removesuffix('.svg') + '-double.svg'
            (directory / output).write_bytes(body)
            files[filename] = (output, width, height, hashlib.sha256(body).hexdigest(), source_hash)
        output, width, height, sha, source_hash = files[filename]
        record.update(file=output, width=width, height=height, sha256=sha,
                      style=STYLE, supplemental=True,
                      intermediateSha256=source_hash,
                      styleModifications=MODIFICATION)
    return records
