"""Powiadomienia o awarii serwera: mail i Telegram, gdy stan z `health.report` robi się czerwony (np. odmontowany dysk USB).

`decide` jest czysta (bez sieci, dysku i zegara), więc da się ją testować: dostaje poprzedni zapis (`state['health_alerts']`)
i świeży wynik `health.evaluate`, a zwraca nowy zapis oraz akcję do wykonania: „alert” (nowa awaria albo nowy alarm w trakcie
awarii), „repeat” (przypomnienie, gdy awaria trwa) albo „recovered” (wszystko wróciło do normy). Żeby jedna chwilowa
czkawka (np. dysk, który wstaje kilkanaście sekund) nie budziła telefonu, awaria musi utrzymać się przez CONFIRM
kolejnych kontroli (pętla discovera sprawdza co ~10 minut); to samo dotyczy zakończenia awarii.

Ustawienia (.env):
  HEALTH_NOTIFY            crit (domyślnie) | warn | off — od jakiego poziomu wysyłać; „warn” dorzuca ostrzeżenia (np. brak kopii)
  HEALTH_NOTIFY_REPEAT_H   co ile godzin przypominać o trwającej awarii (domyślnie 6, 0 = bez przypomnień)
  HEALTH_NOTIFY_CONFIRM    ile kolejnych kontroli musi potwierdzić zmianę (domyślnie 2)
  TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   Telegram; mail idzie na DISCOVER_MAIL_TO
"""

import json
import urllib.error
import urllib.parse
import urllib.request

RANK = {"ok": 0, "warn": 1, "crit": 2}
DEFAULT_REPEAT_H = 6
DEFAULT_CONFIRM = 2
ICONS = {"crit": "🔴", "warn": "🟠", "ok": "🟢"}


def settings(env):
    """Czyta ustawienia z środowiska: {"level": "crit"|"warn"|None, "repeat_h": liczba, "confirm": liczba >= 1}."""
    raw = str(env.get("HEALTH_NOTIFY", "crit")).strip().lower()
    level = raw if raw in ("crit", "warn") else (None if raw in ("off", "0", "no", "nie", "false") else "crit")

    def number(name, default, low):
        try:
            return max(low, float(env.get(name, default)))
        except (TypeError, ValueError):
            return default
    return {"level": level, "repeat_h": number("HEALTH_NOTIFY_REPEAT_H", DEFAULT_REPEAT_H, 0),
            "confirm": int(number("HEALTH_NOTIFY_CONFIRM", DEFAULT_CONFIRM, 1))}


def _bad(result, level):
    """Alarmy z poziomu `level` wzwyż (przy „crit” tylko krytyczne, przy „warn” również ostrzeżenia)."""
    floor = RANK[level]
    return [a for a in (result.get("alerts") or []) if RANK.get(a.get("level"), 0) >= floor]


def decide(prev, result, now, level="crit", repeat_h=DEFAULT_REPEAT_H, confirm=DEFAULT_CONFIRM):
    """Zwraca (nowy_zapis, akcja): akcja to None albo {"kind": "alert"|"repeat"|"recovered", "alerts": [...], "since": ts}."""
    prev = prev if isinstance(prev, dict) else {}
    bad = _bad(result, level)
    codes = sorted({a.get("code") or a.get("title") or "?" for a in bad})
    notified_at = prev.get("notified_at")
    notified_codes = list(prev.get("notified_codes") or [])
    new = {"status": result.get("status") or "ok", "codes": codes, "bad_streak": 0, "ok_streak": 0,
           "since": prev.get("since"), "notified_at": notified_at, "notified_codes": notified_codes}

    if bad:
        new["bad_streak"] = int(prev.get("bad_streak") or 0) + 1
        new["since"] = prev.get("since") or now
        if new["bad_streak"] < confirm:
            return new, None
        fresh = [a for a in bad if (a.get("code") or a.get("title") or "?") not in notified_codes]
        if notified_at is None or fresh:
            kind = "alert"
        elif repeat_h and now - notified_at >= repeat_h * 3600:
            kind = "repeat"
        else:
            return new, None
        new["notified_at"] = now
        new["notified_codes"] = codes
        return new, {"kind": kind, "alerts": bad, "since": new["since"]}

    new["ok_streak"] = int(prev.get("ok_streak") or 0) + 1
    if notified_at is None:      # nic nie zgłaszaliśmy, więc nie ma o czym meldować; przerwana, niepotwierdzona seria znika
        new["since"] = None
        new["notified_codes"] = []
        return new, None
    if new["ok_streak"] < confirm:
        return new, None
    action = {"kind": "recovered", "alerts": [], "since": prev.get("since") or now}
    new.update({"since": None, "notified_at": None, "notified_codes": []})
    return new, action


def _duration(seconds):
    minutes = max(0, int(seconds // 60))
    if minutes < 60:
        return f"{minutes} min"
    hours, minutes = divmod(minutes, 60)
    if hours < 48:
        return f"{hours} h {minutes:02d} min"
    return f"{hours // 24} dni"


def format_message(action, result, now, hostname="piterfy"):
    """(temat, treść) dla mailu; Telegram dostaje ten sam tekst (temat w pierwszej linii)."""
    kind, since = action["kind"], action.get("since") or now
    lasted = _duration(now - since)
    if kind == "recovered":
        subject = "✅ Serwer Piterfy znów w normie"
        lines = [f"Alarm trwał {lasted}, teraz wszystko działa."]
    else:
        worst = "crit" if any(a.get("level") == "crit" for a in action["alerts"]) else "warn"
        title = {"alert": "Awaria serwera Piterfy" if worst == "crit" else "Ostrzeżenie serwera Piterfy",
                 "repeat": "Awaria serwera Piterfy trwa" if worst == "crit" else "Ostrzeżenie serwera Piterfy trwa"}[kind]
        subject = f"{ICONS[worst]} {title}"
        lines = [f"Trwa od {lasted}." if kind == "repeat" else "Wykryto przy ostatniej kontroli.", ""]
        for a in action["alerts"]:
            lines.append(f"{ICONS.get(a.get('level'), '•')} {a.get('title') or a.get('code')}")
            if a.get("detail"):
                lines.append(f"   {a['detail']}")
    lines += ["", f"Szczegóły: Statystyki → Serwer ({hostname})."]
    return subject, "\n".join(lines)


def send_telegram(text, token, chat_id, opener=None, timeout=15):
    """Wysyła wiadomość przez Telegram Bot API. Zwraca (ok, błąd); token nigdy nie trafia do komunikatu błędu."""
    if not token or not chat_id:
        return False, "brak TELEGRAM_BOT_TOKEN albo TELEGRAM_CHAT_ID"
    opener = opener or urllib.request.urlopen
    body = urllib.parse.urlencode({"chat_id": chat_id, "text": text[:4000], "disable_web_page_preview": "true"}).encode()
    request = urllib.request.Request(f"https://api.telegram.org/bot{token}/sendMessage", data=body, method="POST")
    try:
        with opener(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8") or "{}")
        if data.get("ok"):
            return True, None
        return False, str(data.get("description") or "odpowiedź bez ok")[:160]
    except urllib.error.HTTPError as e:
        try:
            detail = json.loads(e.read().decode("utf-8")).get("description") or ""
        except (OSError, ValueError):
            detail = ""
        return False, f"HTTP {e.code} {detail}".strip()[:160]
    except (urllib.error.URLError, OSError, ValueError) as e:
        return False, str(e).replace(token, "…")[:160]
