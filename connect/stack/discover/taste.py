#!/usr/bin/env python3
"""Panel gustu: co noc liczy, czego słuchasz w Navidrome, i zapisuje gust/data/taste.json.

Navidrome pamięta tylko łączną liczbę odsłuchów każdego utworu, więc co noc zapisujemy ten stan.
Różnice z kolejnych dni dają prawdziwą historię słuchania (wykres dni, okno ostatnich 30 dni).
Dopóki historia nie ma tygodnia, okno 30 dni jest szacowane z daty ostatniego odsłuchu.

Cechy nastroju (energia, walencja, tempo, taneczność) pochodzą z eksportu Spotify — tylko dla
utworów, które w nim są; Navidrome ich nie zna. Zdjęcia wykonawców: publiczne API Deezera.

  python taste.py      wygeneruj panel od razu (discover robi to sam co noc o północy)
"""

import csv
import json
import os
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

import discover as d

GUST_DIR = Path(os.environ.get("GUST_DIR", "/gust"))
SPOTIFY_CSV = Path(os.environ.get("SPOTIFY_CSV", "/config/liked_songs.csv"))
PERIOD_DAYS = 30
MIN_HISTORY_DAYS = 7      # od tylu dni zebranej historii okno 30 dni liczy się z prawdziwych odsłuchów
KEEP_DAYS = 400           # tyle dni historii trzymamy w stanie
IMAGE_REFRESH_DAYS = 30
WEEKDAYS = ["pn", "wt", "śr", "cz", "pt", "sb", "nd"]


def all_songs(nd):
    songs, start = [], 0
    while True:
        page = d.as_list(nd.native("song", _start=start, _end=start + 500, _sort="title", _order="ASC"))
        songs += page
        if len(page) < 500:
            return [s for s in songs if not d.podcasts.is_podcast(s)]  # odcinki podcastów to nie gust muzyczny
        start += 500


def song_artist(song):
    artists = (song.get("participants") or {}).get("artist")
    if artists and artists[0].get("name"):
        return artists[0]["name"]
    return d.primary_artist(song.get("artist") or "")


def song_genres(song):
    genres = [g.get("name") for g in d.as_list(song.get("genres")) if g.get("name")]
    return genres or ([song["genre"]] if song.get("genre") else [])


def record_day(songs, taste, now):
    """Dopisuje do historii odsłuchy od poprzedniego zapisu (różnica liczników)."""
    counts = {s["id"]: s.get("playCount") or 0 for s in songs}
    previous = taste.get("counts")
    if previous is None:
        taste["since"] = now.date().isoformat()
    else:
        # zapis tuż po północy dotyczy wczorajszego słuchania
        day = (now - timedelta(hours=3)).date().isoformat()
        plays = taste.setdefault("days", {}).setdefault(day, {})
        for song_id, count in counts.items():
            if count > previous.get(song_id, 0):
                plays[song_id] = plays.get(song_id, 0) + count - previous.get(song_id, 0)
    taste["counts"] = counts
    oldest = (now.date() - timedelta(days=KEEP_DAYS)).isoformat()
    taste["days"] = {day: p for day, p in taste.get("days", {}).items() if day >= oldest}


def window_plays(songs, taste, now):
    """Odsłuchy każdego utworu w ostatnich PERIOD_DAYS dniach i czy to tylko szacunek."""
    first = (now.date() - timedelta(days=PERIOD_DAYS)).isoformat()
    days = {day: p for day, p in taste.get("days", {}).items() if day >= first}
    if len(taste.get("days", {})) >= MIN_HISTORY_DAYS:
        plays = Counter()
        for p in days.values():
            plays.update(p)
        return plays, False
    since = now - timedelta(days=PERIOD_DAYS)
    plays = Counter()
    for s in songs:
        last = d.parse_time(s.get("playDate"))
        if last and last >= since and s.get("playCount"):
            plays[s["id"]] = s["playCount"]
    return plays, True


def spotify_features():
    """Cechy audio z eksportu Spotify (Exportify), po kluczu wykonawca|tytuł."""
    features = {}
    if not SPOTIFY_CSV.is_file():
        return features
    with SPOTIFY_CSV.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            artist = (row.get("Artist Name(s)") or "").split(";")[0]
            try:
                features[d.track_key(artist, row.get("Track Name") or "")] = {
                    "e": float(row["Energy"]), "v": float(row["Valence"]), "t": float(row["Tempo"]),
                    "d": float(row["Danceability"]),
                    "g": [g.strip() for g in (row.get("Genres") or "").split(",") if g.strip()]}
            except (KeyError, TypeError, ValueError):
                continue
    return features


def artist_images(names, taste):
    """Zdjęcia wykonawców z Deezera (publiczne adresy), odświeżane raz na miesiąc."""
    cache = taste.setdefault("images", {})
    deezer, today = d.Deezer(), date.today()
    for name in names:
        cached = cache.get(name)
        if cached and (today - date.fromisoformat(cached["date"])).days < IMAGE_REFRESH_DAYS:
            continue
        found = d.as_list(deezer.call("search/artist", q=name, limit=5).get("data"))
        match = next((a for a in found if d.norm(a.get("name", "")) == d.norm(name)), None)
        cache[name] = {"url": match and (match.get("picture_xl") or match.get("picture_big")), "date": today.isoformat()}
    return {name: cache[name]["url"] for name in names if cache.get(name, {}).get("url")}


def listening_clock(now):
    """Kiedy słuchasz: dzień tygodnia × godzina, ze scrobbli Last.fm (jeśli są)."""
    scrobbles = d.lastfm_scrobbles(now - timedelta(days=PERIOD_DAYS))
    if not scrobbles:
        return None
    grid = [[0] * 24 for _ in WEEKDAYS]
    for _, _, when in scrobbles:
        local = when.astimezone()
        grid[local.weekday()][local.hour] += 1
    return {"days": WEEKDAYS, "grid": grid, "total": len(scrobbles)}


def discover_stats(history):
    downloaded = [h for h in history.values() if h["status"] in ("done", "trashed")]
    liked = [h for h in downloaded if h.get("verdict") == "liked"]
    trashed = [h for h in downloaded if h["status"] == "trashed"]
    reviewed = len(liked) + len(trashed) + sum(h.get("verdict") == "disliked" for h in downloaded)
    recent = sorted(liked, key=lambda h: h["date"], reverse=True)[:8]
    return {"downloaded": len(downloaded), "liked": len(liked), "trashed": len(trashed),
            "hit_rate": round(len(liked) / reviewed, 3) if reviewed else None,
            "recent_liked": [{"title": h["title"], "artist": h["artist"], "via": h.get("via", "")} for h in recent]}


def build(songs, taste, history, now):
    plays, estimated = window_plays(songs, taste, now)
    by_id = {s["id"]: s for s in songs}
    played = [(by_id[sid], n) for sid, n in plays.items() if sid in by_id and n > 0]
    total = sum(n for _, n in played)

    artists, genres, decades = Counter(), Counter(), Counter()
    features, mood, weights = spotify_features(), [], Counter()
    sums = Counter()
    for song, n in played:
        artist = song_artist(song)
        if d.norm(artist) not in d.VARIOUS_ARTISTS:  # bez [Unknown Artist] i składanek w rankingu
            artists[artist] += n
        feat = features.get(d.track_key(artist, song.get("title") or ""))
        for genre in (feat and feat["g"][:3]) or song_genres(song)[:3]:
            genres[genre.lower()] += n
        if song.get("year"):
            decades[song["year"] // 10 * 10] += n
        if feat:
            mood.append({"n": song.get("title"), "a": artist, "e": feat["e"], "v": feat["v"], "t": feat["t"], "p": n})
            for key in "edvt":
                sums[key] += feat[key] * n
            weights["n"] += n

    covered = weights["n"]
    averages = ({"energy": sums["e"] / covered, "dance": sums["d"] / covered,
                 "valence": sums["v"] / covered, "tempo": sums["t"] / covered} if covered else None)

    days = []
    if taste.get("days"):
        start = max(date.fromisoformat(taste["since"]), now.date() - timedelta(days=PERIOD_DAYS))
        for i in range((now.date() - start).days):
            day = (start + timedelta(days=i)).isoformat()
            days.append([day, sum(taste["days"].get(day, {}).values())])

    top_artists = artists.most_common(12)
    images = artist_images([name for name, _ in top_artists[:8]], taste)
    top_tracks = sorted(played, key=lambda x: x[1], reverse=True)[:10]
    library_minutes = sum((s.get("duration") or 0) * n for s, n in played) / 60

    return {
        "version": 2,
        "generated": now.isoformat(timespec="minutes"),
        "period_days": PERIOD_DAYS,
        "estimated": estimated,
        "history_since": taste.get("since"),
        "totals": {"plays": total, "minutes": round(library_minutes), "tracks": len(played),
                   "artists": len(artists), "library": len(songs),
                   "starred": sum(bool(s.get("starred")) for s in songs)},
        "daily": days,
        "top_artists": [{"name": name, "plays": n, "img": images.get(name)} for name, n in top_artists],
        "top_tracks": [{"title": s.get("title"), "artist": song_artist(s), "album": s.get("album"), "plays": n}
                       for s, n in top_tracks],
        "top_genres": genres.most_common(12),
        "decades": sorted(decades.items()),
        "mood": {"coverage": round(covered / total, 3) if total else 0, "averages": averages,
                 "tracks": sorted(mood, key=lambda m: m["p"], reverse=True)[:600]},
        "clock": listening_clock(now),
        "discover": discover_stats(history),
    }


def generate(nd=None, state=None):
    """Liczy panel i zapisuje GUST_DIR/data/taste.json. Zwraca zaktualizowany stan discovera."""
    nd = nd or d.Navidrome()
    state = state if state is not None else d.load_state()
    taste = state.setdefault("taste", {})
    now = datetime.now().astimezone()

    songs = all_songs(nd)
    record_day(songs, taste, now)
    data = build(songs, taste, state.get("history", {}), now)

    folder = GUST_DIR / "data"
    folder.mkdir(parents=True, exist_ok=True)
    old = folder / "taste.json"
    baseline = folder / "spotify-baseline.json"
    if old.is_file() and not baseline.exists():  # poprzedni panel (polubione ze Spotify) zostaje do porównań
        previous = json.loads(old.read_text(encoding="utf-8"))
        if "total_tracks" in previous:
            baseline.write_text(json.dumps(previous, ensure_ascii=False), encoding="utf-8")
    tmp = folder / "taste.json.tmp"
    tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(old)

    taste["date"] = now.date().isoformat()
    d.log(f"Panel gustu odświeżony: {data['totals']['plays']} odsłuchów z {PERIOD_DAYS} dni"
          f"{' (szacunek — historia dopiero się zbiera)' if data['estimated'] else ''}.")
    return state


if __name__ == "__main__":
    d.save_state(generate())
