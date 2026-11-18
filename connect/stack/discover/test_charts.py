import unittest
from datetime import datetime

import charts


def song(i, artist, title, isrc=None, duration=200, **extra):
    s = {"id": f"s{i}", "artist": artist, "albumArtist": artist, "title": title, "duration": duration, "bitRate": 320,
         "tags": {"isrc": [isrc]} if isrc else {}}
    s.update(extra)
    return s


def entry(rank, artist, title, isrc=None, duration=200):
    return {"rank": rank, "artist": artist, "title": title, "isrc": isrc, "duration": duration}


class CleanTitle(unittest.TestCase):
    def test_strips_features_and_edit_labels_but_keeps_other_versions(self):
        self.assertEqual(charts.clean_title("Flower In A Hurricane (feat. Tame Impala, SZA)"), "flower in a hurricane")
        self.assertEqual(charts.clean_title("Ego - Radio Edit"), "ego")
        self.assertEqual(charts.clean_title("Song (Remastered 2011)"), "song")
        self.assertEqual(charts.clean_title("Song (Original Mix)"), "song")
        self.assertEqual(charts.clean_title("Song feat. Someone"), "song")
        self.assertEqual(charts.clean_title("Miłość jest piękna"), "milosc jest piekna")
        self.assertEqual(charts.clean_title("Milosc jest piekna"), charts.clean_title("Miłość jest piękna"))
        # inne nagrania zostają osobne
        self.assertNotEqual(charts.clean_title("AKAI (Slowed)"), charts.clean_title("AKAI"))
        self.assertNotEqual(charts.clean_title("Song - Remix"), charts.clean_title("Song"))

    def test_artist_keys_split_collaborations(self):
        self.assertTrue(charts.artist_keys("A$AP Rocky feat. Drake, 2 Chainz & Kendrick Lamar") >= {"2 chainz", "kendrick lamar"})
        self.assertIn("don omar", charts.artist_keys("Lucenzo; Don Omar"))
        self.assertIn("pro8l3m", charts.artist_keys("Pro8l3m"))

    def test_isrc_is_normalised(self):
        self.assertEqual(charts.isrc_of(song(1, "a", "b", isrc="us-abc-24-00001")), "USABC2400001")
        self.assertEqual(charts.isrc_of(song(1, "a", "b", isrc="USABC2400001")), "USABC2400001")
        self.assertIsNone(charts.isrc_of(song(1, "a", "b", isrc="short")))
        self.assertIsNone(charts.isrc_of(song(1, "a", "b")))


class Match(unittest.TestCase):
    def test_isrc_wins_over_a_different_spelling(self):
        songs = [song(1, "Hugel", "Movin To The Sun (Extended)", isrc="FRZ112500001")]
        hits = charts.match([entry(1, "HUGEL", "Movin' To The Sun", isrc="frz112500001")], songs)
        self.assertEqual([(e["rank"], s["id"], how) for e, s, how in hits], [(1, "s1", "isrc")])

    def test_title_and_artist_match_with_duration_check(self):
        songs = [song(1, "Willy William", "Ego - Radio Edit", duration=181),
                 song(2, "Willy William", "Ego", duration=95)]  # za krótka wersja
        hits = charts.match([entry(1, "Willy William", "Ego", duration=183)], songs)
        self.assertEqual([(s["id"], how) for _, s, how in hits], [("s1", "title")])

    def test_artist_must_overlap_and_versions_do_not_mix(self):
        songs = [song(1, "Ktoś Inny", "Housewife"), song(2, "Tate McRae", "Housewife (Slowed)")]
        self.assertEqual(charts.match([entry(1, "Tate McRae", "Housewife")], songs), [])

    def test_feature_in_either_artist_field_matches(self):
        songs = [song(1, "Lucenzo; Don Omar", "Danza Kuduro")]
        self.assertEqual(len(charts.match([entry(1, "Don Omar", "Danza Kuduro")], songs)), 1)

    def test_order_follows_chart_and_a_song_is_used_once(self):
        songs = [song(1, "A", "One"), song(2, "B", "Two"), song(3, "A", "One", bitRate=128)]
        entries = [entry(3, "B", "Two"), entry(1, "A", "One"), entry(2, "A", "One")]
        hits = charts.match(entries, songs)
        self.assertEqual([(e["rank"], s["id"]) for e, s, _ in hits], [(1, "s1"), (2, "s3"), (3, "s2")])

    def test_prefers_non_compilation_and_better_bitrate(self):
        songs = [song(1, "A", "One", compilation=True, bitRate=900), song(2, "A", "One", bitRate=256), song(3, "A", "One", bitRate=320)]
        self.assertEqual(charts.match([entry(1, "A", "One")], songs)[0][1]["id"], "s3")

    def test_unknown_durations_are_accepted(self):
        songs = [song(1, "A", "One", duration=0)]
        self.assertEqual(len(charts.match([entry(1, "A", "One", duration=None)], songs)), 1)


class Missing(unittest.TestCase):
    def test_lists_unmatched_entries_from_the_top_in_rank_order(self):
        entries = [{"rank": r, "id": f"d{r}", "title": f"T{r}", "artist": "A"} for r in (5, 1, 3, 2, 4)]
        songs = [song(1, "A", "T2"), song(2, "A", "T4")]
        hits = charts.match(entries, songs)
        self.assertEqual([e["rank"] for e in charts.missing(entries, hits, 4)], [1, 3])
        self.assertEqual([e["rank"] for e in charts.missing(entries, hits, 10)], [1, 3, 5])
        self.assertEqual(charts.missing(entries, hits, 0), [])


class Depth(unittest.TestCase):
    def test_extension_skips_duplicates_and_continues_ranks(self):
        first = [entry(1, "A", "One"), entry(2, "B", "Two")]
        extra = [{"title": "One (feat. X)", "artist": "A", "isrc": "", "id": "lf1"}, {"title": "Three", "artist": "C", "id": "lf2"},
                 {"title": "Four", "artist": "D", "id": "lf3"}, {"title": "Five", "artist": "E", "id": "lf4"}]
        got = charts.extend(first, extra, 4)
        self.assertEqual([(e["rank"], e["title"]) for e in got], [(1, "One"), (2, "Two"), (3, "Three"), (4, "Four")])
        self.assertEqual(len(charts.extend(first, extra, 100)), 5)
        self.assertEqual(charts.extend(first, [], 10), first)

    def test_names_follow_depth(self):
        chart = charts.CHARTS[0]
        self.assertEqual(charts.display_name(chart, 200), "Top 200 Polska")
        self.assertIn("Top 100 Polska", charts.names(chart))
        self.assertIn("Top 1000 Polska", charts.names(chart))
        self.assertEqual(charts.STEPS, (100, 200, 300, 500, 1000))


class Config(unittest.TestCase):
    def test_default_chart_and_names(self):
        self.assertEqual(charts.by_names([])[0].name, "Top 100 Polska")
        self.assertEqual(charts.by_names(["top 100 polska"])[0].ref, "1266972311")
        self.assertEqual(charts.by_names(["nie ma takiej"]), [])

    def test_comment_starts_with_the_prefix_the_apps_look_for(self):
        text = charts.comment(charts.CHARTS[0], 31, 100, datetime(2026, 11, 18, 12, 0))
        self.assertTrue(text.startswith(charts.COMMENT_PREFIX))
        self.assertIn("31/100", text)


if __name__ == "__main__":
    unittest.main()
