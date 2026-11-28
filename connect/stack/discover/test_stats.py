import json
import tempfile
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import stats

NOW = datetime(2026, 11, 16, 12, 0, tzinfo=timezone.utc)


def song(sid, artist="A", title=None, **extra):
    base = {"id": sid, "title": title or f"t{sid}", "artist": artist, "album": "Alb", "albumId": "al1",
            "suffix": "flac", "bitRate": 900, "sampleRate": 44100, "bitDepth": 16, "size": 30_000_000,
            "duration": 200, "year": 2010, "genre": "Rock", "playCount": 0,
            "createdAt": "2026-11-10T10:00:00.123456789Z", "libraryPath": "/music"}
    base.update(extra)
    base.setdefault("path", f"/music/{artist}/{sid}.{base['suffix']}")
    return base


class LibraryTest(unittest.TestCase):
    def setUp(self):
        self.songs = [
            song("1", "Alpha", suffix="flac", year=1975, duration=300, size=40_000_000),
            song("2", "Beta", suffix="flac", sampleRate=96000, bitDepth=24, year=2021, duration=120),
            song("3", "Gamma", suffix="mp3", bitRate=320, sampleRate=44100, bitDepth=0, year=2005, size=8_000_000, duration=15),
            song("4", "Delta feat. Eps", suffix="mp3", bitRate=128, bitDepth=0, year=0, genre="", playCount=9,
                 path="/music/Delta/4.mp3"),
            song("5", "Alpha", suffix="m4a", bitRate=700, year=1999, playCount=2, albumId="al2"),
        ]
        self.spectrum = {
            "Alpha/1.flac": {"verdict": "ok"},
            "Beta/2.flac": {"verdict": "upsampled"},
            "Gamma/3.mp3": {"verdict": "transcoded", "source_kbps": 192},
            "Delta/4.mp3": {"verdict": "fake", "source_kbps": 128},
        }
        self.lib = stats.library(self.songs, self.spectrum)

    def test_totals(self):
        self.assertEqual((self.lib["tracks"], self.lib["albums"], self.lib["artists"]), (5, 2, 4))
        self.assertEqual(self.lib["seconds"], 300 + 120 + 15 + 200 + 200)
        self.assertEqual(self.lib["played"], 2)
        self.assertEqual(self.lib["unplayed"], 3)

    def test_formats_and_lossless_detection(self):
        by_name = {name: n for name, n, _ in self.lib["formats"]}
        self.assertEqual(by_name, {"flac": 2, "mp3": 2, "m4a": 1})
        self.assertEqual(self.lib["lossless"], 3)           # m4a z bitrate 700 to ALAC
        self.assertEqual(self.lib["lossy"], 2)
        self.assertTrue(stats.is_lossless({"suffix": "FLAC"}))
        self.assertFalse(stats.is_lossless({"suffix": "m4a", "bitRate": 256}))

    def test_histograms_cover_all_buckets_even_when_empty(self):
        labels = [name for name, _ in self.lib["lossy_bitrates"]]
        self.assertEqual(labels, ["<128", "128–191", "192–255", "256–319", "320+"])
        counts = dict(self.lib["lossy_bitrates"])
        self.assertEqual((counts["128–191"], counts["320+"], counts["<128"]), (1, 1, 0))
        rates = dict(self.lib["sample_rates"])
        self.assertEqual(rates["88–96"], 1)
        self.assertEqual(rates["≤ 44,1"], 4)

    def test_hires_needs_lossless_and_higher_spec(self):
        self.assertEqual(self.lib["hires"], 1)
        self.assertEqual(self.lib["hires_upsampled"], 1)    # ten „hi-res” to CD
        self.assertEqual(self.lib["bit_depths"], [[16, 2], [24, 1]])

    def test_years_and_records(self):
        self.assertEqual(self.lib["years"]["oldest"]["year"], 1975)
        self.assertEqual(self.lib["years"]["newest"]["year"], 2021)
        self.assertEqual(dict(self.lib["decades"])[1970], 1)
        self.assertNotIn(0, dict(self.lib["decades"]))      # rok 0 = brak roku
        self.assertEqual(self.lib["records"]["longest"]["seconds"], 300)
        self.assertEqual(self.lib["records"]["shortest"]["seconds"], 15)

    def test_quality_ledger(self):
        q = self.lib["quality"]
        self.assertEqual((q["ok"], q["fake"], q["transcoded"], q["upsampled"], q["pending"]), (1, 1, 1, 1, 1))
        self.assertEqual(q["genuine_lossless"], 1)
        self.assertEqual(q["replace_total"], 2)
        # najczęściej słuchane „udawane” pliki do wymiany idą pierwsze
        self.assertEqual([r["id"] for r in q["replace"]], ["4", "3"])
        self.assertEqual(q["replace"][0]["source_kbps"], 128)

    def test_primary_artist_ignores_features(self):
        self.assertEqual(stats.artist_of({"artist": "Delta feat. Eps"}), "Delta")
        self.assertEqual(stats.artist_of({"artist": "X", "participants": {"artist": [{"name": "Real"}]}}), "Real")

    def test_empty_library_does_not_crash(self):
        lib = stats.library([], {})
        self.assertEqual(lib["tracks"], 0)
        self.assertIsNone(lib["years"]["oldest"])
        self.assertIsNone(lib["records"]["longest"])


class ServerTest(unittest.TestCase):
    def test_growth_by_week(self):
        songs = [song("1", createdAt="2026-11-16T01:00:00.999999999Z"),        # poniedziałek bieżącego tygodnia
                 song("2", createdAt="2026-11-10T01:00:00Z"),                   # poprzedni tydzień
                 song("3", createdAt="2026-11-11T01:00:00Z"),
                 song("4", createdAt="2020-01-01T01:00:00Z"),                   # poza oknem wykresu
                 song("5", createdAt="")]
        srv = stats.server(songs, {}, {}, NOW)
        weeks = dict((w, n) for w, n, _ in srv["growth"]["weeks"])
        self.assertEqual(len(weeks), stats.GROWTH_WEEKS)
        self.assertEqual(weeks["2026-11-16"], 1)
        self.assertEqual(weeks["2026-11-09"], 2)
        self.assertEqual(srv["growth"]["added_7"], 3)
        self.assertEqual(srv["growth"]["added_30"], 3)
        self.assertEqual(srv["growth"]["first"], "2020-01-01")

    def test_acquisition_rates(self):
        history = {
            "a": {"status": "done", "pool": "new", "engine": "Last.fm", "verdict": "liked"},
            "b": {"status": "trashed", "pool": "new", "engine": "Last.fm"},
            "c": {"status": "notfound", "pool": "familiar", "engine": "Last.fm"},
            "d": {"status": "failed", "pool": "new", "engine": "Deezer"},
            "e": {"status": "pending", "pool": "new", "engine": "Deezer"},
            "f": {"status": "done"},                                            # stary wpis bez źródła
            "g": {"status": "fake"},
        }
        acq = stats.server([], history, {"last_run": "2026-11-15T03:00"}, NOW)["acquisition"]
        self.assertEqual((acq["got"], acq["missed"], acq["pending"], acq["tried"]), (3, 3, 1, 6))
        self.assertEqual(acq["soulseek_rate"], 0.5)
        self.assertEqual(acq["pools"]["new"], {"tried": 3, "got": 2, "rate": 0.667})
        self.assertEqual(acq["engines"]["Last.fm"]["rate"], 0.667)
        self.assertNotIn("Deezer", {k for k, v in acq["engines"].items() if v["tried"] != 1})

    def test_hit_rate_and_passthrough_fields(self):
        history = {"a": {"status": "done", "verdict": "liked"}, "b": {"status": "trashed", "verdict": "trash-unplayed"},
                   "c": {"status": "done", "verdict": "disliked"}, "d": {"status": "done"}}
        srv = stats.server([], history, {"last_run": "2026-11-15T03:00"}, NOW,
                           disk={"total": 10, "used": 4, "free": 6}, scan={"lastScan": "2026-11-16T00:00:00Z", "scanning": True})
        self.assertEqual(srv["discover"]["hit_rate"], 0.333)
        self.assertEqual(srv["disk"]["free"], 6)
        self.assertEqual(srv["last_run"], "2026-11-15T03:00")
        self.assertTrue(srv["scanning"])
        self.assertEqual(stats.server([], {}, {}, NOW)["acquisition"]["soulseek_rate"], None)

    def test_live_refreshes_disk_only(self):
        data = {"server": {"disk": {"free": 1}, "last_run": "x"}, "library": {"tracks": 3}}
        out = stats.live(json.loads(json.dumps(data)))
        self.assertEqual(out["library"], {"tracks": 3})
        self.assertEqual(out["server"]["last_run"], "x")

    def test_server_carries_health_and_live_recomputes_it(self):
        report = {"status": "ok", "alerts": [], "items": [], "files": 7}
        self.assertEqual(stats.server([], {}, {}, NOW, health=report)["health"], report)
        self.assertIsNone(stats.server([], {}, {}, NOW)["health"])         # stary przebieg bez zdrowia nadal się buduje
        with tempfile.TemporaryDirectory() as tmp:
            music, data_dir = Path(tmp) / "music", Path(tmp) / "data"
            music.mkdir()
            data_dir.mkdir()
            old = (stats.MUSIC_DIR, stats.DATA_DIR)
            stats.MUSIC_DIR, stats.DATA_DIR = music, data_dir
            try:
                out = stats.live({"generated": "2026-11-16T12:00", "server": {"health": {"files": 7}}})
            finally:
                stats.MUSIC_DIR, stats.DATA_DIR = old
            health = out["server"]["health"]
            self.assertEqual(health["files"], 7)                              # liczba plików z ostatniego pełnego przebiegu
            self.assertIn(health["status"], ("ok", "warn", "crit"))
            self.assertIn("backup_missing", [a["code"] for a in health["alerts"]])
            json.dumps(out, ensure_ascii=False)


def make_days(spec):
    """spec: {data: {id: odsłuchy}} → dni taste."""
    return {day: dict(row) for day, row in spec.items()}


class WrappedTest(unittest.TestCase):
    def setUp(self):
        self.songs = [song(str(i), f"Artist{i % 4}", year=2024 if i % 2 else 1985, genre=("Rock", "Pop")[i % 2])
                      for i in range(1, 41)]

    def taste(self, days, since="2026-01-01"):
        return {"days": make_days(days), "since": since}

    def test_month_summary_and_compare_with_same_days(self):
        days = {}
        for d in range(1, 11):                                    # października: 10 dni po 4 odsłuchy
            days[f"2026-10-{d:02d}"] = {"1": 2, "2": 2}
        for d in range(1, 16):                                    # listopad (do 16.): 15 dni po 6 odsłuchów
            days[f"2026-11-{d:02d}"] = {"1": 2, "2": 2, "3": 2}
        out = stats.wrapped(self.songs, self.taste(days), [], NOW)
        by_key = {p["key"]: p for p in out["periods"]}
        nov = by_key["2026-11"]
        self.assertEqual((nov["plays"], nov["tracks"], nov["active_days"]), (90, 3, 15))
        self.assertTrue(nov["partial"])
        self.assertEqual(nov["end"], "2026-11-16")
        self.assertEqual(nov["longest_streak"], 15)
        self.assertEqual(nov["label"], "Listopad 2026")
        # porównanie: pierwszych 16 dni października = 10 dni × 4 odsłuchy
        self.assertEqual(nov["compare"]["prev_plays"], 40)
        self.assertEqual(nov["compare"]["plays"], 125)
        self.assertEqual(by_key["2026-10"]["plays"], 40)
        self.assertEqual(out["periods"][0]["key"], "2026-11")     # najnowszy pierwszy

    def test_year_period_and_new_artists_need_history_before_period(self):
        days = {"2026-03-01": {"1": 1, "2": 1}, "2026-11-05": {"1": 1, "3": 4, "4": 1}}
        out = stats.wrapped(self.songs, self.taste(days), [], NOW)
        by_key = {p["key"]: p for p in out["periods"]}
        self.assertEqual(by_key["2026"]["plays"], 8)
        self.assertIsNone(by_key["2026"]["new_artists"])          # historia zaczyna się 1 stycznia, więc rok cały nie jest „nowy”
        nov = by_key["2026-11"]
        self.assertEqual(nov["new_artists"], 2)                   # Artist3 i Artist0 dochodzą dopiero w listopadzie
        self.assertEqual(nov["top_artists"][0]["name"], "Artist3")

    def test_tracks_and_artists_carry_ids_and_covers_for_story_slides(self):
        songs = [song("1", "A", albumId="alX", duration=181), song("2", "A", albumId="alY"),
                 song("3", "B", albumId="", coverArt="mf-3")]
        days = {"2026-11-05": {"1": 3, "2": 9, "3": 4}}
        nov = stats.wrapped(songs, self.taste(days), [], NOW)["periods"][0]
        self.assertEqual([t["id"] for t in nov["top_tracks"]], ["2", "3", "1"])
        self.assertEqual([t["cover"] for t in nov["top_tracks"]], ["al-alY", "mf-3", "al-alX"])
        self.assertEqual(nov["top_tracks"][2]["seconds"], 181)
        # okładka wykonawcy to okładka jego najczęściej słuchanego utworu (A: utwór 2 → alY)
        self.assertEqual([(a["name"], a["cover"]) for a in nov["top_artists"]], [("A", "al-alY"), ("B", "mf-3")])

    def test_genres_are_limited_to_five(self):
        songs = [song(str(i), f"A{i}", genre=f"G{i}") for i in range(1, 8)]
        days = {"2026-11-05": {str(i): 8 - i for i in range(1, 8)}}
        nov = stats.wrapped(songs, self.taste(days), [], NOW)["periods"][0]
        self.assertEqual([g[0] for g in nov["top_genres"]], ["G1", "G2", "G3", "G4", "G5"])

    def test_compare_hidden_when_history_does_not_cover_previous_period(self):
        days = {"2026-11-05": {"1": 3}}
        out = stats.wrapped(self.songs, self.taste(days, since="2026-11-01"), [], NOW)
        self.assertIsNone(out["periods"][0]["compare"])

    def test_deleted_songs_and_empty_periods_are_skipped(self):
        days = {"2026-11-05": {"999": 5}}
        self.assertEqual(stats.wrapped(self.songs, self.taste(days), [], NOW)["periods"], [])

    def test_streak_and_busiest_day(self):
        days = {"2026-11-01": {"1": 1}, "2026-11-02": {"1": 1}, "2026-11-03": {"1": 7}, "2026-11-08": {"1": 1}}
        nov = stats.wrapped(self.songs, self.taste(days), [], NOW)["periods"][0]
        self.assertEqual(nov["longest_streak"], 3)
        self.assertEqual(nov["busiest_day"], {"day": "2026-11-03", "plays": 7})

    def test_night_share_needs_enough_scrobbles(self):
        days = {"2026-11-05": {str(i): 1 for i in range(1, 21)}}
        local = datetime(2026, 11, 5, 23, 30).astimezone()
        late = [("A", "t", local)] * (stats.MIN_SCROBBLES + 5)
        nov = stats.wrapped(self.songs, self.taste(days, since="2026-11-01"), late, NOW)["periods"][0]
        self.assertEqual(nov["night_share"], 1.0)
        self.assertEqual(nov["peak_hour"], 23)
        self.assertEqual(nov["listener"]["id"], "night")
        few = stats.wrapped(self.songs, self.taste(days, since="2026-11-01"), late[:5], NOW)["periods"][0]
        self.assertIsNone(few["night_share"])

    def test_internal_fields_are_not_exposed(self):
        nov = stats.wrapped(self.songs, self.taste({"2026-11-05": {"1": 3}}), [], NOW)["periods"][0]
        for hidden in ("dated_plays", "old_plays", "recent_plays"):
            self.assertNotIn(hidden, nov)

    def test_february_29_previous_year_window(self):
        self.assertEqual(stats.shift_year(date(2024, 2, 29), -1), date(2023, 2, 28))


def metrics(**over):
    base = {"plays": 100, "tracks": 50, "artists": 20, "minutes": 3000, "active_days": 10, "days_elapsed": 30,
            "top_artists": [{"name": "X", "plays": 10}, {"name": "Y", "plays": 8}, {"name": "Z", "plays": 6}],
            "new_artists": None, "night_share": None, "dated_plays": 0, "old_plays": 0, "recent_plays": 0}
    base.update(over)
    return base


class ArchetypeTest(unittest.TestCase):
    def test_too_few_plays_gives_none(self):
        self.assertIsNone(stats.archetype(metrics(plays=stats.MIN_PLAYS - 1)))

    def test_explorer(self):
        got = stats.archetype(metrics(plays=100, tracks=95, artists=60, new_artists=40))
        self.assertEqual(got["id"], "explorer")
        self.assertEqual(got["name"], "Eksplorator")
        self.assertTrue(got["why"])

    def test_loyal_fan(self):
        got = stats.archetype(metrics(tracks=40, top_artists=[{"name": "Muse", "plays": 55}, {"name": "B", "plays": 5}]))
        self.assertEqual(got["id"], "loyal")
        self.assertIn("Muse", got["why"][0])

    def test_marathon_needs_both_regular_and_long(self):
        got = stats.archetype(metrics(active_days=29, days_elapsed=30, minutes=29 * 120, tracks=60))
        self.assertEqual(got["id"], "marathon")
        short = stats.archetype(metrics(active_days=29, days_elapsed=30, minutes=29 * 30, tracks=60))
        self.assertNotEqual(short["id"], "marathon")

    def test_repeater(self):
        got = stats.archetype(metrics(plays=200, tracks=30, top_artists=[{"name": "X", "plays": 30}] * 3))
        self.assertEqual(got["id"], "repeater")

    def test_retro_and_fresh_need_year_coverage(self):
        retro = stats.archetype(metrics(dated_plays=90, old_plays=80, recent_plays=2))
        self.assertEqual(retro["id"], "retro")
        fresh = stats.archetype(metrics(dated_plays=90, old_plays=2, recent_plays=80))
        self.assertEqual(fresh["id"], "fresh")
        unknown_years = stats.archetype(metrics(dated_plays=10, old_plays=10))
        self.assertNotEqual(unknown_years["id"], "retro")

    def test_numbers_in_explanations_use_polish_decimal_comma(self):
        self.assertEqual(stats.pl_number(30.0), "30")
        self.assertEqual(stats.pl_number(7.5), "7,5")
        got = stats.archetype(metrics(plays=200, tracks=40, top_artists=[{"name": "X", "plays": 30}] * 3))
        self.assertEqual(got["id"], "repeater")
        self.assertIn("5 razy", got["why"][0])

    def test_fallback_is_steady(self):
        got = stats.archetype(metrics(tracks=40, artists=25))
        self.assertEqual(got["id"], "steady")


class BuildTest(unittest.TestCase):
    def test_build_and_json_roundtrip(self):
        songs = [song(str(i), f"Artist{i}") for i in range(5)]
        data = stats.build(songs, {}, {"days": {"2026-11-05": {"1": 20}}, "since": "2026-01-01"}, {}, {}, [], NOW)
        self.assertEqual(data["version"], stats.VERSION)
        self.assertEqual(set(data), {"version", "generated", "library", "server", "wrapped"})
        json.dumps(data, ensure_ascii=False)

    def test_folder_size(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "a").mkdir()
            (Path(tmp) / "a" / "x.bin").write_bytes(b"12345")
            (Path(tmp) / "y.bin").write_bytes(b"123")
            self.assertEqual(stats.folder_size(tmp), {"files": 2, "bytes": 8})
            self.assertEqual(stats.folder_size(Path(tmp) / "none"), {"files": 0, "bytes": 0})


if __name__ == "__main__":
    unittest.main()
