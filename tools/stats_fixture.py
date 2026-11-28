#!/usr/bin/env python3
"""Odtwarza app/src/test/resources/piterfy_stats_sample.json: prawdziwy wynik stats.build dla malutkiej biblioteki.

Test Androida (PiterfyStatsModelTest) czyta ten plik, więc łączy kod serwera z parserem w aplikacji: gdy zmienisz
format stats.json, uruchom ten skrypt i popraw parser. Uruchamiaj z katalogu głównego repozytorium.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "connect/stack/discover"))
import health  # noqa: E402
import stats  # noqa: E402


def song(i, artist, **extra):
    s = {"id": f"s{i}", "title": f"Song {i}", "artist": artist, "album": "Album", "albumId": f"al{i % 2}", "suffix": "flac",
         "bitRate": 900, "sampleRate": 44100, "bitDepth": 16, "size": 30_000_000, "duration": 200, "year": 1990 + i,
         "genre": "Rock", "playCount": 0, "createdAt": "2026-11-10T10:00:00.123456789Z", "libraryPath": "/music"}
    s.update(extra)
    s.setdefault("path", f"/music/{artist}/{i}.{s['suffix']}")
    return s


def main():
    now = datetime(2026, 11, 16, 14, 5, tzinfo=timezone.utc)
    songs = [song(1, "Alpha"), song(2, "Beta", sampleRate=96000, bitDepth=24, year=2021),
             song(3, "Gamma", suffix="mp3", bitRate=320, bitDepth=0, duration=15, size=8_000_000),
             song(4, "Delta", suffix="mp3", bitRate=128, bitDepth=0, playCount=9),
             song(5, "Alpha", suffix="m4a", bitRate=700, albumId="al3")]
    spectrum = {"Alpha/1.flac": {"verdict": "ok"}, "Beta/2.flac": {"verdict": "upsampled"},
                "Gamma/3.mp3": {"verdict": "transcoded", "source_kbps": 192}, "Delta/4.mp3": {"verdict": "fake", "source_kbps": 128}}
    days = {f"2026-10-{d:02d}": {"s1": 2, "s2": 2} for d in range(1, 11)}
    days.update({f"2026-11-{d:02d}": {"s1": 2, "s2": 2, "s3": 2} for d in range(1, 16)})
    history = {"a": {"status": "done", "pool": "new", "engine": "Last.fm", "verdict": "liked"},
               "b": {"status": "trashed", "pool": "new", "engine": "Last.fm"},
               "c": {"status": "notfound", "pool": "familiar", "engine": "Deezer"}, "d": {"status": "pending"}}
    # stan serwera: dysk prawie pełny (awaria) i brak kopii zapasowych (ostrzeżenie) — fixture ćwiczy oba poziomy i kolejność alarmów
    gb = health.GB
    report = health.evaluate({
        "library": {"exists": True, "separate_mount": True, "writable": True, "total_bytes": 400 * gb, "free_bytes": 12 * gb, "files": 5},
        "discover": {"last_run": "2026-11-15T04:00", "mixes_checked": now.timestamp() - 3600, "charts_checked": now.timestamp() - 3600,
                     "games_checked": now.timestamp() - 600, "stats_generated": "2026-11-16T14:00",
                     "intervals": {"run": 72, "mixes": 24, "charts": 12, "games": 6, "stats": 6}},
        "alerts": {}, "api": {}, "lastfm_configured": False, "backup": None, "expect_separate": False}, now.timestamp())
    data = stats.build(songs, spectrum, {"days": days, "since": "2026-01-01"}, history, {"last_run": "2026-11-15T04:00"}, [], now,
                       {"total": 1000, "used": 600, "free": 400}, {"lastScan": "2026-11-16T09:12:00Z", "scanning": False},
                       {"files": 2, "bytes": 100}, report)
    out = ROOT / "app/src/test/resources/piterfy_stats_sample.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print("zapisano", out)


if __name__ == "__main__":
    main()
