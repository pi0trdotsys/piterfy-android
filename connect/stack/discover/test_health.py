import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

import health

NOW = datetime(2026, 11, 28, 12, 0).timestamp()
GB = health.GB


def lib(**kw):
    base = {"exists": True, "separate_mount": True, "writable": True, "total_bytes": 400 * GB, "free_bytes": 200 * GB, "files": 2216}
    base.update(kw)
    return base


def disc(**kw):
    base = {"last_run": datetime(2026, 11, 27, 4, 0).isoformat(), "mixes_checked": NOW - 3600, "charts_checked": NOW - 3600,
            "games_checked": NOW - 600, "stats_generated": datetime(2026, 11, 28, 10, 0).isoformat(),
            "intervals": {"run": 72, "mixes": 24, "charts": 12, "games": 6, "stats": 6}}
    base.update(kw)
    return base


def good(**kw):
    base = {"library": lib(), "discover": disc(), "alerts": {}, "api": {}, "lastfm_configured": False,
            "backup": {"at": datetime(2026, 11, 28, 3, 0).isoformat(), "ok": True, "bytes": 3 * GB, "target": "nas"},
            "expect_separate": False}
    base.update(kw)
    return base


def codes(result):
    return [a["code"] for a in result["alerts"]]


def level_of(result, label):
    return next(i["level"] for i in result["items"] if i["label"] == label)


class Evaluate(unittest.TestCase):
    def test_healthy_server_is_ok_with_no_alerts(self):
        r = health.evaluate(good(), NOW)
        self.assertEqual((r["status"], r["alerts"]), ("ok", []))
        labels = [i["label"] for i in r["items"]]
        for want in ("Dysk muzyki", "Zapis do biblioteki", "Wolne miejsce", "Pliki w bibliotece", "Odkrywanie muzyki", "Miksy",
                     "Listy przebojów", "Soundtracki gier", "Statystyki", "Soulseek (slskd)", "Kopie zapasowe"):
            self.assertIn(want, labels)

    def test_missing_library_is_critical_and_explains_usb(self):
        r = health.evaluate(good(library={"exists": False}), NOW)
        self.assertEqual(r["status"], "crit")
        self.assertIn("library_missing", codes(r))
        self.assertIn("USB", r["alerts"][0]["detail"])

    def test_read_only_and_empty_library_are_critical(self):
        r = health.evaluate(good(library=lib(writable=False, files=0)), NOW)
        self.assertEqual(r["status"], "crit")
        self.assertTrue({"library_readonly", "library_empty"} <= set(codes(r)))

    def test_free_space_thresholds(self):
        warn = health.evaluate(good(library=lib(total_bytes=400 * GB, free_bytes=50 * GB)), NOW)   # 12,5%
        self.assertEqual((warn["status"], codes(warn)), ("warn", ["library_space"]))
        crit_pct = health.evaluate(good(library=lib(total_bytes=1000 * GB, free_bytes=40 * GB)), NOW)   # 4%
        self.assertEqual(crit_pct["status"], "crit")
        crit_gb = health.evaluate(good(library=lib(total_bytes=40 * GB, free_bytes=8 * GB)), NOW)       # 20%, ale < 10 GB
        self.assertEqual(crit_gb["status"], "crit")
        fine = health.evaluate(good(library=lib(total_bytes=400 * GB, free_bytes=80 * GB)), NOW)       # 20%
        self.assertEqual(fine["status"], "ok")
        self.assertIn("80,0 GB wolne z 400,0 GB (20%)", next(i["value"] for i in fine["items"] if i["label"] == "Wolne miejsce"))

    def test_separate_mount_matters_only_when_expected(self):
        shared = lib(separate_mount=False)
        self.assertEqual(health.evaluate(good(library=shared), NOW)["status"], "ok")
        r = health.evaluate(good(library=shared, expect_separate=True), NOW)
        self.assertEqual((r["status"], codes(r)), ("crit", ["library_not_separate"]))

    def test_discover_freshness_uses_each_interval(self):
        stale_run = health.evaluate(good(discover=disc(last_run=datetime(2026, 11, 20, 4, 0).isoformat())), NOW)   # ~8 dni przy 3 dniach
        self.assertIn("discover_stale", codes(stale_run))
        self.assertEqual(level_of(stale_run, "Odkrywanie muzyki"), "warn")
        very_stale = health.evaluate(good(discover=disc(games_checked=NOW - 40 * 3600)), NOW)               # 40 h przy 6 h
        self.assertEqual(level_of(very_stale, "Soundtracki gier"), "crit")
        never = health.evaluate(good(discover=disc(charts_checked=None)), NOW)
        self.assertEqual(level_of(never, "Listy przebojów"), "warn")
        self.assertIn("jeszcze nie działało", next(i["value"] for i in never["items"] if i["label"] == "Listy przebojów"))
        off = health.evaluate(good(discover=disc(games_checked=None, intervals={"run": 72, "mixes": 24, "stats": 6})), NOW)
        self.assertNotIn("Soundtracki gier", [i["label"] for i in off["items"]])

    def test_slskd_and_failed_run_alerts(self):
        down = health.evaluate(good(alerts={"slskd_alerted": True, "slskd_down_since": NOW - 2 * 3600}), NOW)
        self.assertEqual(down["status"], "crit")
        self.assertIn("slskd_down", codes(down))
        self.assertIn("od 2 godz.", down["alerts"][0]["title"])
        wobble = health.evaluate(good(alerts={"slskd_down_since": NOW - 300}), NOW)
        self.assertEqual((wobble["status"], codes(wobble)), ("warn", ["slskd_wobble"]))
        failed = health.evaluate(good(alerts={"run_failed": True}), NOW)
        self.assertIn("run_failed", codes(failed))

    def test_api_failures(self):
        row = {"ok": 120, "fail": 6, "streak": 5, "last_error": "timed out", "last_error_at": NOW - 120}
        crit = health.evaluate(good(api={"lastfm": row}, lastfm_configured=True), NOW)
        self.assertEqual(crit["status"], "crit")
        self.assertIn("lastfm_down", codes(crit))
        self.assertIn("5 błędów z rzędu", crit["alerts"][0]["title"])
        recent = health.evaluate(good(api={"deezer": {"ok": 10, "fail": 1, "streak": 1, "last_error": "HTTP 500", "last_error_at": NOW - 1800}}), NOW)
        self.assertEqual((recent["status"], codes(recent)), ("warn", ["deezer_error"]))
        old = health.evaluate(good(api={"deezer": {"ok": 10, "fail": 1, "streak": 0, "last_error": "HTTP 500", "last_error_at": NOW - 7200}}), NOW)
        self.assertEqual(old["status"], "ok")
        self.assertIn("1 błąd", next(i["value"] for i in old["items"] if i["label"] == "Deezer"))
        configured_idle = health.evaluate(good(lastfm_configured=True), NOW)
        self.assertEqual(level_of(configured_idle, "Last.fm"), "ok")
        self.assertNotIn("Last.fm", [i["label"] for i in health.evaluate(good(), NOW)["items"]])

    def test_backups(self):
        none = health.evaluate(good(backup=None), NOW)
        self.assertEqual((none["status"], codes(none)), ("warn", ["backup_missing"]))
        self.assertIn("nie skonfigurowano", next(i["value"] for i in none["items"] if i["label"] == "Kopie zapasowe"))
        old = health.evaluate(good(backup={"at": datetime(2026, 11, 26, 3, 0).isoformat(), "ok": True}), NOW)      # ~57 h
        self.assertEqual(old["status"], "warn")
        ancient = health.evaluate(good(backup={"at": datetime(2026, 11, 10, 3, 0).isoformat(), "ok": True}), NOW)   # 18 dni
        self.assertEqual(ancient["status"], "crit")
        failed = health.evaluate(good(backup={"at": datetime(2026, 11, 28, 3, 0).isoformat(), "ok": False}), NOW)
        self.assertEqual(failed["status"], "crit")
        broken = health.evaluate(good(backup={"error": "zły json"}), NOW)
        self.assertEqual((broken["status"], codes(broken)), ("warn", ["backup_unreadable"]))
        fresh = next(i["value"] for i in health.evaluate(good(), NOW)["items"] if i["label"] == "Kopie zapasowe")
        self.assertIn("3,0 GB", fresh)
        self.assertIn("→ nas", fresh)

    def test_alerts_are_sorted_critical_first(self):
        r = health.evaluate(good(backup=None, library={"exists": False}), NOW)
        self.assertEqual([a["level"] for a in r["alerts"]][0], "crit")


class Formatting(unittest.TestCase):
    def test_ago_and_plural_and_bytes(self):
        self.assertEqual(health.ago(30), "przed chwilą")
        self.assertEqual(health.ago(12 * 60), "12 min temu")
        self.assertEqual(health.ago(3 * 3600), "3 godz. temu")
        self.assertEqual(health.ago(86400), "1 dzień temu")
        self.assertEqual(health.ago(3 * 86400), "3 dni temu")
        self.assertEqual(health.ago(12 * 86400), "12 dni temu")
        self.assertEqual(health.plural(2, "błąd", "błędy", "błędów"), "2 błędy")
        self.assertEqual(health.plural(12, "błąd", "błędy", "błędów"), "12 błędów")
        self.assertEqual(health.plural(22, "błąd", "błędy", "błędów"), "22 błędy")
        self.assertEqual(health.fmt_bytes(0), "0 B")
        self.assertEqual(health.fmt_bytes(1536 * 1024), "2 MB")
        self.assertEqual(health.fmt_bytes(251.3 * GB), "251,3 GB")


class ApiCounters(unittest.TestCase):
    def setUp(self):
        health._api.clear()

    def test_streak_resets_on_success_and_snapshot_restores(self):
        for _ in range(3):
            health.api_record("lastfm", False, "timeout", now=100.0)
        self.assertEqual(health.api_snapshot()["lastfm"]["streak"], 3)
        health.api_record("lastfm", True, now=200.0)
        row = health.api_snapshot()["lastfm"]
        self.assertEqual((row["ok"], row["fail"], row["streak"], row["last_error"]), (1, 3, 0, "timeout"))
        saved = health.api_snapshot()
        health._api.clear()
        health.api_restore(saved)
        health.api_restore({"lastfm": {"ok": 999}})   # nie nadpisuje tego, co już jest w pamięci
        self.assertEqual(health.api_snapshot()["lastfm"]["ok"], 1)


class Readings(unittest.TestCase):
    def test_report_reads_disk_state_and_backup_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            music, data = root / "music", root / "data"
            music.mkdir()
            data.mkdir()
            (music / "a.flac").write_bytes(b"x")
            (data / "state.json").write_text(json.dumps({"last_run": "2026-11-27T04:00", "alerts": {"run_failed": True}}))
            (data / "backup-status.json").write_text(json.dumps({"at": "2026-11-28T03:00:00", "ok": True, "bytes": 1024 ** 3}))
            health._api.clear()
            health._probe.update(at=0.0, dir=None, ok=None)
            r = health.report(music, data, stats_generated="2026-11-28T10:00:00", files=1, now=NOW, env={"DISCOVER_MIXES": ""})
            self.assertIn("run_failed", codes(r))
            self.assertEqual(r["files"], 1)
            self.assertEqual(level_of(r, "Zapis do biblioteki"), "ok")
            self.assertNotIn("Miksy", [i["label"] for i in r["items"]])      # wyłączone przez DISCOVER_MIXES=""
            self.assertEqual(level_of(r, "Kopie zapasowe"), "ok")
            self.assertFalse(list(music.glob(".piterfy-health-*")))            # próba zapisu sprząta po sobie
            missing = health.report(root / "nie-ma", data, now=NOW, env={})
            self.assertIn("library_missing", codes(missing))

    def test_backup_reader_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(health.read_backup(tmp))
            (Path(tmp) / "backup-status.json").write_text("{zepsute")
            self.assertIn("error", health.read_backup(tmp))
            (Path(tmp) / "backup-status.json").write_text("[1]")
            self.assertIn("error", health.read_backup(tmp))

    def test_intervals_follow_the_discover_settings(self):
        got = health.intervals({"DISCOVER_EVERY_DAYS": "1", "DISCOVER_CHARTS": "", "DISCOVER_STATS_EVERY_HOURS": "0"})
        self.assertEqual(got["run"], 24)
        self.assertNotIn("charts", got)
        self.assertNotIn("stats", got)
        self.assertEqual(got["games"], 6)


if __name__ == "__main__":
    unittest.main()
