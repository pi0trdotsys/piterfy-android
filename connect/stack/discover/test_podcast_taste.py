import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import podcast_taste as pt

NOW = datetime(2026, 11, 28, 12, 0, tzinfo=timezone.utc)


def days_ago(n):
    return NOW - timedelta(days=n)


def episode(file, fetched_days=1, status="done"):
    return {"file": file, "status": status, "title": file, "fetched": days_ago(fetched_days).date().isoformat()}


def song(played_days=None, count=1, **extra):
    base = {"playCount": count if played_days is not None else 0}
    if played_days is not None:
        base["playDate"] = days_ago(played_days).isoformat()
    base.update(extra)
    return base


def records(spec):
    """spec: lista (plik, dni od pobrania, utwór albo None) → rekordy epizodów."""
    state = {"episodes": {f"g{i}": episode(f, d) for i, (f, d, _) in enumerate(spec)}}
    songs = {f: s for f, _, s in spec if s is not None}
    return pt.episode_records(state, songs, NOW)


class Affinity(unittest.TestCase):
    def test_played_episodes_make_a_liked_show(self):
        recs = records([(f"a{i}.mp3", 5, song(2)) for i in range(4)])
        result = pt.show_affinity(recs, NOW)
        self.assertEqual(result["label"], "liked")
        self.assertEqual((result["exposed"], result["played"]), (4, 4))

    def test_ignored_episodes_make_a_disliked_show(self):
        recs = records([(f"a{i}.mp3", 20, None) for i in range(4)])
        self.assertEqual(pt.show_affinity(recs, NOW)["label"], "disliked")

    def test_fresh_unplayed_episodes_say_nothing(self):
        recs = records([(f"a{i}.mp3", 3, None) for i in range(5)])
        self.assertEqual(recs, [])
        self.assertEqual(pt.show_affinity(recs, NOW)["label"], "unknown")

    def test_needs_minimum_exposure(self):
        recs = records([("a0.mp3", 3, song(1)), ("a1.mp3", 3, song(1))])
        self.assertEqual(pt.show_affinity(recs, NOW)["label"], "unknown")

    def test_stars_and_ratings(self):
        recs = records([("a0.mp3", 5, song(1, starred=True)), ("a1.mp3", 5, song(1, rating=5)), ("a2.mp3", 5, song(1, rating=4))])
        self.assertEqual(pt.show_affinity(recs, NOW)["label"], "liked")
        bad = records([("a0.mp3", 5, song(1, rating=1)), ("a1.mp3", 5, song(1, rating=1))])
        self.assertEqual(pt.show_affinity(bad, NOW)["label"], "disliked")      # dwie oceny 1★ liczą się od razu

    def test_rating_one_beats_a_play(self):
        recs = records([("a0.mp3", 5, song(1, rating=1)), ("a1.mp3", 5, song(1)), ("a2.mp3", 5, song(1))])
        self.assertLess(recs[0]["value"], 0)
        self.assertTrue(recs[0]["strong"])

    def test_recent_listening_outweighs_old_ignoring(self):
        old_ignored = [(f"o{i}.mp3", 120, None) for i in range(3)]
        recent_played = [(f"n{i}.mp3", 3, song(1)) for i in range(3)]
        self.assertEqual(pt.show_affinity(records(old_ignored + recent_played), NOW)["label"], "liked")
        old_played = [(f"o{i}.mp3", 120, song(110)) for i in range(3)]
        recent_ignored = [(f"n{i}.mp3", 20, None) for i in range(3)]
        self.assertEqual(pt.show_affinity(records(old_played + recent_ignored), NOW)["label"], "disliked")

    def test_deleted_episode_counts_as_played(self):
        state = {"episodes": {"g": episode("x.mp3", 40, "deleted")}}
        recs = pt.episode_records(state, {}, NOW)
        self.assertEqual(recs[0]["value"], 1.0)

    def test_mixed_listening_is_neutral(self):
        recs = records([("p.mp3", 5, song(2)), ("q.mp3", 20, None), ("r.mp3", 20, None), ("s.mp3", 5, song(2)), ("t.mp3", 20, None)])
        self.assertEqual(pt.show_affinity(recs, NOW)["label"], "neutral")

    def test_summarize_shows_matches_files_by_library_key(self):
        state = {"Show": {"episodes": {f"g{i}": episode(f"Podcasty/Show/{i}.mp3", 5) for i in range(3)}}}
        songs = [{"path": f"Podcasty/Show/{i}.mp3", "playCount": 2, "playDate": days_ago(1).isoformat()} for i in range(3)]
        result = pt.summarize_shows(state, songs, NOW)
        self.assertEqual(result["Show"]["label"], "liked")
        self.assertEqual(result["Show"]["played_episodes"], 3)


SHOWS = [{"name": "Easy German", "url": "https://a/1"}, {"name": "Coffee Break Spanish", "url": "https://a/2"},
         {"name": "Futura Podcast", "url": "https://a/3"}, {"name": "Nudny Kanał", "url": "https://a/4"}]
METAS = {
    "Easy German": {"title": "Easy German: Learn German", "language": "de", "categories": ["Education", "Language Learning"]},
    "Coffee Break Spanish": {"title": "Coffee Break Spanish", "language": "en-gb", "categories": ["Education", "Language Learning"]},
    "Futura Podcast": {"title": "Futura Podcast", "language": "pl", "categories": ["Technology"]},
    "Nudny Kanał": {"title": "Nudny Kanał", "language": "pl", "categories": ["News"]},
}
SUMMARIES = {"Easy German": {"label": "liked", "score": 0.8}, "Coffee Break Spanish": {"label": "liked", "score": 0.6},
             "Futura Podcast": {"label": "unknown", "score": 0.0}, "Nudny Kanał": {"label": "disliked", "score": -0.8}}


class Profile(unittest.TestCase):
    def setUp(self):
        self.profile = pt.build_profile(SHOWS, SUMMARIES, METAS)

    def test_liked_shows_outweigh_unknown_ones(self):
        self.assertGreater(self.profile["languages"]["de"], self.profile["languages"]["pl"])
        self.assertGreater(self.profile["categories"]["language learning"], self.profile["categories"]["technology"])

    def test_prior_is_scaled_down_once_real_taste_is_known(self):
        many = [{"name": f"Ręczny {i}", "url": f"https://r/{i}"} for i in range(40)]
        metas = {f"Ręczny {i}": {"title": f"Ręczny {i}", "language": "pl", "categories": ["News"]} for i in range(40)}
        metas.update(METAS)
        summaries = {"Easy German": {"label": "liked", "score": 0.8}}
        profile = pt.build_profile(SHOWS[:1] + many, summaries, metas)
        self.assertGreater(profile["languages"]["de"], profile["languages"]["pl"])        # 40 kanałów bez historii nie przytłacza jednego lubianego
        cold = pt.build_profile(many, {}, metas)
        self.assertGreater(cold["languages"]["pl"], 5)                                    # bez odsłuchów ręczna lista jest punktem wyjścia

    def test_language_of_a_liked_show_is_accepted_even_with_a_small_share(self):
        many = [{"name": f"Ręczny {i}", "url": f"https://r/{i}"} for i in range(30)]
        metas = {f"Ręczny {i}": {"title": f"Ręczny {i}", "language": "pl", "categories": ["News"]} for i in range(30)}
        metas["Easy Spanish"] = {"title": "Easy Spanish", "language": "es", "categories": ["Language Learning"]}
        metas["Easy Greek"] = {"title": "Easy Greek", "language": "el", "categories": ["Language Learning"]}
        shows = many + [{"name": "Easy Spanish", "url": "https://s/1"}, {"name": "Easy Greek", "url": "https://s/2"}]
        summaries = {"Easy Spanish": {"label": "liked", "score": 0.7}, **{f"Ręczny {i}": {"label": "liked", "score": 0.8} for i in range(30)}}
        profile = pt.build_profile(shows, summaries, metas)
        accepted = pt.accepted_languages(profile)
        self.assertIn("es", accepted)                       # lubiany kanał po hiszpańsku, choć to kilka procent profilu
        self.assertNotIn("el", accepted)                    # grecki znamy tylko z ręcznej listy bez odsłuchów
        self.assertIn("pl", accepted)
        self.assertIn("es", {q["language"] for q in pt.query_plan(profile, limit=30)})

    def test_disliked_show_lowers_its_category_and_adds_no_source(self):
        self.assertNotIn("news", self.profile["categories"])
        names = [s["name"] for s in self.profile["sources"]]
        self.assertNotIn("Nudny Kanał", names)
        self.assertEqual(names[0], "Easy German")

    def test_language_code_is_normalised(self):
        self.assertIn("en", self.profile["languages"])
        self.assertNotIn("en-gb", self.profile["languages"])

    def test_keywords_skip_stopwords_and_short_words(self):
        self.assertEqual(pt.keywords("The Easy German Podcast: Learn 100 Dinge"), ["easy", "german", "dinge"])

    def test_cold_start_uses_prior_for_every_show(self):
        profile = pt.build_profile(SHOWS, {}, METAS)
        self.assertEqual(set(profile["languages"]), {"de", "en", "pl"})

    def test_per_language_buckets(self):
        self.assertIn("german", self.profile["by_language"]["de"]["keywords"])
        self.assertNotIn("german", self.profile["by_language"]["en"]["keywords"])

    def test_explicit_share(self):
        metas = {**METAS, "Easy German": {**METAS["Easy German"], "explicit": True}}
        self.assertGreater(pt.build_profile(SHOWS, SUMMARIES, metas)["explicit"], 0.3)
        self.assertEqual(self.profile["explicit"], 0.0)


class Queries(unittest.TestCase):
    def setUp(self):
        self.profile = pt.build_profile(SHOWS, SUMMARIES, METAS)

    def test_plan_covers_languages_with_their_countries(self):
        plan = pt.query_plan(self.profile, limit=20)
        self.assertEqual({q["language"] for q in plan}, {"de", "en", "pl"})
        self.assertTrue(all(q["country"] == {"de": "DE", "en": "US", "pl": "PL"}[q["language"]] for q in plan))
        self.assertEqual(plan[0]["language"], "de")                      # język z największym udziałem pierwszy

    def test_plan_respects_the_limit_and_rotates_weekly(self):
        first = pt.query_plan(self.profile, week=0, limit=6)
        second = pt.query_plan(self.profile, week=1, limit=6)
        self.assertEqual(len(first), 6)
        self.assertEqual(first[:3], second[:3])                          # stała część
        self.assertNotEqual(first, second)                               # reszta się obraca

    def test_small_languages_are_skipped(self):
        profile = {**self.profile, "languages": {"de": 1.0, "pl": 0.05}}
        self.assertEqual({q["language"] for q in pt.query_plan(profile, limit=20)}, {"de"})

    def test_search_url_has_only_generic_terms(self):
        url = pt.search_url("language learning", "DE")
        self.assertIn("term=language+learning", url)
        self.assertIn("country=DE", url)
        self.assertIn("entity=podcast", url)


def row(name, feed, genres=("Education", "Podcasts"), tracks=50, latest=None, explicit=False, **extra):
    base = {"collectionId": hash(name) % 10 ** 6, "collectionName": name, "artistName": "Ktoś", "feedUrl": feed,
            "genres": list(genres), "trackCount": tracks, "releaseDate": (latest or days_ago(3)).isoformat(),
            "collectionExplicitness": "explicit" if explicit else "notExplicit"}
    base.update(extra)
    return base


class Candidates(unittest.TestCase):
    def setUp(self):
        self.profile = pt.build_profile(SHOWS, SUMMARIES, METAS)
        self.known_urls = {pt.norm_url(s["url"]) for s in SHOWS}
        self.known_names = {pt.fold(s["name"]) for s in SHOWS}

    def filtered(self, rows, blocked_urls=(), blocked_names=()):
        cands = pt.parse_search(json.dumps({"results": rows}))
        return pt.prefilter(cands, self.known_urls, self.known_names, set(blocked_urls), set(blocked_names), self.profile, NOW)

    def test_parse_search_handles_garbage(self):
        self.assertEqual(pt.parse_search("nie json"), [])
        self.assertEqual(pt.parse_search(json.dumps({"results": [{"collectionName": "x"}, "tekst", None]})), [])
        self.assertEqual(pt.parse_search(None), [])

    def test_parse_search_drops_the_podcasts_genre(self):
        cand = pt.parse_search(json.dumps({"results": [row("Nowy", "https://x/rss")]}))[0]
        self.assertEqual(cand["genres"], ["Education"])

    def test_known_blocked_old_short_and_explicit_are_dropped(self):
        rows = [row("Easy German", "https://other/feed"),                         # ta sama nazwa co znany
                row("Inny", "http://A/1/"),                                       # ten sam adres co znany (schemat, wielkość liter, ukośnik)
                row("Zablokowany", "https://x/blocked"),
                row("Stary", "https://x/old", latest=days_ago(200)),
                row("Krótki", "https://x/short", tracks=4),
                row("Ostry", "https://x/explicit", explicit=True),
                row("Dobry", "https://x/good")]
        out = self.filtered(rows, blocked_urls={pt.norm_url("https://x/blocked")})
        self.assertEqual([c["name"] for c in out], ["Dobry"])

    def test_minority_interest_is_scored_against_its_own_language(self):
        # w profilu dominują kursy językowe, ale po polsku lubisz technologię — polski kanał technologiczny ma wypaść dobrze
        many = [{"name": f"Kurs {i}", "url": f"https://k/{i}"} for i in range(10)] + [{"name": "Futura Podcast", "url": "https://a/3"}]
        metas = {f"Kurs {i}": {"title": f"Kurs {i}", "language": "de", "categories": ["Language Learning"]} for i in range(10)}
        metas["Futura Podcast"] = METAS["Futura Podcast"]
        summaries = {name: {"label": "liked", "score": 0.8} for name in metas}
        profile = pt.build_profile(many, summaries, metas)
        tech = {"name": "Technikalia", "genres": ["Technology"], "latest": days_ago(2).isoformat(), "tracks": 60, "qlang": "pl", "artist": ""}
        same = dict(tech, qlang="de")                      # to samo, ale zapytanie w języku bez takiego gustu
        self.assertGreater(pt.prescore(tech, profile, NOW), pt.prescore(same, profile, NOW))
        cat_pl, _ = pt.similarity(profile, "pl", ["technology"], [])
        cat_global, _ = pt.similarity(profile, "", ["technology"], [])
        self.assertGreater(cat_pl, cat_global)

    def test_blocked_name_and_duplicates(self):
        rows = [row("Blokada", "https://x/1"), row("Dobry", "https://x/2"), row("Dobry kopia", "https://X/2/")]
        out = self.filtered(rows, blocked_names={pt.fold("Blokada")})
        self.assertEqual([c["name"] for c in out], ["Dobry"])

    def test_explicit_allowed_when_taste_is_explicit(self):
        metas = {n: {**m, "explicit": True} for n, m in METAS.items()}
        profile = pt.build_profile(SHOWS, SUMMARIES, metas)
        cands = pt.parse_search(json.dumps({"results": [row("Ostry", "https://x/e", explicit=True)]}))
        self.assertEqual(len(pt.prefilter(cands, set(), set(), set(), set(), profile, NOW)), 1)

    def test_prescore_prefers_matching_category_and_words(self):
        good = pt.parse_search(json.dumps({"results": [row("Easy Spanish", "https://x/1", genres=("Language Learning", "Education"))]}))[0]
        bad = pt.parse_search(json.dumps({"results": [row("Sport live", "https://x/2", genres=("Sports",))]}))[0]
        self.assertGreater(pt.prescore(good, self.profile, NOW), pt.prescore(bad, self.profile, NOW))


def feed(language="de", episodes=30, newest_days=2, explicit=False, categories=("Education", "Language Learning"), title="Easy Dutch"):
    return {"episodes": [{"date": days_ago(newest_days + i), "title": f"e{i}"} for i in range(episodes)],
            "meta": {"title": title, "language": language, "categories": list(categories), "explicit": explicit}}


class Validation(unittest.TestCase):
    def setUp(self):
        self.profile = pt.build_profile(SHOWS, SUMMARIES, METAS)
        self.cand = pt.parse_search(json.dumps({"results": [row("Easy Dutch", "https://x/nl", genres=("Language Learning", "Education"))]}))[0]

    def test_valid_feed(self):
        meta, why = pt.validate_feed(self.cand, feed("de-DE"), self.profile, NOW)
        self.assertIsNone(why)
        self.assertEqual((meta["episodes"], meta["language"]), (30, "de-DE"))

    def test_rejections(self):
        self.assertIn("odcinków", pt.validate_feed(self.cand, feed(episodes=5), self.profile, NOW)[1])
        self.assertIn("dni", pt.validate_feed(self.cand, feed(newest_days=150), self.profile, NOW)[1])
        self.assertIn("język nl", pt.validate_feed(self.cand, feed("nl"), self.profile, NOW)[1])
        self.assertIn("explicit", pt.validate_feed(self.cand, feed(explicit=True), self.profile, NOW)[1])

    def test_missing_language_is_tolerated(self):
        self.assertIsNone(pt.validate_feed(self.cand, feed(""), self.profile, NOW)[1])

    def test_reason_names_similar_shows_category_and_language(self):
        meta, _ = pt.validate_feed(self.cand, feed("de"), self.profile, NOW)
        score, category, reason, similar = pt.final_score(self.cand, meta, self.profile, NOW)
        self.assertGreater(score, 0)
        self.assertEqual(category, "language learning")
        self.assertIn("Easy German", similar)
        self.assertTrue(reason.startswith("podobny do: Easy German"))
        self.assertIn("kategoria Language Learning", reason)
        self.assertIn("język de", reason)

    def test_better_match_scores_higher(self):
        meta, _ = pt.validate_feed(self.cand, feed("de"), self.profile, NOW)
        other_cand = pt.parse_search(json.dumps({"results": [row("Wiadomości sportowe", "https://x/s", genres=("Sports",))]}))[0]
        other_meta, _ = pt.validate_feed(other_cand, feed("pl", categories=("Sports",), title="Wiadomości sportowe"), self.profile, NOW)
        self.assertGreater(pt.final_score(self.cand, meta, self.profile, NOW)[0], pt.final_score(other_cand, other_meta, self.profile, NOW)[0])


class Diversity(unittest.TestCase):
    def test_one_per_category_and_limit(self):
        scored = [(0.9, "language learning", {"name": "A"}, {}, "r"), (0.8, "language learning", {"name": "B"}, {}, "r"),
                  (0.7, "technology", {"name": "C"}, {}, "r"), (0.6, "history", {"name": "D"}, {}, "r")]
        names = [p[2]["name"] for p in pt.pick_diverse(scored, 2)]
        self.assertEqual(names, ["A", "C"])

    def test_taken_categories_are_skipped(self):
        scored = [(0.9, "technology", {"name": "A"}, {}, "r"), (0.5, "history", {"name": "B"}, {}, "r")]
        self.assertEqual([p[2]["name"] for p in pt.pick_diverse(scored, 2, taken_categories=["technology"])], ["B"])

    def test_second_pick_prefers_another_language_when_scores_are_close(self):
        scored = [(0.90, "language learning", {"name": "A"}, {"language": "en"}, "r"),
                  (0.80, "technology", {"name": "B"}, {"language": "en"}, "r"),
                  (0.74, "history", {"name": "C"}, {"language": "pl"}, "r")]
        self.assertEqual([p[2]["name"] for p in pt.pick_diverse(scored, 2)], ["A", "C"])     # 0.80·0.85 = 0.68 < 0.74

    def test_language_penalty_is_only_a_nudge(self):
        scored = [(0.90, "language learning", {"name": "A"}, {"language": "en"}, "r"),
                  (0.85, "technology", {"name": "B"}, {"language": "en"}, "r"),
                  (0.40, "history", {"name": "C"}, {"language": "pl"}, "r")]
        self.assertEqual([p[2]["name"] for p in pt.pick_diverse(scored, 2)], ["A", "B"])


class BalancedTop(unittest.TestCase):
    def test_round_robin_over_query_languages(self):
        profile = pt.build_profile(SHOWS, SUMMARIES, METAS)
        cands = [{"name": f"De{i}", "genres": ["Language Learning"], "latest": days_ago(2).isoformat(), "tracks": 50, "qlang": "de"} for i in range(5)] + \
                [{"name": f"Pl{i}", "genres": ["Technology"], "latest": days_ago(2).isoformat(), "tracks": 50, "qlang": "pl"} for i in range(5)]
        top = pt.balanced_top(cands, profile, NOW, 6)
        self.assertEqual(len(top), 6)
        self.assertEqual(sorted(c["qlang"] for c in top), ["de"] * 3 + ["pl"] * 3)

    def test_small_pool_and_missing_language(self):
        profile = pt.build_profile(SHOWS, SUMMARIES, METAS)
        cands = [{"name": "X", "genres": [], "latest": None, "tracks": 0}]
        self.assertEqual(pt.balanced_top(cands, profile, NOW, 12), cands)
        self.assertEqual(pt.balanced_top([], profile, NOW, 12), [])


def trial(name, added_days, status="trial", url=None):
    return {"name": name, "url": url or f"https://x/{name}", "last": 3, "status": status,
            "added": days_ago(added_days).isoformat(timespec="seconds")}


class Lifecycle(unittest.TestCase):
    def test_trial_inside_the_window_stays(self):
        auto = {"shows": [trial("A", 10)]}
        self.assertEqual(pt.review_trials(auto, {"A": {"label": "unknown", "played_episodes": 0}}, NOW), [])

    def test_played_trial_is_promoted_after_the_window(self):
        auto = {"shows": [trial("A", 22)]}
        actions = pt.review_trials(auto, {"A": {"label": "neutral", "played_episodes": 2}}, NOW)
        self.assertEqual([a[0] for a in actions], ["kept"])
        block = {"urls": [], "names": []}
        pt.apply_review(auto, block, actions, NOW)
        self.assertEqual((auto["shows"][0]["status"], auto["shows"][0]["last"]), ("kept", pt.KEPT_LAST))
        self.assertEqual(block["urls"], [])
        self.assertEqual(pt.review_trials(auto, {"A": {"label": "liked", "played_episodes": 5}}, NOW), [])   # zatrzymany już nie podlega przeglądowi

    def test_unplayed_trial_is_dropped_and_blocked(self):
        auto = {"shows": [trial("A", 25), trial("B", 25)]}
        summaries = {"A": {"label": "unknown", "played_episodes": 0}, "B": {"label": "liked", "played_episodes": 4}}
        actions = pt.review_trials(auto, summaries, NOW)
        block = {"urls": [], "names": []}
        dropped = pt.apply_review(auto, block, actions, NOW)
        self.assertEqual([s["name"] for s in dropped], ["A"])
        self.assertEqual([s["name"] for s in auto["shows"]], ["B"])
        self.assertEqual(block["names"], ["a"])
        self.assertEqual(block["urls"], [pt.norm_url("https://x/A")])

    def test_disliked_trial_is_dropped_early(self):
        auto = {"shows": [trial("A", 3)]}
        actions = pt.review_trials(auto, {"A": {"label": "disliked", "played_episodes": 0}}, NOW)
        self.assertEqual(actions[0][0], "dropped")
        self.assertIn("gustu", actions[0][2])

    def test_missing_summary_counts_as_unplayed(self):
        self.assertEqual(pt.review_trials({"shows": [trial("A", 30)]}, {}, NOW)[0][0], "dropped")

    def test_add_trials_respects_the_cap(self):
        auto = {"shows": [trial(f"S{i}", 1) for i in range(7)]}
        picks = [(0.9, "c1", {"name": "N1", "url": "https://n/1"}, {"language": "de"}, "r1"),
                 (0.8, "c2", {"name": "N2", "url": "https://n/2"}, {"language": "pl"}, "r2")]
        added = pt.add_trials(auto, picks, NOW, max_auto=8)
        self.assertEqual([s["name"] for s in added], ["N1"])
        self.assertEqual(added[0]["status"], "trial")
        self.assertEqual((added[0]["last"], added[0]["reason"], added[0]["language"]), (pt.TRIAL_LAST, "r1", "de"))

    def test_promote_by_name_fragment(self):
        auto = {"shows": [trial("Easy Dutch", 2), trial("Futura", 2)]}
        self.assertEqual(pt.promote(auto, "dutch", NOW)["status"], "kept")
        self.assertIsNone(pt.promote(auto, "nieistniejący", NOW))
        self.assertIsNone(pt.promote({"shows": [trial("A1", 1), trial("A2", 1)]}, "a", NOW))     # niejednoznaczne

    def test_block_does_not_duplicate(self):
        block = {"urls": [], "names": []}
        pt.block(block, "Zły Kanał", "https://x/zly/")
        pt.block(block, "Zły Kanał", "https://x/zly")
        self.assertEqual(block, {"urls": ["x/zly"], "names": ["zly kanal"]})

    def test_describe_messages(self):
        show = {"name": "Easy Dutch", "reason": "podobny do: Easy German — kategoria Education"}
        self.assertIn("Easy Dutch", pt.describe("added", show))
        self.assertIn("podobny do", pt.describe("added", show))
        self.assertIn("nie wróci", pt.describe("dropped", show, "brak odsłuchu przez 21 dni"))
        self.assertIn("zostaje", pt.describe("kept", show, "odsłuchałeś 2 odc."))


class Merge(unittest.TestCase):
    CURATED = [{"name": "Radio Naukowe", "url": "https://r/1", "last": 20, "keep": 30}]

    def test_auto_shows_are_appended_and_marked(self):
        auto = {"shows": [trial("Nowy", 1), trial("Zatrzymany", 40, "kept")]}
        auto["shows"][1]["last"] = 8
        shows = pt.merge_shows(self.CURATED, auto, {"urls": [], "names": []}, 30)
        self.assertEqual([s["name"] for s in shows], ["Radio Naukowe", "Nowy", "Zatrzymany"])
        self.assertTrue(shows[1]["auto"] and shows[1]["keep"] == 30 and shows[1]["last"] == 3)
        self.assertEqual(shows[2]["last"], 8)
        self.assertNotIn("auto", shows[0])

    def test_collisions_and_blocklist(self):
        auto = {"shows": [{"name": "radio naukowe", "url": "https://other/x"}, {"name": "Inna", "url": "http://R/1/"},
                          {"name": "Zablokowany", "url": "https://z/1"}, {"name": "Ok", "url": "https://ok/1"}]}
        block = {"urls": [pt.norm_url("https://z/1")], "names": []}
        names = [s["name"] for s in pt.merge_shows(self.CURATED, auto, block, 30)]
        self.assertEqual(names, ["Radio Naukowe", "Ok"])

    def test_bad_last_value_falls_back(self):
        auto = {"shows": [{"name": "X", "url": "https://x/1", "last": "dużo"}]}
        self.assertEqual(pt.merge_shows([], auto, {"urls": [], "names": []}, 30)[0]["last"], pt.TRIAL_LAST)


class Files(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "auto.json"

    def tearDown(self):
        self.dir.cleanup()

    def test_missing_file(self):
        self.assertEqual(pt.load_auto(self.path), {"shows": []})
        self.assertEqual(pt.load_block(self.path), {"urls": [], "names": []})

    def test_corrupt_files_are_survived_and_reported(self):
        messages = []
        for content in ("{nie json", "[1,2,3]", '"tekst"', '{"shows": "nie lista"}', '{"shows": [1, {"name": "A"}, {"name": "B", "url": "ftp://x"}]}'):
            self.path.write_text(content, encoding="utf-8")
            self.assertEqual(pt.load_auto(self.path, messages.append), {"shows": []})
        self.assertTrue(messages)
        self.path.write_text('{"urls": [1, "x/y"], "names": null}', encoding="utf-8")
        self.assertEqual(pt.load_block(self.path), {"urls": ["x/y"], "names": []})

    def test_round_trip_and_atomic_write(self):
        pt.save_json(self.path, {"shows": [trial("Zażółć gęślą", 1)]})
        self.assertEqual(pt.load_auto(self.path)["shows"][0]["name"], "Zażółć gęślą")
        self.assertEqual(list(Path(self.dir.name).glob("*.tmp")), [])


class ChannelMeta(unittest.TestCase):
    RSS = b"""<?xml version="1.0"?><rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" version="2.0"><channel>
      <title> Easy  German </title><language>de-DE</language><itunes:explicit>false</itunes:explicit>
      <itunes:category text="Education"><itunes:category text="Language Learning"/></itunes:category>
      <itunes:category text="Society &amp;amp; Culture"/>
      <item><title>1: Hallo</title><enclosure url="https://x/1.mp3" length="10" type="audio/mpeg"/><pubDate>Mon, 23 Nov 2026 09:00:00 GMT</pubDate><guid>1</guid></item>
    </channel></rss>"""

    def test_parse_feed_returns_meta(self):
        import podcasts
        meta = podcasts.parse_feed(self.RSS)["meta"]
        self.assertEqual(meta["title"], "Easy German")
        self.assertEqual(meta["language"], "de-de")
        self.assertEqual(meta["categories"], ["Education", "Language Learning", "Society & Culture"])
        self.assertFalse(meta["explicit"])


class RemoveUnplayed(unittest.TestCase):
    def test_deletes_only_unplayed_and_removes_empty_folder(self):
        import podcasts
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root) / podcasts.FOLDER / "Show"
            folder.mkdir(parents=True)
            for name in ("1.mp3", "2.mp3", "cover.jpg"):
                (folder / name).write_bytes(b"x")
            state = {"podcasts": {"Show": {"episodes": {
                "a": {"file": "Podcasty/Show/1.mp3", "status": "done"}, "b": {"file": "Podcasty/Show/2.mp3", "status": "done"}}}}}
            songs = [{"path": "Podcasty/Show/1.mp3", "playCount": 0}, {"path": "Podcasty/Show/2.mp3", "playCount": 3}]
            removed = podcasts.remove_unplayed("Show", state, songs, root, lambda *_: None)
            self.assertEqual(removed, 1)
            self.assertFalse((folder / "1.mp3").exists())
            self.assertTrue((folder / "2.mp3").exists())
            self.assertEqual(state["podcasts"]["Show"]["episodes"]["a"]["status"], "deleted")
            self.assertEqual(state["podcasts"]["Show"]["episodes"]["b"]["status"], "done")
            self.assertTrue(folder.exists())
            songs[1]["playCount"] = 0
            podcasts.remove_unplayed("Show", state, songs, root, lambda *_: None)
            self.assertFalse(folder.exists())                                 # nic nie zostało: znika też okładka i folder


class DiscoverFlow(unittest.TestCase):
    """discover.py: odczyty → profil → zapytania → sprawdzanie RSS → próba; sieć podmieniona atrapami."""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "discovery"))  # report_formatter
        import discover
        cls.d = discover

    def setUp(self):
        d = self.d
        self.dir = tempfile.TemporaryDirectory()
        root = Path(self.dir.name)
        (root / "podcasts.txt").write_text("Easy German | https://a/1 | last=8\nCoffee Break Spanish | https://a/2\n", encoding="utf-8")
        self.saved = (d.PODCASTS_FILE, d.PODCAST_AUTO_FILE, d.PODCAST_BLOCK_FILE, d.log, d.notify, d.podcast_space_ok,
                      d.PODCAST_DISCOVER_EVERY_DAYS, d.PODCAST_DISCOVER_NOTIFY)
        d.PODCASTS_FILE, d.PODCAST_AUTO_FILE, d.PODCAST_BLOCK_FILE = root / "podcasts.txt", root / "auto.json", root / "block.json"
        self.logs, self.sent = [], []
        d.log = self.logs.append
        d.notify = lambda subject, body: (self.sent.append((subject, body)), True)[1]
        d.podcast_space_ok = lambda: True
        d.PODCAST_DISCOVER_EVERY_DAYS, d.PODCAST_DISCOVER_NOTIFY = 7, 1
        self.state = {"podcasts": {
            "Easy German": {"episodes": {}, "meta": METAS["Easy German"]},
            "Coffee Break Spanish": {"episodes": {}, "meta": METAS["Coffee Break Spanish"]}},
            "podcast_taste": {"shows": {"Easy German": {"label": "liked", "score": 0.8}, "Coffee Break Spanish": {"label": "liked", "score": 0.6}}}}
        self.queries = []

    def tearDown(self):
        d = self.d
        (d.PODCASTS_FILE, d.PODCAST_AUTO_FILE, d.PODCAST_BLOCK_FILE, d.log, d.notify, d.podcast_space_ok,
         d.PODCAST_DISCOVER_EVERY_DAYS, d.PODCAST_DISCOVER_NOTIFY) = self.saved
        self.dir.cleanup()

    def fetch(self, url):
        self.queries.append(url)
        return json.dumps({"results": [
            row("Easy Dutch", "https://n/dutch", genres=("Language Learning", "Education")),
            row("Easy Greek", "https://n/greek", genres=("Language Learning", "Education")),
            row("Sport Live", "https://n/sport", genres=("Sports",)),
            row("Easy German", "https://a/1")]})

    def feed(self, url):
        if "sport" in url:
            raise OSError("nie działa")
        return feed("de", title="Easy Dutch" if "dutch" in url else "Easy Greek")

    def run_discovery(self, **kw):
        return self.d.discover_podcasts(self.state, now=NOW, fetch=self.fetch, fetch_feed=self.feed, pause=0, **kw)

    def test_adds_one_per_category_as_trial_with_notification(self):
        added = self.run_discovery()
        self.assertEqual(len(added), 1)                                           # oba pasują do jednej kategorii → jedna próba
        self.assertEqual(added[0]["status"], "trial")
        auto = pt.load_auto(self.d.PODCAST_AUTO_FILE)
        self.assertEqual(auto["shows"][0]["name"], added[0]["name"])
        self.assertIn("podobny do", auto["shows"][0]["reason"])
        self.assertEqual(self.state["podcasts_checked"], 0)
        self.assertEqual(len(self.sent), 1)
        self.assertIn("Nowy podcast na próbę", self.sent[0][0])
        self.assertTrue(all("itunes.apple.com" in u for u in self.queries))
        for url in self.queries:   # do iTunes idą tylko ogólne hasła i kraj, nic o użytkowniku
            self.assertEqual(set(pt.urllib.parse.parse_qs(pt.urllib.parse.urlsplit(url).query)), {"term", "media", "entity", "country", "limit"})
        merged = [s["name"] for s in self.d.load_podcast_shows(quiet=True)]
        self.assertIn(added[0]["name"], merged)                                   # kanał auto trafia do synchronizacji

    def test_dry_run_changes_nothing(self):
        picks = self.run_discovery(dry_run=True)
        self.assertEqual(len(picks), 1)
        self.assertFalse(self.d.PODCAST_AUTO_FILE.exists())
        self.assertEqual(self.sent, [])
        self.assertTrue(any("→" in line and "podobny do" in line for line in self.logs))

    def test_second_run_does_not_repeat_and_respects_cap(self):
        first = self.run_discovery()[0]["name"]
        self.d.PODCAST_DISCOVER_MAX = 1
        try:
            self.assertEqual(self.run_discovery(), [])                            # limit kanałów auto
        finally:
            self.d.PODCAST_DISCOVER_MAX = 8
        second = self.run_discovery()
        self.assertTrue(all(s["name"] != first for s in second))                  # znany już kanał nie wraca

    def test_blocked_show_never_returns(self):
        block = {"urls": [pt.norm_url("https://n/dutch"), pt.norm_url("https://n/greek")], "names": []}
        pt.save_json(self.d.PODCAST_BLOCK_FILE, block)
        self.assertEqual(self.run_discovery(), [])

    def test_waits_for_metadata_before_the_first_search(self):
        self.state["podcasts"] = {}
        self.assertEqual(self.run_discovery(), [])
        self.assertEqual(self.queries, [])

    def test_no_space_no_discovery(self):
        self.d.podcast_space_ok = lambda: False
        self.assertEqual(self.run_discovery(), [])
        self.assertEqual(self.queries, [])

    def test_search_errors_are_survived(self):
        def broken(url):
            raise OSError("brak sieci")
        self.assertEqual(self.d.discover_podcasts(self.state, now=NOW, fetch=broken, fetch_feed=self.feed, pause=0), [])

    def test_learn_promotes_and_drops_after_the_window_with_notifications(self):
        auto = {"shows": [trial("Grany", 25), trial("Pominięty", 25)]}
        pt.save_json(self.d.PODCAST_AUTO_FILE, auto)
        base = self.d.PODCASTS_FILE.parent
        self.state["podcasts"]["Grany"] = {"episodes": {"g": episode("Podcasty/Grany/1.mp3", 10)}}
        self.state["podcasts"]["Pominięty"] = {"episodes": {"p": episode("Podcasty/Pominięty/1.mp3", 10)}}
        folder = base / "Podcasty" / "Pominięty"
        folder.mkdir(parents=True)
        (folder / "1.mp3").write_bytes(b"x")
        songs = [{"path": "Podcasty/Grany/1.mp3", "playCount": 1, "playDate": days_ago(2).isoformat()},
                 {"path": "Podcasty/Pominięty/1.mp3", "playCount": 0}]
        old = self.d.MUSIC_DIR
        self.d.MUSIC_DIR = base
        try:
            removed = self.d.podcast_learn(self.state, songs, now=NOW)
        finally:
            self.d.MUSIC_DIR = old
        self.assertEqual(removed, 1)
        self.assertFalse((folder / "1.mp3").exists())
        auto = pt.load_auto(self.d.PODCAST_AUTO_FILE)
        self.assertEqual([(s["name"], s["status"]) for s in auto["shows"]], [("Grany", "kept")])
        self.assertIn("pominiety", pt.load_block(self.d.PODCAST_BLOCK_FILE)["names"])
        subjects = " ".join(s for s, _ in self.sent)
        self.assertIn("Podcast zostaje", subjects)
        self.assertIn("Podcast usunięty", subjects)
        self.assertIn("Easy German", self.state["podcast_taste"]["shows"])

    def test_learn_is_inert_when_the_feature_is_off(self):
        self.d.PODCAST_DISCOVER_EVERY_DAYS = 0
        pt.save_json(self.d.PODCAST_AUTO_FILE, {"shows": [trial("Pominięty", 40)]})
        self.assertEqual(self.d.podcast_learn(self.state, [], now=NOW), 0)
        self.assertEqual(len(pt.load_auto(self.d.PODCAST_AUTO_FILE)["shows"]), 1)

    def test_notifications_can_be_switched_off(self):
        self.d.PODCAST_DISCOVER_NOTIFY = 0
        self.run_discovery()
        self.assertEqual(self.sent, [])

    def test_corrupt_auto_file_does_not_break_the_show_list(self):
        self.d.PODCAST_AUTO_FILE.write_text("{zepsute", encoding="utf-8")
        self.assertEqual([s["name"] for s in self.d.load_podcast_shows(quiet=True)], ["Easy German", "Coffee Break Spanish"])


if __name__ == "__main__":
    unittest.main()
