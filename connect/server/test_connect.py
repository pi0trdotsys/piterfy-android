import json
from pathlib import Path
import tempfile
import unittest
from connect_core import Broker, Rejected


class ConnectTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.now = 100
        self.b = Broker(self.tmp.name, lambda: self.now)

    def poll(self, device, user="alice", **kwargs):
        return self.b.poll(user, {"device": {"id": device, "name": device}, **kwargs})

    def command(self, state, action, **kwargs):
        return self.b.command("alice", {"version": state["version"], "requestId": str(self.now) + action + str(kwargs), "action": action, **kwargs})

    def start(self):
        self.poll("phone")
        return self.poll("web", report={"items": [{"id": "a", "duration": 300}, {"id": "a", "duration": 300}, {"id": "b", "duration": 300}], "index": 1, "position": 12000, "playing": True})

    def test_handoff_waits_for_pause_and_keeps_duplicate_occurrence(self):
        state = self.start()
        pending = self.command(state, "transfer", target="phone")
        self.assertEqual("web", pending["owner"])
        self.assertEqual("web", self.poll("phone")["owner"])
        final = self.poll("web", stopAck=pending["pending"]["id"], report={**state, "position": 13200, "playing": False})
        self.assertEqual("phone", final["owner"])
        self.assertEqual(13200, final["position"])
        self.assertEqual(1, final["index"])
        self.assertTrue(final["playing"])

    def test_stale_owner_cannot_overwrite_new_queue(self):
        state = self.start()
        updated = self.command(state, "next")
        result = self.poll("web", version=state["version"], report=state)
        self.assertEqual(updated["index"], result["index"])
        with self.assertRaises(Rejected):
            self.command(state, "pause")

    def test_offline_handoff_waits_full_lease(self):
        state = self.command(self.start(), "transfer", target="phone")
        self.now += 10
        self.assertEqual("web", self.poll("phone")["owner"])
        self.now += 6
        self.assertEqual("phone", self.poll("phone")["owner"])

    def test_expired_target_is_not_started(self):
        self.command(self.start(), "transfer", target="phone")
        self.now += 16
        state = self.poll("web")
        self.assertIsNone(state["owner"])
        self.assertFalse(state["playing"])

    def test_accounts_are_isolated(self):
        self.start()
        other = self.poll("phone", user="bob")
        self.assertEqual([], other["items"])
        self.assertEqual(1, len(other["devices"]))

    def test_restart_keeps_queue_but_never_resumes_old_owner(self):
        self.start()
        self.b = Broker(self.tmp.name, lambda: self.now)
        state = self.poll("phone")
        self.assertEqual(["a", "a", "b"], [x["id"] for x in state["items"]])
        self.assertIsNone(state["owner"])
        self.assertFalse(state["playing"])

    def test_idempotent_command_and_queue_edits(self):
        state = self.start()
        body = {"version": state["version"], "requestId": "once", "action": "next"}
        first = self.b.command("alice", body)
        self.assertEqual(first, self.b.command("alice", body))
        state = self.command(first, "edit", edit="move", **{"from": 2, "to": 0})
        self.assertEqual(0, state["index"])
        state = self.command(state, "edit", edit="remove", **{"from": 0, "to": 1})
        self.assertEqual(0, state["position"])
        self.assertEqual(["a", "a"], [x["id"] for x in state["items"]])

    def test_invalid_inputs_leave_state_unchanged(self):
        state = self.start()
        for args in ({"action": "seek", "position": float("nan")},
                     {"action": "edit", "edit": "move", "from": -1, "to": 0},
                     {"action": "edit", "edit": "replace", "items": [{"id": "a"}] * 501}):
            with self.assertRaises(Rejected):
                self.command(state, **args)
        self.assertEqual(state["version"], self.poll("phone")["version"])


if __name__ == "__main__":
    unittest.main()
