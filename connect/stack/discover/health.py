"""Stan serwera w jednym miejscu: odczyty (dysk z muzyką, przebiegi discover, Last.fm i Deezer, kopie zapasowe) zamieniane na
status i alarmy dla strony „Statystyki → Serwer” oraz aplikacji.

`evaluate(odczyty)` jest czysta (bez dysku, sieci i zegara), żeby dało się ją testować; reszta pliku zbiera odczyty:
`api_record` zlicza wywołania Last.fm i Deezera (wołane z discover.py), `report` czyta dysk, stan discovera i plik
kopii zapasowych. Poziomy: „ok”, „warn”, „crit”; status całości to najgorszy z alarmów.

Kopie zapasowe: dowolny skrypt zapisuje `DATA_DIR/backup-status.json` z polami {"at": ISO, "ok": bool, "bytes": liczba,
"target": tekst}; brak pliku = „nie skonfigurowano” (ostrzeżenie).
"""

import json
import os
import shutil
import time
from datetime import datetime
from pathlib import Path

RANK = {"ok": 0, "warn": 1, "crit": 2}
GB = 1024 ** 3
FREE_CRIT_PCT, FREE_CRIT_GB, FREE_WARN_PCT = 5.0, 10.0, 15.0   # wolne miejsce w bibliotece
FAIL_STREAK_CRIT = 5                                            # tyle porażek API z rzędu = alarm krytyczny
API_RECENT_S = 3600                                             # porażka młodsza niż godzina = ostrzeżenie
BACKUP_WARN_H, BACKUP_CRIT_H = 36, 7 * 24
PROBE_TTL = 60                                                  # próbę zapisu do biblioteki powtarzamy najwyżej raz na minutę

API_LABELS = {"lastfm": "Last.fm", "deezer": "Deezer"}


# ── liczniki wywołań API (pamięć procesu discover; pętla główna zapisuje je do state['api_health']) ──

_api = {}


def api_record(name, ok, error=None, now=None):
    """Zlicza jedno wywołanie zewnętrznego API: sukces albo porażka z krótkim opisem błędu."""
    now = time.time() if now is None else now
    row = _api.setdefault(name, {"ok": 0, "fail": 0, "streak": 0, "last_error": None, "last_error_at": None, "last_ok_at": None})
    if ok:
        row["ok"] += 1
        row["streak"] = 0
        row["last_ok_at"] = now
    else:
        row["fail"] += 1
        row["streak"] += 1
        row["last_error"] = str(error or "błąd")[:160]
        row["last_error_at"] = now


def api_snapshot():
    return {name: dict(row) for name, row in _api.items()}


def api_restore(saved):
    """Po restarcie wraca do liczników zapisanych w stanie (tylko tych, których jeszcze nie ma w pamięci)."""
    for name, row in (saved or {}).items():
        if name not in _api and isinstance(row, dict):
            _api[name] = {"ok": int(row.get("ok") or 0), "fail": int(row.get("fail") or 0), "streak": int(row.get("streak") or 0),
                          "last_error": row.get("last_error"), "last_error_at": row.get("last_error_at"), "last_ok_at": row.get("last_ok_at")}


# ── formatowanie ──

def fmt_bytes(n):
    n = float(n or 0)
    for unit, size in (("TB", 1024 ** 4), ("GB", GB), ("MB", 1024 ** 2), ("KB", 1024)):
        if n >= size:
            value = n / size
            return (f"{value:.1f}" if unit in ("TB", "GB") else f"{value:.0f}").replace(".", ",") + " " + unit
    return f"{int(n)} B"


def plural(n, one, few, many):
    n = int(n)
    if n == 1:
        return f"{n} {one}"
    if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
        return f"{n} {few}"
    return f"{n} {many}"


def ago(seconds):
    """„przed chwilą”, „12 min temu”, „3 godz. temu”, „2 dni temu”."""
    s = max(0, int(seconds))
    if s < 90:
        return "przed chwilą"
    if s < 3600:
        return f"{s // 60} min temu"
    if s < 86400:
        return f"{s // 3600} godz. temu"
    return plural(s // 86400, "dzień", "dni", "dni") + " temu"


def parse_time(value):
    """Epoka z liczby albo tekstu ISO (bez strefy = czas lokalny); None, gdy się nie da."""
    if value in (None, ""):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


# ── ocena ──

def _worse(a, b):
    return a if RANK[a] >= RANK[b] else b


def _freshness(label, checked_at, interval_h, now, code, title, hint):
    """Pozycja i ewentualny alarm dla czynności wykonywanej co `interval_h` godzin. None = czynność wyłączona."""
    if not interval_h:
        return None
    if checked_at is None:
        item = {"label": label, "value": "jeszcze nie działało", "level": "warn"}
        return item, {"level": "warn", "code": code, "title": f"{title}: jeszcze nie działało",
                      "detail": f"Zwykle co {_every(interval_h)}. {hint}"}
    age = max(0.0, now - checked_at)
    warn_after = max(2 * interval_h, interval_h + 2) * 3600
    crit_after = max(4 * interval_h, interval_h + 24) * 3600
    level = "crit" if age > crit_after else "warn" if age > warn_after else "ok"
    item = {"label": label, "value": ago(age), "level": level}
    alert = None
    if level != "ok":
        alert = {"level": level, "code": code, "title": f"{title}: ostatnio {ago(age)}",
                 "detail": f"Zwykle co {_every(interval_h)}. {hint}"}
    return item, alert


def _every(hours):
    if hours % 24 == 0 and hours >= 24:
        return plural(int(hours // 24), "dzień", "dni", "dni")
    return plural(int(hours), "godzinę", "godziny", "godzin")


def evaluate(r, now=None):
    """Odczyty → {"status", "alerts": [{"level","code","title","detail"}], "items": [{"label","value","level"}]}.

    `r`: {"library": {...} | None, "discover": {"last_run","mixes_checked","charts_checked","games_checked","stats_generated",
    "intervals": {"run","mixes","charts","games","stats"} (godziny; brak/0 = wyłączone)}, "alerts": {...stan slskd...},
    "api": {"lastfm": {...}}, "lastfm_configured": bool, "backup": None | {"at","ok","bytes","target"} | {"error"},
    "expect_separate": bool}.
    """
    now = time.time() if now is None else now
    items, alerts = [], []

    def add(item, alert=None):
        items.append(item)
        if alert:
            alerts.append(alert)

    # biblioteka (dysk z muzyką)
    lib = r.get("library")
    if lib is not None:
        if not lib.get("exists"):
            add({"label": "Biblioteka muzyki", "value": "brak katalogu", "level": "crit"},
                {"level": "crit", "code": "library_missing", "title": "Brak katalogu biblioteki muzyki",
                 "detail": "Dysk z muzyką (USB) prawdopodobnie nie jest zamontowany. Podłącz go i uruchom kontenery muzyczne."})
        else:
            sep = lib.get("separate_mount")
            if sep is False and r.get("expect_separate"):
                add({"label": "Dysk muzyki", "value": "ten sam dysk co dane", "level": "crit"},
                    {"level": "crit", "code": "library_not_separate", "title": "Biblioteka nie jest na osobnym dysku",
                     "detail": "Katalog muzyki leży na tym samym dysku co dane discovera — dysk USB nie jest zamontowany."})
            else:
                add({"label": "Dysk muzyki", "value": "zamontowany osobno" if sep else "zamontowany" if sep is None else "ten sam dysk co dane", "level": "ok"})
            writable = lib.get("writable")
            if writable is False:
                add({"label": "Zapis do biblioteki", "value": "tylko do odczytu", "level": "crit"},
                    {"level": "crit", "code": "library_readonly", "title": "Biblioteka jest tylko do odczytu",
                     "detail": "Nowe utwory nie mogą być zapisane (dysk zamontowany tylko do odczytu albo wymaga naprawy, np. ntfsfix)."})
            elif writable is True:
                add({"label": "Zapis do biblioteki", "value": "działa", "level": "ok"})
            total, free = lib.get("total_bytes"), lib.get("free_bytes")
            if total:
                pct = free / total * 100.0
                level = "crit" if pct < FREE_CRIT_PCT or free < FREE_CRIT_GB * GB else "warn" if pct < FREE_WARN_PCT else "ok"
                value = f"{fmt_bytes(free)} wolne z {fmt_bytes(total)} ({pct:.0f}%)".replace(".", ",")
                alert = None
                if level != "ok":
                    alert = {"level": level, "code": "library_space",
                             "title": "Kończy się miejsce na dysku muzyki" if level == "warn" else "Prawie brak miejsca na dysku muzyki",
                             "detail": f"Wolne {fmt_bytes(free)} ({pct:.0f}%). Discover przestaje pobierać poniżej ustawionego limitu."}
                add({"label": "Wolne miejsce", "value": value, "level": level}, alert)
            files = lib.get("files")
            if files is not None:
                if files == 0:
                    add({"label": "Pliki w bibliotece", "value": "0", "level": "crit"},
                        {"level": "crit", "code": "library_empty", "title": "Biblioteka jest pusta",
                         "detail": "W katalogu muzyki nie ma żadnych plików — sprawdź, czy dysk jest zamontowany."})
                else:
                    add({"label": "Pliki w bibliotece", "value": f"{files:,}".replace(",", " "), "level": "ok"})

    # discover
    d = r.get("discover") or {}
    iv = d.get("intervals") or {}
    for label, key, ikey, code, title, hint in (
        ("Odkrywanie muzyki", "last_run", "run", "discover_stale", "Odkrywanie muzyki", "Sprawdź: docker logs --tail 100 piterfy-discover."),
        ("Miksy", "mixes_checked", "mixes", "mixes_stale", "Miksy tematyczne", "Odśwież: piterfy-discover --mixes."),
        ("Listy przebojów", "charts_checked", "charts", "charts_stale", "Listy przebojów", "Odśwież: piterfy-discover --charts."),
        ("Soundtracki gier", "games_checked", "games", "games_stale", "Soundtracki gier", "Odśwież: piterfy-discover --games."),
    ):
        result = _freshness(label, parse_time(d.get(key)), iv.get(ikey), now, code, title, hint)
        if result:
            add(*result)
    stats_age = _freshness("Statystyki", parse_time(d.get("stats_generated")), iv.get("stats"), now, "stats_stale", "Statystyki",
                           "Odśwież: piterfy-discover --stats.")
    if stats_age:
        add(*stats_age)

    # Soulseek i ostatni przebieg (stan alertów discovera)
    al = r.get("alerts") or {}
    if al.get("slskd_alerted"):
        since = parse_time(al.get("slskd_down_since"))
        how = f" od {ago(now - since).replace(' temu', '')}" if since else ""
        add({"label": "Soulseek (slskd)", "value": "brak połączenia", "level": "crit"},
            {"level": "crit", "code": "slskd_down", "title": "slskd nie jest połączony z Soulseek" + how,
             "detail": "Nic się nie pobiera. Sprawdź: docker logs --tail 50 piterfy-slskd."})
    elif al.get("slskd_down_since"):
        add({"label": "Soulseek (slskd)", "value": "chwilowo bez połączenia", "level": "warn"},
            {"level": "warn", "code": "slskd_wobble", "title": "slskd chwilowo bez połączenia z Soulseek",
             "detail": "Jeśli to potrwa, przyjdzie mail z alarmem."})
    else:
        add({"label": "Soulseek (slskd)", "value": "połączony", "level": "ok"})
    if al.get("run_failed"):
        add({"label": "Ostatni przebieg", "value": "nie udał się", "level": "crit"},
            {"level": "crit", "code": "run_failed", "title": "Ostatni przebieg odkrywania się nie udał",
             "detail": "Discover próbuje ponownie co godzinę. Logi: docker logs --tail 100 piterfy-discover."})

    # Last.fm i Deezer
    api = r.get("api") or {}
    for name, label in API_LABELS.items():
        row = api.get(name)
        if row is None:
            if name == "lastfm" and not r.get("lastfm_configured"):
                continue
            if name == "deezer":
                continue
            add({"label": label, "value": "bez wywołań od startu", "level": "ok"})
            continue
        streak, last_at = int(row.get("streak") or 0), parse_time(row.get("last_error_at"))
        level = "crit" if streak >= FAIL_STREAK_CRIT else "warn" if last_at is not None and now - last_at < API_RECENT_S else "ok"
        value = f"{row.get('ok', 0)} ok · {plural(row.get('fail', 0), 'błąd', 'błędy', 'błędów')}"
        if row.get("last_error") and last_at is not None:
            value += f" · ostatni {ago(now - last_at)}: {row['last_error']}"
        alert = None
        if level == "crit":
            alert = {"level": "crit", "code": f"{name}_down", "title": f"{label}: {streak} błędów z rzędu",
                     "detail": f"Ostatni błąd: {row.get('last_error') or 'nieznany'}. Rekomendacje i listy z tego źródła mogą się nie odświeżać."}
        elif level == "warn":
            alert = {"level": "warn", "code": f"{name}_error", "title": f"{label}: niedawny błąd ({ago(now - last_at)})",
                     "detail": str(row.get("last_error") or "nieznany błąd")}
        add({"label": label, "value": value, "level": level}, alert)

    # kopie zapasowe
    backup = r.get("backup")
    if backup is None:
        add({"label": "Kopie zapasowe", "value": "nie skonfigurowano", "level": "warn"},
            {"level": "warn", "code": "backup_missing", "title": "Brak kopii zapasowych",
             "detail": "Nic nie zapisuje DATA_DIR/backup-status.json — biblioteka i baza Navidrome mają jeden egzemplarz."})
    elif backup.get("error"):
        add({"label": "Kopie zapasowe", "value": "nieczytelny status", "level": "warn"},
            {"level": "warn", "code": "backup_unreadable", "title": "Nie da się odczytać statusu kopii zapasowych",
             "detail": str(backup["error"])})
    else:
        at = parse_time(backup.get("at"))
        age = None if at is None else max(0.0, now - at)
        extra = []
        if backup.get("bytes"):
            extra.append(fmt_bytes(backup["bytes"]))
        if backup.get("target"):
            extra.append("→ " + str(backup["target"]))
        if backup.get("ok") is False:
            level, title = "crit", "Ostatnia kopia zapasowa się nie udała"
        elif age is None:
            level, title = "warn", "Kopia zapasowa bez daty"
        elif age > BACKUP_CRIT_H * 3600:
            level, title = "crit", f"Kopia zapasowa jest stara: {ago(age)}"
        elif age > BACKUP_WARN_H * 3600:
            level, title = "warn", f"Kopia zapasowa jest stara: {ago(age)}"
        else:
            level, title = "ok", ""
        value = ("nie udała się" if backup.get("ok") is False else ago(age) if age is not None else "brak daty")
        if extra:
            value += " · " + " ".join(extra)
        alert = None if level == "ok" else {"level": level, "code": "backup_stale", "title": title,
                                            "detail": "Sprawdź skrypt kopii i miejsce docelowe."}
        add({"label": "Kopie zapasowe", "value": value, "level": level}, alert)

    status = "ok"
    for alert in alerts:
        status = _worse(status, alert["level"])
    for item in items:  # pozycja bez alarmu (np. dysk) też może podnieść status
        status = _worse(status, item["level"])
    alerts.sort(key=lambda a: -RANK[a["level"]])
    return {"status": status, "alerts": alerts, "items": items}


# ── odczyty z dysku i stanu ──

_probe = {"at": 0.0, "dir": None, "ok": None}


def probe_writable(directory, now=None):
    """Czy da się utworzyć i skasować plik w katalogu (wynik trzymamy PROBE_TTL sekund, żeby nie pisać po dysku przy każdym pytaniu)."""
    now = time.time() if now is None else now
    if _probe["dir"] == str(directory) and now - _probe["at"] < PROBE_TTL and _probe["ok"] is not None:
        return _probe["ok"]
    path = Path(directory) / f".piterfy-health-{os.getpid()}"
    try:
        path.write_bytes(b"1")
        path.unlink()
        ok = True
    except OSError:
        ok = False
    _probe.update(at=now, dir=str(directory), ok=ok)
    return ok


def read_library(music_dir, data_dir, files=None, probe=True):
    music = Path(music_dir)
    if not music.is_dir():
        return {"exists": False}
    out = {"exists": True, "files": files}
    try:
        out["separate_mount"] = os.stat(music).st_dev != os.stat(data_dir).st_dev
    except OSError:
        out["separate_mount"] = None
    out["writable"] = probe_writable(music) if probe else None
    try:
        usage = shutil.disk_usage(music)
        out["total_bytes"], out["free_bytes"] = usage.total, usage.free
    except OSError:
        pass
    return out


def read_state(data_dir):
    try:
        return json.loads((Path(data_dir) / "state.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def read_backup(data_dir):
    path = Path(data_dir) / "backup-status.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {"error": "plik nie zawiera obiektu JSON"}
    except (OSError, ValueError) as e:
        return {"error": f"backup-status.json: {e}"}


def intervals(env=None):
    """Co ile godzin działa każda czynność (te same zmienne co w discover.py); wyłączone = brak klucza."""
    env = os.environ if env is None else env

    def hours(name, default):
        try:
            return float(env.get(name, default))
        except (TypeError, ValueError):
            return float(default)

    result = {"run": hours("DISCOVER_EVERY_DAYS", 3) * 24, "stats": hours("DISCOVER_STATS_EVERY_HOURS", 6)}
    for key, switch, name, default in (("mixes", "DISCOVER_MIXES", "DISCOVER_MIX_EVERY_HOURS", 24),
                                       ("charts", "DISCOVER_CHARTS", "DISCOVER_CHART_EVERY_HOURS", 12),
                                       ("games", "DISCOVER_GAMES", "DISCOVER_GAME_EVERY_HOURS", 6)):
        if str(env.get(switch, "all")).strip():
            result[key] = hours(name, default)
    return {k: v for k, v in result.items() if v}


def report(music_dir, data_dir, stats_generated=None, files=None, now=None, env=None, probe=True):
    """Zbiera odczyty z dysku, stanu discovera i pamięci procesu, i zwraca wynik `evaluate` (+ "files" do ponownego użycia)."""
    env = os.environ if env is None else env
    state = read_state(data_dir)
    live = api_snapshot() or state.get("api_health") or {}
    readings = {
        "library": read_library(music_dir, data_dir, files, probe),
        "discover": {"last_run": state.get("last_run"), "mixes_checked": state.get("mixes_checked"),
                     "charts_checked": state.get("charts_checked"), "games_checked": state.get("games_checked"),
                     "stats_generated": stats_generated, "intervals": intervals(env)},
        "alerts": state.get("alerts") or {},
        "api": live,
        "lastfm_configured": bool(env.get("LASTFM_API_KEY")),
        "backup": read_backup(data_dir),
        "expect_separate": str(env.get("HEALTH_LIBRARY_SEPARATE", "")).strip().lower() in ("1", "true", "yes", "tak"),
    }
    result = evaluate(readings, now)
    result["files"] = files
    return result
