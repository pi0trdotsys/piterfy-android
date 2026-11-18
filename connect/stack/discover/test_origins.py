import unittest

import mixes
import origins


def song(i, artist, genre="pop", lang=None, isrc=None, **extra):
    tags = {}
    if lang:
        tags["language"] = [lang]
    if isrc:
        tags["isrc"] = [isrc]
    s = {"id": f"s{i}", "artist": artist, "albumArtist": artist, "title": f"t{i}", "genre": genre, "duration": 200, "tags": tags}
    s.update(extra)
    return s


class Signals(unittest.TestCase):
    def test_languages_are_normalised_across_spellings(self):
        self.assertEqual(origins.languages_of(song(1, "a", lang="pol")), {"pol"})
        self.assertEqual(origins.languages_of(song(1, "a", lang="Polish")), {"pol"})
        self.assertEqual(origins.languages_of(song(1, "a", lang="fr")), {"fra"})
        self.assertEqual(origins.languages_of(song(1, "a", lang="spa; eng")), {"spa", "eng"})
        self.assertEqual(origins.languages_of(song(1, "a", lang="mul")), set())

    def test_isrc_country_and_primary_name(self):
        self.assertEqual(origins.isrc_country(song(1, "a", isrc="FR-Z03-24-00001")), "FR")
        self.assertIsNone(origins.isrc_country(song(1, "a", isrc="bad")))
        self.assertEqual(origins.primary_name(song(1, "Lucenzo; Don Omar")), "Lucenzo")
        self.assertEqual(origins.primary_name(song(1, "A$AP Rocky feat. Drake")), "A$AP Rocky")
        self.assertEqual(origins.primary_name(song(1, "Tyler, The Creator")), "Tyler, The Creator")   # przecinek to część nazwy
        self.assertEqual(origins.primary_name(song(1, "Earth, Wind & Fire")), "Earth, Wind & Fire")
        self.assertEqual(origins.primary_name(song(1, "HXVSAGE • Mc Luizinho")), "HXVSAGE")

    def test_relevant_tags_keep_origin_tags_even_when_weak(self):
        tags = [("rap", 100), ("Hip-Hop", 78), ("polish", 37), ("seen live", 3), ("french", 4), ("x", 1)] + [(f"t{i}", 50 - i) for i in range(20)]
        kept = origins.relevant_tags(tags, limit=5)
        self.assertEqual(kept["polish"], 37)
        self.assertEqual(kept["french"], 4)
        self.assertNotIn("x", kept)
        self.assertLessEqual(len([t for t in kept if t not in ("polish", "french")]), 5)


class Points(unittest.TestCase):
    def test_two_weak_signals_make_a_match_but_one_does_not(self):
        french = origins.BY_KEY["french"]
        self.assertEqual(origins.points(french, song(1, "Willy William", isrc="FRZ112500001"), {"french": 7}), 2)   # tag słaby + ISRC
        self.assertEqual(origins.points(french, song(2, "2 Chainz", isrc="FRZ112500001"), {"rap": 100}), 1)           # sam ISRC to za mało
        self.assertEqual(origins.points(french, song(3, "X"), {"french": 40}), 2)                                      # mocny tag
        self.assertEqual(origins.points(french, song(4, "X", lang="fra"), {}), 2)                                      # język
        self.assertEqual(origins.points(french, song(5, "Daft Punk"), {"electronic": 100, "french": 6}), 1)

    def test_genre_requirement_for_polish_rap(self):
        pr = origins.BY_KEY["polish-rap"]
        self.assertGreaterEqual(origins.points(pr, song(1, "Pro8l3m", genre="hip hop", lang="pol"), {"rap": 100, "polish": 37}), 2)
        self.assertEqual(origins.points(pr, song(2, "Myslovitz", genre="rock", lang="pol"), {"polish": 60}), 0)
        self.assertGreaterEqual(origins.points(origins.BY_KEY["polish"], song(2, "Myslovitz", genre="rock", lang="pol"), {"polish": 60}), 2)

    def test_library_genre_counts_as_a_signal(self):
        self.assertGreaterEqual(origins.points(origins.BY_KEY["latin"], song(1, "Bad Bunny", genre="reggaeton"), {}), 2)
        self.assertEqual(origins.points(origins.BY_KEY["latin"], song(2, "Someone", genre="pop"), {}), 0)


class Classify(unittest.TestCase):
    def test_groups_by_origin_and_caches_artist_lookups(self):
        songs = [song(1, "Pro8l3m", genre="hip hop", lang="pol"), song(2, "Pro8l3m", genre="hip hop"), song(3, "Las Ketchup"),
                 song(4, "Rammstein", genre="metal"), song(5, "Eminem", genre="rap")]
        lookups = []
        tags = {"pro8l3m": {"rap": 100, "polish": 37}, "las ketchup": {"spanish": 100, "latin": 50}, "rammstein": {"german": 36},
                "eminem": {"rap": 100, "american": 3}}

        def tags_of(key):
            lookups.append(key)
            return tags.get(key)

        got = origins.classify(songs, tags_of)
        self.assertEqual(got["polish-rap"], {"s1", "s2"})
        self.assertEqual(got["polish"], {"s1", "s2"})
        self.assertEqual(got["latin"], {"s3"})
        self.assertEqual(got["german"], {"s4"})
        self.assertEqual(got["french"], set())
        self.assertEqual(lookups.count("pro8l3m"), 1)


class MixIntegration(unittest.TestCase):
    def test_origin_and_decade_mixes_are_defined(self):
        by = {m.name: m for m in mixes.MIXES}
        self.assertEqual(by["French Music"].origin, "french")
        self.assertEqual(by["Polski Rap"].origin, "polish-rap")
        self.assertEqual(by["Lata 90."].years, (1990, 1999))
        self.assertTrue(all(o.key in origins.BY_KEY for o in (type("x", (), {"key": m.origin}) for m in mixes.MIXES if m.origin)))

    def test_pick_uses_include_and_decade_rule(self):
        by = {m.name: m for m in mixes.MIXES}
        songs = [song(i, f"artist{i}", year=1995) for i in range(30)] + [song(100 + i, f"b{i}", year=2010) for i in range(30)]
        got = mixes.pick(by["Lata 90."], songs, base=10, cap=40)
        self.assertTrue(got and all(i.startswith("s") and int(i[1:]) < 30 for i in got))
        only = {f"s{i}" for i in range(100, 120)}
        got = mixes.pick(by["French Music"], songs, base=10, cap=40, include=lambda s: s["id"] in only)
        self.assertTrue(got and set(got) <= only)
        self.assertEqual(mixes.pick(by["French Music"], songs, include=lambda s: False), [])


if __name__ == "__main__":
    unittest.main()
