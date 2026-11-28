import unittest
from datetime import date, datetime, timedelta, timezone

import yearago


def song(i, artist, title):
    return {"id": f"s{i}", "artist": artist, "albumArtist": artist, "title": title, "duration": 200, "tags": {}}


def utc(y, m, d):
    return datetime(y, m, d, 12, tzinfo=timezone.utc)


class Window(unittest.TestCase):
    def test_window_is_a_year_back_plus_minus_a_week(self):
        start, end = yearago.window(date(2026, 11, 28))
        self.assertEqual(start, datetime(2025, 11, 21, tzinfo=timezone.utc))
        self.assertEqual(end, datetime(2025, 12, 6, tzinfo=timezone.utc))      # koniec wyłączny: cały dzień +7

    def test_leap_day_and_year_boundaries(self):
        start, end = yearago.window(date(2028, 2, 29))
        self.assertEqual((start.year, start.month, start.day), (2027, 2, 21))
        start, end = yearago.window(date(2026, 1, 3))
        self.assertEqual((start.year, start.month, start.day), (2024, 12, 27))
        self.assertEqual((end.year, end.month, end.day), (2025, 1, 11))


class Fetching(unittest.TestCase):
    def test_pages_and_missing_dates(self):
        pages = {1: {"recenttracks": {"@attr": {"totalPages": "2"}, "track": [
                    {"artist": {"#text": "A"}, "name": "t1", "date": {"uts": "1764000000"}},
                    {"artist": {"#text": "B"}, "name": "teraz gra"}]}},   # bez daty = właśnie grane
                 2: {"recenttracks": {"@attr": {"totalPages": "2"}, "track": {"artist": {"name": "C"}, "name": "t2", "date": {"uts": "1764000100"}}}}}
        asked = []

        def call(method, **params):
            asked.append((method, params["page"], params["from"] < params["to"]))
            return pages[params["page"]]

        got = yearago.fetch_scrobbles(call, "ja", utc(2025, 11, 21), utc(2025, 12, 6))
        self.assertEqual([(a, t) for a, t, _ in got], [("A", "t1"), ("C", "t2")])
        self.assertEqual(asked, [("user.getRecentTracks", 1, True), ("user.getRecentTracks", 2, True)])

    def test_garbage_answers_are_empty(self):
        self.assertEqual(yearago.fetch_scrobbles(lambda m, **p: None, "ja", utc(2025, 1, 1), utc(2025, 1, 2)), [])
        self.assertEqual(yearago.fetch_scrobbles(lambda m, **p: {}, "ja", utc(2025, 1, 1), utc(2025, 1, 2)), [])


class Selecting(unittest.TestCase):
    def scrobbles(self):
        rows = []
        for i in range(10):
            rows += [(f"Artysta {i}", f"Utwór {i}", utc(2025, 11, 25) + timedelta(hours=j)) for j in range(10 - i)]
        rows.append(("Artysta 0", "Utwór 0 (feat. X)", utc(2025, 11, 26)))     # ten sam utwór z dopiskiem
        return rows

    def test_top_entries_count_plays_and_merge_featurings(self):
        entries = yearago.top_entries(self.scrobbles())
        self.assertEqual([e["rank"] for e in entries[:3]], [1, 2, 3])
        self.assertEqual(entries[0]["plays"], 11)
        self.assertEqual(entries[0]["title"], "Utwór 0")
        self.assertEqual(len(entries), 10)

    def test_select_keeps_order_and_requires_a_minimum(self):
        entries = yearago.top_entries(self.scrobbles())
        songs = [song(i, f"Artysta {i}", f"Utwór {i}") for i in range(10)]
        self.assertEqual(yearago.select(entries, songs), [f"s{i}" for i in range(10)])
        self.assertEqual(yearago.select(entries, songs[:5]), [])               # poniżej 8 utworów — brak playlisty
        self.assertEqual(len(yearago.select(entries, songs, size=9)), 9)
        self.assertEqual(yearago.select([], songs), [])

    def test_comment_has_the_mix_prefix(self):
        start, end = yearago.window(date(2026, 11, 28))
        text = yearago.comment(12, start, end)
        self.assertTrue(text.startswith("Piterfy Mix"))
        self.assertIn("21.11.2025", text)
        self.assertIn("05.12.2025", text)


if __name__ == "__main__":
    unittest.main()
