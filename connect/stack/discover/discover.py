#!/usr/bin/env python3
"""piterfy-discover — codziennie (albo co kilka dni) dociąga przez slskd nowe utwory podobne do tego,
czego słuchasz w Navidrome i na Last.fm, układa je w playlisty „Odkrywaj”, uczy się z Twoich reakcji,
sprząta nieodsłuchane do kosza i wysyła podsumowania oraz alarmy mailem.

  python discover.py                            tryb ciągły (sprawdza co 10 min)
  python discover.py --now                      jeden przebieg od razu
  python discover.py --dry-run                  tylko pokazuje, co by pobrał (nic nie ściąga)
  python discover.py --search "Artysta - Tytuł" jedno próbne wyszukiwanie w Soulseek
  python discover.py --test-mail                próbny mail

Bez zewnętrznych bibliotek — wystarczy goły Python 3.11+.
"""

import argparse
import hashlib
import json
import math
import os
import random
import re
import secrets
import shutil
import smtplib
import time
import traceback
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from email.message import EmailMessage
import report_formatter
from pathlib import Path


def env_int(name, default):
    return int(os.environ.get(name) or default)


def env_float(name, default):
    return float(os.environ.get(name) or default)


NAVIDROME_URL      = os.environ.get("NAVIDROME_URL", "http://piterfy-navidrome:4533").rstrip("/")
NAVIDROME_USER     = os.environ.get("NAVIDROME_USER", "")
NAVIDROME_PASSWORD = os.environ.get("NAVIDROME_PASSWORD", "")
SLSKD_URL          = os.environ.get("SLSKD_URL", "http://piterfy-slskd:5030").rstrip("/")
SLSKD_API_KEY      = os.environ.get("SLSKD_API_KEY", "")
SLSKD_USER         = os.environ.get("SLSKD_USER") or os.environ.get("SLSKD_USERNAME", "")  # login do panelu slskd
SLSKD_PASSWORD     = os.environ.get("SLSKD_PASSWORD", "")
LASTFM_API_KEY     = os.environ.get("LASTFM_API_KEY", "")  # opcjonalny; bez niego rekomendacje idą z Deezera
LASTFM_USER        = os.environ.get("LASTFM_USER", "")     # opcjonalny; scrobble ze wszystkich urządzeń jako historia

EVERY_DAYS       = env_int("DISCOVER_EVERY_DAYS", 3)
RUN_HOUR         = env_int("DISCOVER_HOUR", 4)
TRACKS_PER_RUN   = env_int("DISCOVER_TRACKS", 20)
FAMILIAR_SHARE   = env_float("DISCOVER_FAMILIAR", 0.3)  # jaka część utworów od wykonawców, których już masz
MAX_PER_ARTIST   = env_int("DISCOVER_MAX_PER_ARTIST", 2)
LOOKBACK_DAYS    = env_int("DISCOVER_LOOKBACK_DAYS", 30)
FORMATS          = [f.strip().lower() for f in os.environ.get("DISCOVER_FORMATS", "flac,mp3").split(",") if f.strip()]
MIN_BITRATE      = env_int("DISCOVER_MIN_BITRATE", 220)  # mp3 320 / 256 / V0 przechodzą, 192 i niżej nie
PLAYLIST_NAME    = os.environ.get("DISCOVER_PLAYLIST", "Odkrywaj")
PLAYLIST_SIZE    = env_int("DISCOVER_PLAYLIST_SIZE", 60)
DAILY_PLAYLIST   = os.environ.get("DISCOVER_DAILY_PLAYLIST", "Odkrywaj")  # + data, np. „Odkrywaj 12.10”; pusta = bez
DAILY_KEEP_DAYS  = env_int("DISCOVER_DAILY_KEEP_DAYS", 7)  # po tylu dniach playlista dnia znika; 0 = nigdy
DOWNLOAD_TIMEOUT = env_int("DISCOVER_DOWNLOAD_TIMEOUT_MIN", 90) * 60
BLOCKED_ARTISTS  = [a for a in os.environ.get("DISCOVER_BLOCK", "").split(";") if a.strip()]
DATA_DIR         = Path(os.environ.get("DATA_DIR", "/data"))
REPORTS_DIR      = Path(os.environ.get("REPORTS_DIR", "/reports"))
CLEANUP_DAYS     = env_int("DISCOVER_CLEANUP_DAYS", 30)    # nieodsłuchane odkrycia po tylu dniach idą do kosza; 0 = nigdy
TRASH_DAYS       = env_int("DISCOVER_TRASH_DAYS", 30)      # po tylu dniach w koszu pliki są kasowane; 0 = nigdy
MIN_FREE_GB      = env_float("DISCOVER_MIN_FREE_GB", 20)   # przy mniejszej ilości wolnego miejsca nic nie pobiera
MAIL_TO          = os.environ.get("DISCOVER_MAIL_TO", "")   # adres na podsumowania i alarmy; pusty = bez maili
MUSIC_DIR        = Path(os.environ.get("MUSIC_DIR", "/music"))  # biblioteka — ta sama ścieżka co w kontenerze Navidrome
TRASH_DIR        = Path(os.environ.get("TRASH_DIR", "/trash"))
INCOMING_DIR     = os.environ.get("INCOMING_DIR", "")      # pobrania slskd, z których beets przenosi do biblioteki
MSMTPRC          = Path(os.environ.get("MSMTPRC", "/config/msmtprc"))
SPECTRUM_FILE    = Path(os.environ.get("SPECTRUM_FILE", "/app/beets/spectrum.json"))  # analiza widma z kontenera beets
PODCASTS_FILE    = Path(os.environ.get("PODCASTS_FILE", "/app/podcasts.txt"))  # lista audycji (podcasts.py)
PODCAST_EVERY_HOURS = env_float("DISCOVER_PODCAST_EVERY_HOURS", 3)  # co ile godzin sprawdza kanały RSS
PODCAST_LATEST   = env_int("DISCOVER_PODCAST_LATEST", 20)      # tyle najnowszych odcinków audycji trzyma
PODCAST_KEEP_DAYS = env_int("DISCOVER_PODCAST_KEEP_DAYS", 30)  # odsłuchany odcinek znika po tylu dniach

SEED_TRACKS   = 15        # ile najczęściej słuchanych utworów bierze za punkt wyjścia
SEED_ARTISTS  = 12        # ile najczęściej słuchanych wykonawców
SIMILAR_PER_ARTIST = 12
MAX_ATTEMPTS  = 3         # ilu różnych użytkowników Soulseek próbuje dla jednego utworu
STALL_SECONDS = 15 * 60   # tyle czeka na start pobierania, zanim spróbuje u kogoś innego
POLL_SECONDS  = 30
SEARCH_PAUSE  = 10        # odstęp między wyszukiwaniami — za szybkie kończą się czasową blokadą na serwerze Soulseek
EMPTY_SEARCH_LIMIT = 6    # tyle wyszukiwań z rzędu bez żadnej odpowiedzi przerywa przebieg (awaria Soulseek)
MAX_FILE_SIZE = 300 * 1024 * 1024
RETRY_AFTER_DAYS = 60     # po tylu dniach ponawia utwory, których wcześniej nie udało się znaleźć
REVIEW_DAYS   = 120       # tak długo sprawdza, jak przyjąłeś odkrycie
LIKED_PLAYS   = 3         # od tylu odsłuchów odkrycie liczy się jako polubione
BAD_QUALITY   = {"fake", "transcoded"}  # FLAC zrobiony z MP3, MP3 podbite z niższego bitrate (spectrum.py)
MAX_FAKES     = 3         # po tylu fałszywych kopiach jednego utworu przestaje go szukać
SLSKD_DOWN_ALERT_MIN = 30 # po tylu minutach bez połączenia z Soulseek wysyła alarm

LOSSY = {"mp3", "m4a", "aac", "ogg", "opus", "wma"}
NOISE_WORDS = {"live", "remix", "rmx", "karaoke", "instrumental", "cover", "acoustic", "demo", "nightcore",
               "slowed", "sped", "reverb", "8d", "acapella", "unplugged", "bootleg", "mashup"}
FILLER_WORDS = {"the", "and", "feat", "ft"}
# „Wykonawcy”, którzy nie są wykonawcami: składanki i pliki bez tagu (Navidrome pokazuje je jako [Unknown Artist])
import podcasts  # noqa: E402  (podcasty: ściąganie odcinków RSS jako pliki audio w bibliotece)

VARIOUS_ARTISTS = {"various artists", "various", "va", "unknown artist", "unknown", ""}


def log(message):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}", flush=True)


# ── tekst ────────────────────────────────────────────────────────────────────

SPECIAL_LETTERS = str.maketrans({"ł": "l", "ø": "o", "đ": "d", "æ": "ae", "œ": "oe", "þ": "th"})
VERSION_TAG = re.compile(
    r"\s*[(\[][^)\]]*\b(?:feat|ft|featuring|with|remaster(?:ed)?|version|mono|stereo|deluxe|bonus|"
    r"explicit|clean|radio edit|album edit|single edit)\b[^)\]]*[)\]]", re.I)
VERSION_SUFFIX = re.compile(r"\s+-\s+[^-]*\b(?:remaster(?:ed)?|version|mono|stereo|edit|bonus(?: track)?)\b.*$", re.I)
FEAT_SUFFIX = re.compile(r"\s+(?:feat\.?|ft\.?|featuring)\s+.*$", re.I)
# „A feat. B”, „A x B”, „A; B”, „A , B” (przecinek ze spacją PRZED nim — „Tyler, The Creator” zostaje w całości)
ARTIST_SPLIT = re.compile(r"\s+(?:feat\.?|ft\.?|featuring|vs\.?|x)\s+|\s*;\s*|\s+,\s*", re.I)


def norm(text):
    """Małe litery bez ogonków i interpunkcji: 'Beyoncé & Jay-Z' -> 'beyonce and jay z'."""
    text = unicodedata.normalize("NFKD", str(text).casefold().translate(SPECIAL_LETTERS))
    text = "".join(c for c in text if not unicodedata.combining(c)).replace("&", " and ")
    return " ".join(re.sub(r"[\W_]+", " ", text).split())


def words(text):
    return set(norm(text).split()) - FILLER_WORDS


def contains(haystack, needle):
    """Czy wszystkie słowa z needle są w haystack (albo needle bez spacji, np. AC/DC -> 'acdc')."""
    want = words(needle)
    if not want or want <= words(haystack):
        return True
    compact = norm(needle).replace(" ", "")
    return len(compact) >= 4 and compact in norm(haystack).replace(" ", "")


def clean_title(title):
    """'Song - Remastered 2011' / 'Song (feat. X)' -> 'Song'."""
    cleaned = FEAT_SUFFIX.sub("", VERSION_SUFFIX.sub("", VERSION_TAG.sub("", title))).strip()
    return cleaned or title


def primary_artist(artist):
    return ARTIST_SPLIT.split(artist, maxsplit=1)[0].strip() or artist


def track_key(artist, title):
    return f"{norm(primary_artist(artist))}|{' '.join(sorted(words(clean_title(title))))}"


def parse_time(value):
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(re.sub(r"(\.\d{6})\d+", r"\1", value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


# ── HTTP ─────────────────────────────────────────────────────────────────────

def http(method, url, params=None, body=None, headers=None, timeout=30):
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params, doseq=True)
    headers = {"User-Agent": "piterfy-discover/1.0", "Accept": "application/json", **(headers or {})}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
    return json.loads(raw) if raw.strip() else None


def as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


# ── Navidrome (Subsonic API) ─────────────────────────────────────────────────

class Navidrome:
    def __init__(self):
        if not NAVIDROME_USER or not NAVIDROME_PASSWORD:
            raise SystemExit("Ustaw NAVIDROME_USER i NAVIDROME_PASSWORD.")
        self.token = None

    def call(self, endpoint, **params):
        salt = secrets.token_hex(8)
        auth = {"u": NAVIDROME_USER, "t": hashlib.md5((NAVIDROME_PASSWORD + salt).encode()).hexdigest(),
                "s": salt, "v": "1.16.1", "c": "piterfy-discover", "f": "json"}
        reply = http("GET", f"{NAVIDROME_URL}/rest/{endpoint}", params={**auth, **params})["subsonic-response"]
        if reply.get("status") != "ok":
            raise RuntimeError(f"Navidrome {endpoint}: {reply.get('error', {}).get('message', reply)}")
        return reply

    def known_artists(self):
        index = self.call("getArtists").get("artists", {}).get("index", [])
        return {norm(a["name"]) for group in as_list(index) for a in as_list(group.get("artist"))}

    def find_song(self, artist, title):
        """Id utworu, jeśli już jest w bibliotece."""
        want = words(clean_title(title))
        artist = primary_artist(artist)
        reply = self.call("search3", query=norm(clean_title(title)) or norm(artist),
                          songCount=50, artistCount=0, albumCount=0)
        close = []
        for song in as_list(reply.get("searchResult3", {}).get("song")):
            names = [song.get("artist", ""), song.get("albumArtist", ""), song.get("displayArtist", "")]
            names += [a.get("name", "") for a in as_list(song.get("artists"))]
            if not any(n and (contains(n, artist) or contains(artist, n)) for n in names):
                continue
            have = words(clean_title(song.get("title", "")))
            if have == want:
                return song["id"]
            if min(len(have), len(want)) >= 2 and (have <= want or want <= have):
                close.append(song["id"])  # np. tytuł po poprawkach beets: „Kiss cam” i „Kiss cam (podryw roku)”
        return close[0] if close else None

    def native(self, path, **params):
        """Natywne API Navidrome (to, z którego korzysta jego interfejs WWW)."""
        if not self.token:
            self.token = http("POST", f"{NAVIDROME_URL}/auth/login",
                              body={"username": NAVIDROME_USER, "password": NAVIDROME_PASSWORD})["token"]
        return http("GET", f"{NAVIDROME_URL}/api/{path}", params=params,
                    headers={"x-nd-authorization": f"Bearer {self.token}"})

    def real_path(self, song_id):
        """Prawdziwa ścieżka pliku — Subsonic API podaje tylko udawaną, więc pytamy natywne API Navidrome."""
        song = self.native(f"song/{song_id}")
        return Path(song.get("libraryPath") or "/", song["path"])

    def rescan(self):
        try:
            self.call("startScan")
        except (RuntimeError, urllib.error.URLError) as e:
            log(f"Nie mogę uruchomić skanowania ({e}) — Navidrome zeskanuje sam według swojego harmonogramu.")
            return
        deadline = time.time() + 15 * 60
        while time.time() < deadline:
            time.sleep(10)
            if not self.call("getScanStatus").get("scanStatus", {}).get("scanning"):
                return


def song_artist(song):
    artists = as_list(song.get("artists"))
    return artists[0]["name"] if artists and artists[0].get("name") else primary_artist(song.get("artist") or "")


def listening_profile(nd, history):
    """Najczęściej i najświeżej słuchane utwory i wykonawcy z ostatnich LOOKBACK_DAYS dni.

    Historia z Navidrome i ze scrobbli Last.fm (jeśli jest LASTFM_USER). Gdy utwór jest w obu,
    liczy się mocniejszy sygnał — odsłuchy z Navidrome scrobblowane do Last.fm nie liczą się podwójnie.
    """
    now = datetime.now(timezone.utc)
    since = now - timedelta(days=LOOKBACK_DAYS)
    tracks, artists, played = {}, Counter(), {}

    def add(artist, title, weight):
        if not artist or not title or norm(artist) in VARIOUS_ARTISTS:
            return
        track = tracks.setdefault(track_key(artist, title), {"artist": artist, "title": title, "weight": 0.0})
        track["weight"] += weight
        artists[artist] += weight

    def offer(artist, title, last, plays):
        recency = 0.5 ** ((now - last).days / 14)  # słuchane dwa tygodnie temu liczy się w połowie
        weight, key = recency * (1 + math.log1p(plays)), track_key(artist, title)
        if weight > played.get(key, (None, None, 0.0))[2]:
            played[key] = (artist, title, weight)

    recent = nd.call("getAlbumList2", type="recent", size=100).get("albumList2", {}).get("album")
    for album in as_list(recent):
        last = parse_time(album.get("played"))
        if last and last < since:
            break
        for song in as_list(nd.call("getAlbum", id=album["id"]).get("album", {}).get("song")):
            if podcasts.is_podcast(song):  # odcinki podcastów nie są gustem muzycznym
                continue
            last = parse_time(song.get("played"))
            if last and last >= since:
                offer(song_artist(song), song.get("title") or "", last, song.get("playCount", 0))

    scrobbles = {}
    shows = podcasts.show_names(podcasts.load_shows(PODCASTS_FILE, log=lambda *_: None))
    for artist, title, when in lastfm_scrobbles(since):
        if unicodedata.normalize("NFKC", artist).casefold() in shows:  # scrobble odcinka audycji
            continue
        entry = scrobbles.setdefault(track_key(artist, title), [artist, title, when, 0])
        entry[2], entry[3] = max(entry[2], when), entry[3] + 1
    for artist, title, last, count in scrobbles.values():
        offer(artist, title, last, count)
    if scrobbles:
        log(f"Scrobble z Last.fm: {sum(e[3] for e in scrobbles.values())} odsłuchów, {len(scrobbles)} utworów.")

    for artist, title, weight in played.values():
        add(artist, title, weight)
    for entry in history.values():  # odkrycia, które Ci się spodobały, to mocny punkt wyjścia
        if entry.get("verdict") == "liked":
            add(primary_artist(entry["artist"]), entry["title"], 1.0)

    # Długofalowy gust: najczęściej grane albumy i polubione utwory — z mniejszą wagą.
    frequent = nd.call("getAlbumList2", type="frequent", size=30).get("albumList2", {}).get("album")
    for album in as_list(frequent):
        artist = primary_artist(album.get("artist") or "")
        if artist and norm(artist) not in VARIOUS_ARTISTS:
            artists[artist] += 0.3 * math.log1p(album.get("playCount", 0))
    starred = as_list(nd.call("getStarred2").get("starred2", {}).get("song"))
    for song in random.sample(starred, min(len(starred), 50)):
        add(song_artist(song), song.get("title") or "", 0.3)

    if not artists:
        raise RuntimeError("Navidrome nie ma jeszcze historii odsłuchów ani polubionych utworów — nie mam na czym bazować.")

    top = max(artists.values())
    seed_tracks = sorted(tracks.values(), key=lambda t: t["weight"], reverse=True)[:SEED_TRACKS]
    for track in seed_tracks:
        track["weight"] /= top
    seed_artists = [{"name": name, "weight": weight / top} for name, weight in artists.most_common(SEED_ARTISTS)]
    return seed_tracks, seed_artists


def lastfm_scrobbles(since):
    """(wykonawca, tytuł, kiedy) ze scrobbli Last.fm — słuchanie ze wszystkich urządzeń."""
    if not (LASTFM_API_KEY and LASTFM_USER):
        return []
    lastfm, result = LastFm(), []
    for page in range(1, 6):  # do 1000 ostatnich scrobbli
        recent = lastfm.call("user.getRecentTracks", user=LASTFM_USER, limit=200, page=page,
                             **{"from": int(since.timestamp())}).get("recenttracks", {})
        for t in as_list(recent.get("track")):
            uts = (t.get("date") or {}).get("uts")
            artist = (t.get("artist") or {}).get("#text") or (t.get("artist") or {}).get("name")
            if uts and artist and t.get("name"):  # bez daty = właśnie grane
                result.append((artist, t["name"], datetime.fromtimestamp(int(uts), timezone.utc)))
        if page >= int(recent.get("@attr", {}).get("totalPages") or 1):
            break
    return result


# ── źródła rekomendacji ──────────────────────────────────────────────────────

class LastFm:
    """track.getSimilar + artist.getSimilar + artist.getTopTracks."""

    def call(self, method, **params):
        params.update(method=method, api_key=LASTFM_API_KEY, format="json", autocorrect=1)
        try:
            data = http("GET", "https://ws.audioscrobbler.com/2.0/", params=params)
        except (urllib.error.URLError, ValueError) as e:
            log(f"   Last.fm {method}: {e}")
            return {}
        finally:
            time.sleep(0.25)
        return {} if not data or "error" in data else data

    def similar_tracks(self, artist, title):
        data = self.call("track.getSimilar", artist=artist, track=title, limit=15)
        return [(t["artist"]["name"], t["name"], float(t.get("match") or 0), None)
                for t in as_list(data.get("similartracks", {}).get("track"))]

    def similar_artists(self, artist):
        data = self.call("artist.getSimilar", artist=artist, limit=SIMILAR_PER_ARTIST)
        return [(a["name"], float(a.get("match") or 0)) for a in as_list(data.get("similarartists", {}).get("artist"))]

    def top_tracks(self, artist, limit):
        data = self.call("artist.getTopTracks", artist=artist, limit=limit)
        return [(t["name"], None) for t in as_list(data.get("toptracks", {}).get("track"))][:limit]


class Deezer:
    """Bez klucza API: powiązani wykonawcy + ich najpopularniejsze utwory."""

    def __init__(self):
        self.ids = {}

    def call(self, path, **params):
        for attempt in range(2):
            try:
                data = http("GET", f"https://api.deezer.com/{path}", params=params)
            except (urllib.error.URLError, ValueError) as e:
                log(f"   Deezer {path}: {e}")
                return {}
            finally:
                time.sleep(0.15)
            error = data.get("error") if isinstance(data, dict) else None
            if not error:
                return data
            if error.get("code") == 4 and attempt == 0:  # limit zapytań
                time.sleep(5)
                continue
            return {}
        return {}

    def artist_id(self, name):
        key = norm(name)
        if key not in self.ids:
            found = as_list(self.call("search/artist", q=name, limit=5).get("data"))
            self.ids[key] = next((a["id"] for a in found if norm(a["name"]) == key), None)
        return self.ids[key]

    def similar_tracks(self, artist, title):
        return []

    def similar_artists(self, artist):
        artist_id = self.artist_id(artist)
        if not artist_id:
            return []
        related = as_list(self.call(f"artist/{artist_id}/related", limit=SIMILAR_PER_ARTIST).get("data"))
        result = []
        for rank, a in enumerate(related[:SIMILAR_PER_ARTIST]):
            self.ids[norm(a["name"])] = a["id"]
            result.append((a["name"], 1 - rank / (SIMILAR_PER_ARTIST * 1.5)))
        return result

    def top_tracks(self, artist, limit):
        artist_id = self.artist_id(artist)
        if not artist_id:
            return []
        top = as_list(self.call(f"artist/{artist_id}/top", limit=limit).get("data"))
        return [(t["title"], t.get("duration")) for t in top][:limit]


def recommend(seed_tracks, seed_artists):
    source = LastFm() if LASTFM_API_KEY else Deezer()
    log(f"Szukam podobnych utworów ({'Last.fm' if LASTFM_API_KEY else 'Deezer'})...")
    candidates = {}

    def add(artist, title, score, via, duration=None):
        if not artist or not title or score <= 0:
            return
        key = track_key(artist, title)
        c = candidates.setdefault(key, {"key": key, "artist": artist, "title": title, "score": 0.0,
                                        "best": 0.0, "via": via, "duration": duration})
        c["score"] += score
        c["duration"] = c["duration"] or duration
        if score > c["best"]:
            c["best"], c["via"] = score, via

    for seed in seed_tracks:
        for artist, title, match, duration in source.similar_tracks(seed["artist"], seed["title"]):
            add(artist, title, seed["weight"] * match, f"{seed['artist']} — {seed['title']}", duration)
    for seed in seed_artists:
        name = seed["name"]
        similar_artists = source.similar_artists(name)
        if not similar_artists and "," in name:  # tag „Ofenbach, Nick Waterhouse” -> „Ofenbach”
            name = name.split(",")[0].strip()
            similar_artists = source.similar_artists(name)
        for similar, match in similar_artists:
            for i, (title, duration) in enumerate(source.top_tracks(similar, 5)):
                add(similar, title, seed["weight"] * match * 0.8 ** i, name, duration)
        for i, (title, duration) in enumerate(source.top_tracks(name, 15)):
            add(name, title, seed["weight"] * 0.6 * 0.85 ** i, name, duration)
    return list(candidates.values())


# ── slskd ────────────────────────────────────────────────────────────────────

class Slskd:
    def __init__(self):
        self.headers = {}
        self.last_search, self.last_state = 0.0, ""
        if SLSKD_API_KEY:
            self.headers["X-API-Key"] = SLSKD_API_KEY
        elif SLSKD_USER and SLSKD_PASSWORD:
            session = http("POST", f"{SLSKD_URL}/api/v0/session",
                           body={"username": SLSKD_USER, "password": SLSKD_PASSWORD})
            self.headers["Authorization"] = f"Bearer {session['token']}"
        else:
            raise SystemExit("Ustaw SLSKD_API_KEY albo SLSKD_USER i SLSKD_PASSWORD.")

    def api(self, method, path, **kwargs):
        return http(method, f"{SLSKD_URL}/api/v0/{path}", headers=self.headers, **kwargs)

    def search(self, text):
        wait = self.last_search + SEARCH_PAUSE - time.time()
        if wait > 0:
            time.sleep(wait)
        self.last_search = time.time()
        search_id = str(uuid.uuid4())
        # searchTimeout w milisekundach: slskd przekazuje go wprost do Soulseek.NET,
        # choć komentarz w jego API mówi o sekundach.
        body = {"id": search_id, "searchText": text, "searchTimeout": 15000, "responseLimit": 100,
                "fileLimit": 10000, "filterResponses": True}
        for attempt in range(6):
            try:
                self.api("POST", "searches", body=body)
                break
            except urllib.error.HTTPError as e:
                # 409/500: slskd nie jest akurat połączony z serwerem Soulseek; 429: za dużo naraz
                if e.code not in (409, 429, 500):
                    raise
                if attempt == 5:
                    raise RuntimeError(f"slskd odrzuca wyszukiwania (HTTP {e.code}) — pewnie nie jest połączony "
                                       "z serwerem Soulseek. Sprawdź: docker logs --tail 50 piterfy-slskd") from e
                if attempt == 0 and e.code != 429:
                    log("   slskd nie jest teraz połączony z Soulseek — czekam do 2,5 min...")
                time.sleep(30)
        search, deadline = {}, time.time() + 180
        while time.time() < deadline:
            time.sleep(3)
            search = self.api("GET", f"searches/{search_id}")
            if search.get("isComplete") or "Completed" in str(search.get("state", "")):
                break
        self.last_state = str(search.get("state", ""))
        if "Errored" in self.last_state:  # przerywamy, żeby nie oznaczać utworów jako „brak w Soulseek”
            raise RuntimeError(f"Wyszukiwanie w slskd skończyło się błędem ({self.last_state}) — "
                               "sprawdź połączenie slskd z Soulseek: docker logs --tail 50 piterfy-slskd")
        responses = self.api("GET", f"searches/{search_id}/responses") or []
        try:
            self.api("DELETE", f"searches/{search_id}")
        except urllib.error.URLError:
            pass
        return responses

    def enqueue(self, username, filename, size):
        self.api("POST", f"transfers/downloads/{urllib.parse.quote(username, safe='')}",
                 body=[{"filename": filename, "size": size}])

    def downloads(self):
        result = {}
        for user in as_list(self.api("GET", "transfers/downloads")):
            for directory in as_list(user.get("directories")):
                for f in as_list(directory.get("files")):
                    result[(user["username"], f["filename"])] = f
        return result

    def cancel(self, username, transfer_id):
        try:
            self.api("DELETE", f"transfers/downloads/{urllib.parse.quote(username, safe='')}/{transfer_id}",
                     params={"remove": "true"})
        except urllib.error.URLError:
            pass


def artist_variants(artist):
    """'Jessie J, Ariana Grande & Nicki Minaj' -> [cała nazwa, 'Jessie J'] — pliki zwykle mają tylko pierwszego."""
    artist = primary_artist(artist)
    first = artist.split(",")[0].strip()
    return [artist] + ([first] if first and first != artist else [])


def search_texts(cand):
    texts = [f"{artist} {clean_title(cand['title'])}" for artist in artist_variants(cand["artist"])]
    plain = [" ".join(re.sub(r"[^\w\s]|_", " ", t.casefold()).split()) for t in texts]
    return list(dict.fromkeys(plain + [norm(t) for t in texts]))[:3]  # potem wersje bez ogonków


def file_options(responses, cand):
    """Pasujące pliki z wyników wyszukiwania, od najlepszego."""
    title = clean_title(cand["title"])
    want_title = words(title)
    artists = artist_variants(cand["artist"])
    artist_words = set().union(*(words(a) for a in artists))
    allowed_noise = words(cand["title"]) | words(cand["artist"])
    if not want_title:
        return []
    options = []
    for response in as_list(responses):
        if response.get("username") in cand.get("avoid_users", ()):  # już raz dał fałszywą kopię
            continue
        for f in as_list(response.get("files")):
            path = f.get("filename") or ""
            parts = re.split(r"[\\/]", path)
            stem, _, ext = parts[-1].rpartition(".")
            ext = (f.get("extension") or ext).lower().lstrip(".")
            folder = parts[-2] if len(parts) > 1 else ""
            if ext not in FORMATS or f.get("isLocked") or (f.get("size") or 0) > MAX_FILE_SIZE:
                continue
            if ext in LOSSY and (f.get("bitRate") or MIN_BITRATE) < MIN_BITRATE:
                continue
            name_words = words(stem)
            if not want_title <= name_words or not any(contains(" ".join(parts[-3:]), a) for a in artists):
                continue
            if ((name_words | words(folder)) & NOISE_WORDS) - allowed_noise:
                continue
            if cand.get("duration") and f.get("length") and abs(f["length"] - cand["duration"]) > 15:
                continue
            extra = {w for w in name_words - want_title - artist_words - words(folder) if not w.isdigit()}
            if len(extra) > 3:  # pewnie inny utwór o podobnym tytule
                continue
            # po fałszywym FLAC-u bezpieczniej wziąć MP3 320 — ten sam fałszywy plik często krąży u wielu osób
            format_rank = (ext not in LOSSY, FORMATS.index(ext)) if cand.get("prefer_lossy") else (FORMATS.index(ext),)
            options.append({"user": response["username"], "file": path, "size": f.get("size") or 0, "ext": ext,
                            "rank": (len(extra), not response.get("hasFreeUploadSlot"), format_rank,
                                     response.get("queueLength") or 0, -(response.get("uploadSpeed") or 0))})
    options.sort(key=lambda o: o["rank"])
    return options


def queue_next(sl, job):
    """Zleca pobranie od kolejnego użytkownika, którego jeszcze nie próbowaliśmy."""
    while len(job["tried"]) < MAX_ATTEMPTS:
        option = next((o for o in job["options"] if o["user"] not in job["tried"]), None)
        if not option:
            break
        job["tried"].append(option["user"])
        try:
            sl.enqueue(option["user"], option["file"], option["size"])
        except urllib.error.URLError as e:
            log(f"   slskd nie przyjął pobrania od {option['user']}: {e}")
            continue
        job.update(user=option["user"], file=option["file"], ext=option["ext"], since=time.time(), status="pending")
        return True
    job["status"] = "failed" if job["options"] else "notfound"
    return False


CANARY_QUERY = os.environ.get("CANARY_QUERY", "daft punk one more time")  # utwór, który w Soulseek ma na pewno każdy


def soulseek_alive(sl):
    """Czy Soulseek zwraca wyniki dla popularnego utworu — odróżnia awarię od serii rzadkich utworów."""
    try:
        return bool(sl.search(CANARY_QUERY))
    except (RuntimeError, urllib.error.URLError):
        return False


def search_and_queue(sl, cand):
    options, responded = [], False
    for text in search_texts(cand):
        responses = sl.search(text)
        responded = responded or bool(responses)
        options = file_options(responses, cand)
        if options:
            break
    # empty: nikt w ogóle nie odpowiedział — przy popularnych utworach to znak awarii, nie braku utworu
    job = {**cand, "options": options, "tried": [], "status": "notfound", "empty": not responded}
    if options:
        queue_next(sl, job)
    return job


def wait_for_downloads(sl, jobs):
    deadline = time.time() + DOWNLOAD_TIMEOUT
    while time.time() < deadline:
        active = [j for j in jobs if j["status"] == "pending"]
        if not active:
            return
        time.sleep(POLL_SECONDS)
        transfers = sl.downloads()
        for job in active:
            transfer = transfers.get((job["user"], job["file"]))
            state = transfer.get("state", "") if transfer else ""
            waited = time.time() - job["since"]
            if "Succeeded" in state:
                job["status"] = "done"
                log(f"   ✓ {job['artist']} — {job['title']} ({job['ext']})")
            elif ("Completed" in state or (not transfer and waited > 300)
                  or ("InProgress" not in state and waited > STALL_SECONDS)):
                if transfer and "Completed" not in state:
                    sl.cancel(job["user"], transfer["id"])
                log(f"   ✗ {job['artist']} — {job['title']} od {job['user']} ({state or 'zniknęło z kolejki'})")
                if queue_next(sl, job):
                    log(f"     próbuję u {job['user']}")
    still = sum(j["status"] == "pending" for j in jobs)
    if still:
        log(f"Minął limit czasu — w kolejce slskd zostaje {still}, dociągną się później.")


# ── wybór utworów ────────────────────────────────────────────────────────────

def skip_from_history(entry):
    if not entry:
        return False
    if entry.get("status") == "fake":  # poprzednia kopia była fałszywa — szukamy lepszej
        return entry.get("fake_count", 0) >= MAX_FAKES
    if entry.get("status") in ("notfound", "failed"):
        tried = datetime.fromisoformat(entry.get("date", "2000-01-01"))
        return datetime.now() - tried < timedelta(days=RETRY_AFTER_DAYS)
    return True  # już pobrane albo w kolejce — także jeśli potem je usunąłeś


def pick_tracks(nd, sl, candidates, seed_tracks, history, dry_run):
    known = nd.known_artists()
    seed_keys = {track_key(t["artist"], t["title"]) for t in seed_tracks}
    blocked = {norm(a) for a in BLOCKED_ARTISTS}
    feedback = artist_feedback(history)
    pools = {"new": [], "familiar": []}
    for c in candidates:
        artist = norm(primary_artist(c["artist"]))
        if c["key"] in seed_keys or artist in blocked or skip_from_history(history.get(c["key"])):
            continue
        # losowość, żeby kolejne przebiegi się różniły, i Twoje reakcje na wcześniejsze odkrycia tego wykonawcy
        c["score"] *= random.uniform(0.75, 1.25) * feedback_factor(feedback[artist])
        pools["familiar" if artist in known else "new"].append(c)
    for pool in pools.values():
        pool.sort(key=lambda c: c["score"], reverse=True)
    log(f"Kandydaci: {len(pools['new'])} od nowych wykonawców, {len(pools['familiar'])} od znanych.")

    iterators = {name: iter(pool) for name, pool in pools.items()}
    got, tried, per_artist, jobs = Counter(), Counter(), Counter(), []
    budget = [TRACKS_PER_RUN * 4]  # maksymalnie tyle wyszukiwań w Soulseek na jeden przebieg
    empty_streak = [0]

    def take(pool, want, limit=None):
        # limit: tyle wyszukiwań najwyżej na tę pulę w tym przejściu — żeby rzadkie utwory nowych wykonawców
        # nie zjadły całego budżetu, zanim w ogóle sięgniemy po utwory znanych (łatwiejsze do znalezienia)
        spent = 0
        while got[pool] < want and got.total() < TRACKS_PER_RUN and budget[0] > 0 and (limit is None or spent < limit):
            cand = next(iterators[pool], None)
            if cand is None:
                return
            artist = norm(primary_artist(cand["artist"]))
            if per_artist[artist] >= MAX_PER_ARTIST or nd.find_song(cand["artist"], cand["title"]):
                continue
            if dry_run:
                job = {**cand, "status": "pending"}
            else:
                budget[0] -= 1
                spent += 1
                tried[pool] += 1
                log(f"🔎 {cand['artist']} — {cand['title']}")
                try:
                    job = search_and_queue(sl, cand)
                except (RuntimeError, urllib.error.URLError) as e:
                    if not any(j["status"] == "pending" for j in jobs):
                        raise  # nic jeszcze nie zlecone — cały przebieg spróbuje ponownie za godzinę
                    log(f"Przerywam wyszukiwanie: {e}")
                    log("Dokończę to, co już jest w kolejce slskd.")
                    budget[0] = 0
                    return
                if job["status"] == "pending":
                    log(f"   → {job['user']} ({job['ext']})")
                else:
                    log("   brak w Soulseek" if job["status"] == "notfound" else "   slskd nie przyjął żadnego pobrania")
                empty_streak[0] = empty_streak[0] + 1 if job.get("empty") else 0
                if empty_streak[0] >= EMPTY_SEARCH_LIMIT and soulseek_alive(sl):
                    # Soulseek odpowiada na popularny utwór, więc to po prostu rzadkie utwory (np. mało znani polscy
                    # wykonawcy) — to nie awaria. Zostają zapisane jako „brak w Soulseek”, a szukamy dalej.
                    log(f"   {EMPTY_SEARCH_LIMIT} utworów z rzędu bez wyniku, ale Soulseek odpowiada — to rzadkie utwory, szukam dalej.")
                    empty_streak[0] = 0
                if empty_streak[0] >= EMPTY_SEARCH_LIMIT:
                    # Seria wyszukiwań bez ani jednej odpowiedzi, także na popularny utwór, to awaria Soulseek —
                    # nie zapisujemy ich jako „brak w Soulseek”, żeby nie przepadły na RETRY_AFTER_DAYS dni.
                    jobs[:] = [j for j in jobs if not j.get("empty")]
                    message = (f"{EMPTY_SEARCH_LIMIT} wyszukiwań z rzędu bez żadnej odpowiedzi — Soulseek nie zwraca "
                               "wyników (blokada wyszukiwania albo problem z siecią slskd)")
                    if not any(j["status"] == "pending" for j in jobs):
                        raise RuntimeError(message)
                    log(f"Przerywam wyszukiwanie: {message}. Dokończę to, co już jest w kolejce slskd.")
                    budget[0] = 0
                    return
            job["pool"] = pool
            jobs.append(job)
            if job["status"] == "pending":
                got[pool] += 1
                per_artist[artist] += 1

    want_familiar = round(TRACKS_PER_RUN * FAMILIAR_SHARE)
    per_pool = TRACKS_PER_RUN * 2  # połowa budżetu na pulę w pierwszym przejściu
    take("new", TRACKS_PER_RUN - want_familiar, per_pool)
    take("familiar", want_familiar, per_pool)
    # dobierz resztę: najpierw z puli, która lepiej trafia w Soulseek
    for pool in sorted(("new", "familiar"), key=lambda p: got[p] / max(tried[p], 1), reverse=True):
        take(pool, TRACKS_PER_RUN)
    return jobs


# ── reakcje na odkrycia, kosz, miejsce na dysku ─────────────────────────────

VERDICT_POINTS = {"liked": 1.0, "disliked": -1.0, "trash-rated": -2.0, "trash-unplayed": -0.5}


def load_spectrum():
    try:
        return json.loads(SPECTRUM_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def file_quality(nd, song_id, spectrum):
    """Wynik analizy widma pliku (spectrum.py w kontenerze beets) albo None, jeśli jeszcze go nie policzono."""
    try:
        path = nd.real_path(song_id)
        return spectrum.get(path.relative_to(MUSIC_DIR).as_posix())
    except (RuntimeError, urllib.error.URLError, KeyError, ValueError):
        return None


def review_discoveries(nd, history):
    """Sprawdza, jak przyjąłeś wcześniejsze odkrycia.

    Polubione (★, ocena 4–5 albo min. LIKED_PLAYS odsłuchy) wzmacniają rekomendacje. Ocenione na 1★ od razu,
    a nieodsłuchane po CLEANUP_DAYS dniach trafiają do kosza, a ich wykonawcy pojawiają się rzadziej.
    Fałszywe kopie (FLAC z MP3, podbite MP3) idą do kosza i ten sam przebieg szuka ich lepszej wersji.
    """
    today = date.today()
    liked, trashed, fakes = [], [], []
    spectrum = load_spectrum()
    for entry in history.values():
        age = (today - date.fromisoformat(entry["date"])).days
        if entry["status"] not in ("done", "pending") or age > REVIEW_DAYS:
            continue
        song = None
        if entry.get("song_id"):
            try:
                song = nd.call("getSong", id=entry["song_id"]).get("song")
            except RuntimeError:
                entry.pop("song_id")
        if not song:
            song_id = nd.find_song(entry["artist"], entry["title"])
            if not song_id:
                continue  # jeszcze nie w bibliotece albo usunięty ręcznie
            song = nd.call("getSong", id=song_id).get("song", {})
            entry["song_id"] = song_id
        entry["status"] = "done"  # także te, które dociągnęły się po limicie czasu
        rating, plays, starred = song.get("userRating") or 0, song.get("playCount") or 0, bool(song.get("starred"))
        quality = None if entry.get("quality") else file_quality(nd, entry["song_id"], spectrum)
        if quality:
            entry["quality"] = quality["verdict"]
            # podmieniamy tylko, jeśli nie dałeś mu ★ ani oceny — wtedy nowy plik odebrałby Ci je
            if quality["verdict"] in BAD_QUALITY and not starred and not rating and move_to_trash(nd, entry["song_id"]):
                entry.update(status="fake", fake_count=entry.get("fake_count", 0) + 1,
                             bad_users=entry.get("bad_users", []) + [entry.get("user")],
                             fake_info={"user": entry.get("user"), "cutoff": quality.get("cutoff"),
                                        "source_kbps": quality.get("source_kbps"), "verdict": quality["verdict"]})
                for field in ("song_id", "quality", "verdict"):
                    entry.pop(field, None)
                fakes.append(entry)
                continue
        if rating == 1:
            verdict = "trash-rated"
        elif CLEANUP_DAYS and age >= CLEANUP_DAYS and not plays and not starred and rating < 3:
            verdict = "trash-unplayed"
        elif starred or rating >= 4 or plays >= LIKED_PLAYS:
            verdict = "liked"
        elif rating == 2:
            verdict = "disliked"
        else:
            verdict = None
        if verdict and verdict.startswith("trash"):
            if not move_to_trash(nd, entry["song_id"]):
                continue
            entry["status"] = "trashed"
            trashed.append(entry)
        elif verdict == "liked" and entry.get("verdict") != "liked":
            liked.append(entry)
        if verdict:
            entry["verdict"] = verdict
        else:
            entry.pop("verdict", None)
    return liked, trashed, fakes


def artist_feedback(history):
    """Bilans reakcji na odkrycia każdego wykonawcy: + polubione, − 1★ i pominięte."""
    points = Counter()
    for entry in history.values():
        points[norm(primary_artist(entry["artist"]))] += VERDICT_POINTS.get(entry.get("verdict"), 0)
    return points


def feedback_factor(points):
    return 1.3 ** min(points, 3) if points >= 0 else 0.5 ** min(-points, 4)


def move_to_trash(nd, song_id):
    try:
        source = nd.real_path(song_id)
    except (RuntimeError, urllib.error.URLError, KeyError, ValueError) as e:
        log(f"   Nie znam ścieżki pliku ({e}) — zostawiam.")
        return False
    if MUSIC_DIR not in source.parents or not source.is_file():
        log(f"   Nie ma pliku {source} w {MUSIC_DIR} — zostawiam.")
        return False
    target = TRASH_DIR / source.relative_to(MUSIC_DIR)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(source, target)
    os.utime(target)  # od tej chwili liczy się DISCOVER_TRASH_DAYS
    folder = source.parent
    while folder != MUSIC_DIR and not any(folder.iterdir()):  # sprzątnij opustoszałe foldery
        folder.rmdir()
        folder = folder.parent
    return True


def empty_trash():
    """Kasuje pliki, które leżą w koszu dłużej niż TRASH_DAYS dni."""
    if not TRASH_DAYS or not TRASH_DIR.is_dir():
        return 0
    limit, removed = time.time() - TRASH_DAYS * 86400, 0
    for path in sorted(TRASH_DIR.rglob("*"), reverse=True):  # najpierw zawartość, potem foldery
        if path.is_file() and path.stat().st_mtime < limit:
            path.unlink()
            removed += 1
        elif path.is_dir() and not any(path.iterdir()):
            path.rmdir()
    return removed


def free_gb():
    for path in (MUSIC_DIR, DATA_DIR):
        if path.exists():
            return shutil.disk_usage(path).free / 1024 ** 3
    return None


def wait_for_import(jobs):
    """Czeka, aż beets przeniesie świeżo pobrane pliki z folderu pobrań do biblioteki."""
    if not INCOMING_DIR:
        return
    names = {re.split(r"[\\/]", j["file"])[-1] for j in jobs if j["status"] == "done"}
    log("Czekam, aż beets przeniesie pliki do biblioteki...")
    deadline = time.time() + 20 * 60
    while True:
        waiting = [p for p in Path(INCOMING_DIR).rglob("*") if p.name in names]
        if not waiting:
            return
        if time.time() > deadline:
            log(f"beets nie przeniósł jeszcze {len(waiting)} plików — trafią do playlist przy następnym przebiegu.")
            return
        time.sleep(30)


# ── stan, playlista, raport ──────────────────────────────────────────────────

def load_state():
    try:
        return json.loads((DATA_DIR / "state.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}


def save_state(state):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = DATA_DIR / "state.json.tmp"
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(DATA_DIR / "state.json")


def remember(history, jobs):
    today = datetime.now().date().isoformat()
    for job in jobs:
        old = history.get(job["key"], {})
        history[job["key"]] = {"artist": job["artist"], "title": job["title"], "status": job["status"],
                               "date": today, "via": job["via"], "user": job.get("user"), "file": job.get("file")}
        for field in ("bad_users", "fake_count", "fake_info"):  # pamięć o fałszywych kopiach tego utworu
            if field in old:
                history[job["key"]][field] = old[field]


def update_playlist(nd, history):
    if not PLAYLIST_NAME:
        return
    recent = sorted((h for h in history.values() if h["status"] in ("done", "pending")),
                    key=lambda h: h["date"], reverse=True)
    song_ids = []
    for entry in recent:
        if len(song_ids) >= PLAYLIST_SIZE:
            break
        song_id = nd.find_song(entry["artist"], entry["title"])
        if song_id and song_id not in song_ids:
            song_ids.append(song_id)
    if not song_ids:
        log("Playlista: nic jeszcze nie trafiło do biblioteki Navidrome "
            "(sprawdź, czy slskd zapisuje pobrane pliki w folderze, który skanuje Navidrome).")
        return
    playlists = as_list(nd.call("getPlaylists").get("playlists", {}).get("playlist"))
    existing = next((p for p in playlists if p.get("name") == PLAYLIST_NAME), None)
    if existing:
        nd.call("createPlaylist", playlistId=existing["id"], songId=song_ids)
        playlist_id = existing["id"]
    else:
        playlist_id = nd.call("createPlaylist", name=PLAYLIST_NAME, songId=song_ids).get("playlist", {}).get("id")
    if playlist_id:
        comment = f"Podobne do tego, czego słuchasz. Aktualizacja: {datetime.now():%Y-%m-%d %H:%M}"
        try:
            nd.call("updatePlaylist", playlistId=playlist_id, comment=comment)
        except RuntimeError:
            pass
    log(f"Playlista „{PLAYLIST_NAME}” — utworów: {len(song_ids)}.")


def update_daily_playlists(nd, state, started, jobs):
    """Osobna playlista z utworami z każdego dnia, np. „Odkrywaj 12.10”.

    Przy każdym przebiegu odświeża też playlisty z poprzednich dni (dochodzą utwory, które
    ściągnęły się później) i usuwa te starsze niż DAILY_KEEP_DAYS. Same utwory zostają w bibliotece.
    """
    if not DAILY_PLAYLIST:
        return
    daily = state.setdefault("daily_playlists", [])
    keys = [j["key"] for j in jobs if j["status"] in ("done", "pending")]
    today = next((p for p in daily if p["date"] == started.date().isoformat()), None)
    if today:
        today["keys"] += [k for k in keys if k not in today["keys"]]
    elif keys:
        daily.append({"name": f"{DAILY_PLAYLIST} {started:%d.%m}", "date": started.date().isoformat(),
                      "keys": keys, "id": None})

    for playlist in list(daily):
        if DAILY_KEEP_DAYS and (started.date() - date.fromisoformat(playlist["date"])).days >= DAILY_KEEP_DAYS:
            if playlist["id"]:
                try:
                    nd.call("deletePlaylist", id=playlist["id"])
                except RuntimeError:
                    pass
            daily.remove(playlist)
            log(f"Usunięta stara playlista „{playlist['name']}”.")
            continue
        song_ids = []
        for key in playlist["keys"]:
            entry = state["history"].get(key)
            song_id = entry and nd.find_song(entry["artist"], entry["title"])
            if song_id and song_id not in song_ids:
                song_ids.append(song_id)
        if not song_ids:
            continue
        try:
            if playlist["id"]:
                nd.call("createPlaylist", playlistId=playlist["id"], songId=song_ids)
            else:
                reply = nd.call("createPlaylist", name=playlist["name"], songId=song_ids)
                playlist["id"] = reply.get("playlist", {}).get("id")
        except RuntimeError as e:  # np. playlista usunięta ręcznie w Navidrome — nie odtwarzamy jej
            log(f"Pomijam playlistę „{playlist['name']}”: {e}")
            daily.remove(playlist)
            continue
        if playlist["date"] == started.date().isoformat():
            log(f"Playlista „{playlist['name']}” — utworów: {len(song_ids)}.")


def report_lines(started, seed_artists, jobs, liked, trashed, free, low_space, fakes=()):
    lines = [f"# Odkrywanie {started:%Y-%m-%d %H:%M}", ""]
    if low_space:
        lines += [f"⚠️ Wolne miejsce: {free:.0f} GB (limit {MIN_FREE_GB:.0f} GB) — dziś nic nie pobrałem.", ""]
    lines += ["Na podstawie: " + ", ".join(a["name"] for a in seed_artists[:8]), ""]
    if DAILY_PLAYLIST and any(j["status"] in ("done", "pending") for j in jobs):
        lines += [f"Playlista w Piterfy: „{DAILY_PLAYLIST} {started:%d.%m}”", ""]
    groups = [("Pobrane", "done"), ("Wciąż w kolejce slskd", "pending"),
              ("Nie udało się pobrać", "failed"), ("Nie ma w Soulseek", "notfound")]
    for header, status in groups:
        items = [j for j in jobs if j["status"] == status]
        if items:
            lines.append(f"## {header} ({len(items)})")
            lines += [f"- {j['artist']} — {j['title']}  ·  bo słuchasz: {j['via']}" for j in items]
            lines.append("")
    if liked:
        lines.append(f"## Spodobały Ci się ({len(liked)}) — będzie więcej podobnych")
        lines += [f"- {e['artist']} — {e['title']}" for e in liked]
        lines.append("")
    if trashed:
        reasons = {"trash-rated": "ocena 1★", "trash-unplayed": f"nieodsłuchane przez {CLEANUP_DAYS} dni"}
        lines.append(f"## Przeniesione do kosza ({len(trashed)})")
        lines += [f"- {e['artist']} — {e['title']}  ·  {reasons[e['verdict']]}" for e in trashed]
        lines.append("")
    if fakes:
        lines.append(f"## Fałszywa jakość — do kosza, szukam lepszej kopii ({len(fakes)})")
        for e in fakes:
            info = e.get("fake_info") or {}
            what = "FLAC zrobiony z MP3" if info.get("verdict") == "fake" else "MP3 podbite z niższego bitrate"
            cutoff = f", dźwięk tylko do {info['cutoff'] / 1000:.1f} kHz".replace(".", ",") if info.get("cutoff") else ""
            lines.append(f"- {e['artist']} — {e['title']}  ·  {what} (ok. {info.get('source_kbps', '?')} kb/s{cutoff}), "
                         f"od {info.get('user') or '?'}")
        lines.append("")
    if free is not None:
        lines.append(f"Wolne miejsce na dysku: {free:.0f} GB")
    return lines


def write_report(started, lines):
    try:
        REPORTS_DIR.mkdir(parents=True, exist_ok=True)
        path = REPORTS_DIR / f"discover-{started:%Y-%m-%d_%H%M}.md"
        path.write_text("\n".join(lines), encoding="utf-8")
        log(f"Raport: {path}")
    except OSError as e:
        log(f"Nie zapisałem raportu: {e}")


# ── maile ────────────────────────────────────────────────────────────────────

def smtp_settings():
    """Konto SMTP z ~/.msmtprc (to samo, którego używa msmtp na serwerze); zmienne SMTP_* mają pierwszeństwo."""
    accounts, current, default = {}, None, None
    if MSMTPRC.is_file():
        for line in MSMTPRC.read_text(encoding="utf-8").splitlines():
            key, value = (re.split(r"\s+", line.strip(), maxsplit=1) + [""])[:2]
            value = value.strip().strip('"')
            if not key or key.startswith("#"):
                continue
            if key == "defaults":
                current = accounts.setdefault("", {})
            elif key == "account" and value.startswith("default") and ":" in value:
                default = value.split(":", 1)[1].strip()
            elif key == "account":
                current = accounts.setdefault(value, {})
            elif current is not None:
                current[key] = value
    name = default or ("default" if "default" in accounts else next((a for a in accounts if a), ""))
    conf = {**accounts.get("", {}), **accounts.get(name, {})}
    for key in ("host", "port", "user", "password", "from", "tls", "tls_starttls"):
        conf[key] = os.environ.get(f"SMTP_{key.upper()}") or conf.get(key)
    return conf


def send_mail(subject, body, html_body=None):
    if not MAIL_TO:
        return
    conf = smtp_settings()
    if not conf.get("host"):
        log("Mail: brak ustawień SMTP (zamontuj ~/.msmtprc albo ustaw SMTP_HOST).")
        return
    if conf.get("passwordeval") and not conf.get("password"):
        log("Mail: msmtprc bierze hasło z passwordeval — ustaw SMTP_PASSWORD w .env.")
        return
    message = EmailMessage()
    message["Subject"], message["To"] = subject, MAIL_TO
    message["From"] = conf.get("from") or conf.get("user") or MAIL_TO
    message.set_content(body)
    if html_body:
        message.add_alternative(html_body, subtype="html")
    tls = conf.get("tls") == "on"
    starttls = (conf.get("tls_starttls") or "on") != "off"
    port = int(conf.get("port") or (465 if tls and not starttls else 587 if tls else 25))
    try:
        if tls and not starttls:
            server = smtplib.SMTP_SSL(conf["host"], port, timeout=30)
        else:
            server = smtplib.SMTP(conf["host"], port, timeout=30)
            if tls:
                server.starttls()
        with server:
            if conf.get("user") and conf.get("auth") != "off":
                server.login(conf["user"], conf.get("password") or "")
            server.send_message(message)
        log(f"Wysłany mail: {subject}")
    except (OSError, smtplib.SMTPException) as e:
        log(f"Nie wysłałem maila „{subject}”: {e}")


def check_slskd(state):
    """Mail, gdy slskd od SLSKD_DOWN_ALERT_MIN minut nie jest zalogowany do Soulseek, i drugi, gdy wróci."""
    alerts = state.setdefault("alerts", {})
    try:
        server = Slskd().api("GET", "server") or {}
        ok, problem = bool(server.get("isLoggedIn")), f"stan: {server.get('state', '?')}"
    except (urllib.error.URLError, OSError, ValueError, KeyError) as e:
        ok, problem = False, f"slskd nie odpowiada: {e}"
    if ok:
        if alerts.pop("slskd_alerted", None):
            send_mail("✅ slskd znów połączony z Soulseek", "Połączenie wróciło, odkrywanie działa normalnie.")
        alerts.pop("slskd_down_since", None)
        return
    minutes = int((time.time() - alerts.setdefault("slskd_down_since", time.time())) / 60)
    if minutes >= SLSKD_DOWN_ALERT_MIN and not alerts.get("slskd_alerted"):
        alerts["slskd_alerted"] = True
        send_mail("⚠️ slskd nie jest połączony z Soulseek",
                  f"slskd od {minutes} min nie jest zalogowany do Soulseek ({problem}), więc nic się nie pobiera.\n\n"
                  "Sprawdź: docker logs --tail 50 piterfy-slskd\n"
                  "„The wait timed out” zaraz po „Connected” zwykle oznacza czasową blokadę na serwerze Soulseek.")


# ── przebieg ─────────────────────────────────────────────────────────────────

def run(dry_run=False):
    started = datetime.now()
    log("════ Odkrywanie nowej muzyki ════")
    nd = Navidrome()
    state = load_state()
    history = state.setdefault("history", {})

    liked, trashed, fakes = [], [], []
    if not dry_run:
        liked, trashed, fakes = review_discoveries(nd, history)
        removed = empty_trash()
        if liked or trashed or removed:
            log(f"Twoje reakcje — polubione: {len(liked)}, do kosza: {len(trashed)}; skasowane z kosza: {removed}.")
        save_state(state)
        if fakes:
            log(f"Fałszywa jakość (FLAC z MP3 / podbite MP3) — do kosza: {len(fakes)}; poszukam lepszych kopii.")
            nd.rescan()  # inaczej Navidrome wciąż „ma” te utwory i nie dałoby się ich pobrać ponownie

    seed_tracks, seed_artists = listening_profile(nd, history)
    log("Ostatnio słuchasz: " + ", ".join(f"{a['name']} ({a['weight']:.2f})" for a in seed_artists))

    free = free_gb()
    low_space = free is not None and free < MIN_FREE_GB
    jobs = []
    if low_space:
        log(f"Za mało miejsca na dysku: {free:.0f} GB (limit {MIN_FREE_GB:.0f} GB) — dziś nic nie pobieram.")
    else:
        # najpierw ponowne pobranie utworów, których poprzednia kopia okazała się fałszywa
        retry = [{"key": key, "artist": e["artist"], "title": e["title"], "score": 1e9, "best": 0.0,
                  "via": e.get("via") or "lepsza kopia", "duration": None,
                  "avoid_users": e.get("bad_users", []), "prefer_lossy": True}
                 for key, e in history.items() if e.get("status") == "fake" and not skip_from_history(e)]
        retry_keys = {c["key"] for c in retry}
        candidates = retry + [c for c in recommend(seed_tracks, seed_artists) if c["key"] not in retry_keys]
        sl = None if dry_run else Slskd()
        jobs = pick_tracks(nd, sl, candidates, seed_tracks, history, dry_run)
        if dry_run:
            log(f"Dry run — do pobrania ({len(jobs)}):")
            for job in jobs:
                log(f"   [{job['pool']:8}] {job['artist']} — {job['title']}   (bo słuchasz: {job['via']})")
        else:
            remember(history, jobs)
            save_state(state)
            queued = sum(j["status"] == "pending" for j in jobs)
            if queued:
                log(f"Zlecone pobrania: {queued}. Czekam, aż się ściągną (maks. {DOWNLOAD_TIMEOUT // 60} min)...")
                wait_for_downloads(sl, jobs)
    if dry_run:
        return

    remember(history, jobs)
    state["last_run"] = started.isoformat(timespec="minutes")
    state.setdefault("alerts", {}).pop("run_failed", None)
    save_state(state)

    if any(j["status"] == "done" for j in jobs):
        wait_for_import(jobs)
    if any(j["status"] == "done" for j in jobs) or trashed:
        log("Skanuję bibliotekę Navidrome...")
        nd.rescan()
    update_playlist(nd, history)
    update_daily_playlists(nd, state, started, jobs)
    save_state(state)

    lines = report_lines(started, seed_artists, jobs, liked, trashed, free, low_space, fakes)
    write_report(started, lines)
    done = sum(j["status"] == "done" for j in jobs)
    subject = (f"⚠️ Odkrywaj {started:%d.%m}: za mało miejsca na dysku" if low_space
               else f"🎵 Odkrywaj {started:%d.%m}: nowe utwory — {done}")
    send_mail(subject, report_formatter.generate_report_text(lines), report_formatter.generate_report_html(lines))
    log(f"Gotowe — pobrane: {done}, w kolejce: {sum(j['status'] == 'pending' for j in jobs)}, "
        f"nieudane: {sum(j['status'] in ('failed', 'notfound') for j in jobs)}.")


def is_due(state, now):
    if not state.get("last_run"):
        return True
    days = (now.date() - datetime.fromisoformat(state["last_run"]).date()).days
    return days >= EVERY_DAYS and now.hour >= RUN_HOUR


def test_search(query):
    """Jedno wyszukiwanie w Soulseek z wypisaniem, co znalazł i co przeszło przez filtry."""
    artist, _, title = query.partition(" - ")
    if not title.strip():
        raise SystemExit('Podaj w formacie: --search "Artysta - Tytuł"')
    cand = {"artist": artist.strip(), "title": title.strip(), "duration": None}
    sl = Slskd()
    for text in search_texts(cand):
        responses = as_list(sl.search(text))
        files = sum(len(as_list(r.get("files"))) for r in responses)
        log(f"„{text}”: {sl.last_state or '?'}, użytkowników: {len(responses)}, plików: {files}")
        options = file_options(responses, cand)
        log(f"Pasujących plików: {len(options)}")
        for option in options[:5]:
            log(f"   {option['user']}: {option['file']}")
        if options:
            break


def refresh_taste(state, force=False):
    """Panel gustu (taste.py) — raz na dobę, w pierwszym sprawdzeniu po północy."""
    taste_state = state.setdefault("taste", {})
    if not os.environ.get("GUST_DIR") or not force and (
            taste_state.get("date") == date.today().isoformat() or time.time() < taste_state.get("retry_at", 0)):
        return
    import taste  # osobny moduł, który sam korzysta z discover — dlatego import dopiero tutaj
    try:
        taste.generate(state=state)
        taste_state.pop("retry_at", None)
    except Exception as e:  # panel nie może zatrzymać odkrywania
        log(f"Panel gustu się nie odświeżył: {e} — spróbuję za godzinę.")
        taste_state["retry_at"] = time.time() + 3600


# ── podcasty ─────────────────────────────────────────────────────────────────

def load_podcast_shows():
    return podcasts.load_shows(PODCASTS_FILE, PODCAST_LATEST, PODCAST_KEEP_DAYS, log)


def all_native_songs(nd):
    songs, start = [], 0
    while True:
        page = as_list(nd.native("song", _start=start, _end=start + 500, _sort="title", _order="ASC"))
        songs += page
        if len(page) < 500:
            return songs
        start += 500


def podcast_space_ok():
    free = free_gb()
    return free is None or free >= MIN_FREE_GB


def refresh_podcasts(state, force=False):
    """Co PODCAST_EVERY_HOURS godzin: nowe odcinki z kanałów RSS i sprzątanie odsłuchanych (podcasts.py)."""
    if not PODCASTS_FILE.is_file():
        return
    if not force and time.time() - state.get("podcasts_checked", 0) < PODCAST_EVERY_HOURS * 3600:
        return
    changed = 0
    try:
        shows = load_podcast_shows()
        for show in shows:
            try:
                changed += podcasts.sync_show(show, MUSIC_DIR, state, log, podcast_space_ok)
            except (OSError, ValueError, urllib.error.URLError, podcasts.ET.ParseError) as e:
                log(f"Podcast „{show['name']}”: {e}")
            save_state(state)  # po każdej audycji — przerwany przebieg nie zgubi pobranych odcinków
        nd = Navidrome()
        changed += podcasts.cleanup(all_native_songs(nd), shows, state, MUSIC_DIR, log)
        if changed:
            log(f"Podcasty: zmiany w bibliotece ({changed}) — skanuję Navidrome...")
            nd.rescan()
        state["podcasts_checked"] = time.time()
    except Exception as e:  # podcasty nie mogą zatrzymać odkrywania muzyki
        log(f"Podcasty się nie odświeżyły: {e} — spróbuję przy następnym sprawdzeniu.")
        state["podcasts_checked"] = time.time() - PODCAST_EVERY_HOURS * 3600 + 1800  # ponowna próba za pół godziny


def podcast_fetch_older(name, year, pattern, execute):
    """Starsze odcinki na życzenie: --podcast-fetch audycja [--year RRRR] [--match REGEX] [--yes]."""
    shows = [s for s in load_podcast_shows() if name.casefold() in s["name"].casefold()]
    if len(shows) != 1:
        names = ", ".join(s["name"] for s in load_podcast_shows()) or "(lista w podcasts.txt jest pusta)"
        raise SystemExit(f"Podaj fragment nazwy jednej audycji. Masz: {names}")
    show, state = shows[0], load_state()
    episodes = podcasts.select_older(show, state, year, pattern)
    size = sum(e["length"] for e in episodes) / 1024 ** 2
    log(f"„{show['name']}”: {len(episodes)} odcinków do pobrania (ok. {size / 1024:.1f} GB)"
        + (f", rok {year}" if year else "") + (f", pasuje do „{pattern}”" if pattern else "") + ".")
    for e in episodes[:10]:
        log(f"   {e['date']:%Y-%m-%d}  {e['title'][:90]}")
    if len(episodes) > 10:
        log(f"   … i {len(episodes) - 10} kolejnych")
    if not execute:
        log("To tylko podgląd. Dodaj --yes, żeby pobrać.")
        return
    got = podcasts.sync_show(show, MUSIC_DIR, state, log, podcast_space_ok, episodes=episodes)
    save_state(state)
    if got:
        Navidrome().rescan()
    log(f"Pobrane: {got}. Odcinki znikną po {show['keep']} dniach od odsłuchania, tak jak pozostałe.")


def main():
    parser = argparse.ArgumentParser(description="Ściąga nowe utwory podobne do tego, czego słuchasz w Navidrome.")
    parser.add_argument("--now", action="store_true", help="uruchom jeden raz od razu")
    parser.add_argument("--dry-run", action="store_true", help="pokaż, co by pobrał, nic nie ściągaj")
    parser.add_argument("--search", metavar='"ARTYSTA - TYTUŁ"', help="sprawdź jedno wyszukiwanie w Soulseek")
    parser.add_argument("--test-mail", action="store_true", help="wyślij próbnego maila")
    parser.add_argument("--taste", action="store_true", help="odśwież panel gustu od razu")
    parser.add_argument("--podcasts", action="store_true", help="sprawdź kanały podcastów od razu")
    parser.add_argument("--podcast-fetch", metavar="AUDYCJA", help="pobierz starsze odcinki audycji (podgląd, dopóki nie dodasz --yes)")
    parser.add_argument("--year", type=int, help="z --podcast-fetch: tylko odcinki z tego roku")
    parser.add_argument("--match", metavar="REGEX", help="z --podcast-fetch: tylko odcinki z pasującym tytułem (np. serię)")
    parser.add_argument("--yes", action="store_true", help="z --podcast-fetch: naprawdę pobierz")
    args = parser.parse_args()
    if args.podcast_fetch:
        podcast_fetch_older(args.podcast_fetch, args.year, args.match, args.yes)
        return
    if args.podcasts:
        state = load_state()
        refresh_podcasts(state, force=True)
        save_state(state)
        return
    if args.taste:
        state = load_state()
        refresh_taste(state, force=True)
        save_state(state)
        return
    if args.test_mail:
        if not MAIL_TO:
            raise SystemExit("Ustaw DISCOVER_MAIL_TO w .env.")
        send_mail("🎵 piterfy-discover: próbny mail", "Jeśli to czytasz, powiadomienia działają.")
        return
    if args.search:
        try:
            test_search(args.search)
        except RuntimeError as e:
            raise SystemExit(str(e))
        return
    if args.now or args.dry_run:
        run(dry_run=args.dry_run)
        return

    every = "codziennie" if EVERY_DAYS == 1 else f"co {EVERY_DAYS} dni"
    log(f"Start: {every} od godziny {RUN_HOUR}:00, utworów na przebieg: {TRACKS_PER_RUN}.")
    import profile_api  # zdjęcia profilowe Piterfy — mały serwer w tle, do którego Caddy kieruje /piterfy-api/
    profile_api.start(NAVIDROME_URL, DATA_DIR / "avatars", log)
    retry_at = 0
    while True:
        state = load_state()
        check_slskd(state)
        refresh_taste(state)
        refresh_podcasts(state)
        save_state(state)
        if time.time() >= retry_at and is_due(state, datetime.now()):
            try:
                run()
            except Exception as e:
                known = isinstance(e, (RuntimeError, urllib.error.URLError))
                log(f"Przebieg się nie udał: {e}" if known else traceback.format_exc())
                log("Spróbuję ponownie za godzinę.")
                retry_at = time.time() + 3600
                state = load_state()
                if not state.setdefault("alerts", {}).get("run_failed"):  # jeden mail na serię niepowodzeń
                    state["alerts"]["run_failed"] = True
                    save_state(state)
                    send_mail("❌ Odkrywanie nowej muzyki się nie udało",
                              f"{e}\n\nSpróbuję ponownie za godzinę.\nLogi: docker logs --tail 100 piterfy-discover")
        time.sleep(600)


if __name__ == "__main__":
    main()
