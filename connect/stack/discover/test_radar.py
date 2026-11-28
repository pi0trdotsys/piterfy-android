import json
import os
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

import radar

TODAY = date(2026, 11, 28)


def song(i, artist, title="T", album="A", plays=0, **extra):
    s = {"id": f"s{i}", "artist": artist, "albumArtist": artist, "title": title, "album": album, "playCount": plays}
    s.update(extra)
    return s


def album(i, title, released, kind="album", tracks=0, **extra):
    a = {"id": i, "title": title, "release_date": released, "record_type": kind, "nb_tracks": tracks, "cover_medium": f"https://c/{i}.jpg"}
    a.update(extra)
    return a


class Keys(unittest.TestCase):
    def test_album_key_merges_editions(self):
        self.assertEqual(radar.album_key("Utopia (Deluxe Edition)"), radar.album_key("Utopia"))
        self.assertEqual(radar.album_key("Utopia - Remastered 2020"), radar.album_key("UTOPIA"))
        self.assertEqual(radar.album_key("Hit EP"), radar.album_key("Hit"))
        self.assertEqual(radar.album_key("Miłość - Single"), radar.album_key("Milosc"))
        self.assertNotEqual(radar.album_key("Utopia"), radar.album_key("Astroworld"))

    def test_recent_window(self):
        self.assertTrue(radar.is_recent("2026-11-20", TODAY, 60))
        self.assertTrue(radar.is_recent("2026-09-30", TODAY, 60))
        self.assertFalse(radar.is_recent("2026-09-20", TODAY, 60))
        self.assertFalse(radar.is_recent("2027-02-01", TODAY, 60))      # zapowiedź z daleka nie jest nowością
        self.assertFalse(radar.is_recent("", TODAY, 60))
        self.assertFalse(radar.is_recent("nie data", TODAY, 60))


class Picking(unittest.TestCase):
    def test_only_new_unowned_albums_singles_and_eps(self):
        index = radar.owned_index([song(1, "Taco Hemingway", "Jarmark", "Jarmark"), song(2, "Taco Hemingway", "Wszystko", "Single Wszystko")])
        albums = [album(1, "Jarmark (Deluxe)", "2026-11-10"),          # już masz (wersja deluxe)
                  album(2, "Nowy album", "2026-11-12", tracks=12),
                  album(3, "Stary album", "2025-01-01"),                # za stary
                  album(4, "Wszystko", "2026-11-15", kind="single"),     # single = utwór, który masz
                  album(5, "Nowy singiel", "2026-11-20", kind="single"),
                  album(6, "Składanka", "2026-11-20", kind="compile")]  # kompilacje pomijamy
        got = radar.pick_new("Taco Hemingway", albums, index, TODAY, 60)
        self.assertEqual([a["title"] for a in got], ["Nowy singiel", "Nowy album"])   # od najnowszego
        self.assertEqual(got[1]["tracks"], 12)
        self.assertTrue(all(a["status"] == "new" and a["cover"].startswith("https://") for a in got))

    def test_versions_of_one_release_collapse_to_the_bigger_one(self):
        albums = [album(1, "Utopia", "2026-11-10", tracks=10), album(2, "Utopia (Deluxe)", "2026-11-10", tracks=14), album(3, "UTOPIA", "2026-11-11", tracks=10)]
        got = radar.pick_new("Travis Scott", albums, radar.owned_index([]), TODAY, 60)
        self.assertEqual([(a["id"], a["tracks"]) for a in got], [("2", 14)])

    def test_owned_by_featured_artist_name(self):
        index = radar.owned_index([song(1, "Mata feat. Taco Hemingway", "X", "Album Y", albumArtist="Mata")])
        self.assertTrue(radar.is_owned("Mata", "Album Y", "album", index))
        self.assertFalse(radar.is_owned("Taco Hemingway", "Album Y", "album", index))


class Artists(unittest.TestCase):
    def test_ranking_prefers_plays_stars_and_recency(self):
        now = datetime(2026, 11, 28, tzinfo=timezone.utc)
        songs = [song(1, "Rzadko", plays=1, playDate="2025-01-01T00:00:00Z"),
                 song(2, "Często", plays=80, playDate="2026-11-20T00:00:00Z"),
                 song(3, "Gwiazdka", plays=0, starred=True),
                 song(4, "Various Artists", plays=999), song(5, "Podcast", plays=999, type="podcast"),
                 song(6, "Często", plays=10)]
        self.assertEqual(radar.top_artists(songs, 3, now), ["Często", "Gwiazdka", "Rzadko"])
        self.assertEqual(radar.top_artists(songs, 1, now), ["Często"])

    def test_limit_and_empty(self):
        self.assertEqual(radar.top_artists([], 5), [])
        self.assertEqual(len(radar.top_artists([song(i, f"a{i}", plays=i) for i in range(20)], 7)), 7)


class Merging(unittest.TestCase):
    def test_requested_state_survives_a_refresh_and_old_requests_expire(self):
        previous = [{"id": "1", "artist": "A", "title": "T", "released": "2026-11-10", "status": "queued", "have": 3, "total": 10},
                    {"id": "9", "artist": "A", "title": "Old", "released": "2025-01-01", "status": "done"},
                    {"id": "5", "artist": "A", "title": "Stale new", "released": "2026-11-01", "status": "new"}]
        fresh = [{"id": "1", "artist": "A", "title": "T", "released": "2026-11-10", "status": "new"},
                 {"id": "2", "artist": "A", "title": "U", "released": "2026-11-20", "status": "new"}]
        got = radar.merge(previous, fresh, TODAY)
        self.assertEqual([i["id"] for i in got], ["2", "1"])
        self.assertEqual(got[1]["status"], "queued")
        self.assertEqual(got[1]["have"], 3)


class Refresh(unittest.TestCase):
    def deezer(self):
        calls = []
        data = {"search/artist": {"data": [{"id": 7, "name": "Taco Hemingway", "nb_fan": 100}, {"id": 8, "name": "Taco", "nb_fan": 999}]},
                "artist/7/albums": {"data": [album(21, "Nowy", "2026-11-18", tracks=9)]}}

        def call(path, **params):
            calls.append(path)
            return data.get(path, {})
        return call, calls

    def test_refresh_resolves_artists_once_and_writes_the_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            call, calls = self.deezer()
            state, logs = {}, []
            songs = [song(1, "Taco Hemingway", plays=50)]
            items = radar.refresh(call, songs, state, tmp, logs.append, today=TODAY)
            self.assertEqual([(i["artist"], i["title"], i["tracks"]) for i in items], [("Taco Hemingway", "Nowy", 9)])
            self.assertEqual(state["radar_artist_ids"]["taco hemingway"]["id"], 7)   # dokładna nazwa wygrywa z „Taco”
            self.assertEqual(json.loads((Path(tmp) / "radar.json").read_text())[0]["id"], "21")
            calls.clear()
            radar.refresh(call, songs, state, tmp, logs.append, today=TODAY)
            self.assertNotIn("search/artist", calls)       # id wykonawcy z pamięci
            radar.refresh(call, [song(2, "Nieznany", plays=1)], state, tmp, logs.append, today=TODAY)

    def test_unknown_artist_is_cached_as_missing_for_thirty_days(self):
        cache = {}
        radar.resolve_artist(lambda path, **p: {"data": []}, "Nikt", cache, TODAY)
        self.assertIsNone(cache["nikt"]["id"])
        asked = []
        radar.resolve_artist(lambda path, **p: asked.append(path) or {"data": []}, "Nikt", cache, date(2026, 12, 10))
        self.assertEqual(asked, [])
        radar.resolve_artist(lambda path, **p: asked.append(path) or {"data": []}, "Nikt", cache, date(2027, 1, 15))
        self.assertEqual(asked, ["search/artist"])

    def test_dry_run_does_not_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            call, _ = self.deezer()
            radar.refresh(call, [song(1, "Taco Hemingway", plays=5)], {}, tmp, lambda m: None, today=TODAY, dry_run=True)
            self.assertFalse((Path(tmp) / "radar.json").exists())


class Requests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        radar.save(self.dir, [{"id": "1", "artist": "A", "title": "T", "released": "2026-11-10", "status": "new"},
                              {"id": "2", "artist": "B", "title": "U", "released": "2026-11-11", "status": "new"}])
        self.old = os.environ.get("DISCOVER_CHART_FILL")

    def tearDown(self):
        if self.old is None:
            os.environ.pop("DISCOVER_CHART_FILL", None)
        else:
            os.environ["DISCOVER_CHART_FILL"] = self.old
        self.tmp.cleanup()

    def test_disabled_fill_gives_a_clear_note_and_queues_nothing(self):
        os.environ["DISCOVER_CHART_FILL"] = "0"
        item, code = radar.request_fetch(self.dir, "1")
        self.assertEqual((code, item["status"]), ("disabled", "disabled"))
        self.assertIn("DISCOVER_CHART_FILL", item["note"])
        self.assertIsNone(radar.next_job(self.dir))

    def test_queue_one_at_a_time_and_finish(self):
        os.environ["DISCOVER_CHART_FILL"] = "1"
        self.assertEqual(radar.request_fetch(self.dir, "2")[1], "queued")
        self.assertEqual(radar.request_fetch(self.dir, "1")[1], "queued")
        self.assertEqual(radar.request_fetch(self.dir, "1")[1], "already")
        self.assertEqual(radar.request_fetch(self.dir, "404")[1], "unknown")
        self.assertEqual(radar.next_job(self.dir)["id"], "2")          # najstarsza prośba pierwsza
        radar.finish(self.dir, "2", "queued", have=4, total=10)       # w toku: zostaje w kolejce
        self.assertEqual(radar.next_job(self.dir)["id"], "2")
        radar.finish(self.dir, "2", "done", note="Są 9 z 10 utworów.", have=9, total=10)
        self.assertEqual(radar.next_job(self.dir)["id"], "1")
        done = [i for i in radar.load(self.dir) if i["id"] == "2"][0]
        self.assertEqual((done["status"], done["have"], done["total"]), ("done", 9, 10))
        radar.finish(self.dir, "1", "failed", note="Soulseek nie odpowiada.")
        self.assertIsNone(radar.next_job(self.dir))

    def test_album_entries(self):
        got = radar.album_entries([{"id": 5, "title": "Intro", "track_position": 1, "duration": 60, "artist": {"name": "X"}}, {"id": 6, "title": "Bez pozycji"}], "Y")
        self.assertEqual([(e["rank"], e["artist"], e["id"]) for e in got], [(1, "X", "dz:5"), (2, "Y", "dz:6")])

    def test_enabled_switch(self):
        old = os.environ.get("DISCOVER_RADAR")
        try:
            for value, expected in (("all", True), ("1", True), ("0", False), ("", False), ("off", False)):
                os.environ["DISCOVER_RADAR"] = value
                self.assertEqual(radar.enabled(), expected, value)
        finally:
            if old is None:
                os.environ.pop("DISCOVER_RADAR", None)
            else:
                os.environ["DISCOVER_RADAR"] = old


class Routes(unittest.TestCase):
    def test_routes_register_and_answer(self):
        class FakeApi:
            def __init__(self):
                self.routes = {}

            def register(self, method, pattern, function, auth=True):
                self.routes[(method, pattern)] = function

        class FakeHandler:
            def __init__(self, body=None):
                self.body, self.sent = body, None

            def read_json(self):
                return self.body

            def send(self, code, body, ctype="text/plain", headers=None):
                self.sent = (code, body)

        with tempfile.TemporaryDirectory() as tmp:
            os.environ["DISCOVER_CHART_FILL"] = "1"
            radar.save(tmp, [{"id": "1", "artist": "A", "title": "T", "released": "2026-11-10", "status": "new"}])
            api = FakeApi()
            radar.register_routes(api, tmp, lambda m: None)
            h = FakeHandler()
            api.routes[("GET", r"/piterfy-api/radar")](h, None, "ja")
            self.assertEqual(h.sent[0], 200)
            self.assertEqual(json.loads(h.sent[1])["albums"][0]["id"], "1")
            self.assertTrue(json.loads(h.sent[1])["fill"])
            h = FakeHandler({"id": "1"})
            api.routes[("POST", r"/piterfy-api/radar/fetch")](h, None, "ja")
            self.assertEqual(json.loads(h.sent[1])["result"], "queued")
            h = FakeHandler({"id": "nie ma"})
            api.routes[("POST", r"/piterfy-api/radar/fetch")](h, None, "ja")
            self.assertEqual(h.sent[0], 404)
            os.environ.pop("DISCOVER_CHART_FILL", None)


if __name__ == "__main__":
    unittest.main()
