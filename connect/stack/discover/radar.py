"""Radar nowości: nowe wydania wykonawców z Twojej biblioteki (Deezer) z przyciskiem „Dociągnij”.

Raz na dobę (`discover.refresh_radar`) bierzemy ok. 150 najważniejszych wykonawców biblioteki (odsłuchy, gwiazdki, ocena,
niedawność), szukamy ich w Deezerze, czytamy ich albumy i zostawiamy albumy, EP i single z ostatnich dni, których nie masz.
Wynik trafia do `radar.json`, który serwuje trasa GET /piterfy-api/radar. Przycisk „Dociągnij” (POST /piterfy-api/radar/fetch)
dopisuje prośbę do `radar-requests.json`, a pętla główna discovera (`discover.process_radar`) dociąga utwory albumu tym samym
mechanizmem co listy przebojów.

Moduł nie importuje discovera (żeby dało się go testować): sieć i Navidrome dostaje przez wstrzykiwane funkcje, a stan
(`state['radar_artist_ids']`) jako zwykły słownik. Prośby leżą w osobnym pliku, bo trasy HTTP działają w innym wątku niż pętla
główna, która co obieg wczytuje i zapisuje cały `state.json` (zapis z wątku HTTP by się zgubił).
"""

import json
import math
import os
import re
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import charts

FILE = "radar.json"
REQUESTS_FILE = "radar-requests.json"
RECORD_TYPES = ("album", "ep", "single")
VARIOUS = {"various artists", "rozni wykonawcy", "va", "soundtrack", "original soundtrack"}
EDITION_WORDS = re.compile(r"\b(deluxe|expanded|extended|special|anniversary|bonus|remaster(?:ed)?|edition|version|explicit|clean)\b")
TYPE_WORDS = re.compile(r"\b(ep|single|lp)\b")
_LOCK = threading.RLock()


def enabled():
    """DISCOVER_RADAR=0 (albo puste) wyłącza radar; domyślnie włączony."""
    value = os.environ.get("DISCOVER_RADAR", "all").strip().lower()
    return value not in ("", "0", "off", "false", "no")


def fill_enabled():
    try:
        return int(os.environ.get("DISCOVER_CHART_FILL", "0")) > 0
    except ValueError:
        return False


# ── czysta logika ──

def album_key(title):
    """Klucz tytułu albumu bez „(Deluxe)”, „remastered”, „EP”, „- Single” itp., żeby wersje jednego wydania były tym samym."""
    text = charts.fold(charts.clean_title(title))
    text = EDITION_WORDS.sub(" ", text)
    text = TYPE_WORDS.sub(" ", text)
    return " ".join(text.split())


def parse_day(value):
    try:
        return datetime.strptime(str(value)[:10], "%Y-%m-%d").date()
    except ValueError:
        return None


def is_recent(released, today, days):
    day = parse_day(released)
    return bool(day) and today - timedelta(days=days) <= day <= today + timedelta(days=1)


def owned_index(songs):
    """({(klucz wykonawcy, klucz albumu)}, {(klucz wykonawcy, klucz tytułu utworu)}) z utworów biblioteki."""
    albums, titles = set(), set()
    for song in songs:
        track_names = charts.artist_keys(song.get("artist"))
        album_names = charts.artist_keys(song.get("albumArtist")) or track_names  # album należy do wykonawcy albumu, nie do gościa
        album, title = album_key(song.get("album") or ""), charts.clean_title(song.get("title") or "")
        if album:
            albums.update((name, album) for name in album_names)
        if title:
            titles.update((name, title) for name in track_names | album_names)
    return albums, titles


def is_owned(artist, title, record_type, index):
    albums, titles = index
    key = album_key(title)
    for name in charts.artist_keys(artist):
        if (name, key) in albums:
            return True
        if record_type in ("single", "ep") and (name, key) in titles:  # single zwykle nazywa się jak swój utwór
            return True
    return False


def pick_new(artist, albums, index, today, days):
    """Nowe (ostatnie `days` dni) albumy/EP/single wykonawcy, których nie masz; jedna pozycja na wydanie (wersje deluxe,
    explicit i clean zlewają się w jedną; zostaje ta z większą liczbą utworów, a przy remisie najwcześniejsza)."""
    best = {}
    for album in albums or []:
        kind = str(album.get("record_type") or "").lower()
        if kind not in RECORD_TYPES or not is_recent(album.get("release_date"), today, days):
            continue
        title = str(album.get("title") or "")
        if not title or is_owned(artist, title, kind, index):
            continue
        key = album_key(title)
        entry = {"id": str(album.get("id")), "artist": artist, "title": title, "type": kind,
                 "released": str(album.get("release_date"))[:10], "cover": album.get("cover_medium") or album.get("cover") or "",
                 "tracks": int(album.get("nb_tracks") or 0), "status": "new"}
        old = best.get(key)
        if not old or (entry["tracks"], old["released"]) > (old["tracks"], entry["released"]):
            best[key] = entry
    return sorted(best.values(), key=lambda e: e["released"], reverse=True)


def top_artists(songs, limit=150, now=None):
    """Najważniejsi wykonawcy biblioteki: odsłuchy (logarytmicznie), gwiazdki, oceny 4–5 i niedawne słuchanie."""
    now = now or datetime.now(timezone.utc)
    score, names = {}, {}
    for song in songs:
        name = str(song.get("albumArtist") or song.get("artist") or "").strip()
        key = charts.fold(name)
        if not key or key in VARIOUS or (song.get("type") or "").lower() == "podcast":
            continue
        names.setdefault(key, name)
        value = math.log1p(int(song.get("playCount") or 0)) * 2 + 0.3
        if song.get("starred"):
            value += 3
        if int(song.get("rating") or 0) >= 4:
            value += 2
        played = song.get("playDate")
        if played:
            try:
                when = datetime.fromisoformat(str(played).replace("Z", "+00:00"))
                value += 2 * 0.5 ** (max(0, (now - when).days) / 60)
            except ValueError:
                pass
        score[key] = score.get(key, 0.0) + value
    ranked = sorted(score, key=lambda k: score[k], reverse=True)[:limit]
    return [names[k] for k in ranked]


def merge(previous, fresh, today, keep_days=30):
    """Nowa lista = świeże wyniki z zachowanym stanem pozycji, o które prosiłeś (queued/done/failed/disabled); dawne prośby
    znikają po `keep_days` dniach od wydania."""
    old = {item["id"]: item for item in previous or []}
    result, seen = [], set()
    for item in fresh:
        before = old.get(item["id"])
        if before and before.get("status") != "new":
            item = {**item, **{k: before[k] for k in ("status", "note", "have", "total") if k in before}}
        result.append(item)
        seen.add(item["id"])
    for item in previous or []:
        if item["id"] in seen or item.get("status") == "new":
            continue
        day = parse_day(item.get("released"))
        if day and today - day <= timedelta(days=keep_days + 60):
            result.append(item)
    return sorted(result, key=lambda e: e.get("released", ""), reverse=True)


def album_entries(tracks, artist):
    """Wpisy do `charts.match` / `discover.fill_chart` z utworów albumu z Deezera."""
    entries = []
    for i, t in enumerate(tracks or [], 1):
        entries.append({"rank": int(t.get("track_position") or i), "id": f"dz:{t.get('id')}", "title": t.get("title", ""),
                        "artist": (t.get("artist") or {}).get("name") or artist, "isrc": t.get("isrc") or "",
                        "duration": t.get("duration")})
    return entries


# ── pliki (radar.json, prośby) ──

def _read(path, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(path)


def load(data_dir):
    with _LOCK:
        items = _read(Path(data_dir) / FILE, [])
        return items if isinstance(items, list) else []


def save(data_dir, items):
    with _LOCK:
        _write(Path(data_dir) / FILE, items)


def request_fetch(data_dir, album_id, now=None):
    """Prośba „Dociągnij”: zwraca (pozycja, kod) — kod: „queued”, „already”, „disabled” albo „unknown”."""
    with _LOCK:
        items = load(data_dir)
        item = next((i for i in items if i["id"] == str(album_id)), None)
        if not item:
            return None, "unknown"
        if item.get("status") in ("queued", "done"):
            return item, "already"
        if not fill_enabled():
            item.update(status="disabled", note="Dociąganie jest wyłączone na serwerze (DISCOVER_CHART_FILL=0).")
            save(data_dir, items)
            return item, "disabled"
        item.update(status="queued")
        item.pop("note", None)
        save(data_dir, items)
        requests = _read(Path(data_dir) / REQUESTS_FILE, [])
        requests = [r for r in requests if r.get("id") != item["id"]]
        requests.append({"id": item["id"], "at": (now or datetime.now(timezone.utc)).isoformat(timespec="seconds")})
        _write(Path(data_dir) / REQUESTS_FILE, requests)
        return item, "queued"


def next_job(data_dir):
    """Najstarsza prośba o dociągnięcie, której pozycja nadal ma status „queued” (jedna prośba naraz)."""
    with _LOCK:
        items = {i["id"]: i for i in load(data_dir)}
        for request in _read(Path(data_dir) / REQUESTS_FILE, []):
            item = items.get(request.get("id"))
            if item and item.get("status") == "queued":
                return dict(item)
    return None


def finish(data_dir, album_id, status, note="", have=None, total=None):
    """Zapisuje wynik przetwarzania prośby (status, komunikat, ile utworów już jest); gotowe prośby znikają z kolejki."""
    with _LOCK:
        items = load(data_dir)
        for item in items:
            if item["id"] == str(album_id):
                item["status"] = status
                if note:
                    item["note"] = note
                else:
                    item.pop("note", None)
                if have is not None:
                    item["have"] = have
                if total is not None:
                    item["total"] = total
        save(data_dir, items)
        if status != "queued":
            requests = [r for r in _read(Path(data_dir) / REQUESTS_FILE, []) if r.get("id") != str(album_id)]
            _write(Path(data_dir) / REQUESTS_FILE, requests)


# ── odświeżanie z Deezera ──

def resolve_artist(call, name, cache, today):
    """Id wykonawcy w Deezerze (dokładne dopasowanie nazwy, przy kilku trafieniach ten z większą liczbą fanów); wynik, także
    „nie ma”, zapamiętujemy w `cache` (brak sprawdzamy ponownie po 30 dniach)."""
    key = charts.fold(name)
    entry = cache.get(key)
    if entry and (entry.get("id") or (today - parse_day(entry.get("at", "2000-01-01"))).days < 30):
        return entry.get("id")
    found = (call("search/artist", q=name, limit=5) or {}).get("data") or []
    wanted = charts.artist_keys(name)
    match = [a for a in found if charts.artist_keys(a.get("name")) & wanted]
    best = max(match, key=lambda a: int(a.get("nb_fan") or 0), default=None)
    cache[key] = {"id": best["id"] if best else None, "at": today.isoformat()}
    return cache[key]["id"]


def refresh(call, songs, state, data_dir, log, today=None, limit=150, days=60, now=None, dry_run=False):
    """Szuka nowości dla najważniejszych wykonawców i zapisuje radar.json. `call(ścieżka, **params)` to zapytanie do Deezera."""
    today = today or date.today()
    index = owned_index(songs)
    cache = state.setdefault("radar_artist_ids", {})
    fresh, checked = [], 0
    for artist in top_artists(songs, limit, now):
        artist_id = resolve_artist(call, artist, cache, today)
        if not artist_id:
            continue
        checked += 1
        albums = (call(f"artist/{artist_id}/albums", limit=50) or {}).get("data") or []
        fresh.extend(pick_new(artist, albums, index, today, days))
    for item in fresh:  # liczba utworów jest dopiero w szczegółach albumu
        if not item["tracks"]:
            item["tracks"] = int((call(f"album/{item['id']}") or {}).get("nb_tracks") or 0)
    items = merge(load(data_dir), fresh, today)
    log(f"Radar nowości: {len(fresh)} nowych wydań od {checked} wykonawców (pozycji na liście: {len(items)}).")
    if dry_run:
        for item in fresh[:20]:
            log(f"   {item['released']} {item['artist']} — {item['title']} ({item['type']}, {item['tracks']} utw.)")
    else:
        save(data_dir, items)
    return items


# ── trasy HTTP ──

def register_routes(profile_api, data_dir, log=print):
    """Dopisuje GET /piterfy-api/radar i POST /piterfy-api/radar/fetch do serwera profile_api (z autoryzacją tokenem Navidrome)."""

    def listing(handler, match, user):
        path = Path(data_dir) / FILE
        updated = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(timespec="minutes") if path.exists() else ""
        body = {"albums": load(data_dir), "updated": updated, "fill": fill_enabled()}
        handler.send(200, json.dumps(body, ensure_ascii=False, separators=(",", ":")), "application/json", {"Cache-Control": "no-cache"})

    def fetch(handler, match, user):
        try:
            album_id = str(handler.read_json().get("id") or "")
        except (ValueError, AttributeError):
            return handler.send(400, json.dumps({"error": "Zły format prośby."}), "application/json")
        item, code = request_fetch(data_dir, album_id)
        if code == "unknown":
            return handler.send(404, json.dumps({"error": "Nie znam tego wydania — odśwież listę."}), "application/json")
        if code == "queued":
            log(f"Radar: {user} prosi o dociągnięcie „{item['artist']} — {item['title']}”.")
        handler.send(200, json.dumps({"album": item, "result": code}, ensure_ascii=False, separators=(",", ":")), "application/json",
                     {"Cache-Control": "no-cache"})

    profile_api.register("GET", r"/piterfy-api/radar", listing)
    profile_api.register("POST", r"/piterfy-api/radar/fetch", fetch)
