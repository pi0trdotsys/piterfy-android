import io
import json
import unittest
import urllib.error

import health_alerts as ha

T0 = 1_800_000_000.0
H = 3600


def crit(*codes):
    return {"status": "crit", "alerts": [{"level": "crit", "code": c, "title": f"Alarm {c}", "detail": f"opis {c}"} for c in codes]}


def warn(*codes):
    return {"status": "warn", "alerts": [{"level": "warn", "code": c, "title": f"Ostrzeżenie {c}", "detail": ""} for c in codes]}


OK = {"status": "ok", "alerts": []}


def run(steps, **kw):
    """Przepuszcza kolejne (czas, wynik) przez decide i zwraca listę akcji (None, jeśli nic)."""
    state, out = None, []
    for at, result in steps:
        state, action = ha.decide(state, result, at, **kw)
        out.append(action and action["kind"])
    return out, state


class Decide(unittest.TestCase):
    def test_single_blip_is_ignored(self):
        out, state = run([(T0, crit("usb")), (T0 + 600, OK), (T0 + 1200, OK)])
        self.assertEqual(out, [None, None, None])
        self.assertIsNone(state["notified_at"])

    def test_confirmed_failure_alerts_once_then_stays_quiet(self):
        out, _ = run([(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 1200, crit("usb")), (T0 + 1800, crit("usb"))])
        self.assertEqual(out, [None, "alert", None, None])

    def test_repeat_after_interval(self):
        steps = [(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 5 * H, crit("usb")), (T0 + 7 * H, crit("usb")), (T0 + 7 * H + 600, crit("usb"))]
        out, _ = run(steps, repeat_h=6)
        self.assertEqual(out, [None, "alert", None, "repeat", None])

    def test_repeat_can_be_disabled(self):
        out, _ = run([(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 30 * H, crit("usb"))], repeat_h=0)
        self.assertEqual(out, [None, "alert", None])

    def test_new_critical_alert_during_failure_notifies_immediately(self):
        out, _ = run([(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 1200, crit("usb", "disk_full"))])
        self.assertEqual(out, [None, "alert", "alert"])

    def test_recovery_needs_confirmation_and_is_sent_once(self):
        out, state = run([(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 1200, OK), (T0 + 1800, OK), (T0 + 2400, OK)])
        self.assertEqual(out, [None, "alert", None, "recovered", None])
        self.assertIsNone(state["notified_at"])
        self.assertIsNone(state["since"])

    def test_flapping_back_to_bad_does_not_send_recovery(self):
        out, _ = run([(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 1200, OK), (T0 + 1800, crit("usb"))])
        self.assertEqual(out, [None, "alert", None, None])

    def test_new_failure_after_recovery_alerts_again(self):
        steps = [(T0, crit("usb")), (T0 + 600, crit("usb")), (T0 + 1200, OK), (T0 + 1800, OK), (T0 + 2400, crit("usb")), (T0 + 3000, crit("usb"))]
        out, _ = run(steps)
        self.assertEqual(out, [None, "alert", None, "recovered", None, "alert"])

    def test_warnings_ignored_at_crit_level_but_not_at_warn_level(self):
        steps = [(T0, warn("backup")), (T0 + 600, warn("backup"))]
        self.assertEqual(run(steps, level="crit")[0], [None, None])
        self.assertEqual(run(steps, level="warn")[0], [None, "alert"])

    def test_warn_level_alert_lists_only_relevant_alerts(self):
        state = None
        result = {"status": "crit", "alerts": crit("usb")["alerts"] + warn("backup")["alerts"]}
        state, _ = ha.decide(state, result, T0, level="crit")
        _, action = ha.decide(state, result, T0 + 600, level="crit")
        self.assertEqual([a["code"] for a in action["alerts"]], ["usb"])

    def test_confirm_one_alerts_at_once(self):
        out, _ = run([(T0, crit("usb"))], confirm=1)
        self.assertEqual(out, ["alert"])

    def test_survives_garbage_state(self):
        for prev in (None, "x", 5, {"bad_streak": None, "notified_codes": None}):
            _, action = ha.decide(prev, crit("usb"), T0, confirm=1)
            self.assertEqual(action["kind"], "alert")

    def test_state_is_json_serialisable(self):
        _, state = run([(T0, crit("usb")), (T0 + 600, crit("usb"))])
        self.assertEqual(json.loads(json.dumps(state)), state)


class Settings(unittest.TestCase):
    def test_defaults(self):
        self.assertEqual(ha.settings({}), {"level": "crit", "repeat_h": 6, "confirm": 2})

    def test_off_warn_and_numbers(self):
        self.assertIsNone(ha.settings({"HEALTH_NOTIFY": "off"})["level"])
        self.assertEqual(ha.settings({"HEALTH_NOTIFY": " WARN "})["level"], "warn")
        self.assertEqual(ha.settings({"HEALTH_NOTIFY": "dziwne"})["level"], "crit")
        s = ha.settings({"HEALTH_NOTIFY_REPEAT_H": "0", "HEALTH_NOTIFY_CONFIRM": "0"})
        self.assertEqual((s["repeat_h"], s["confirm"]), (0, 1))
        s = ha.settings({"HEALTH_NOTIFY_REPEAT_H": "abc", "HEALTH_NOTIFY_CONFIRM": "x"})
        self.assertEqual((s["repeat_h"], s["confirm"]), (6, 2))


class Message(unittest.TestCase):
    def test_alert_lists_alerts_and_details(self):
        action = {"kind": "alert", "alerts": crit("usb")["alerts"], "since": T0}
        subject, text = ha.format_message(action, crit("usb"), T0 + 600)
        self.assertIn("🔴", subject)
        self.assertIn("Alarm usb", text)
        self.assertIn("opis usb", text)
        self.assertIn("Statystyki → Serwer", text)

    def test_repeat_mentions_duration(self):
        action = {"kind": "repeat", "alerts": crit("usb")["alerts"], "since": T0}
        subject, text = ha.format_message(action, crit("usb"), T0 + 7 * H + 5 * 60)
        self.assertIn("trwa", subject)
        self.assertIn("7 h 05 min", text)

    def test_recovered(self):
        subject, text = ha.format_message({"kind": "recovered", "alerts": [], "since": T0}, OK, T0 + 90 * 60)
        self.assertIn("✅", subject)
        self.assertIn("1 h 30 min", text)

    def test_warn_only_gets_orange_marker(self):
        action = {"kind": "alert", "alerts": warn("backup")["alerts"], "since": T0}
        self.assertIn("🟠", ha.format_message(action, warn("backup"), T0)[0])


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


class Telegram(unittest.TestCase):
    TOKEN = "123:SECRET-TOKEN"

    def test_missing_settings(self):
        self.assertFalse(ha.send_telegram("x", "", "1")[0])
        self.assertFalse(ha.send_telegram("x", self.TOKEN, "")[0])

    def test_success_posts_form_to_the_bot_api(self):
        seen = {}

        def opener(request, timeout):
            seen.update(url=request.full_url, data=request.data.decode(), method=request.get_method(), timeout=timeout)
            return FakeResponse(b'{"ok":true}')
        self.assertEqual(ha.send_telegram("Cześć: awaria", self.TOKEN, "42", opener=opener), (True, None))
        self.assertEqual(seen["url"], f"https://api.telegram.org/bot{self.TOKEN}/sendMessage")
        self.assertEqual(seen["method"], "POST")
        self.assertIn("chat_id=42", seen["data"])
        self.assertIn("disable_web_page_preview=true", seen["data"])

    def test_api_refusal_reports_description(self):
        ok, error = ha.send_telegram("x", self.TOKEN, "42", opener=lambda r, timeout: FakeResponse(b'{"ok":false,"description":"chat not found"}'))
        self.assertFalse(ok)
        self.assertEqual(error, "chat not found")

    def test_http_error_and_network_error_never_leak_the_token(self):
        def http_error(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 401, "Unauthorized", {}, io.BytesIO(b'{"description":"Unauthorized"}'))

        def network_error(request, timeout):
            raise urllib.error.URLError(f"nie ma sieci {self.TOKEN}")
        for opener in (http_error, network_error):
            ok, error = ha.send_telegram("x", self.TOKEN, "42", opener=opener)
            self.assertFalse(ok)
            self.assertNotIn("SECRET-TOKEN", error)
        self.assertIn("401", ha.send_telegram("x", self.TOKEN, "42", opener=http_error)[1])

    def test_long_text_is_truncated(self):
        seen = {}

        def opener(request, timeout):
            seen["data"] = request.data
            return FakeResponse(b'{"ok":true}')
        ha.send_telegram("a" * 9000, self.TOKEN, "42", opener=opener)
        self.assertLess(len(seen["data"]), 4400)


class CheckHealthLoop(unittest.TestCase):
    """Pętla discovera: odczyty → decide → wysyłka; zapis w state['health_alerts'] i ponawianie, gdy nic nie dotarło."""

    def setUp(self):
        import os
        import sys
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "discovery"))  # report_formatter
        import discover
        self.d = discover
        self.sent, self.deliver, self.report = [], True, crit("usb")
        self.saved = (discover.health.report, discover.notify, discover.log, discover.os.environ.get("HEALTH_NOTIFY_CONFIRM"))
        discover.health.report = lambda *a, **k: self.report
        discover.notify = lambda subject, body: (self.sent.append(subject), self.deliver)[1]
        discover.log = lambda message: None
        discover.os.environ["HEALTH_NOTIFY_CONFIRM"] = "1"

    def tearDown(self):
        self.d.health.report, self.d.notify, self.d.log = self.saved[:3]
        if self.saved[3] is None:
            self.d.os.environ.pop("HEALTH_NOTIFY_CONFIRM", None)
        else:
            self.d.os.environ["HEALTH_NOTIFY_CONFIRM"] = self.saved[3]

    def test_alert_then_quiet_then_recovery(self):
        state = {}
        self.d.check_health(state)
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 1)
        self.assertIn("Awaria", self.sent[0])
        self.report = OK
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 2)
        self.assertIn("w normie", self.sent[1])
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 2)

    def test_failed_delivery_is_retried_on_the_next_check(self):
        state, self.deliver = {}, False
        self.d.check_health(state)
        self.assertIsNone(state["health_alerts"]["notified_at"])
        self.deliver = True
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 2)
        self.assertIsNotNone(state["health_alerts"]["notified_at"])
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 2)

    def test_failed_recovery_message_is_retried(self):
        state = {}
        self.d.check_health(state)
        self.report, self.deliver = OK, False
        self.d.check_health(state)
        self.assertIsNotNone(state["health_alerts"]["notified_at"])
        self.deliver = True
        self.d.check_health(state)
        self.assertEqual(len(self.sent), 3)
        self.assertIsNone(state["health_alerts"]["notified_at"])

    def test_dry_run_sends_and_stores_nothing(self):
        state = {}
        self.assertEqual(self.d.check_health(state, dry_run=True)["status"], "crit")
        self.assertEqual(self.sent, [])
        self.assertNotIn("health_alerts", state)

    def test_switched_off(self):
        self.d.os.environ["HEALTH_NOTIFY"] = "off"
        try:
            state = {}
            self.assertIsNone(self.d.check_health(state))
            self.assertEqual(self.sent, [])
        finally:
            del self.d.os.environ["HEALTH_NOTIFY"]

    def test_broken_report_does_not_raise(self):
        def boom(*a, **k):
            raise OSError("dysk zniknął")
        self.d.health.report = boom
        self.assertIsNone(self.d.check_health({}))


if __name__ == "__main__":
    unittest.main()
