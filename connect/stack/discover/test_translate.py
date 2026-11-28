import tempfile
import unittest
from pathlib import Path

import translate


class Fake:
    name = "fake"

    def __init__(self, fail=False):
        self.fail, self.calls = fail, []

    def translate(self, texts, source, target):
        self.calls.append((list(texts), source, target))
        if self.fail:
            raise translate.BackendError("nie działa")
        return ["PL:" + t for t in texts]


class Input(unittest.TestCase):
    def test_clean_lines_keeps_blanks_and_validates(self):
        self.assertEqual(translate.clean_lines(["  a   b ", "", "c"]), ["a b", "", "c"])
        for bad in (None, [], "tekst", [""], [" ", ""]):
            with self.assertRaises(ValueError):
                translate.clean_lines(bad)
        with self.assertRaises(ValueError):
            translate.clean_lines(["x"] * (translate.MAX_LINES + 1))
        with self.assertRaises(ValueError):
            translate.clean_lines(["x" * 5000] * 3)

    def test_clean_target(self):
        self.assertEqual(translate.clean_target(None), "pl")
        self.assertEqual(translate.clean_target("EN"), "en")
        self.assertEqual(translate.clean_target("zh-CN"), "zh-cn")
        with self.assertRaises(ValueError):
            translate.clean_target("../etc")


class Detection(unittest.TestCase):
    def test_scripts_and_languages(self):
        self.assertEqual(translate.detect_language(["사랑해 너를 영원히", "내 마음은 너에게"]), "ko")
        self.assertEqual(translate.detect_language(["愛してる ずっと", "君がいない夜は"]), "ja")
        self.assertEqual(translate.detect_language(["我爱你", "永远在一起"]), "zh")
        self.assertEqual(translate.detect_language(["Я тебя люблю", "навсегда"]), "ru")
        self.assertEqual(translate.detect_language(["Te quiero mucho, mi amor", "No sé qué hacer sin ti", "Por ti lo doy todo"]), "es")
        self.assertEqual(translate.detect_language(["I love you baby", "You are the one for me", "And I don't know why"]), "en")
        self.assertEqual(translate.detect_language(["Nie wiem, co ze mną jest", "Ale jest mi tak dobrze i się boję"]), "pl")
        self.assertEqual(translate.detect_language(["", "123 456"]), "")
        self.assertEqual(translate.detect_language(["Zażółć gęślą jaźń"]), "pl")

    def test_needs_translation(self):
        self.assertFalse(translate.needs_translation("pl", "pl"))
        self.assertFalse(translate.needs_translation("zh", "zh-cn"))
        self.assertTrue(translate.needs_translation("ko", "pl"))
        self.assertTrue(translate.needs_translation("", "pl"))


class Batching(unittest.TestCase):
    def test_batches_respect_limit_and_skip_blank_lines(self):
        lines = ["aaaa", "", "bbbb", "cccc", "dddd", ""]
        groups = translate.make_batches(lines, 9)
        self.assertEqual(groups, [[0, 2], [3, 4]])
        self.assertEqual(translate.make_batches(["", ""], 10), [])
        # linijka dłuższa niż limit dostaje własną paczkę
        self.assertEqual(translate.make_batches(["x" * 20, "y"], 10), [[0], [1]])
        for g in translate.make_batches(["abc def"] * 100, 50):
            self.assertLessEqual(sum(len("abc def") for _ in g) + len(g) - 1, 50)

    def test_merge_and_split(self):
        lines = ["a", "", "b", "c"]
        groups = translate.make_batches(lines, 3)
        out = translate.merge(lines, groups, [["A", "B"], ["C"]][: len(groups)])
        self.assertEqual(out[1], "")
        self.assertEqual(out[0], "A")
        self.assertEqual(translate.split_translated("x\ny\nz", 3), ["x", "y", "z"])
        self.assertEqual(translate.split_translated("x\n\ny\nz\n", 3), ["x", "y", "z"])  # puste linie z backendu
        self.assertIsNone(translate.split_translated("x\ny", 3))
        self.assertIsNone(translate.split_translated("", 2))

    def test_cache_key_depends_on_content_and_target(self):
        a = translate.cache_key(["x", "y"], "pl")
        self.assertEqual(a, translate.cache_key(["x", "y"], "pl"))
        self.assertNotEqual(a, translate.cache_key(["x", "y"], "en"))
        self.assertNotEqual(a, translate.cache_key(["x", "z"], "pl"))
        self.assertEqual(len(a), 40)


class Ollama(unittest.TestCase):
    def test_prompt_and_numbered_parsing(self):
        prompt = translate.ollama_prompt(["uno", "dos"], "es", "pl")
        self.assertIn("1. uno", prompt)
        self.assertIn("Polish", prompt)
        self.assertEqual(translate.parse_numbered("1. jeden\n2) dwa\nuwaga", 2), ["jeden", "dwa"])
        self.assertIsNone(translate.parse_numbered("1. jeden", 2))
        self.assertEqual(translate.parse_numbered("Oto:\n1. a\n3. c\n2. b", 3), ["a", "b", "c"])


class Backends(unittest.TestCase):
    def test_backends_follow_env(self):
        self.assertEqual([b.name for b in translate.configured_backends({})], ["mymemory"])
        self.assertEqual([b.name for b in translate.configured_backends({"TRANSLATE_MYMEMORY": "0"})], [])
        env = {"LIBRETRANSLATE_URL": "http://lt:5000/", "OLLAMA_URL": "http://o:11434", "OLLAMA_MODEL": "qwen2.5:3b"}
        self.assertEqual([b.name for b in translate.configured_backends(env)], ["libretranslate", "mymemory", "ollama"])
        self.assertEqual([b.name for b in translate.configured_backends({"OLLAMA_URL": "http://o"})], ["mymemory"])


class Whole(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_translates_caches_and_keeps_blank_lines(self):
        backend = Fake()
        lines = ["사랑해", "", "영원히"]
        first = translate.translate_lines(lines, "pl", [backend], self.dir)
        self.assertEqual(first["lines"], ["PL:사랑해", "", "PL:영원히"])
        self.assertEqual((first["source"], first["backend"], first["cached"]), ("ko", "fake", False))
        second = translate.translate_lines(lines, "pl", [Fake(fail=True)], self.dir)
        self.assertTrue(second["cached"])
        self.assertEqual(second["lines"], first["lines"])
        self.assertEqual(len(backend.calls), 1)

    def test_skips_when_text_is_already_in_the_target_language(self):
        out = translate.translate_lines(["Nie wiem, co ze mną jest", "Ale jest mi tak dobrze i się boję"], "pl", [Fake()], self.dir)
        self.assertTrue(out["skipped"])
        self.assertEqual(out["lines"], [])

    def test_falls_back_to_the_next_backend_and_reports_failures(self):
        good = Fake()
        out = translate.translate_lines(["Te quiero mucho, mi amor"], "pl", [Fake(fail=True), good], self.dir)
        self.assertEqual(out["backend"], "fake")
        with self.assertRaises(translate.BackendError):
            translate.translate_lines(["Ich liebe dich so sehr"], "pl", [Fake(fail=True)], self.dir)
        with self.assertRaises(translate.NoBackend):
            translate.translate_lines(["Ich liebe dich so sehr"], "pl", [], self.dir)

    def test_cache_survives_garbage_and_is_pruned(self):
        key = translate.cache_key(["x"], "pl")
        (self.dir / f"{key}.json").write_text("{nie json", encoding="utf-8")
        self.assertIsNone(translate.read_cache(key, self.dir))
        old = translate.CACHE_LIMIT
        translate.CACHE_LIMIT = 3
        try:
            for i in range(6):
                translate.write_cache(f"k{i}", {"lines": []}, self.dir)
            self.assertLessEqual(len(list(self.dir.glob("*.json"))), 3)
        finally:
            translate.CACHE_LIMIT = old


class Handler:
    def __init__(self, body):
        self.body, self.sent = body, None

    def read_json(self, limit=65536):
        if isinstance(self.body, Exception):
            raise self.body
        return self.body

    def send(self, code, body=b"", ctype="", headers=None):
        self.sent = (code, body)


class Route(unittest.TestCase):
    def test_handler_maps_errors_to_status_codes(self):
        import json
        h = Handler({"lines": []})
        translate.handle(h, None, "u")
        self.assertEqual(h.sent[0], 400)
        h = Handler(ValueError("za duże żądanie"))
        translate.handle(h, None, "u")
        self.assertEqual(h.sent[0], 400)
        h = Handler(["nie słownik"])
        translate.handle(h, None, "u")
        self.assertEqual(h.sent[0], 400)
        self.assertIn("error", json.loads(h.sent[1]))


if __name__ == "__main__":
    unittest.main()
