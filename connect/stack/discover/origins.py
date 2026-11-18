"""Miksy według pochodzenia: French Music, Latin Music, Polska Muzyka, Polski Rap i inne.

Moduł jest czysty (bez sieci i bez Navidrome), żeby dało się go testować. Gatunki w tagach niewiele mówią o kraju
wykonawcy (w bibliotece zwykle jest „hip hop”, a nie „French hip hop”), więc pochodzenie utworu liczymy z trzech
słabych sygnałów, z których dopiero zgodność dwóch wystarcza:

* tagi wykonawcy z Last.fm (artist.getTopTags: „french”, „polish”, „latin”…; liczba to ważność tagu 0–100),
* język utworu z tagów pliku (MusicBrainz: `language`),
* kraj z kodu ISRC (pierwsze dwie litery) — to kraj rejestracji, więc bywa mylący i sam nie wystarcza.

Punkty: tag wykonawcy ≥ 25 → 2, ≥ 5 → 1; język zgodny → 2; ISRC zgodny → 1; gatunek z biblioteki zgodny → 2.
Utwór należy do pochodzenia od 2 punktów.
"""

import re
from dataclasses import dataclass

import mixes

TAG_STRONG, TAG_WEAK = 25, 5
MIN_POINTS = 2


@dataclass(frozen=True)
class Origin:
    key: str
    tags: tuple = ()          # tagi Last.fm wykonawcy (znormalizowane)
    languages: tuple = ()     # kody języka (ISO 639-3)
    isrc: tuple = ()          # prefiksy ISRC (kraj rejestracji)
    genres: tuple = ()        # gatunki z biblioteki, które same świadczą o pochodzeniu (np. „reggaeton”)
    require: tuple = ()       # dodatkowo wymagany gatunek (np. rap dla „Polski Rap”)


ORIGINS = (
    Origin("french",
           tags=("french", "france", "chanson", "french pop", "french rap", "french hip hop", "francais", "variete francaise",
                 "french touch", "musique francaise", "chanson francaise"),
           languages=("fra",), isrc=("FR",), genres=("chanson", "french pop", "french hip hop", "french rap", "variete francaise")),
    Origin("latin",
           tags=("latin", "spanish", "reggaeton", "latin pop", "salsa", "bachata", "cumbia", "latino", "latin trap", "merengue",
                 "dembow", "mexican", "colombian", "puerto rican", "argentine", "cuban", "regional mexican", "corridos tumbados",
                 "spanish rap", "urbano latino", "latin rap"),
           languages=("spa",), isrc=("ES", "MX", "CO", "AR", "PR", "CL", "PE", "DO", "CU", "VE", "UY", "EC"),
           genres=("latin", "reggaeton", "salsa", "bachata", "cumbia", "latin pop", "latin trap", "merengue", "dembow", "latino")),
    Origin("polish",
           tags=("polish", "poland", "polska", "polish rock", "polish pop", "disco polo", "polski", "polish hip hop", "polish rap",
                 "polish music", "polskie"),
           languages=("pol",), isrc=("PL",), genres=("polish", "polska", "disco polo", "polski rap", "polish hip hop")),
    Origin("polish-rap",
           tags=("polish", "poland", "polska", "polish hip hop", "polish rap", "polski rap", "polski hip hop", "polskie"),
           languages=("pol",), isrc=("PL",), genres=("polski rap", "polish hip hop", "polish rap"),
           require=("hip hop", "rap", "trap", "drill", "pop rap", "cloud rap", "emo rap")),
    Origin("german",
           tags=("german", "germany", "deutsch", "deutschrap", "german rap", "german hip hop", "neue deutsche welle", "schlager",
                 "neue deutsche harte", "austrian"),
           languages=("deu",), isrc=("DE", "AT", "CH"), genres=("deutschrap", "german hip hop", "neue deutsche welle", "schlager")),
    Origin("asia",
           tags=("k pop", "kpop", "korean", "j pop", "jpop", "japanese", "anime", "city pop", "k rap", "korea", "japan", "j rock"),
           languages=("kor", "jpn"), isrc=("KR", "JP"), genres=("k pop", "j pop", "kpop", "jpop", "city pop", "anime", "k rap")),
    Origin("brazil",
           tags=("brazilian", "brazil", "mpb", "bossa nova", "samba", "sertanejo", "funk carioca", "baile funk", "brasil",
                 "portuguese", "pagode", "forro"),
           languages=("por",), isrc=("BR", "PT"), genres=("bossa nova", "samba", "mpb", "sertanejo", "funk carioca", "baile funk", "pagode")),
)
BY_KEY = {o.key: o for o in ORIGINS}

_LANGUAGES = {
    "fra": ("fra", "fre", "fr", "french", "francais"), "spa": ("spa", "es", "spanish", "espanol"),
    "pol": ("pol", "pl", "polish", "polski"), "deu": ("deu", "ger", "de", "german", "deutsch"),
    "kor": ("kor", "ko", "korean"), "jpn": ("jpn", "ja", "jp", "japanese"), "por": ("por", "pt", "portuguese"),
    "ita": ("ita", "it", "italian"), "eng": ("eng", "en", "english", "en us", "en gb"),
}
_LANGUAGE_OF = {alias: code for code, aliases in _LANGUAGES.items() for alias in aliases}


def _tag_values(song, name):
    values = (song.get("tags") or {}).get(name) or []
    if isinstance(values, str):
        values = [values]
    return [str(v) for v in values if v]


def languages_of(song):
    """Kody języka (ISO 639-3) z tagów pliku, np. {"pol"}; nieznane wartości są pomijane."""
    found = set()
    for value in _tag_values(song, "language"):
        for part in re.split(r"[;,/]", value):
            code = _LANGUAGE_OF.get(mixes.normalize(part))
            if code:
                found.add(code)
    return found


def isrc_country(song):
    for value in _tag_values(song, "isrc"):
        code = re.sub(r"[^A-Za-z0-9]", "", value).upper()
        if len(code) == 12:
            return code[:2]
    return None


# bez przecinka i „&”: „Tyler, The Creator”, „Earth, Wind & Fire” i „Simon & Garfunkel” to jedna nazwa
_SPLIT = re.compile(r"\s*(?:;|•|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bx\b)\s*", re.I)


def primary_name(song):
    """Pierwszy wykonawca utworu („A feat. B & C” → „A”): po nim pytamy Last.fm o tagi i pamiętamy je."""
    name = str(song.get("albumArtist") or song.get("artist") or "")
    return next((p for p in _SPLIT.split(name) if p.strip()), name).strip()


def artist_key(song):
    return mixes.normalize(primary_name(song))


def tag_strength(origin, tags):
    """Największa ważność (0–100) któregokolwiek z tagów pochodzenia w `tags` ({znormalizowany tag: liczba})."""
    wanted = {mixes.normalize(t) for t in origin.tags}
    return max((count for tag, count in (tags or {}).items() if mixes.normalize(tag) in wanted), default=0)


def points(origin, song, tags):
    """Punkty zgodności utworu z pochodzeniem (sygnały opisane na górze pliku); 0, gdy brak wymaganego gatunku."""
    names = mixes.genre_names(song)
    if origin.require and not any(mixes.normalize(g) in names for g in origin.require):
        return 0
    total = 0
    strength = tag_strength(origin, tags)
    total += 2 if strength >= TAG_STRONG else 1 if strength >= TAG_WEAK else 0
    if languages_of(song) & set(origin.languages):
        total += 2
    if isrc_country(song) in origin.isrc:
        total += 1
    if any(mixes.normalize(g) in names for g in origin.genres):
        total += 2
    return total


def classify(songs, tags_of):
    """{klucz pochodzenia: zbiór id utworów}. `tags_of(klucz wykonawcy)` → {tag: liczba} (puste, gdy nieznane)."""
    result = {o.key: set() for o in ORIGINS}
    cache = {}
    for song in songs:
        key = artist_key(song)
        if key not in cache:
            cache[key] = tags_of(key) or {}
        for origin in ORIGINS:
            if points(origin, song, cache[key]) >= MIN_POINTS:
                result[origin.key].add(song["id"])
    return result


def relevant_tags(tags, limit=12):
    """Z odpowiedzi Last.fm zostawia najważniejsze tagi i wszystkie tagi pochodzenia (do zapamiętania w stanie)."""
    known = {mixes.normalize(t) for o in ORIGINS for t in o.tags}
    ranked = sorted(((mixes.normalize(name), int(count)) for name, count in tags), key=lambda p: -p[1])
    keep = {}
    for name, count in ranked:
        if name and (len(keep) < limit or name in known) and count >= 2:
            keep[name] = max(count, keep.get(name, 0))
    return keep
