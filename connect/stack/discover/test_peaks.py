import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from array import array
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "beets"))
import peaks  # noqa: E402
import peaks_api  # noqa: E402


def pcm(values):
    data = array("h", values)
    if sys.byteorder == "big":
        data.byteswap()
    return data.tobytes()


def tone(seconds, amplitude, rate=peaks.SAMPLE_RATE):
    return [int(amplitude * math.sin(2 * math.pi * 440 * i / rate)) for i in range(int(seconds * rate))]


def windows_of(samples):
    w = peaks.Windower()
    w.feed(pcm(samples))
    return w.finish()


class Buckets(unittest.TestCase):
    def test_bucket_count_is_clamped_to_the_contract(self):
        self.assertEqual(peaks.bucket_count(0), 120)
        self.assertEqual(peaks.bucket_count(30), 120)
        self.assertEqual(peaks.bucket_count(180), 180)
        self.assertEqual(peaks.bucket_count(215.4), 215)
        self.assertEqual(peaks.bucket_count(7200), 240)
        for bad in (None, "abc", -5, float("nan"), float("inf")):
            self.assertIn(peaks.bucket_count(bad), (120, 240))
            self.assertTrue(120 <= peaks.bucket_count(bad) <= 240)


class WindowerTest(unittest.TestCase):
    def test_counts_and_partial_last_window(self):
        sums, counts = windows_of([100] * (peaks.WINDOW * 3 + 5))
        self.assertEqual(list(counts), [peaks.WINDOW] * 3 + [5])
        self.assertEqual(list(sums), [100.0 ** 2 * peaks.WINDOW] * 3 + [100.0 ** 2 * 5])

    def test_chunk_boundaries_do_not_change_the_result(self):
        samples = tone(1.3, 12000)
        whole = windows_of(samples)
        data = pcm(samples)
        for step in (1, 3, 7, 160, 1001):
            w = peaks.Windower()
            for i in range(0, len(data), step):
                w.feed(data[i:i + step])
            sums, counts = w.finish()
            self.assertEqual(list(counts), list(whole[1]))
            self.assertEqual(list(sums), list(whole[0]))

    def test_sample_counter_includes_buffered_tail(self):
        w = peaks.Windower()
        w.feed(pcm([1] * (peaks.WINDOW + 10)))
        self.assertEqual(w.samples, peaks.WINDOW + 10)

    def test_empty_input(self):
        sums, counts = peaks.Windower().finish()
        self.assertEqual((len(sums), len(counts)), (0, 0))


class Rebin(unittest.TestCase):
    def test_constant_signal_gives_constant_levels(self):
        sums, counts = windows_of([1000] * 8000 * 10)
        levels = peaks.rebin(sums, counts, 150)
        self.assertEqual(len(levels), 150)
        for level in levels:
            self.assertAlmostEqual(level, 1000, delta=1)

    def test_loud_second_half(self):
        sums, counts = windows_of([100] * 8000 * 4 + [8000] * 8000 * 4)
        levels = peaks.rebin(sums, counts, 120)
        self.assertAlmostEqual(levels[10], 100, delta=1)
        self.assertAlmostEqual(levels[110], 8000, delta=1)
        self.assertTrue(all(a <= b + 1e-6 for a, b in zip(levels, levels[1:])))

    def test_uneven_bucket_edges_keep_energy(self):
        sums, counts = windows_of(tone(3.0, 20000))
        levels = peaks.rebin(sums, counts, 240)   # 3 s = 300 okien, 240 słupków: granice wypadają w środku okien
        expected = 20000 / math.sqrt(2)
        for level in levels:
            self.assertAlmostEqual(level, expected, delta=expected * 0.02)

    def test_very_short_audio_still_fills_every_bucket(self):
        sums, counts = windows_of([5000] * 30)
        levels = peaks.rebin(sums, counts, 120)
        self.assertEqual(len(levels), 120)
        self.assertTrue(all(abs(level - 5000) < 1 for level in levels))

    def test_nothing_to_bin(self):
        self.assertEqual(peaks.rebin([], [], 5), [0.0] * 5)
        self.assertEqual(peaks.rebin([1.0], [1], 0), [])


class Normalise(unittest.TestCase):
    def test_loudest_is_255(self):
        self.assertEqual(peaks.normalise([10.0, 20.0, 40.0]), [64, 128, 255])
        self.assertEqual(max(peaks.normalise([3.3, 90.0, 12.0])), 255)

    def test_silence_stays_zero(self):
        self.assertEqual(peaks.normalise([0.0, 1.0, 2.0]), [0, 0, 0])
        self.assertEqual(peaks.normalise([]), [])

    def test_values_are_ints_in_range(self):
        out = peaks.normalise([float(i) for i in range(1, 400, 3)])
        self.assertTrue(all(isinstance(v, int) and 0 <= v <= 255 for v in out))


class Build(unittest.TestCase):
    def test_result_matches_the_contract(self):
        sums, counts = windows_of(tone(2, 3000) + tone(2, 12000))
        result = peaks.build(sums, counts)
        self.assertTrue(peaks.valid(result))
        self.assertEqual(result["v"], 1)
        self.assertEqual(result["n"], 120)
        self.assertEqual(len(result["peaks"]), 120)
        self.assertEqual(result["duration"], 4.0)
        self.assertEqual(max(result["peaks"]), 255)
        self.assertLess(result["peaks"][10], result["peaks"][110])
        self.assertEqual(json.loads(json.dumps(result)), result)

    def test_duration_comes_from_the_real_sample_count(self):
        sums, counts = windows_of([2000] * 8000 * 200)
        result = peaks.build(sums, counts)
        self.assertEqual(result["duration"], 200.0)
        self.assertEqual(result["n"], 200)

    def test_valid_rejects_garbage(self):
        good = {"v": 1, "n": 120, "peaks": [1] * 120, "duration": 3}
        self.assertTrue(peaks.valid(good))
        for bad in (None, [], {}, {**good, "v": 2}, {**good, "n": 119}, {**good, "peaks": [1] * 119},
                    {**good, "peaks": [300] * 120}, {**good, "peaks": ["a"] * 120}, {**good, "peaks": [True] * 120 + [0]}):
            self.assertFalse(peaks.valid(bad), bad)


class CacheKey(unittest.TestCase):
    def test_changes_with_path_mtime_and_size(self):
        base = peaks.cache_key("A/B.flac", 100.9, 5)
        self.assertEqual(base, peaks.cache_key("A/B.flac", 100.2, 5))      # ułamki sekund nie liczą się
        self.assertEqual(base, peaks.cache_key("/A/B.flac", 100, 5))
        self.assertNotEqual(base, peaks.cache_key("A/C.flac", 100, 5))
        self.assertNotEqual(base, peaks.cache_key("A/B.flac", 101, 5))
        self.assertNotEqual(base, peaks.cache_key("A/B.flac", 100, 6))

    def test_is_a_safe_file_name(self):
        key = peaks.cache_key("../../etc/passwd", 1, 1)
        self.assertRegex(key, r"^[0-9a-f]{24}$")


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)

    def test_enqueue_is_idempotent(self):
        self.assertTrue(peaks.enqueue(self.dir, "k1", "A/B.flac", now=100))
        self.assertFalse(peaks.enqueue(self.dir, "k1", "A/B.flac", now=200))
        self.assertTrue(peaks.is_queued(self.dir, "k1"))
        self.assertEqual(peaks.read_queue(self.dir, now=300), [("k1", "A/B.flac")])

    def test_oldest_first_and_done_removes(self):
        peaks.enqueue(self.dir, "b", "B.mp3", now=200)
        peaks.enqueue(self.dir, "a", "A.mp3", now=100)
        peaks.enqueue(self.dir, "c", "C.mp3", now=300)
        self.assertEqual([k for k, _ in peaks.read_queue(self.dir, now=400)], ["a", "b", "c"])
        peaks.done(self.dir, "a")
        peaks.done(self.dir, "missing")   # kasowanie nieistniejącej prośby niczego nie psuje
        self.assertEqual([k for k, _ in peaks.read_queue(self.dir, now=400)], ["b", "c"])

    def test_expired_and_corrupt_requests_are_dropped(self):
        peaks.enqueue(self.dir, "old", "Old.mp3", now=0)
        peaks.enqueue(self.dir, "new", "New.mp3", now=peaks.REQUEST_MAX_AGE_S + 10)
        (peaks.queue_dir(self.dir) / "broken.req").write_text("{nie json", encoding="utf-8")
        got = peaks.read_queue(self.dir, now=peaks.REQUEST_MAX_AGE_S + 20)
        self.assertEqual(got, [("new", "New.mp3")])
        self.assertFalse((peaks.queue_dir(self.dir) / "old.req").exists())
        self.assertFalse((peaks.queue_dir(self.dir) / "broken.req").exists())

    def test_unicode_paths_survive(self):
        peaks.enqueue(self.dir, "u", "Pokój/Łódź — żółć.flac", now=1)
        self.assertEqual(peaks.read_queue(self.dir, now=2), [("u", "Pokój/Łódź — żółć.flac")])

    def test_missing_queue_directory(self):
        self.assertEqual(peaks.read_queue(self.dir / "nie-ma"), [])


class Results(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir, True)

    def test_save_and_load_roundtrip_and_rejects_corrupt(self):
        result = {"v": 1, "n": 120, "peaks": list(range(120)), "duration": 12.5}
        self.assertIsNone(peaks.load_result(self.dir, "k"))
        peaks.save_result(self.dir, "k", result)
        self.assertEqual(peaks.load_result(self.dir, "k"), result)
        peaks.result_path(self.dir, "k").write_text("{uciete", encoding="utf-8")
        self.assertIsNone(peaks.load_result(self.dir, "k"))

    def test_stale_results(self):
        for key in ("keep", "gone"):
            peaks.save_result(self.dir, key, {"v": 1, "n": 120, "peaks": [0] * 120, "duration": 1})
        peaks.mark_bad(self.dir, "gone2")
        self.assertEqual(sorted(peaks.stale_results(self.dir, ["keep"])), ["gone.json", "gone2.bad"])
        self.assertEqual(peaks.stale_results(self.dir, []), [])   # pusta biblioteka (odmontowany dysk) niczego nie kasuje


class WorkerTest(unittest.TestCase):
    def setUp(self):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        self.music, self.dir = root / "music", root / "peaks"
        self.music.mkdir()
        self.computed, self.fail = [], set()
        self.messages = []

        def fake(path, timeout=0):
            self.computed.append(Path(path).relative_to(self.music).as_posix())
            if Path(path).name in self.fail:
                raise ValueError("zepsuty plik")
            return {"v": 1, "n": 120, "peaks": [7] * 120, "duration": 1.0}
        self.worker = peaks.Worker(self.music, self.dir, self.messages.append, compute_fn=fake)

    def song(self, rel, data=b"x"):
        path = self.music / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return peaks.cache_key(rel, path.stat().st_mtime, path.stat().st_size)

    def test_requests_go_before_the_backlog(self):
        self.song("A/one.flac")
        wanted = self.song("B/two.flac")
        peaks.enqueue(self.dir, wanted, "B/two.flac")
        self.assertTrue(self.worker.step())
        self.assertEqual(self.computed, ["B/two.flac"])
        self.assertIsNotNone(peaks.load_result(self.dir, wanted))
        self.assertFalse(peaks.is_queued(self.dir, wanted))
        while self.worker.step():
            pass
        self.assertEqual(sorted(self.computed), ["A/one.flac", "B/two.flac"])

    def test_backlog_skips_done_podcasts_and_non_audio(self):
        done_key = self.song("A/done.flac")
        peaks.save_result(self.dir, done_key, {"v": 1, "n": 120, "peaks": [1] * 120, "duration": 1})
        self.song("A/todo.mp3")
        self.song("Podcasty/Show/ep.mp3")
        self.song("A/cover.jpg")
        while self.worker.step():
            pass
        self.assertEqual(self.computed, ["A/todo.mp3"])

    def test_requested_podcast_is_computed(self):
        key = self.song("Podcasty/Show/ep.mp3")
        peaks.enqueue(self.dir, key, "Podcasty/Show/ep.mp3")
        self.worker.step()
        self.assertEqual(self.computed, ["Podcasty/Show/ep.mp3"])

    def test_broken_file_is_marked_and_not_retried(self):
        key = self.song("A/bad.flac")
        self.fail.add("bad.flac")
        peaks.enqueue(self.dir, key, "A/bad.flac")
        self.worker.step()
        self.assertTrue(peaks.is_bad(self.dir, key))
        self.assertTrue(any("bad.flac" in m for m in self.messages))
        peaks.enqueue(self.dir, key, "A/bad.flac")
        self.worker.step()
        while self.worker.step():
            pass
        self.assertEqual(self.computed, ["A/bad.flac"])

    def test_request_for_a_vanished_file_is_dropped(self):
        peaks.enqueue(self.dir, "ghost", "A/ghost.flac")
        self.worker.step()
        self.assertEqual(self.computed, [])
        self.assertFalse(peaks.is_queued(self.dir, "ghost"))

    def test_file_changed_after_the_request_is_computed_under_the_current_key(self):
        old = self.song("A/x.flac", b"old")
        peaks.enqueue(self.dir, old, "A/x.flac")
        new = self.song("A/x.flac", b"a longer body")
        self.worker.step()
        self.assertIsNone(peaks.load_result(self.dir, old))
        self.assertIsNotNone(peaks.load_result(self.dir, new))
        self.assertFalse(peaks.is_queued(self.dir, old))

    def test_scan_prunes_results_of_replaced_files_but_not_on_empty_library(self):
        stale = peaks.cache_key("A/old.flac", 1, 1)
        peaks.save_result(self.dir, stale, {"v": 1, "n": 120, "peaks": [1] * 120, "duration": 1})
        self.worker.scan_library()           # biblioteka pusta (np. odmontowany dysk): zostaje
        self.assertTrue(peaks.result_path(self.dir, stale).exists())
        self.song("A/new.flac")
        self.worker.scan_library()
        self.assertFalse(peaks.result_path(self.dir, stale).exists())

    def test_rescan_waits_for_the_interval(self):
        self.song("A/a.flac")
        self.worker.step()                    # pierwszy przegląd + zaległość
        self.assertEqual(self.computed, ["A/a.flac"])
        self.song("A/b.flac")
        self.assertFalse(self.worker.step())  # za wcześnie na kolejny przegląd
        self.assertTrue(self.worker.step(rescan_every=-1))
        self.assertEqual(self.computed, ["A/a.flac", "A/b.flac"])


class AnswerTest(unittest.TestCase):
    def setUp(self):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        self.music, self.dir = root / "music", root / "peaks"
        (self.music / "A").mkdir(parents=True)
        (self.music / "A" / "t.flac").write_bytes(b"audio")

    def key(self):
        st = (self.music / "A" / "t.flac").stat()
        return peaks.cache_key("A/t.flac", st.st_mtime, st.st_size)

    def test_unknown_song_and_missing_file_are_404(self):
        self.assertEqual(peaks_api.answer(self.dir, self.music, "", "1")[0], 404)
        self.assertEqual(peaks_api.answer(self.dir, self.music, "A/nie-ma.flac", "1")[0], 404)
        self.assertFalse(self.dir.exists())   # nic nie trafia do kolejki

    def test_first_call_queues_and_answers_202_without_duplicates(self):
        code, body, headers = peaks_api.answer(self.dir, self.music, "A/t.flac", "42", now=5)
        self.assertEqual((code, body), (202, {"pending": True}))
        self.assertEqual(headers["Retry-After"], str(peaks_api.RETRY_AFTER_S))
        self.assertEqual(peaks.read_queue(self.dir, now=6), [(self.key(), "A/t.flac")])
        self.assertEqual(peaks_api.answer(self.dir, self.music, "A/t.flac", "42", now=7)[0], 202)
        self.assertEqual(len(peaks.read_queue(self.dir, now=8)), 1)

    def test_ready_result_is_200_with_the_song_id_and_cache_header(self):
        result = {"v": 1, "n": 120, "peaks": list(range(120)), "duration": 33.3}
        peaks.save_result(self.dir, self.key(), result)
        code, body, headers = peaks_api.answer(self.dir, self.music, "A/t.flac", "42")
        self.assertEqual(code, 200)
        self.assertEqual(body, {**result, "id": "42"})
        self.assertIn("max-age", headers["Cache-Control"])
        self.assertFalse(peaks.queue_dir(self.dir).exists())

    def test_replaced_file_gets_a_fresh_request(self):
        peaks.save_result(self.dir, self.key(), {"v": 1, "n": 120, "peaks": [1] * 120, "duration": 1})
        (self.music / "A" / "t.flac").write_bytes(b"different audio")
        self.assertEqual(peaks_api.answer(self.dir, self.music, "A/t.flac", "42")[0], 202)

    def test_unreadable_file_is_404_not_queued_forever(self):
        peaks.mark_bad(self.dir, self.key())
        self.assertEqual(peaks_api.answer(self.dir, self.music, "A/t.flac", "42")[0], 404)
        self.assertEqual(peaks.read_queue(self.dir), [])

    def test_peaks_dir_defaults_next_to_spectrum_json(self):
        saved = {k: os.environ.pop(k, None) for k in ("PEAKS_DIR", "SPECTRUM_FILE")}
        try:
            self.assertEqual(peaks_api.peaks_dir(), Path("/app/beets/peaks"))
            os.environ["SPECTRUM_FILE"] = "/x/beets/spectrum.json"
            self.assertEqual(peaks_api.peaks_dir(), Path("/x/beets/peaks"))
            os.environ["PEAKS_DIR"] = "/elsewhere"
            self.assertEqual(peaks_api.peaks_dir(), Path("/elsewhere"))
        finally:
            for k, v in saved.items():
                os.environ.pop(k, None)
                if v is not None:
                    os.environ[k] = v


class RouteTest(unittest.TestCase):
    """Trasa zarejestrowana w profile_api: autoryzacja i odpowiedzi HTTP, z podstawionym Navidrome."""

    def test_route_registers_and_answers(self):
        import profile_api
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        music = root / "music"
        (music / "A").mkdir(parents=True)
        (music / "A" / "t.flac").write_bytes(b"audio")
        before = list(profile_api.EXTRA)
        self.addCleanup(lambda: profile_api.EXTRA.__setitem__(slice(None), before))
        saved_get, saved_env = profile_api.navidrome_get, os.environ.get("PEAKS_DIR")
        os.environ["PEAKS_DIR"] = str(root / "peaks")

        def restore():
            profile_api.navidrome_get = saved_get
            os.environ.pop("PEAKS_DIR", None)
            if saved_env is not None:
                os.environ["PEAKS_DIR"] = saved_env
        self.addCleanup(restore)
        songs = {"1": {"path": "/music/A/t.flac", "libraryPath": "/music"}, "2": {}}
        profile_api.navidrome_get = lambda url, path, auth: songs.get(path.rsplit("/", 1)[-1])
        peaks_api.register_routes(profile_api, music)
        verb, pattern, function, auth = profile_api.EXTRA[-1]
        self.assertEqual((verb, auth), ("GET", True))
        self.assertTrue(pattern.fullmatch("/piterfy-api/peaks/abc123"))
        self.assertFalse(pattern.fullmatch("/piterfy-api/peaks/a/b"))

        sent = []

        class Fake:
            navidrome_url = "http://nd"
            headers = {"X-ND-Authorization": "Bearer t"}

            def send(self, code, body=b"", ctype="", headers=None):
                sent.append((code, json.loads(body), headers))

        function(Fake(), pattern.fullmatch("/piterfy-api/peaks/1"), "piotr")
        function(Fake(), pattern.fullmatch("/piterfy-api/peaks/2"), "piotr")
        function(Fake(), pattern.fullmatch("/piterfy-api/peaks/3"), "piotr")
        self.assertEqual([s[0] for s in sent], [202, 404, 404])
        self.assertEqual(sent[0][1], {"pending": True})


@unittest.skipUnless(shutil.which("ffmpeg"), "brak ffmpeg")
class FfmpegIntegration(unittest.TestCase):
    def make(self, expr, seconds, name):
        path = Path(self.tmp) / name
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", expr, "-t", str(seconds), str(path)], check=True)
        return path

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_quiet_then_loud_sine(self):
        quiet = self.make("sine=frequency=440:sample_rate=44100,volume=0.1", 5, "quiet.wav")
        loud = self.make("sine=frequency=440:sample_rate=44100,volume=0.9", 5, "loud.wav")
        joined = Path(self.tmp) / "both.flac"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(quiet), "-i", str(loud), "-filter_complex", "[0:a][1:a]concat=n=2:v=0:a=1",
                        str(joined)], check=True)
        result = peaks.compute(joined)
        self.assertTrue(peaks.valid(result))
        self.assertAlmostEqual(result["duration"], 10.0, delta=0.2)
        self.assertEqual(result["n"], 120)
        self.assertEqual(max(result["peaks"]), 255)
        self.assertLess(result["peaks"][20], 40)       # cicha połowa: ok. 11% głośności
        self.assertGreater(result["peaks"][100], 240)  # głośna połowa

    def test_silence_is_all_zeros(self):
        result = peaks.compute(self.make("anullsrc=r=44100:cl=mono", 3, "silence.wav"))
        self.assertTrue(peaks.valid(result))
        self.assertEqual(set(result["peaks"]), {0})

    def test_garbage_file_raises(self):
        bad = Path(self.tmp) / "bad.flac"
        bad.write_bytes(b"to nie jest dzwiek" * 50)
        with self.assertRaises((ValueError, OSError)):
            peaks.compute(bad)


if __name__ == "__main__":
    unittest.main()
