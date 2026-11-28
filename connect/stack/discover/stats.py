#!/usr/bin/env python3
"""Statystyki dla nerdów: anatomia biblioteki, stan serwera i „Wrapped” (podsumowanie miesiąca albo roku).

discover liczy to co kilka godzin i zapisuje DATA_DIR/stats.json; aplikacja i strona WWW pobierają plik przez
GET /piterfy-api/stats (profile_api.py, wymaga zalogowania do Navidrome). Wszystko, co da się policzyć w jednym
miejscu, liczymy tutaj, żeby Android i WWW tylko rysowały gotowe liczby i nie musiały powtarzać reguł.

Źródła: lista utworów Navidrome (metadane plików, liczniki odsłuchów), analiza widma (spectrum.json z kontenera
beets), historia pobierania discovera (state.json) i dzienna historia odsłuchów z panelu gustu (taste.py). Godziny
słuchania („Nocny marek”) pochodzą ze scrobbli Last.fm, jeśli są podpięte.

Funkcje poniżej są czyste (dostają dane, zwracają dane) — testy w test_stats.py nie potrzebują Navidrome.

  python stats.py      policz od razu (discover robi to sam co STATS_EVERY_HOURS godzin)
"""

import json
import os
import re
import shutil
import unicodedata
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path

import health as health_mod
from profile_api import library_key

VERSION = 1
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
MUSIC_DIR = Path(os.environ.get("MUSIC_DIR", "/music"))
TRASH_DIR = Path(os.environ.get("TRASH_DIR", "/trash"))
STATS_FILE = Path(os.environ.get("STATS_FILE") or DATA_DIR / "stats.json")

LOSSLESS = {"flac", "alac", "wav", "aif", "aiff", "ape", "wv", "tta", "dsf", "dff"}
REPLACE_LIMIT = 30          # tyle „udawanych” plików wypisujemy do wymiany
GROWTH_WEEKS = 12
WRAPPED_MONTHS = 13         # bieżący miesiąc i dwanaście poprzednich
MIN_PLAYS = 15              # poniżej tego „typ słuchacza” byłby zgadywaniem
MIN_SCROBBLES = 40          # tyle scrobbli w okresie, by mówić o godzinach słuchania
NIGHT_HOURS = (22, 5)       # od 22:00 do 4:59
HIDDEN_VERDICTS = ("fake", "transcoded")
MONTHS = ["styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień", "wrzesień",
          "październik", "listopad", "grudzień"]
MISSED = ("notfound", "failed", "fake")
GOT = ("done", "trashed")

ARCHETYPES = {
    "explorer": ("Eksplorator", "Ciągle szukasz czegoś nowego — niemal każdy utwór to inna piosenka."),
    "loyal": ("Wierny fan", "Wiesz, co kochasz: kilku wykonawców gra u Ciebie pierwsze skrzypce."),
    "night": ("Nocny marek", "Najlepiej gra Ci po zmroku."),
    "marathon": ("Maratończyk", "Muzyka towarzyszy Ci prawie codziennie, i to godzinami."),
    "repeater": ("Powtarzacz", "Dobry kawałek zasługuje na dziesiąty raz."),
    "retro": ("Podróżnik w czasie", "Twoje serce zostało w dawnych dekadach."),
    "fresh": ("Na fali", "Słuchasz tego, co dopiero wyszło."),
    "steady": ("Równy rytm", "Słuchasz spokojnie i różnorodnie, bez skrajności."),
}


def norm(text):
    text = unicodedata.normalize("NFKD", str(text or "").lower())
    return re.sub(r"\s+", " ", "".join(c for c in text if not unicodedata.combining(c))).strip()


def artist_of(song):
    artists = (song.get("participants") or {}).get("artist")
    if artists and artists[0].get("name"):
        return artists[0]["name"]
    return re.split(r"\s*(?:;|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b)\s*", song.get("artist") or "", maxsplit=1, flags=re.I)[0].strip()


def genres_of(song):
    names = [g.get("name") for g in (song.get("genres") or []) if g.get("name")]
    return names or ([song["genre"]] if song.get("genre") else [])


def is_lossless(song):
    suffix = (song.get("suffix") or "").lower()
    return suffix in LOSSLESS or (suffix in ("m4a", "mp4") and (song.get("bitRate") or 0) >= 500)


def day_of(value):
    """Data (RRRR-MM-DD) z czasu ISO Navidrome — ułamki sekund mają tam dziewięć cyfr, więc bierzemy tylko datę."""
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        return None


def pl_number(value):
    """1 miejsce po przecinku, po polsku i bez zbędnego „,0”: 30 → „30”, 7.5 → „7,5”."""
    return f"{value:.1f}".rstrip("0").rstrip(".").replace(".", ",")


def pct_change(now, before):
    return round((now - before) / before * 100) if before else None


# ── anatomia biblioteki ──────────────────────────────────────────────────────

def bucket(value, edges):
    """Etykieta przedziału: edges to lista (dolna granica włącznie, etykieta) rosnąco."""
    label = None
    for low, name in edges:
        if value >= low:
            label = name
    return label


LOSSY_BITRATES = [(0, "<128"), (128, "128–191"), (192, "192–255"), (256, "256–319"), (320, "320+")]
SAMPLE_RATES = [(1, "≤ 44,1"), (44101, "48"), (48001, "88–96"), (96001, "176+")]


def histogram(counter, edges):
    return [[name, counter.get(name, 0)] for _, name in edges]


def library(songs, spectrum):
    tracks = len(songs)
    seconds = sum(s.get("duration") or 0 for s in songs)
    size = sum(s.get("size") or 0 for s in songs)
    albums = {s.get("albumId") or (norm(s.get("album")), norm(artist_of(s))) for s in songs}
    artists = {norm(artist_of(s)) for s in songs if artist_of(s)}

    formats, format_bytes = Counter(), Counter()
    lossy_rates, sample_rates, depths = Counter(), Counter(), Counter()
    decades, genres = Counter(), Counter()
    quality = Counter()
    lossless = hires = hires_fake = genuine = 0
    replace = []
    for s in songs:
        suffix = (s.get("suffix") or "?").lower()
        formats[suffix] += 1
        format_bytes[suffix] += s.get("size") or 0
        flac = is_lossless(s)
        rate, depth, kbps = s.get("sampleRate") or 0, s.get("bitDepth") or 0, s.get("bitRate") or 0
        if rate:
            sample_rates[bucket(rate, SAMPLE_RATES)] += 1
        if flac:
            lossless += 1
            if depth:
                depths[depth] += 1
        elif kbps:
            lossy_rates[bucket(kbps, LOSSY_BITRATES)] += 1
        year = s.get("year") or 0
        if year >= 1900:
            decades[year // 10 * 10] += 1
        for g in genres_of(s)[:1]:
            genres[g] += 1

        entry = spectrum.get(library_key(s))
        verdict = (entry or {}).get("verdict") or "pending"
        quality[verdict] += 1
        is_hires = flac and (rate > 48000 or depth > 16)
        hires += is_hires
        genuine += flac and verdict == "ok"
        if is_hires and verdict == "upsampled":
            hires_fake += 1
        if verdict in HIDDEN_VERDICTS:
            replace.append({"id": s.get("id"), "title": s.get("title"), "artist": artist_of(s),
                            "album": s.get("album"), "format": suffix, "verdict": verdict,
                            "source_kbps": (entry or {}).get("source_kbps"), "plays": s.get("playCount") or 0})
    replace.sort(key=lambda r: (-r["plays"], r["artist"] or "", r["title"] or ""))

    dated = [s for s in songs if (s.get("year") or 0) >= 1900]
    pick = lambda items, key, reverse=False: (sorted(items, key=key, reverse=reverse) or [None])[0]  # noqa: E731
    short = pick([s for s in songs if (s.get("duration") or 0) >= 10], lambda s: s["duration"])
    describe = lambda s, extra: s and {"title": s.get("title"), "artist": artist_of(s), **extra(s)}  # noqa: E731

    played = sum(bool(s.get("playCount")) for s in songs)
    return {
        "tracks": tracks, "albums": len(albums), "artists": len(artists),
        "seconds": int(seconds), "bytes": int(size), "starred": sum(bool(s.get("starred")) for s in songs),
        "played": played, "unplayed": tracks - played,
        "formats": [[name, n, int(format_bytes[name])] for name, n in formats.most_common()],
        "lossless": lossless, "lossy": tracks - lossless,
        "lossy_bitrates": histogram(lossy_rates, LOSSY_BITRATES),
        "sample_rates": histogram(sample_rates, SAMPLE_RATES),
        "bit_depths": sorted([[depth, n] for depth, n in depths.items()]),
        "hires": hires, "hires_upsampled": hires_fake,
        "decades": sorted(decades.items()),
        "years": {"oldest": describe(pick(dated, lambda s: s["year"]), lambda s: {"year": s["year"]}),
                  "newest": describe(pick(dated, lambda s: s["year"], True), lambda s: {"year": s["year"]}),
                  "average": round(sum(s["year"] for s in dated) / len(dated)) if dated else None},
        "records": {
            "longest": describe(pick(songs, lambda s: s.get("duration") or 0, True), lambda s: {"seconds": s.get("duration") or 0}),
            "shortest": describe(short, lambda s: {"seconds": s["duration"]}),
            "biggest": describe(pick(songs, lambda s: s.get("size") or 0, True), lambda s: {"bytes": s.get("size") or 0}),
        },
        "top_genres": genres.most_common(8),
        "quality": {
            "checked": tracks - quality.get("pending", 0), "pending": quality.get("pending", 0),
            "ok": quality.get("ok", 0), "suspect": quality.get("suspect", 0),
            "fake": quality.get("fake", 0), "transcoded": quality.get("transcoded", 0),
            "upsampled": quality.get("upsampled", 0), "unknown": quality.get("unknown", 0),
            "genuine_lossless": genuine,
            "replace_total": len(replace), "replace": replace[:REPLACE_LIMIT],
        },
    }


# ── stan serwera ─────────────────────────────────────────────────────────────

def disk_usage(path=None):
    try:
        usage = shutil.disk_usage(path or MUSIC_DIR)
        return {"total": usage.total, "used": usage.used, "free": usage.free}
    except OSError:
        return None


def week_start(day):
    return day - timedelta(days=day.weekday())


def server(songs, history, state, now, disk=None, scan=None, trash=None, health=None):
    today = now.date()
    first_week = week_start(today) - timedelta(weeks=GROWTH_WEEKS - 1)
    weeks = {first_week + timedelta(weeks=i): [0, 0] for i in range(GROWTH_WEEKS)}
    added_7, added_30, oldest = 0, 0, None
    for s in songs:
        day = day_of(s.get("createdAt"))
        if not day:
            continue
        oldest = min(oldest, day) if oldest else day
        added_7 += (today - day).days < 7
        added_30 += (today - day).days < 30
        if week_start(day) in weeks:
            weeks[week_start(day)][0] += 1
            weeks[week_start(day)][1] += s.get("size") or 0

    outcomes = Counter(h.get("status") for h in history.values())
    got = sum(outcomes[k] for k in GOT)
    missed = sum(outcomes[k] for k in MISSED)

    def rates(field):
        groups = {}
        for h in history.values():
            name = h.get(field)
            if not name or h.get("status") == "pending":
                continue
            row = groups.setdefault(name, {"tried": 0, "got": 0})
            row["tried"] += 1
            row["got"] += h.get("status") in GOT
        return {name: {**row, "rate": round(row["got"] / row["tried"], 3)} for name, row in sorted(groups.items())}

    liked = sum(h.get("verdict") == "liked" for h in history.values() if h.get("status") in GOT)
    trashed = outcomes["trashed"]
    reviewed = liked + trashed + sum(h.get("verdict") == "disliked" for h in history.values() if h.get("status") in GOT)
    return {
        "growth": {"weeks": [[w.isoformat(), n, b] for w, (n, b) in sorted(weeks.items())],
                   "added_7": added_7, "added_30": added_30, "first": oldest.isoformat() if oldest else None},
        "acquisition": {
            "tried": got + missed, "got": got, "missed": missed, "pending": outcomes["pending"],
            "soulseek_rate": round(got / (got + missed), 3) if got + missed else None,
            "outcomes": {k: outcomes[k] for k in ("done", "pending", "trashed", "notfound", "failed", "fake") if outcomes[k]},
            "engines": rates("engine"), "pools": rates("pool"),
        },
        "discover": {"downloaded": got, "liked": liked, "trashed": trashed,
                     "hit_rate": round(liked / reviewed, 3) if reviewed else None},
        "disk": disk, "trash": trash,
        "health": health,  # stan serwera: status, alarmy i pozycje (health.py); None, gdy nie policzono
        "last_run": state.get("last_run"),
        "last_scan": (scan or {}).get("lastScan"),
        "scanning": bool((scan or {}).get("scanning")),
    }


# ── Wrapped ──────────────────────────────────────────────────────────────────

def month_end(first):
    return (first.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)


def shift_year(day, years):
    try:
        return day.replace(year=day.year + years)
    except ValueError:  # 29 lutego
        return day.replace(year=day.year + years, day=28)


def previous_window(kind, start, end, full_end):
    """Ta sama liczba dni w poprzednim miesiącu albo roku (przy trwającym okresie — tyle dni, ile już minęło)."""
    if kind == "month":
        prev_start = (start - timedelta(days=1)).replace(day=1)
    else:
        prev_start = shift_year(start, -1)
    prev_full_end = start - timedelta(days=1) if kind == "month" else shift_year(full_end, -1)
    return prev_start, min(prev_start + (end - start), prev_full_end)


def archetype(m):
    """Typ słuchacza z liczb okresu; zwraca {id, name, blurb, why} albo None, gdy odsłuchów jest za mało."""
    if m["plays"] < MIN_PLAYS:
        return None
    plays, tracks = m["plays"], m["tracks"]
    candidates = []

    unique = tracks / plays
    new_ratio = m["new_artists"] / m["artists"] if m.get("new_artists") is not None and m["artists"] else None
    explorer = max(unique / 0.85, (new_ratio or 0) / 0.35)
    if tracks >= 15:
        candidates.append(("explorer", explorer, [f"{round(unique * 100)}% odsłuchów to różne utwory"] +
                           ([f"{m['new_artists']} nowych wykonawców"] if m.get("new_artists") else [])))

    top = m["top_artists"][0] if m["top_artists"] else None
    top3 = sum(a["plays"] for a in m["top_artists"][:3]) / plays
    if top:
        share = top["plays"] / plays
        candidates.append(("loyal", max(share / 0.30, top3 / 0.60),
                           [f"{top['name']} to {round(share * 100)}% Twoich odsłuchów"]))

    if m.get("night_share") is not None:
        candidates.append(("night", m["night_share"] / 0.40, [f"{round(m['night_share'] * 100)}% słuchania po 22:00"]))

    if m["days_elapsed"] and m["active_days"]:
        active = m["active_days"] / m["days_elapsed"]
        per_day = m["minutes"] / m["active_days"]
        candidates.append(("marathon", min(active / 0.8, per_day / 90),
                           [f"muzyka w {m['active_days']} dniach na {m['days_elapsed']}", f"średnio {round(per_day)} min dziennie"]))

    repeat = plays / tracks
    candidates.append(("repeater", repeat / 3.5, [f"średnio {pl_number(repeat)} razy ten sam utwór"]))

    dated = m.get("dated_plays") or 0
    if dated >= plays * 0.6:
        old, recent = m["old_plays"] / dated, m["recent_plays"] / dated
        candidates.append(("retro", old / 0.4, [f"{round(old * 100)}% odsłuchów sprzed 1990"]))
        candidates.append(("fresh", recent / 0.4, [f"{round(recent * 100)}% odsłuchów z ostatnich dwóch lat"]))

    order = list(ARCHETYPES)
    qualified = [c for c in candidates if c[1] >= 1]
    if qualified:
        kind, score, why = max(qualified, key=lambda c: (c[1], -order.index(c[0])))
    else:
        kind, score, why = "steady", 1.0, [f"{m['artists']} wykonawców, {m['tracks']} różnych utworów"]
    name, blurb = ARCHETYPES[kind]
    return {"id": kind, "name": name, "blurb": blurb, "why": why, "score": round(score, 2)}


def cover_of(song):
    """Identyfikator okładki dla getCoverArt: okładka albumu, a w ostateczności sam utwór."""
    if song.get("coverArt"):
        return song["coverArt"]
    return f"al-{song['albumId']}" if song.get("albumId") else song.get("id")


def summarize(days, by_id, start, end, first_seen, history_since, scrobbles, now_year):
    """Liczby jednego okna czasu [start, end] (obie daty włącznie)."""
    plays, per_day = Counter(), {}
    for day, row in days.items():
        if start.isoformat() <= day <= end.isoformat():
            for sid, n in row.items():
                if sid in by_id:
                    plays[sid] += n
                    per_day[day] = per_day.get(day, 0) + n
    total = sum(plays.values())
    artists, genres = Counter(), Counter()
    seconds = old = recent = dated = 0
    for sid, n in plays.items():
        s = by_id[sid]
        artists[artist_of(s)] += n
        for g in genres_of(s)[:1]:
            genres[g] += n
        seconds += (s.get("duration") or 0) * n
        if (s.get("year") or 0) >= 1900:
            dated += n
            old += n * (s["year"] < 1990)
            recent += n * (s["year"] >= now_year - 1)

    streak = best = 0
    for i in range((end - start).days + 1):
        streak = streak + 1 if (start + timedelta(days=i)).isoformat() in per_day else 0
        best = max(best, streak)
    busiest = max(per_day.items(), key=lambda kv: (kv[1], kv[0]), default=None)

    known = history_since is not None and history_since < start.isoformat()
    new = sum(1 for a in artists if first_seen.get(a, "") >= start.isoformat()) if known else None
    hours = [w.astimezone().hour for _, _, w in scrobbles if start <= w.astimezone().date() <= end]
    night = sum(h >= NIGHT_HOURS[0] or h < NIGHT_HOURS[1] for h in hours) / len(hours) if len(hours) >= MIN_SCROBBLES else None
    peak = Counter(hours).most_common(1)[0][0] if len(hours) >= MIN_SCROBBLES else None

    top_tracks = sorted(plays.items(), key=lambda kv: (-kv[1], by_id[kv[0]].get("title") or ""))[:5]
    best_of = {}  # najczęściej słuchany utwór każdego wykonawcy: z niego bierzemy okładkę wykonawcy
    for sid, n in sorted(plays.items(), key=lambda kv: (-kv[1], by_id[kv[0]].get("title") or "")):
        best_of.setdefault(artist_of(by_id[sid]), sid)
    return {
        "plays": total, "minutes": round(seconds / 60), "tracks": len(plays), "artists": len(artists),
        "active_days": len(per_day), "days_elapsed": (end - start).days + 1,
        "longest_streak": best,
        "busiest_day": {"day": busiest[0], "plays": busiest[1]} if busiest else None,
        "top_artists": [{"name": name, "plays": n, "cover": cover_of(by_id[best_of[name]])}
                        for name, n in artists.most_common(5) if name],
        "top_tracks": [{"id": sid, "title": by_id[sid].get("title"), "artist": artist_of(by_id[sid]), "plays": n,
                        "cover": cover_of(by_id[sid]), "seconds": round(by_id[sid].get("duration") or 0)}
                       for sid, n in top_tracks],
        "top_genres": genres.most_common(5),
        "new_artists": new, "night_share": None if night is None else round(night, 3), "peak_hour": peak,
        "dated_plays": dated, "old_plays": old, "recent_plays": recent,
    }


def wrapped(songs, taste, scrobbles, now):
    days = taste.get("days") or {}
    since = taste.get("since")
    by_id = {s["id"]: s for s in songs}
    today = now.date()

    first_seen = {}
    for day in sorted(days):
        for sid in days[day]:
            if sid in by_id:
                first_seen.setdefault(artist_of(by_id[sid]), day)

    periods = []

    def add(kind, key, label, start, end, full_end):
        if not any(start.isoformat() <= day <= end.isoformat() for day in days):
            return
        now_stats = summarize(days, by_id, start, end, first_seen, since, scrobbles, today.year)
        if not now_stats["plays"]:
            return
        p_start, p_end = previous_window(kind, start, end, full_end)
        before = summarize(days, by_id, p_start, p_end, first_seen, since, [], today.year)
        covered = since is not None and since <= p_start.isoformat()
        compare = {"plays": pct_change(now_stats["plays"], before["plays"]),
                   "minutes": pct_change(now_stats["minutes"], before["minutes"]),
                   "artists": pct_change(now_stats["artists"], before["artists"]),
                   "prev_plays": before["plays"], "prev_minutes": before["minutes"],
                   "prev_label": f"{p_start.isoformat()} – {p_end.isoformat()}"} if covered and before["plays"] else None
        internal = ("dated_plays", "old_plays", "recent_plays")  # tylko dla typu słuchacza, klientom niepotrzebne
        periods.append({"kind": kind, "key": key, "label": label, "start": start.isoformat(), "end": end.isoformat(),
                        "partial": end < full_end, **{k: v for k, v in now_stats.items() if k not in internal},
                        "listener": archetype(now_stats), "compare": compare})

    first = today.replace(day=1)
    for i in range(WRAPPED_MONTHS):
        year, month = divmod(first.year * 12 + first.month - 1 - i, 12)
        start = date(year, month + 1, 1)
        add("month", start.strftime("%Y-%m"), f"{MONTHS[start.month - 1].capitalize()} {start.year}",
            start, min(month_end(start), today), month_end(start))
    for year in (today.year, today.year - 1):
        start, full_end = date(year, 1, 1), date(year, 12, 31)
        add("year", str(year), f"Rok {year}", start, min(full_end, today), full_end)
    return {"periods": periods, "history_since": since}


# ── całość ───────────────────────────────────────────────────────────────────

def build(songs, spectrum, taste, history, state, scrobbles, now, disk=None, scan=None, trash=None, health=None):
    return {"version": VERSION, "generated": now.isoformat(timespec="minutes"),
            "library": library(songs, spectrum),
            "server": server(songs, history, state, now, disk, scan, trash, health),
            "wrapped": wrapped(songs, taste, scrobbles, now)}


def live(data):
    """Dane, które pytanie o statystyki odświeża na bieżąco (miejsce na dysku), bez ponownego liczenia reszty."""
    server_part = data.get("server")
    if isinstance(server_part, dict):
        server_part["disk"] = disk_usage() or server_part.get("disk")
        try:  # stan serwera liczymy od nowa (dysk, zapis, kopie, liczniki API); liczbę plików bierzemy z ostatniego pełnego przebiegu
            old = server_part.get("health") or {}
            server_part["health"] = health_mod.report(MUSIC_DIR, DATA_DIR, stats_generated=data.get("generated"), files=old.get("files"))
        except Exception:  # panel zdrowia nie może zepsuć reszty statystyk
            pass
    return data


def folder_size(path):
    total = files = 0
    try:
        for item in Path(path).rglob("*"):
            if item.is_file():
                total += item.stat().st_size
                files += 1
    except OSError:
        pass
    return {"files": files, "bytes": total}


def generate(nd=None, state=None):
    """Liczy i zapisuje STATS_FILE. Zwraca policzone dane (stan discovera zostaje bez zmian)."""
    import discover as d  # osobny moduł (potrzebuje ustawień środowiska), więc dopiero tutaj
    import taste

    nd = nd or d.Navidrome()
    state = state if state is not None else d.load_state()
    now = datetime.now().astimezone()
    songs = taste.all_songs(nd)
    try:
        spectrum = json.loads(d.SPECTRUM_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        spectrum = {}
    try:
        scrobbles = d.lastfm_scrobbles(now - timedelta(days=400))
    except Exception as e:  # Last.fm tylko dopełnia (godziny słuchania)
        d.log(f"Statystyki: bez godzin słuchania ({e}).")
        scrobbles = []
    try:
        scan = nd.call("getScanStatus").get("scanStatus")
    except Exception:
        scan = None
    try:
        report = health_mod.report(MUSIC_DIR, DATA_DIR, stats_generated=now.isoformat(timespec="minutes"),
                                   files=folder_size(MUSIC_DIR)["files"])
    except Exception as e:
        d.log(f"Statystyki: bez stanu serwera ({e}).")
        report = None
    data = build(songs, spectrum, state.get("taste") or {}, state.get("history") or {}, state, scrobbles, now,
                 disk_usage(), scan, folder_size(TRASH_DIR), report)
    STATS_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(STATS_FILE)
    d.log(f"Statystyki policzone: {data['library']['tracks']} utworów, {len(data['wrapped']['periods'])} okresów Wrapped.")
    return data


if __name__ == "__main__":
    generate()
