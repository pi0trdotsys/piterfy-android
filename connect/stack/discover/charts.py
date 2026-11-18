"""Listy przebojów Piterfy: „Top 100 Polska” i podobne.

Moduł jest czysty (bez sieci i bez Navidrome), żeby dało się go testować. Dostaje wpisy z listy przebojów (kolejność,
tytuł, wykonawca, ISRC) i utwory z natywnego API Navidrome, a zwraca te utwory z biblioteki, które są na liście,
w kolejności listy. Pobranie listy z Deezera i zapis playlisty robi `discover.refresh_charts`.

Playlista Navidrome może zawierać tylko utwory z biblioteki, więc na liście są wyłącznie te, które masz. Dopasowanie:
najpierw po ISRC (to samo nagranie bez względu na pisownię), potem po tytule i wykonawcy, z kontrolą długości, żeby
„Slowed”, „Sped Up” i inne wersje nie podszywały się pod oryginał.
"""

import re
from dataclasses import dataclass

import mixes

COMMENT_PREFIX = "Piterfy Chart"  # po tym początku komentarza aplikacja rozpoznaje playlisty-listy przebojów
DURATION_SLACK = 12               # tyle sekund może różnić się długość tej samej wersji (przy dopasowaniu po tytule)


@dataclass(frozen=True)
class Chart:
    name: str            # nazwa konfiguracji (klucz w stanie); playlista nazywa się wg głębokości, np. „Top 200 Polska”
    source: str          # skąd lista: „deezer”
    ref: str             # identyfikator w źródle (dla Deezera: id playlisty)
    title: str           # opis w komentarzu playlisty
    extension: str = ""  # kraj Last.fm (geo.getTopTracks), z którego dobieramy dalsze miejsca, gdy w bibliotece jest mało
    template: str = "Top {depth} Polska"  # nazwa playlisty zależna od głębokości listy


CHARTS = (
    Chart("Top 100 Polska", "deezer", "1266972311", "Deezer Top Poland + Last.fm Polska", extension="poland"),
)
STEPS = (100, 200, 300, 500, 1000)  # kolejne głębokości listy, gdy w bibliotece nie ma jeszcze wystarczająco utworów


def display_name(chart, depth):
    """Nazwa playlisty dla danej głębokości listy: „Top 100 Polska”, „Top 200 Polska”…"""
    return chart.template.format(depth=depth)


def dedupe_key(artist, title):
    keys = sorted(artist_keys(artist))
    return f"{clean_title(title)}|{keys[0] if keys else ''}"


def extend(entries, extra, depth):
    """Dokłada do `entries` (z numerami miejsc) wpisy z `extra` (kolejność źródła dodatkowego), pomijając te, które już
    są, aż lista ma `depth` pozycji. Nowe miejsca idą dalej po numerach pierwszej listy."""
    result = list(entries)
    seen = {dedupe_key(e["artist"], e["title"]) for e in result}
    rank = max((e["rank"] for e in result), default=0)
    for item in extra:
        if len(result) >= depth:
            break
        key = dedupe_key(item["artist"], item["title"])
        if key in seen:
            continue
        seen.add(key)
        rank += 1
        result.append({**item, "rank": rank})
    return result


def by_names(names):
    """Listy o podanych nazwach (bez względu na wielkość liter); `names` puste = wszystkie."""
    wanted = {mixes.normalize(n) for n in names if n and n.strip()}
    return [c for c in CHARTS if not wanted or mixes.normalize(c.name) in wanted]


# ── czyszczenie tytułów i wykonawców ──

_LETTERS = str.maketrans({"ł": "l", "Ł": "L", "ø": "o", "Ø": "O", "đ": "d", "Đ": "D", "ß": "ss", "æ": "ae", "Æ": "AE",
                          "œ": "oe", "Œ": "OE", "ð": "d", "þ": "th", "ı": "i"})


def fold(text):
    """Jak mixes.normalize, ale z literami, których NFKD nie rozkłada (ł, ø, ß…): „Miłość” i „Milosc” to ten sam klucz."""
    return mixes.normalize(str(text or "").translate(_LETTERS))


_IGNORABLE = (
    "radio edit", "radio version", "original mix", "original version", "album version", "single version", "explicit",
    "clean", "deluxe", "bonus track", "mono", "stereo", "main version", "official audio", "official video",
)
_IGNORABLE_RE = re.compile(r"^(?:" + "|".join(re.escape(p) for p in _IGNORABLE) + r"|remaster(?:ed)?(?: \d{4})?|\d{4} remaster(?:ed)?)$")
_FEAT_BRACKET = re.compile(r"\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|with|prod\.?)\b[^\)\]]*[\)\]]", re.I)
_BRACKET = re.compile(r"\s*[\(\[]([^\)\]]*)[\)\]]")
_DASH_TAIL = re.compile(r"\s+-\s+([^-]+)$")
_FEAT_TAIL = re.compile(r"\s+(?:feat\.?|ft\.?|featuring)\s+.*$", re.I)


def clean_title(title):
    """Klucz tytułu: bez „feat.”, „radio edit”, „remastered” itp., ale z „remix”, „slowed”, „live” (to inne nagrania)."""
    text = _FEAT_BRACKET.sub("", str(title or ""))
    text = _FEAT_TAIL.sub("", text)

    def drop_ignorable(match):
        return "" if _IGNORABLE_RE.match(fold(match.group(1))) else match.group(0)

    text = _BRACKET.sub(drop_ignorable, text)
    tail = _DASH_TAIL.search(text)
    if tail and _IGNORABLE_RE.match(fold(tail.group(1))):
        text = text[:tail.start()]
    return fold(text)


_ARTIST_SPLIT = re.compile(r"\s*(?:;|,|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b|&|\band\b)\s*", re.I)


def artist_keys(name):
    """Zbiór znormalizowanych nazw wykonawców z napisu typu „A feat. B & C”."""
    keys = {fold(part) for part in _ARTIST_SPLIT.split(str(name or ""))}
    whole = fold(name)
    keys.add(whole)
    return {k for k in keys if k}


def isrc_of(song):
    """ISRC utworu z tagów (12 znaków, wielkie litery, bez myślników) albo None."""
    tags = song.get("tags") or {}
    values = tags.get("isrc") or []
    if isinstance(values, str):
        values = [values]
    for value in values:
        code = re.sub(r"[^A-Za-z0-9]", "", str(value)).upper()
        if len(code) == 12:
            return code
    return None


def _duration_ok(entry_seconds, song_seconds):
    try:
        a, b = float(entry_seconds or 0), float(song_seconds or 0)
    except (TypeError, ValueError):
        return True
    return not a or not b or abs(a - b) <= DURATION_SLACK


def _better(song):
    """Który z kilku pasujących utworów wybrać: nie z kompilacji, o większym bitrate, częściej słuchany."""
    return (not song.get("compilation"), int(song.get("bitRate") or 0), int(song.get("playCount") or 0))


def match(entries, songs):
    """Dla wpisów listy (w kolejności) zwraca [(wpis, utwór z biblioteki, sposób: „isrc” albo „title”)].

    Wpis: {"rank", "title", "artist", "isrc", "duration"}. Jeden utwór trafia do wyniku najwyżej raz.
    """
    by_isrc, by_title = {}, {}
    for song in songs:
        code = isrc_of(song)
        if code:
            by_isrc.setdefault(code, []).append(song)
        key = clean_title(song.get("title"))
        if key:
            by_title.setdefault(key, []).append(song)

    used, result = set(), []
    for entry in sorted(entries, key=lambda e: e.get("rank", 0)):
        found, how = None, None
        code = re.sub(r"[^A-Za-z0-9]", "", str(entry.get("isrc") or "")).upper()
        candidates = [s for s in by_isrc.get(code, []) if s["id"] not in used] if len(code) == 12 else []
        if candidates:
            found, how = max(candidates, key=_better), "isrc"
        else:
            wanted = artist_keys(entry.get("artist"))
            candidates = []
            for song in by_title.get(clean_title(entry.get("title")), []):
                if song["id"] in used or not _duration_ok(entry.get("duration"), song.get("duration")):
                    continue
                have = artist_keys(song.get("artist")) | artist_keys(song.get("albumArtist"))
                if wanted & have:
                    candidates.append(song)
            if candidates:
                found, how = max(candidates, key=_better), "title"
        if found:
            used.add(found["id"])
            result.append((entry, found, how))
    return result


def missing(entries, hits, top):
    """Wpisy z `top` pierwszych miejsc listy, których nie ma w bibliotece (według wyniku `match`), od najwyższego miejsca."""
    have = {entry["id"] for entry, _, _ in hits}
    return [e for e in sorted(entries, key=lambda e: e.get("rank", 0)) if e.get("rank", 0) <= top and e["id"] not in have]


def comment(chart, matched, total, now):
    return f"{COMMENT_PREFIX}: {chart.title} · w bibliotece {matched}/{total} · aktualizacja {now:%Y-%m-%d}. Odświeżana automatycznie."


def names(chart):
    """Wszystkie nazwy, pod którymi playlista tej listy może występować w Navidrome (zależnie od głębokości)."""
    return [display_name(chart, d) for d in STEPS]
