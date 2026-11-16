import random
import unittest
from datetime import datetime, timedelta, timezone

import mixes

NOW = datetime(2026, 11, 16, 12, 0, tzinfo=timezone.utc)
BY_NAME = {m.name: m for m in mixes.MIXES}


def song(sid, genre="", artist=None, **extra):
    base = {"id": sid, "title": f"t{sid}", "artist": artist or f"artist{sid}", "genre": genre, "duration": 200}
    base.update(extra)
    return base


class ClassificationTest(unittest.TestCase):
    def test_genre_matching_is_normalized(self):
        self.assertTrue(mixes.matches(BY_NAME["Bass Boost"], song(1, "Drum & Bass")))
        self.assertTrue(mixes.matches(BY_NAME["Bass Boost"], song(1, "Hip-Hop")))
        self.assertTrue(mixes.matches(BY_NAME["Hip-Hop Mix"], song(1, "R&B")))
        self.assertTrue(mixes.matches(BY_NAME["Electronic Mix"], song(1, "Électronique House")))

    def test_whole_word_only(self):
        self.assertFalse(mixes.matches(BY_NAME["Hip-Hop Mix"], song(1, "Therapy")))   # „rap” w środku słowa
        self.assertFalse(mixes.matches(BY_NAME["Rock Mix"], song(1, "Crockpot")))

    def test_navidrome_genres_list_is_used(self):
        s = song(1, "", genres=[{"id": "x", "name": "Darkwave"}])
        self.assertTrue(mixes.matches(BY_NAME["Dark Mix"], s))

    def test_exclusions_win(self):
        self.assertFalse(mixes.matches(BY_NAME["Happy Mix"], song(1, "Pop Metal")))
        self.assertFalse(mixes.matches(BY_NAME["Dark Mix"], song(1, "Dark Disco")))
        self.assertFalse(mixes.matches(BY_NAME["Bass Boost"], song(1, "Lo-Fi Hip Hop")))

    def test_mood_from_audio_features(self):
        sunny = {"e": 0.8, "v": 0.9, "t": 120, "g": []}
        gloomy = {"e": 0.6, "v": 0.1, "t": 100, "g": []}
        self.assertTrue(mixes.matches(BY_NAME["Happy Mix"], song(1, "Alternative"), sunny))
        self.assertFalse(mixes.matches(BY_NAME["Happy Mix"], song(1, "Alternative"), gloomy))
        self.assertTrue(mixes.matches(BY_NAME["Dark Mix"], song(1, "Alternative"), gloomy))

    def test_mood_vetoes_a_genre_match(self):
        gloomy_pop = {"e": 0.5, "v": 0.05, "t": 90, "g": []}
        self.assertFalse(mixes.matches(BY_NAME["Happy Mix"], song(1, "Pop"), gloomy_pop))
        self.assertTrue(mixes.matches(BY_NAME["Happy Mix"], song(1, "Pop")))  # bez cech decyduje gatunek

    def test_fast_electronic_goes_to_bass_boost(self):
        self.assertTrue(mixes.matches(BY_NAME["Bass Boost"], song(1, "House", bpm=128)))
        self.assertFalse(mixes.matches(BY_NAME["Bass Boost"], song(1, "House", bpm=100)))

    def test_unknown_song_matches_nothing(self):
        for mix in mixes.MIXES:
            self.assertFalse(mixes.matches(mix, song(1, "")), mix.name)

    def test_feature_genres_are_used(self):
        self.assertTrue(mixes.matches(BY_NAME["Electronic Mix"], song(1, ""), {"e": 0.5, "v": 0.5, "t": 120, "g": ["Techno"]}))


class PickTest(unittest.TestCase):
    def library(self, count, genre="Techno"):
        return [song(i, genre) for i in range(count)]

    def test_too_small_pool_creates_nothing(self):
        self.assertEqual(mixes.pick(BY_NAME["Electronic Mix"], self.library(mixes.MIN_POOL - 1), now=NOW), [])

    def test_size_grows_with_library_and_is_capped(self):
        self.assertEqual(mixes.target_size(30, 40, 100), 30)
        self.assertEqual(mixes.target_size(100, 40, 100), 40)
        self.assertEqual(mixes.target_size(300, 40, 100), 60)
        self.assertEqual(mixes.target_size(5000, 40, 100), 100)

    def test_per_artist_cap(self):
        songs = [song(i, "Techno", artist="Same") for i in range(20)] + self.library(30)[20:]
        picked = mixes.pick(BY_NAME["Electronic Mix"], songs, now=NOW, rng=random.Random(1), base=40, max_per_artist=3)
        self.assertEqual(sum(1 for i in picked if i < 20), 3)

    def test_ineligible_songs_are_skipped(self):
        songs = self.library(30)
        songs[0]["rating"] = 1            # nie chcę tego słyszeć
        songs[1]["duration"] = 20         # zbyt krótkie (np. jingle)
        songs[2]["duration"] = 3 * 3600   # zbyt długie (np. set DJ-ski)
        picked = mixes.pick(BY_NAME["Electronic Mix"], songs, now=NOW, rng=random.Random(2), base=100)
        self.assertFalse({0, 1, 2} & set(picked))
        self.assertEqual(len(picked), 27)

    def test_favourites_and_fresh_songs_are_preferred(self):
        songs = self.library(200)
        songs[7]["starred"] = "2026-01-01T00:00:00Z"
        songs[8]["createdAt"] = (NOW - timedelta(days=2)).isoformat()
        runs = [mixes.pick(BY_NAME["Electronic Mix"], songs, now=NOW, rng=random.Random(n)) for n in range(40)]
        self.assertTrue(all(7 in run for run in runs))                    # gwiazdka (+3) pokonuje każde losowanie
        self.assertGreater(sum(8 in run for run in runs), 24)             # świeżość daje przewagę (zwykły utwór: ~20%)

    def test_mix_rotates_between_refreshes(self):
        songs = self.library(300)
        first = mixes.pick(BY_NAME["Electronic Mix"], songs, now=NOW, rng=random.Random(3))
        second = mixes.pick(BY_NAME["Electronic Mix"], songs, previous=first, now=NOW, rng=random.Random(4))
        self.assertEqual(len(first), len(second))
        self.assertLess(len(set(first) & set(second)), len(first) * 0.5)

    def test_returns_unique_ids(self):
        picked = mixes.pick(BY_NAME["Electronic Mix"], self.library(80), now=NOW, rng=random.Random(5))
        self.assertEqual(len(picked), len(set(picked)))


class NamesTest(unittest.TestCase):
    def test_by_names(self):
        self.assertEqual(len(mixes.by_names([])), len(mixes.MIXES))
        self.assertEqual([m.name for m in mixes.by_names(["happy mix", " BASS BOOST "])], ["Happy Mix", "Bass Boost"])
        self.assertEqual(mixes.by_names(["nie ma takiego"]), [])

    def test_comment_has_marker_for_the_app(self):
        self.assertTrue(mixes.comment(BY_NAME["Dark Mix"], 42, NOW).startswith(mixes.COMMENT_PREFIX))

    def test_names_are_unique(self):
        names = [m.name for m in mixes.MIXES]
        self.assertEqual(len(names), len(set(names)))


class FakeNavidrome:
    """Zapamiętuje wywołania Subsonic API i trzyma playlisty w pamięci."""

    def __init__(self, playlists=()):
        self.playlists = {p["id"]: dict(p) for p in playlists}
        self.calls = []

    def call(self, endpoint, **params):
        self.calls.append((endpoint, params))
        if endpoint == "getPlaylists":
            return {"playlists": {"playlist": list(self.playlists.values())}}
        if endpoint == "createPlaylist":
            pid = params.get("playlistId") or f"pl{len(self.playlists) + 1}"
            self.playlists.setdefault(pid, {"id": pid, "name": params.get("name")})["songs"] = params["songId"]
            return {"playlist": {"id": pid}}
        if endpoint == "updatePlaylist":
            self.playlists[params["playlistId"]]["comment"] = params.get("comment")
        return {}


class RefreshTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import os
        import sys
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "discovery"))  # report_formatter
        import discover
        cls.discover = discover

    def setUp(self):
        d = self.discover
        self.songs = ([song(i, "Techno") for i in range(30)] + [song(100 + i, "Pop") for i in range(30)]
                      + [song(200 + i, "Death Metal", genres=[]) for i in range(30)])
        self.saved = (d.Navidrome, d.all_native_songs, d.MIXES, d.log)
        self.fake = FakeNavidrome()
        d.Navidrome = lambda: self.fake
        d.all_native_songs = lambda nd: self.songs
        d.MIXES = "Electronic Mix;Happy Mix;Dark Mix"
        d.log = lambda message: None

    def tearDown(self):
        d = self.discover
        d.Navidrome, d.all_native_songs, d.MIXES, d.log = self.saved

    def test_creates_marked_playlists_and_skips_empty_pools(self):
        state = {}
        self.discover.refresh_mixes(state)
        names = {p["name"]: p for p in self.fake.playlists.values()}
        self.assertEqual(set(names), {"Electronic Mix", "Happy Mix", "Dark Mix"})
        self.assertTrue(names["Electronic Mix"]["comment"].startswith(mixes.COMMENT_PREFIX))
        self.assertEqual(len(state["mixes"]["Electronic Mix"]["ids"]), 30)
        self.assertTrue(all(i < 30 for i in names["Electronic Mix"]["songs"]))
        self.assertTrue(all(100 <= i < 130 for i in names["Happy Mix"]["songs"]))
        self.assertTrue(all(i >= 200 for i in names["Dark Mix"]["songs"]))

    def test_second_run_updates_in_place_and_respects_the_interval(self):
        state = {}
        self.discover.refresh_mixes(state)
        created = len(self.fake.playlists)
        calls = len(self.fake.calls)
        self.discover.refresh_mixes(state)                 # za wcześnie — nic się nie dzieje
        self.assertEqual(len(self.fake.calls), calls)
        self.discover.refresh_mixes(state, force=True)     # po nowej muzyce — odświeża te same playlisty
        self.assertEqual(len(self.fake.playlists), created)

    def test_manually_deleted_mix_does_not_come_back(self):
        state = {}
        self.discover.refresh_mixes(state)
        gone = state["mixes"]["Happy Mix"]["id"]
        del self.fake.playlists[gone]
        self.discover.refresh_mixes(state, force=True)
        self.assertNotIn("Happy Mix", {p["name"] for p in self.fake.playlists.values()})
        self.assertTrue(state["mixes"]["Happy Mix"]["removed"])

    def test_foreign_playlist_with_same_name_is_not_overwritten(self):
        self.fake.playlists["mine"] = {"id": "mine", "name": "Dark Mix", "comment": "moja lista", "songs": ["x"]}
        state = {}
        self.discover.refresh_mixes(state)
        self.assertEqual(self.fake.playlists["mine"]["songs"], ["x"])

    def test_disabled_and_unknown_names(self):
        self.discover.MIXES = ""
        self.discover.refresh_mixes({})
        self.discover.MIXES = "Nie ma takiego"
        self.discover.refresh_mixes({})
        self.assertEqual(self.fake.calls, [])


if __name__ == "__main__":
    unittest.main()
