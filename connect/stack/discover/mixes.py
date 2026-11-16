"""Tematyczne miksy Piterfy: Happy Mix, Dark Mix, Electronic Mix, Bass Boost i inne.

Moduł jest czysty (bez sieci i bez Navidrome), żeby dało się go testować. Dostaje listę utworów z natywnego
API Navidrome i zwraca identyfikatory do playlisty; zapis playlist robi `discover.refresh_mixes`.

Utwór trafia do miksu, gdy pasuje jego gatunek (tagi w bibliotece albo gatunki z eksportu Spotify) albo — jeśli
eksport zna ten utwór — jego nastrój (energia, walencja, tempo). Wykluczenia mają pierwszeństwo, więc
w „Happy Mix” nie wyląduje death metal, a w „Dark Mix” wesoły pop.

Miks odświeża się sam: przy każdym przebiegu losowanie liczy się od nowa na całej bibliotece (rośnie razem z nią,
a świeże odkrycia dostają premię), utwory z poprzedniej wersji są lekko karane, żeby playlista się obracała,
a ulubione (gwiazdka, wysoka ocena, dużo odsłuchań) wracają częściej.
"""

import math
import random
import re
import unicodedata
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

COMMENT_PREFIX = "Piterfy Mix"  # po tym początku komentarza aplikacja rozpoznaje playlisty-miksy
MIN_SECONDS = 60
MAX_SECONDS = 12 * 60


@dataclass(frozen=True)
class Mix:
    name: str
    description: str
    genres: tuple = ()                 # pasujący gatunek wystarcza, żeby utwór wszedł do miksu
    exclude: tuple = ()                # pasujący gatunek wyklucza utwór
    mood: tuple = field(default=())    # (min_valence, max_valence, min_energy, max_energy, min_tempo) z eksportu Spotify
    min_bpm: int = 0                   # tag BPM z pliku; 0 = nie sprawdzaj (używane razem z `bpm_genres`)
    bpm_genres: tuple = ()             # gatunki, które wchodzą do miksu dopiero przy szybkim tempie


MIXES = (
    Mix("Happy Mix", "Radosne, energiczne i taneczne",
        genres=("pop", "dance pop", "disco", "funk", "reggae", "ska", "motown", "soul", "salsa", "latin", "reggaeton",
                "surf", "swing", "feel good", "happy", "summer", "eurodance", "k pop", "j pop", "europop", "synthpop",
                "new wave", "party", "afrobeat", "calypso"),
        exclude=("metal", "doom", "goth", "dark", "black metal", "death", "industrial", "funeral", "sad", "melancholic",
                 "depressive", "grindcore", "emo", "post punk", "darkwave", "coldwave", "dark ambient"),
        mood=(0.62, 1.0, 0.5, 1.0, 0)),
    Mix("Dark Mix", "Mroczne, ciężkie i melancholijne",
        genres=("dark", "darkwave", "goth", "gothic", "industrial", "doom", "black metal", "death metal", "dark ambient",
                "trip hop", "post punk", "coldwave", "ebm", "witch house", "drone", "horror", "horrorcore", "noir",
                "dark electro", "darksynth", "gothic rock", "sludge", "post metal", "dungeon synth", "cold wave"),
        exclude=("happy", "feel good", "children", "disco", "reggae", "ska", "party", "eurodance", "summer"),
        mood=(0.0, 0.28, 0.4, 1.0, 0)),
    Mix("Electronic Mix", "Elektronika: house, techno, trance, synth",
        genres=("electronic", "electronica", "edm", "house", "techno", "trance", "electro", "synthwave", "idm",
                "dubstep", "drum and bass", "dnb", "breakbeat", "downtempo", "electropop", "synth pop", "synthpop",
                "future bass", "progressive house", "deep house", "minimal", "dance", "chillwave", "vaporwave",
                "darksynth", "big beat", "hardstyle", "uk garage", "garage"),
        exclude=("electric blues", "electric guitar", "classical")),
    Mix("Bass Boost", "Mocny bas: dubstep, DnB, trap, hip-hop, hardstyle",
        genres=("bass", "dubstep", "drum and bass", "dnb", "drumstep", "riddim", "trap", "hip hop", "rap", "grime",
                "drill", "phonk", "hardstyle", "hardcore techno", "gabber", "bass house", "future bass", "uk garage",
                "jungle", "moombahton", "reggaeton", "dancehall", "bassline", "brostep", "neurofunk", "breakbeat",
                "crunk", "bounce", "g funk", "gangsta"),
        exclude=("lo fi", "lofi", "acoustic", "ambient", "classical", "new age", "easy listening"),
        bpm_genres=("electronic", "house", "techno", "trance", "edm", "electro", "dance"), min_bpm=124),
    Mix("Chill Mix", "Spokojne, do pracy i odpoczynku",
        genres=("chill", "chillout", "ambient", "downtempo", "lofi", "lo fi", "acoustic", "easy listening", "bossa nova",
                "new age", "jazz", "trip hop", "singer songwriter", "folk", "lounge", "chillhop", "soft", "smooth",
                "piano", "instrumental", "soundtrack", "classical", "neoclassical", "dream pop", "shoegaze"),
        exclude=("metal", "punk", "hardcore", "hardstyle", "dubstep", "drum and bass", "gabber", "trap", "industrial",
                 "death", "thrash", "grindcore", "hard rock", "eurodance"),
        mood=(0.2, 0.85, 0.0, 0.42, 0)),
    Mix("Workout Mix", "Tempo i energia na trening",
        genres=("hardstyle", "hard rock", "metal", "thrash", "hardcore", "punk", "drum and bass", "dnb", "dubstep",
                "trap", "techno", "hard techno", "gabber", "edm", "big room", "trance", "rap metal", "metalcore",
                "pop punk", "power metal", "crossfit", "workout", "gym", "hip hop", "rap"),
        exclude=("ambient", "lofi", "lo fi", "chill", "easy listening", "classical", "new age", "acoustic", "ballad"),
        mood=(0.0, 1.0, 0.8, 1.0, 110)),
    Mix("Rock Mix", "Rock, indie, punk i alternatywa",
        genres=("rock", "indie", "punk", "grunge", "alternative", "britpop", "shoegaze", "garage rock", "post punk",
                "new wave", "hard rock", "classic rock", "psychedelic", "stoner", "emo", "pop punk", "blues rock",
                "math rock", "post rock", "progressive rock"),
        exclude=("rock and roll revival", "electronic", "hip hop", "rap", "classical", "synth pop", "synthpop")),
    Mix("Hip-Hop Mix", "Hip-hop, rap i R&B",
        genres=("hip hop", "rap", "trap", "boom bap", "grime", "drill", "g funk", "cloud rap", "r and b", "rnb",
                "gangsta", "conscious hip hop", "crunk", "phonk", "polish hip hop", "polski rap"),
        exclude=("rap metal", "rap rock", "nu metal", "classical")),
)


def normalize(text):
    """Małe litery, bez akcentów; `&` → `and`, znaki rozdzielające → spacja (np. „Drum & Bass” → „drum and bass”)."""
    text = unicodedata.normalize("NFKD", str(text or "")).encode("ascii", "ignore").decode().lower()
    text = text.replace("&", " and ").replace("+", " and ").replace("'", "")
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def _pattern(phrases):
    if not phrases:
        return None
    return re.compile(r"(?<![a-z0-9])(?:" + "|".join(re.escape(normalize(p)) for p in phrases) + r")(?![a-z0-9])")


_PATTERNS = {}


def _compiled(mix):
    if mix.name not in _PATTERNS:
        _PATTERNS[mix.name] = (_pattern(mix.genres), _pattern(mix.exclude), _pattern(mix.bpm_genres))
    return _PATTERNS[mix.name]


def genre_names(song, feature=None):
    names = [g.get("name") for g in (song.get("genres") or []) if isinstance(g, dict) and g.get("name")]
    if song.get("genre"):
        names.append(song["genre"])
    names += (feature or {}).get("g", [])
    return [normalize(n) for n in names if n]


def matches(mix, song, feature=None):
    """Czy utwór pasuje do miksu. `feature` = {"e","v","t",...} z eksportu Spotify albo None."""
    include, exclude, fast = _compiled(mix)
    names = genre_names(song, feature)
    text = " | ".join(names)
    if exclude and exclude.search(text):
        return False
    if include and include.search(text):
        if feature and mix.mood:  # znamy nastrój — nie wpuszczamy utworów, które mu przeczą
            return _mood_vetoed(mix, feature) is False
        return True
    if feature and mix.mood and _mood_fits(mix, feature):
        return True
    bpm = song.get("bpm") or (feature or {}).get("t") or 0
    return bool(fast and mix.min_bpm and bpm >= mix.min_bpm and fast.search(text))


def _mood_fits(mix, feature):
    low_v, high_v, low_e, high_e, min_tempo = mix.mood
    return (low_v <= feature["v"] <= high_v and low_e <= feature["e"] <= high_e
            and feature.get("t", 0) >= min_tempo)


def _mood_vetoed(mix, feature):
    """Gatunek pasuje, ale nastrój utworu jest wyraźnie po drugiej stronie."""
    low_v, high_v, low_e, high_e, _ = mix.mood
    slack = 0.18
    return not (low_v - slack <= feature["v"] <= high_v + slack and low_e - slack <= feature["e"] <= high_e + slack)


def _parse(value):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def affinity(song, now, previous=()):
    """Im wyższa, tym chętniej utwór trafia do miksu (ulubione, świeże odkrycia, rzadziej grane ostatnio)."""
    score = 0.0
    if song.get("starred") or song.get("starredAt"):
        score += 3
    rating = song.get("rating") or 0
    score += {5: 2.5, 4: 2.0, 3: 0.5}.get(rating, 0)
    score += min(math.log1p(song.get("playCount") or 0), 3.0) * 0.5
    added = _parse(song.get("createdAt"))
    if added and now - added < timedelta(days=21):
        score += 1.5
    played = _parse(song.get("playDate"))
    if played and now - played < timedelta(days=2):
        score -= 0.7
    if song.get("id") in previous:
        score -= 2.0
    return score


def eligible(song):
    if (song.get("rating") or 0) == 1:  # ocena 1★ = „nie chcę tego słyszeć”
        return False
    seconds = song.get("duration") or 0
    return song.get("id") is not None and MIN_SECONDS <= seconds <= MAX_SECONDS


def target_size(pool, base, cap):
    """Miks rośnie razem z biblioteką: ok. piąta część puli, od `base` do `cap`, nigdy ponad pulę."""
    return min(pool, cap, max(base, pool // 5))


def artist_of(song):
    return normalize(song.get("albumArtist") or song.get("artist") or "")


def pick(mix, songs, features=None, previous=(), now=None, rng=None, base=40, cap=100, max_per_artist=3):
    """Zwraca listę identyfikatorów utworów (w losowej kolejności) albo [] gdy pula jest za mała.

    `features` — funkcja utwór → cechy audio albo None (domyślnie brak cech).
    """
    now = now or datetime.now(timezone.utc)
    rng = rng or random.Random()
    previous = set(previous)
    feature_of = features or (lambda song: None)
    pool = [s for s in songs if eligible(s) and matches(mix, s, feature_of(s))]
    if len(pool) < MIN_POOL:
        return []
    scored = sorted(((affinity(s, now, previous) + rng.random() * 2.5, s) for s in pool),
                    key=lambda pair: pair[0], reverse=True)
    want = target_size(len(pool), base, cap)
    chosen, per_artist = [], {}
    for _, song in scored:
        artist = artist_of(song)
        if per_artist.get(artist, 0) >= max_per_artist:
            continue
        per_artist[artist] = per_artist.get(artist, 0) + 1
        chosen.append(song["id"])
        if len(chosen) >= want:
            break
    rng.shuffle(chosen)
    return chosen


MIN_POOL = 12  # mniej pasujących utworów = za mało na sensowny miks; playlista nie powstaje


def by_names(names):
    """Miksy o podanych nazwach (bez względu na wielkość liter); `names` puste = wszystkie."""
    wanted = {normalize(n) for n in names if n and n.strip()}
    return [m for m in MIXES if not wanted or normalize(m.name) in wanted]


def comment(mix, count, now):
    return f"{COMMENT_PREFIX}: {mix.description}. Odświeżany automatycznie ({count} utworów, {now:%Y-%m-%d %H:%M})."
