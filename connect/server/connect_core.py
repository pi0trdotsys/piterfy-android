"""Account-scoped Connect state machine. No credentials or stream URLs are stored.

Clients poll every two seconds and stop after nine seconds without a response.
The server waits fifteen seconds before handing an unreachable owner's lease on.
An online transfer waits for an explicit pause acknowledgement, preserving position.
"""
import copy
import hashlib
import json
import math
from pathlib import Path
import threading
import time
import uuid

LIMIT = 500
LEASE = 15
ONLINE = 12


class Rejected(Exception):
    def __init__(self, reason, status=409):
        self.reason, self.status = reason, status


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise Rejected("invalid_number", 400)
    return max(low, min(high, value))


def text(value, limit=256):
    if not isinstance(value, str) or len(value) > limit:
        raise Rejected("invalid_text", 400)
    return value


def snapshot(raw):
    items = raw.get("items", [])
    if not isinstance(items, list) or len(items) > LIMIT:
        raise Rejected("queue_limit", 400)
    clean = []
    for item in items:
        identity = text(item.get("id", ""), 512)
        if not identity or identity.startswith(("ir_", "local_piterfy_")):
            raise Rejected("library_tracks_only", 400)
        row = {"id": identity}
        for key in ("title", "artist", "album", "albumId", "artistId", "cover", "suffix"):
            row[key] = text(item.get(key, ""), 512)
        row["duration"] = number(item.get("duration", 0), 0, 864000)
        row["bitrate"] = number(item.get("bitrate", 0), 0, 100000)
        clean.append(row)
    index = int(number(raw.get("index", 0), 0, max(0, len(clean) - 1)))
    duration = clean[index]["duration"] * 1000 if clean else 0
    return dict(items=clean, index=index, position=int(number(raw.get("position", 0), 0, duration or 864000000)),
                playing=raw.get("playing") is True and bool(clean),
                repeat=int(number(raw.get("repeat", 0), 0, 2)), shuffle=raw.get("shuffle") is True,
                speed=number(raw.get("speed", 1), .25, 3), volume=number(raw.get("volume", 1), 0, 1))


class Broker:
    def __init__(self, directory, clock=time.monotonic):
        self.directory = Path(directory)
        self.clock = clock
        self.lock = threading.RLock()
        self.accounts = {}
        self.epoch = uuid.uuid4().hex

    def _path(self, user):
        return self.directory / (hashlib.sha256(user.encode()).hexdigest() + ".json")

    def _account(self, user):
        if user not in self.accounts:
            state = snapshot({})
            try:
                state = snapshot(json.loads(self._path(user).read_text("utf-8")))
            except (OSError, ValueError, Rejected, AttributeError, TypeError):
                pass
            state["playing"] = False
            self.accounts[user] = dict(state=state, owner=None, devices={}, pending=None, serial=0,
                                       version=self.epoch + ":0", at=self.clock(), saved=0, commands=[])
        return self.accounts[user]

    def _bump(self, a):
        a["serial"] += 1
        a["version"] = self.epoch + ":" + str(a["serial"])

    def _save(self, user, a):
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self._path(user)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(a["state"], ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)
        a["saved"] = self.clock()

    def _expire(self, a):
        now = self.clock()
        owner = a["devices"].get(a["owner"], {})
        if a["pending"] and now >= a["pending"]["deadline"]:
            self._finish(a)
        elif a["owner"] and now - owner.get("seen", -100) >= LEASE:
            a["owner"] = None
            a["state"]["playing"] = False
            self._bump(a)
        a["devices"] = {k: v for k, v in a["devices"].items() if now - v["seen"] < 90}

    def _finish(self, a):
        pending = a["pending"]
        target = a["devices"].get(pending["to"], {})
        available = self.clock() - target.get("seen", -100) < ONLINE
        a["owner"] = pending["to"] if available else None
        a["state"]["playing"] = pending["playing"] if available else False
        a["pending"] = None
        a["at"] = self.clock()
        self._bump(a)

    def _view(self, a):
        state = copy.deepcopy(a["state"])
        # Position is the last confirmed receiver position, never guessed across a buffer stall.
        state.update(version=a["version"], owner=a["owner"], pending=copy.deepcopy(a["pending"]),
                     devices=[dict(id=k, **{n: v[n] for n in ("name", "kind", "output")},
                                   online=self.clock() - v["seen"] < ONLINE) for k, v in a["devices"].items()],
                     leaseMs=9000, limit=LIMIT)
        return state

    def poll(self, user, body):
        with self.lock:
            a = self._account(user)
            self._expire(a)
            device = body.get("device", {})
            identity = text(device.get("id", ""), 80)
            if not identity:
                raise Rejected("device_required", 400)
            if identity not in a["devices"] and len(a["devices"]) >= 32:
                raise Rejected("device_limit", 429)
            a["devices"][identity] = dict(name=text(device.get("name", "Urządzenie"), 80),
                kind=text(device.get("kind", "web"), 20), output=text(device.get("output", ""), 120), seen=self.clock())
            pending = a["pending"]
            report = body.get("report")
            if pending and pending["from"] == identity and body.get("stopAck") == pending["id"]:
                if report is not None:
                    final = snapshot(report)
                    if [x["id"] for x in final["items"]] == [x["id"] for x in a["state"]["items"]]:
                        a["state"]["position"] = final["position"]
                        a["state"]["index"] = final["index"]
                self._finish(a)
                self._save(user, a)
            elif not pending and report is not None:
                clean = snapshot(report)
                # Only an empty session may be adopted automatically. Restored sessions require a click.
                initial = not a["owner"] and not a["state"]["items"] and clean["playing"]
                if initial or (a["owner"] == identity and body.get("version") == a["version"]):
                    old = a["state"]
                    a["state"] = clean
                    if initial:
                        a["owner"] = identity
                    semantic = any(old[k] != clean[k] for k in ("items", "index", "playing", "repeat", "shuffle", "speed", "volume"))
                    # Heartbeats do not invalidate controls. Local seeks do.
                    expected = old["position"] + ((self.clock() - a["at"]) * 1000 * old["speed"] if old["playing"] else 0)
                    if initial or semantic or abs(clean["position"] - expected) > 3500:
                        self._bump(a)
                    a["at"] = self.clock()
                    if semantic or self.clock() - a["saved"] >= 15:
                        self._save(user, a)
            return self._view(a)

    def command(self, user, body):
        with self.lock:
            a = self._account(user)
            self._expire(a)
            command_id = text(body.get("requestId", ""), 80)
            if not command_id:
                raise Rejected("request_id_required", 400)
            if command_id in a["commands"]:
                return self._view(a)
            if body.get("version") != a["version"]:
                raise Rejected("session_changed")
            if a["pending"]:
                raise Rejected("transfer_pending")
            action = body.get("action")
            s = copy.deepcopy(a["state"])
            if action == "transfer":
                target = text(body.get("target", ""), 80)
                if self.clock() - a["devices"].get(target, {}).get("seen", -100) >= ONLINE:
                    raise Rejected("device_offline")
                if target != a["owner"]:
                    if a["owner"]:
                        a["pending"] = dict(id=uuid.uuid4().hex, **{"from": a["owner"]}, to=target,
                            deadline=a["devices"][a["owner"]]["seen"] + LEASE, playing=s["playing"])
                    else:
                        a["owner"] = target
            elif action == "adopt":
                target = text(body.get("target", ""), 80)
                if a["owner"] and a["owner"] != target:
                    raise Rejected("transfer_first")
                if self.clock() - a["devices"].get(target, {}).get("seen", -100) >= ONLINE:
                    raise Rejected("device_offline")
                s = snapshot(body.get("state", {}))
                a["owner"] = target
            else:
                if not a["owner"]:
                    raise Rejected("choose_device")
                count = len(s["items"])
                if action in ("play", "pause"):
                    s["playing"] = action == "play" and bool(count)
                elif action == "seek":
                    s["position"] = number(body.get("position"), 0, (s["items"][s["index"]]["duration"] * 1000) if count else 0)
                elif action == "next":
                    if count:
                        s["index"] = (s["index"] + 1) % count if s["repeat"] == 2 else min(count - 1, s["index"] + 1)
                        s["position"] = 0
                elif action == "previous":
                    s["index"] = max(0, s["index"] - 1) if s["position"] < 3000 else s["index"]
                    s["position"] = 0
                elif action in ("repeat", "volume", "speed"):
                    limits = {"repeat": (0, 2), "volume": (0, 1), "speed": (.25, 3)}
                    s[action] = number(body.get("value"), *limits[action])
                elif action == "shuffle":
                    s["shuffle"] = body.get("enabled") is True
                elif action == "edit":
                    self._edit(s, body)
                else:
                    raise Rejected("unknown_command", 400)
            a["state"] = snapshot(s)
            a["at"] = self.clock()
            self._bump(a)
            a["commands"] = (a["commands"] + [command_id])[-128:]
            self._save(user, a)
            return self._view(a)

    def _edit(self, s, body):
        action = body.get("edit")
        items = s["items"]
        count = len(items)
        if action in ("replace", "append", "next"):
            added = snapshot({"items": body.get("items")})["items"]
            if not added or (action != "replace" and count + len(added) > LIMIT):
                raise Rejected("queue_limit", 400)
            if action == "replace":
                s.update(items=added, index=body.get("index", 0), position=body.get("position", 0), playing=True)
            else:
                at = s["index"] + 1 if action == "next" and count else count
                items[at:at] = added
        elif action == "clear":
            s.update(items=[], index=0, position=0, playing=False)
        else:
            first, last = body.get("from", body.get("index")), body.get("to")
            if not isinstance(first, int) or not 0 <= first < count:
                raise Rejected("invalid_index", 400)
            if action == "select":
                s.update(index=first, position=0, playing=True)
            elif action == "move":
                if not isinstance(last, int) or not 0 <= last < count:
                    raise Rejected("invalid_index", 400)
                # Use identity of the queue occurrence, preserving duplicates.
                current = items[s["index"]]
                items.insert(last, items.pop(first))
                s["index"] = next(i for i, item in enumerate(items) if item is current)
            elif action == "remove":
                if not isinstance(last, int) or not first < last <= count:
                    raise Rejected("invalid_index", 400)
                current = s["index"]
                del items[first:last]
                if first <= current < last:
                    s.update(index=min(first, max(0, len(items) - 1)), position=0)
                elif current >= last:
                    s["index"] -= last - first
                if not items:
                    s["playing"] = False
            else:
                raise Rejected("unsupported_edit", 400)
