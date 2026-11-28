"""„Rok temu o tej porze”: playlista z tego, czego słuchałeś mniej więcej rok temu (±7 dni).

Historię bierzemy ze scrobbli Last.fm (`user.getRecentTracks` z `from`/`to`; LASTFM_API_KEY i LASTFM_USER są już ustawione
dla discovera). Navidrome pamięta tylko ostatnie odtworzenie każdego utworu, bez dat historycznych, więc nie nadaje się
do takiego okna. Moduł jest czysty (bez sieci i bez Navidrome): zapytanie do Last.fm dostaje jako funkcję `call`.
"""

from collections import Counter
from datetime import datetime, timedelta, timezone

import charts
import mixes

NAME = "Rok temu o tej porze"
MIN_TRACKS = 8
MAX_TRACKS = 60
WINDOW_DAYS = 7


def window(today, years=1, days=WINDOW_DAYS):
    """Okno dat sprzed `years` lat: (początek, koniec) jako datetime UTC; 29 lutego przechodzi na 28 lutego."""
    try:
        center = today.replace(year=today.year - years)
    except ValueError:
        center = today.replace(year=today.year - years, day=28)
    start = datetime(center.year, center.month, center.day, tzinfo=timezone.utc) - timedelta(days=days)
    end = datetime(center.year, center.month, center.day, tzinfo=timezone.utc) + timedelta(days=days + 1)
    return start, end


def fetch_scrobbles(call, user, start, end, pages=5):
    """(wykonawca, tytuł, kiedy) ze scrobbli z okna [start, end) przez `call("user.getRecentTracks", …)`."""
    result = []
    for page in range(1, pages + 1):
        data = call("user.getRecentTracks", user=user, limit=200, page=page, **{"from": int(start.timestamp()), "to": int(end.timestamp())})
        recent = (data or {}).get("recenttracks", {}) if isinstance(data, dict) else {}
        tracks = recent.get("track") or []
        if isinstance(tracks, dict):
            tracks = [tracks]
        for t in tracks:
            uts = (t.get("date") or {}).get("uts")
            artist = (t.get("artist") or {}).get("#text") or (t.get("artist") or {}).get("name")
            if uts and artist and t.get("name"):
                result.append((artist, t["name"], datetime.fromtimestamp(int(uts), timezone.utc)))
        if page >= int((recent.get("@attr") or {}).get("totalPages") or 1):
            break
    return result


def top_entries(scrobbles, limit=MAX_TRACKS * 3):
    """Najczęściej słuchane wtedy utwory jako wpisy dla `charts.match` (rank = miejsce wg liczby odtworzeń, potem świeżości)."""
    counts, last, names = Counter(), {}, {}
    for artist, title, when in scrobbles:
        key = charts.dedupe_key(artist, title)
        counts[key] += 1
        last[key] = max(last.get(key, when), when)
        names.setdefault(key, (artist, title))
    ranked = sorted(counts, key=lambda k: (-counts[k], -last[k].timestamp()))[:limit]
    return [{"rank": i, "id": f"ya:{key}", "title": names[key][1], "artist": names[key][0], "isrc": "", "duration": None,
             "plays": counts[key]} for i, key in enumerate(ranked, 1)]


def select(entries, songs, size=MAX_TRACKS, minimum=MIN_TRACKS):
    """Utwory biblioteki z tej listy w kolejności odtworzeń; [] gdy jest ich mniej niż `minimum`."""
    hits = charts.match(entries, songs)
    ids = [song["id"] for _, song, _ in hits][:size]
    return ids if len(ids) >= minimum else []


def comment(count, start, end):
    last = end - timedelta(days=1)
    return (f"{mixes.COMMENT_PREFIX}: Co słuchałeś rok temu o tej porze ({start:%d.%m.%Y} – {last:%d.%m.%Y}). "
            f"Odświeżany codziennie ({count} utworów).")
