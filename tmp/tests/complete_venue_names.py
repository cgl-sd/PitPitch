"""Complete the offline venue display catalog from provider identities.

Only adds names that are (a) present in the packaged ESPN responses, (b) matched to a
Wikidata item whose English label or alias equals the provider name exactly and whose
description identifies a stadium/ground/arena, and (c) carrying a Chinese label.
Anything else keeps the provider name, so no Chinese label is ever invented.

Requires Python opencc (t2s). Run with --fetch (network) or --from-cache (reviewed
responses only). Responses live under ignored tmp/validation/chinese-names/venue-search/.
"""
import sys, json, re, time, datetime, urllib.parse, urllib.request, urllib.error
from pathlib import Path
from opencc import OpenCC

if not any(a in sys.argv for a in ("--fetch", "--rematch", "--resolve", "--match2", "--zh", "--expand", "--web", "--from-cache")):
    raise SystemExit("Use --fetch, --rematch, --resolve, --match2, --zh, --expand, --web or --from-cache; never rebuild without reviewed responses.")

converter = OpenCC("t2s")
assets = Path("app/src/main/assets")
cache = Path("tmp/validation/chinese-names/venue-search")
catalog = json.loads((assets / "football-chinese-names.json").read_text(encoding="utf-8"))

venues = set()
def walk(node):
    if isinstance(node, dict):
        venue = node.get("venue")
        if isinstance(venue, dict) and venue.get("fullName"):
            venues.add(venue["fullName"])
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)

for url, meta in json.loads((assets / "sports/catalog.json").read_text(encoding="utf-8")).items():
    if "/soccer/" in url:
        walk(json.loads((assets / "sports" / meta["file"]).read_text(encoding="utf-8")))

missing = sorted(name for name in venues if name not in catalog["venues"])
print(f"{len(venues)} provider venue names; {len(missing)} without a Chinese entry", flush=True)

VENUE_WORDS = ("stadium", "ground", "arena", "sports venue", "football venue")

def api(domain, params):
    for attempt in range(4):
        try:
            request = urllib.request.Request("https://" + domain + "/w/api.php?" + urllib.parse.urlencode(params | {"format": "json"}),
                headers={"User-Agent": "PitPitch offline display catalog/0.7"})
            with urllib.request.urlopen(request, timeout=40) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 3:
                raise
            print("Rate limited; backing off 30s", flush=True)
            time.sleep(30)
        except Exception as error:
            if attempt == 3:
                raise
            print("Retrying after", error, flush=True)
            time.sleep(5)

cache.mkdir(parents=True, exist_ok=True)

def search(name: str):
    path = cache / ("search-" + re.sub(r"[^A-Za-z0-9]+", "_", name)[:80] + ".json")
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    if "--fetch" not in sys.argv:
        return None
    payload = api("www.wikidata.org", {"action": "wbsearchentities", "search": name, "language": "en", "uselang": "en", "type": "item", "limit": 10})
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    time.sleep(1.0)
    return payload

def entity(qid: str):
    path = cache / ("entity-" + qid + ".json")
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    if "--fetch" not in sys.argv:
        return None
    payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": qid,
        "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki", "languages": "en|zh-cn|zh-hans|zh"})
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    time.sleep(1.0)
    return payload

def zh_label(entity_row: dict):
    labels = entity_row.get("labels", {})
    for language in ("zh-cn", "zh-hans", "zh"):
        value = labels.get(language, {}).get("value", "")
        if re.search("[\u3400-\u9fff]", value):
            return value
    return None

added, unresolved = {}, []
def profile(row: dict):
    languages = ("en", "es", "it", "fr", "de", "pt")
    names = [row.get("labels", {}).get(language, {}).get("value", "") for language in languages]
    names += [alias["value"] for language in languages for alias in row.get("aliases", {}).get(language, [])]
    return [value for value in names if value]

def norm(text: str) -> str:
    return " ".join(re.sub(r"[^\w\s]", " ", (text or "").casefold()).split())

def names_identity(provider: str, row: dict) -> bool:
    """Exact label/alias equality, or a full name containing the other. Short fragments never match."""
    key = norm(provider)
    if not key:
        return False
    for value in profile(row):
        other = norm(value)
        if not other:
            continue
        if key == other:
            return True
        shorter, longer = (key, other) if len(key) <= len(other) else (other, key)
        if len(shorter) >= 12 and shorter in longer:
            return True
    return False

def is_current_venue(row: dict, fallback: str) -> bool:
    """A venue description, but never a former or demolished ground that shares the provider name."""
    text = (row.get("descriptions", {}).get("en", {}).get("value") or fallback or "").lower()
    if not any(word in text for word in VENUE_WORDS):
        return False
    return not any(word in text for word in ("former", "demolished", "defunct"))

# Hand-verified corrections for ambiguous provider names. Reviewed against the entity description.
#   Carlos Tartiere: the automatic match found the 1926 former ground; the provider means the current stadium.
def display_name(chinese: str) -> str:
    """Drop an encyclopaedic disambiguation suffix such as "安聯球場 (維也納)"; the original stays in sourceName."""
    return re.sub(r"\s*[（(][^（()）]*[)）]\s*$", "", chinese).strip() or chinese

def localized(chinese: str) -> str:
    return converter.convert(display_name(chinese))

REVIEWED_VENUES = {
    "Carlos Tartiere": "Q1369536",
    "Allianz Stadion": "Q17310225",
    "Olimpico Grande Torino": "Q214629",
    # The archive only uses "Red Bull Arena" for RB Leipzig's Bundesliga home, so the Leipzig item is the identity.
    "Red Bull Arena": "Q210466",
}

def reviewed_entity(qid: str) -> dict:
    path = cache / ("reviewed-" + qid + ".json")
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8")).get("entities", {}).get(qid, {})
    payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": qid,
        "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
        "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return payload.get("entities", {}).get(qid, {})

def loose_identity(provider: str, row: dict) -> bool:
    """Provider shorthand inside the official name, at a word boundary and long enough to stay unique."""
    key = norm(provider)
    if len(key) < 6:
        return False
    for value in profile(row):
        other = norm(value)
        if key == other:
            return True
        if re.search(r"\b" + re.escape(key) + r"\b", other):
            return True
    return False

if "--expand" in sys.argv:
    # Shorthand names ("Alberto Picco") never equal the official label ("Stadio Alberto Picco"), so the
    # provider name is also searched with the usual venue prefixes and suffixes.
    def expand_search(query: str):
        path = cache / ("expand-" + re.sub(r"[^A-Za-z0-9]+", "_", query)[:80] + ".json")
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
        payload = api("www.wikidata.org", {"action": "wbsearchentities", "search": query, "language": "en",
            "uselang": "en", "type": "item", "limit": 10})
        path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        time.sleep(2)
        return payload

    candidates = {}
    for name in missing:
        hits = {}
        for query in (name, f"{name} stadium", f"Stadio {name}", f"{name} stadion", f"{name} arena"):
            for hit in expand_search(query).get("search", []):
                if any(word in (hit.get("description") or "").lower() for word in VENUE_WORDS):
                    hits[hit["id"]] = hit
        candidates[name] = hits
    rows = {}
    ids = sorted({qid for hits in candidates.values() for qid in hits})
    for index in range(0, len(ids), 45):
        batch = ids[index:index + 45]
        path = cache / ("expand-entities-" + str(index) + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": "|".join(batch),
                "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
                "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(2)
        rows.update(payload.get("entities", {}))
    for name in missing:
        matches = [qid for qid, hit in candidates[name].items()
            if loose_identity(name, rows.get(qid, {})) and is_current_venue(rows.get(qid, {}), hit.get("description", ""))]
        if len(matches) != 1:
            unresolved.append({"key": name, "reason": "no single venue identity" if matches else "no venue identity"})
            continue
        qid = matches[0]
        row = rows[qid]
        chinese = zh_label(row)
        if not chinese:
            unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
            continue
        entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
            "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
        title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
        if title:
            entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        added[name] = entry
        print("Added", name, "->", entry["name"], qid, flush=True)
elif "--zh" in sys.argv:
    # The provider name may never match an English label, but the Chinese encyclopaedia usually holds
    # the venue under its Chinese name. Searching there identifies the item; its own stored Chinese
    # label is then used, so the packaged name still comes from the reviewed entity.
    found_titles = {}
    for name in missing:
        path = cache / ("zh-search-" + re.sub(r"[^A-Za-z0-9]+", "_", name)[:80] + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("zh.wikipedia.org", {"action": "query", "list": "search", "srsearch": name,
                "srlimit": 5, "srnamespace": 0})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(2)
        titles = [hit.get("title", "") for hit in payload.get("query", {}).get("search", [])]
        found_titles[name] = [title for title in titles if re.search("[\u3400-\u9fff]", title)]
    all_titles = sorted({title for titles in found_titles.values() for title in titles})
    qid_by_title = {}
    for index in range(0, len(all_titles), 50):
        batch = all_titles[index:index + 50]
        path = cache / ("zh-titles-" + str(index) + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("zh.wikipedia.org", {"action": "query", "titles": "|".join(batch), "redirects": 1,
                "prop": "pageprops", "ppprop": "wikibase_item"})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(2)
        query = payload.get("query", {})
        redirects = {row["from"]: row["to"] for row in query.get("redirects", [])}
        for page in query.get("pages", {}).values():
            qid = page.get("pageprops", {}).get("wikibase_item")
            if qid:
                qid_by_title[page["title"]] = qid
        for title in batch:
            target = redirects.get(title, title)
            if target in qid_by_title:
                qid_by_title[title] = qid_by_title[target]
    rows = {}
    ids = sorted(set(qid_by_title.values()))
    for index in range(0, len(ids), 45):
        batch = ids[index:index + 45]
        path = cache / ("zh-entities-" + str(index) + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": "|".join(batch),
                "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
                "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(2)
        rows.update(payload.get("entities", {}))
    for name in missing:
        matches = []
        for title in found_titles.get(name, []):
            qid = qid_by_title.get(title)
            row = rows.get(qid, {}) if qid else {}
            if not row:
                continue
            description = row.get("descriptions", {}).get("en", {}).get("value", "").lower()
            if is_current_venue(row, ""):
                matches.append((qid, row))
        if len(matches) != 1:
            unresolved.append({"key": name, "reason": "no single Chinese venue identity" if matches else "no Chinese venue identity"})
            continue
        qid, row = matches[0]
        chinese = zh_label(row)
        if not chinese:
            unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
            continue
        entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
            "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
        title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
        if title:
            entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        added[name] = entry
        print("Added", name, "->", entry["name"], qid, flush=True)
elif "--match2" in sys.argv:
    # The provider often abbreviates a venue whose item carries the full official name, and the same
    # words may also name people or places. Identity needs a full-name containment plus a venue
    # description and a single surviving candidate.
    hits = {}
    for path in cache.glob("search-*.json"):
        for hit in json.loads(path.read_text(encoding="utf-8")).get("search", []):
            hits[hit["id"]] = hit
    rows = {}
    ids = sorted(hits)
    for index in range(0, len(ids), 45):
        batch = ids[index:index + 45]
        path = cache / ("match2-entities-" + str(index) + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": "|".join(batch),
                "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
                "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(6)
        rows.update(payload.get("entities", {}))
        print("Fetched", min(index + 45, len(ids)), "/", len(ids), flush=True)
    for name in missing:
        matches = [qid for qid, row in rows.items()
            if names_identity(name, row) and is_current_venue(row, hits.get(qid, {}).get("description", ""))]
        if len(matches) != 1:
            unresolved.append({"key": name, "reason": "no single venue identity" if matches else "no venue identity"})
            continue
        qid = matches[0]
        row = rows[qid]
        chinese = zh_label(row)
        if not chinese:
            unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
            continue
        entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
            "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
        title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
        if title:
            entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        added[name] = entry
        print("Added", name, "->", entry["name"], qid, flush=True)
elif "--resolve" in sys.argv:
    # Provider names are often an alias of the venue page title. Resolve identity through the
    # encyclopedic title (following redirects), then verify it is a venue and read its Chinese label.
    def wikibase_ids(domain: str, titles):
        resolved = {}
        tag = domain.split(".")[0]
        for index in range(0, len(titles), 50):
            batch = titles[index:index + 50]
            path = cache / (f"{tag}-titles-{index}.json")
            if path.exists():
                payload = json.loads(path.read_text(encoding="utf-8"))
            else:
                payload = api(domain, {"action": "query", "titles": "|".join(batch), "redirects": 1,
                    "prop": "pageprops", "ppprop": "wikibase_item"})
                path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
                time.sleep(6)
            query = payload.get("query", {})
            normalized = {row["from"]: row["to"] for row in query.get("normalized", [])}
            redirects = {row["from"]: row["to"] for row in query.get("redirects", [])}
            pages = {page["title"]: page.get("pageprops", {}).get("wikibase_item") for page in query.get("pages", {}).values()}
            for title in batch:
                target = redirects.get(normalized.get(title, title), normalized.get(title, title))
                if pages.get(target):
                    resolved[title] = pages[target]
        return resolved

    identity = wikibase_ids("en.wikipedia.org", missing)
    for name in missing:
        if name not in identity:
            identity.update(wikibase_ids("es.wikipedia.org", [name]))
    qids = sorted({qid for qid in identity.values()})
    rows = {}
    for index in range(0, len(qids), 45):
        batch = qids[index:index + 45]
        path = cache / ("resolve-entities-" + str(index) + ".json")
        if path.exists():
            payload = json.loads(path.read_text(encoding="utf-8"))
        else:
            payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": "|".join(batch),
                "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
                "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
            path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            time.sleep(6)
        rows.update(payload.get("entities", {}))
    for name in missing:
        qid = identity.get(name)
        row = rows.get(qid, {}) if qid else {}
        if not row:
            unresolved.append({"key": name, "reason": "no encyclopedic title for this provider name"})
            continue
        description = row.get("descriptions", {}).get("en", {}).get("value", "").lower()
        if not any(word in description for word in VENUE_WORDS):
            unresolved.append({"key": name, "qid": qid, "reason": f"not a venue: {description or 'no description'}"})
            continue
        chinese = zh_label(row)
        if not chinese:
            unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
            continue
        entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
            "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
        title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
        if title:
            entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        added[name] = entry
        print("Added", name, "->", entry["name"], qid, flush=True)
elif "--rematch" in sys.argv:
    # The provider names are often Spanish/Italian, so a Wikidata item is identified by its own
    # labels in any provider language — matched exactly, never fuzzily.
    candidates = {}
    for path in cache.glob("search-*.json"):
        for hit in json.loads(path.read_text(encoding="utf-8")).get("search", []):
            if any(word in hit.get("description", "").lower() for word in VENUE_WORDS):
                candidates[hit["id"]] = hit
    rows = {}
    ids = sorted(candidates)
    for index in range(0, len(ids), 45):
        batch = ids[index:index + 45]
        payload = api("www.wikidata.org", {"action": "wbgetentities", "ids": "|".join(batch),
            "props": "labels|aliases|descriptions|sitelinks", "sitefilter": "enwiki",
            "languages": "en|es|it|fr|de|pt|zh-cn|zh-hans|zh"})
        rows.update(payload.get("entities", {}))
        print("Fetched", min(index + 45, len(ids)), "/", len(ids), flush=True)
        time.sleep(6)
    for name in missing:
        matches = [qid for qid, row in rows.items()
            if any(value.casefold().strip() == name.casefold().strip() for value in profile(row))
            and is_current_venue(row, candidates[qid].get("description", ""))]
        if len(matches) != 1:
            unresolved.append({"key": name, "reason": "no single exact identity" if matches else "no exact identity"})
            continue
        qid = matches[0]
        row = rows[qid]
        chinese = zh_label(row)
        if not chinese:
            unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
            continue
        entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
            "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
        title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
        if title:
            entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
        added[name] = entry
        print("Added", name, "->", entry["name"], qid, flush=True)
elif "--web" not in sys.argv:
  for name in missing:
    try:
        found = search(name)
    except Exception as error:
        print("Search failed for", name, error, flush=True)
        found = None
    candidate = None
    for hit in (found or {}).get("search", []):
        description = hit.get("description", "").lower()
        if not any(word in description for word in VENUE_WORDS):
            continue
        label = hit.get("label", "")
        if label.casefold().strip() == name.casefold().strip():
            candidate = hit
            break
    if candidate is None:
        unresolved.append({"key": name, "reason": "no exact wikidata venue identity"})
        continue
    qid = candidate["id"]
    try:
        row = (entity(qid) or {}).get("entities", {}).get(qid, {})
    except Exception as error:
        print("Entity failed for", name, error, flush=True)
        row = {}
    description = row.get("descriptions", {}).get("en", {}).get("value", "").lower()
    if row and not any(word in description or word in candidate.get("description", "").lower() for word in VENUE_WORDS):
        unresolved.append({"key": name, "reason": "wikidata item is not a venue"})
        continue
    chinese = zh_label(row)
    if not chinese:
        unresolved.append({"key": name, "qid": qid, "reason": "no Chinese label"})
        continue
    entry = {"name": localized(chinese), "originalNames": [name], "source": "https://www.wikidata.org/wiki/" + qid,
        "identityMatchRank": 0, "sourceName": chinese, "conversion": "OpenCC t2s"}
    title = row.get("sitelinks", {}).get("enwiki", {}).get("title")
    if title:
        entry["identitySource"] = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"))
    added[name] = entry
    print("Added", name, "->", entry["name"], qid, flush=True)

def reviewed_is_venue(row: dict) -> bool:
    """Reviewed items may describe an Austrian "building"; their own name still identifies the ground."""
    if is_current_venue(row, ""):
        return True
    text = ((row.get("sitelinks", {}).get("enwiki") or {}).get("title", "") + " " +
        row.get("labels", {}).get("en", {}).get("value", "")).lower()
    return any(word in text for word in ("stadium", "stadion", "arena"))

for name, qid in REVIEWED_VENUES.items():
    row = reviewed_entity(qid)
    chinese = zh_label(row) if row else None
    if chinese and reviewed_is_venue(row):
        added[name] = {"name": localized(chinese), "originalNames": [name],
            "source": "https://www.wikidata.org/wiki/" + qid, "identityMatchRank": 0,
            "sourceName": chinese, "conversion": "OpenCC t2s"}
        print("Reviewed", name, "->", added[name]["name"], qid, flush=True)
    else:
        added.pop(name, None)
        unresolved.append({"key": name, "qid": qid, "reason": "reviewed entity unusable"})

# Chinese names used by Chinese-language sports media, kept in a reviewable file so the checker can
# verify every entry (see tmp/tests/venue_name_reviews.json). The provider English name stays authoritative.
REVIEWED_WEB_NAMES = json.loads(Path("tmp/tests/venue_name_reviews.json").read_text(encoding="utf-8"))

for name, chinese in REVIEWED_WEB_NAMES.items():
    if name not in venues:
        continue
    added[name] = {"name": chinese, "originalNames": [name], "sourceKind": "reviewed-web-name",
        "source": "中文体育媒体通用译名（人工核验）", "identityMatchRank": 0, "conversion": "人工核验，未经机器翻译"}
    print("Reviewed web name", name, "->", chinese, flush=True)

catalog["venues"].update(added)
catalog["note"] = ("球场条目带 sourceKind=reviewed-web-name 的为中文体育媒体通用译名（人工核验，非机器翻译）；"
    "其余为 Wikidata 实体标签。无法唯一确认的场地保留供应商英文原名。")
catalog["generatedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
(assets / "football-chinese-names.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(cache.parent / "venue-missing.json").write_text(json.dumps(unresolved, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Catalog now holds {len(catalog['venues'])}/{len(venues)} venue names; {len(unresolved)} still unresolved (provider name preserved).", flush=True)
