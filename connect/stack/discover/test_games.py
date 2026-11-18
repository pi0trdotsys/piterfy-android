import unittest
from datetime import datetime

import games


def album(i, title, tracks=30):
    return {"id": i, "title": title, "nb_tracks": tracks, "artist": {"name": "X"}}


def song(i, title, artist="Marcin Przybyłowicz", album="", **extra):
    s = {"id": f"s{i}", "title": title, "artist": artist, "albumArtist": artist, "album": album, "duration": 200, "bitRate": 320,
         "trackNumber": i, "discNumber": 1, "tags": {}}
    s.update(extra)
    return s


class Catalog(unittest.TestCase):
    def test_games_are_unique_and_have_keywords(self):
        titles = [g.title for g in games.GAMES]
        self.assertEqual(len(titles), len(set(titles)))
        for g in games.GAMES:
            self.assertTrue(g.words and g.query and g.short, g.title)
            self.assertTrue(g.playlist.startswith("Soundtrack: "))
        self.assertEqual(games.by_names(["the witcher 3"])[0].short, "Witcher 3")
        self.assertEqual(games.by_names(["nie ma"]), [])
        self.assertEqual(len(games.by_names([])), len(games.GAMES))


class PickAlbum(unittest.TestCase):
    def test_takes_the_first_relevant_original_soundtrack_and_rejects_covers(self):
        witcher = games.by_names(["The Witcher 3"])[0]
        results = [album(1, "The Witcher 3: Wild Hunt - Songs of the Past EP", 5), album(2, "Witcher 3 Piano Collection", 40),
                   album(3, "The Witcher 3: Wild Hunt (Original Game Soundtrack)", 34), album(4, "Witcher 3 Lofi Beats", 30),
                   album(5, "The Witcher 3: Wild Hunt (Original Game Soundtrack) (Live at Video Game Show 2016)", 34)]
        self.assertEqual(games.pick_album(witcher, results)["id"], 3)

    def test_requires_a_keyword_and_enough_tracks(self):
        halo = games.by_names(["Halo"])[0]
        self.assertIsNone(games.pick_album(halo, [album(1, "Halo", 3), album(2, "Another Album", 30)]))
        self.assertIsNone(games.pick_album(halo, [album(1, "Halo Infinite Original Soundtrack", 40)]))   # sequel z `reject`
        self.assertEqual(games.pick_album(halo, [album(1, "Halo 3 Original Soundtrack", 30)])["id"], 1)
        self.assertIsNone(games.pick_album(halo, []))
        self.assertIsNone(games.pick_album(halo, None))


    def test_sequels_are_skipped_in_favour_of_the_original(self):
        hades = games.by_names(["Hades"])[0]
        results = [album(1, "Hades II: Original Soundtrack", 52), album(2, "Hades (Original Soundtrack)", 38)]
        self.assertEqual(games.pick_album(hades, results)["id"], 2)
        self.assertEqual(games.pick_album(games.by_names(["Minecraft"])[0], [album(1, "Minecraft - Volume Beta", 30),
                                                                              album(2, "Minecraft - Volume Alpha", 24)])["id"], 2)


class Collect(unittest.TestCase):
    def test_library_album_and_deezer_titles_both_count_and_come_out_in_album_order(self):
        witcher = games.by_names(["The Witcher 3"])[0]
        songs = [song(3, "Geralt of Rivia", album="The Witcher 3: Wild Hunt (Original Game Soundtrack)"),
                 song(1, "Kaer Morhen", album="The Witcher 3: Wild Hunt (Original Game Soundtrack)"),
                 song(7, "Lilac and Gooseberries", artist="Marcin Przybylowicz", album="Compilation"),
                 song(8, "Halo", artist="Beyoncé", album="I Am... Sasha Fierce")]
        entries = games.album_entries([{"id": 1, "title": "Lilac and Gooseberries", "track_position": 12, "duration": 199,
                                        "artist": {"name": "Marcin Przybyłowicz"}},
                                       {"id": 2, "title": "Other", "track_position": 2, "duration": 100, "artist": {"name": "Y"}}])
        found, hits = games.collect(witcher, entries, songs)
        self.assertEqual({s["id"] for s in found}, {"s1", "s3", "s7"})
        self.assertEqual(len(hits), 1)
        self.assertEqual([s["id"] for s in found], ["s7", "s1", "s3"])  # po albumie, numerze płyty i utworu
        self.assertNotIn("s8", {s["id"] for s in found})

    def test_a_song_titled_like_the_game_is_not_enough(self):
        halo = games.by_names(["Halo"])[0]
        self.assertEqual(games.album_songs(halo, [song(1, "Halo", artist="Beyoncé", album="I Am... Sasha Fierce")]), [])

    def test_comment_prefix(self):
        text = games.comment(games.GAMES[0], 12, 24, datetime(2026, 11, 18))
        self.assertTrue(text.startswith(games.COMMENT_PREFIX))
        self.assertIn("12/24", text)


if __name__ == "__main__":
    unittest.main()
