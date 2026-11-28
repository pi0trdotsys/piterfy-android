"""Tłumaczenia tekstów piosenek obok oryginału (K-pop, J-pop, hiszpański…): POST /piterfy-api/translate.

Żądanie: {"song": id, "target": "pl", "lines": ["linijka 1", …]} (z tokenem Navidrome w X-ND-Authorization).
Odpowiedź: {"lines": [tłumaczenia równoległe do linijek], "source": "ko", "backend": "mymemory", "cached": false}
albo {"skipped": true, "source": "pl", "lines": []}, gdy tłumaczenie nie jest potrzebne (tekst już jest w języku docelowym).

Moduł jest w większości czysty (wykrywanie języka, paczkowanie, scalanie, klucz cache, prompty); sieć jest tylko w klasach
backendów. Backendy próbowane są po kolei, pierwszy, który odpowie, wygrywa:
  1. LibreTranslate (LIBRETRANSLATE_URL, opcjonalnie LIBRETRANSLATE_KEY) — własna instancja,
  2. MyMemory (domyślnie włączony; MYMEMORY_EMAIL zwiększa dzienny limit; TRANSLATE_MYMEMORY=0 wyłącza),
  3. Ollama (OLLAMA_URL + OLLAMA_MODEL; domyślnie wyłączony — kontener Ollamy bywa w innej sieci Dockera).
Wynik trafia do cache na dysku (DATA_DIR/translations/<skrót treści i języka>.json), więc każdy tekst tłumaczymy raz.
UWAGA: LibreTranslate z cudzego serwera i MyMemory dostają tekst piosenki (nie dane osobowe) — jeśli to przeszkadza,
ustaw TRANSLATE_MYMEMORY=0 i użyj własnej instancji albo Ollamy.
"""

import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MAX_LINES = 200
MAX_CHARS = 12000
BATCH_CHARS = 450          # MyMemory przyjmuje ok. 500 znaków na zapytanie
MAX_REQUESTS = 60          # twardy limit zapytań do backendu na jedno tłumaczenie
TIMEOUT = 20
CACHE_LIMIT = 600          # tyle plików cache zostaje (najstarsze znikają)
DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
CACHE_DIR = DATA_DIR / "translations"

LANGUAGE_NAMES = {"pl": "Polish", "en": "English", "es": "Spanish", "ko": "Korean", "ja": "Japanese", "ru": "Russian",
                  "de": "German", "fr": "French", "it": "Italian", "pt": "Portuguese", "zh": "Chinese", "uk": "Ukrainian"}


class NoBackend(Exception):
    """Żaden backend nie jest skonfigurowany."""


class BackendError(Exception):
    """Backend odpowiedział błędem albo nie odpowiedział."""


# ── wejście ──

def clean_lines(lines):
    """Lista linijek tekstu (puste zostają, żeby numeracja zgadzała się z oryginałem); ValueError przy złych danych."""
    if not isinstance(lines, list) or not lines:
        raise ValueError("brak linijek do przetłumaczenia")
    if len(lines) > MAX_LINES:
        raise ValueError(f"za dużo linijek (najwyżej {MAX_LINES})")
    cleaned = [re.sub(r"\s+", " ", str(line if line is not None else "")).strip() for line in lines]
    if sum(len(line) for line in cleaned) > MAX_CHARS:
        raise ValueError(f"tekst jest za długi (najwyżej {MAX_CHARS} znaków)")
    if not any(cleaned):
        raise ValueError("brak linijek do przetłumaczenia")
    return cleaned


def clean_target(target):
    code = str(target or "pl").strip().lower()
    if not re.fullmatch(r"[a-z]{2}(-[a-z]{2})?", code):
        raise ValueError("nieprawidłowy język docelowy")
    return code


# ── wykrywanie języka źródłowego (heurystyka skryptów i słów, bez bibliotek) ──

_HANGUL = re.compile(r"[가-힯ᄀ-ᇿ㄰-㆏]")
_KANA = re.compile(r"[぀-ヿㇰ-ㇿ]")
_HAN = re.compile(r"[一-鿿]")
_CYRILLIC = re.compile(r"[Ѐ-ӿ]")
_WORDS = {
    "pl": set("i w nie się że to na jest do z co jak ale tylko mnie ty ja mi tak już dla jestem moje twoje bo gdy czy być był".split()),
    "es": set("el la los las de que y en un una te me mi tu yo no es por con para amor corazón como pero más muy esta eres quiero estoy sin ya lo se si".split()),
    "en": set("the you and i to a of in it is that my me your for on with baby love be this but we are not so all just don't i'm".split()),
}
_POLISH_CHARS = re.compile(r"[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]")
_SPANISH_CHARS = re.compile(r"[ñÑ¿¡áéíóúÁÉÍÓÚ]")


def detect_language(lines):
    """Kod języka tekstu (ko, ja, zh, ru, pl, es, en) albo "" gdy nie wiadomo (backend wykryje sam)."""
    text = " ".join(line for line in lines if line)
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return ""
    total = len(letters)
    count = lambda pattern: len(pattern.findall(text))  # noqa: E731
    if count(_HANGUL) / total > 0.15:
        return "ko"
    if count(_KANA) / total > 0.1:
        return "ja"
    if count(_HAN) / total > 0.3:
        return "zh"
    if count(_CYRILLIC) / total > 0.3:
        return "ru"
    words = re.findall(r"[^\W\d_]+", text.lower())
    if not words:
        return ""
    score = {code: sum(1 for w in words if w in vocabulary) / len(words) for code, vocabulary in _WORDS.items()}
    score["pl"] += min(count(_POLISH_CHARS) / total * 4, 0.3)
    score["es"] += min(count(_SPANISH_CHARS) / total * 3, 0.3)
    best = max(score, key=score.get)
    return best if score[best] >= 0.12 else ""


def needs_translation(source, target):
    return not (source and source.split("-")[0] == target.split("-")[0])


# ── paczkowanie i scalanie ──

def make_batches(lines, limit=BATCH_CHARS):
    """Grupy indeksów niepustych linijek, każda w sumie ≤ limit znaków (z separatorem); pusta lista dla samych pustych."""
    groups, current, size = [], [], 0
    for index, line in enumerate(lines):
        if not line:
            continue
        extra = len(line) + (1 if current else 0)
        if current and size + extra > limit:
            groups.append(current)
            current, size = [], 0
            extra = len(line)
        current.append(index)
        size += extra
    if current:
        groups.append(current)
    return groups


def merge(lines, groups, results):
    """Wynik równoległy do `lines`: tłumaczenia wpisane pod indeksy z `groups`, puste linijki zostają puste."""
    merged = [""] * len(lines)
    for indexes, translated in zip(groups, results):
        for index, value in zip(indexes, translated):
            merged[index] = value
    return merged


def split_translated(text, count):
    """Rozdziela odpowiedź zawierającą `count` linijek; None, gdy liczba linijek się nie zgadza (wtedy tłumaczymy po jednej)."""
    parts = [p.strip() for p in str(text or "").replace("\r", "").split("\n")]
    parts = [p for p in parts if p] if len(parts) != count else parts
    return parts if len(parts) == count else None


def cache_key(lines, target):
    blob = json.dumps({"lines": lines, "target": target}, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:40]


# ── Ollama: prompt i parsowanie numerowanej odpowiedzi (czyste) ──

def ollama_prompt(lines, source, target):
    name = LANGUAGE_NAMES.get(target, target)
    src = LANGUAGE_NAMES.get(source, "the original language")
    numbered = "\n".join(f"{i + 1}. {line}" for i, line in enumerate(lines))
    return (f"Translate these song lyric lines from {src} into {name}. Keep the meaning and poetic tone, do not explain, "
            f"keep exactly the same numbering and one translated line per number.\n\n{numbered}\n")


def parse_numbered(text, count):
    """Z odpowiedzi „1. …\\n2. …” robi listę `count` linii; None przy niezgodności."""
    found = {}
    for raw in str(text or "").splitlines():
        m = re.match(r"\s*(\d{1,3})[.)]\s*(.*)", raw)
        if m:
            found[int(m.group(1))] = m.group(2).strip()
    if all(i in found for i in range(1, count + 1)):
        return [found[i] for i in range(1, count + 1)]
    return None


# ── backendy (sieć) ──

def _request(url, data=None, headers=None, method=None, timeout=TIMEOUT):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read(2_000_000).decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise BackendError(str(e)[:160]) from e


class LibreTranslate:
    name = "libretranslate"

    def __init__(self, url, key=""):
        self.url, self.key = url.rstrip("/"), key

    def translate(self, texts, source, target):
        body = {"q": texts, "source": source or "auto", "target": target, "format": "text"}
        if self.key:
            body["api_key"] = self.key
        data = _request(self.url + "/translate", json.dumps(body).encode(), {"Content-Type": "application/json"})
        out = data.get("translatedText")
        if isinstance(out, str):
            out = [out]
        if not isinstance(out, list) or len(out) != len(texts):
            raise BackendError("LibreTranslate zwrócił nieoczekiwaną odpowiedź")
        return [str(x) for x in out]


class MyMemory:
    name = "mymemory"
    CODES = {"zh": "zh-CN"}

    def __init__(self, email=""):
        self.email = email

    def _one(self, text, source, target):
        pair = f"{self.CODES.get(source, source) or 'Autodetect'}|{self.CODES.get(target, target)}"
        params = {"q": text, "langpair": pair}
        if self.email:
            params["de"] = self.email
        data = _request("https://api.mymemory.translated.net/get?" + urllib.parse.urlencode(params))
        if data.get("quotaFinished") or str(data.get("responseStatus")) not in ("200", "None"):
            raise BackendError("MyMemory: " + str(data.get("responseDetails") or data.get("responseStatus"))[:120])
        return str((data.get("responseData") or {}).get("translatedText") or "")

    def translate(self, texts, source, target):
        joined = self._one("\n".join(texts), source, target)
        split = split_translated(joined, len(texts)) if len(texts) > 1 else [joined]
        if split is not None:
            return split
        return [self._one(t, source, target) for t in texts]  # paczka rozjechała się — po jednej linijce


class Ollama:
    name = "ollama"

    def __init__(self, url, model):
        self.url, self.model = url.rstrip("/"), model

    def translate(self, texts, source, target):
        body = {"model": self.model, "prompt": ollama_prompt(texts, source, target), "stream": False, "options": {"temperature": 0}}
        data = _request(self.url + "/api/generate", json.dumps(body).encode(), {"Content-Type": "application/json"}, timeout=90)
        parsed = parse_numbered(data.get("response"), len(texts))
        if parsed is None:
            raise BackendError("Ollama nie zachował numeracji linijek")
        return parsed


def configured_backends(env=None):
    env = os.environ if env is None else env
    backends = []
    if env.get("LIBRETRANSLATE_URL"):
        backends.append(LibreTranslate(env["LIBRETRANSLATE_URL"], env.get("LIBRETRANSLATE_KEY", "")))
    if env.get("TRANSLATE_MYMEMORY", "1") != "0":
        backends.append(MyMemory(env.get("MYMEMORY_EMAIL", "")))
    if env.get("OLLAMA_URL") and env.get("OLLAMA_MODEL"):
        backends.append(Ollama(env["OLLAMA_URL"], env["OLLAMA_MODEL"]))
    return backends


# ── cache na dysku ──

def read_cache(key, directory=None):
    try:
        return json.loads(((directory or CACHE_DIR) / f"{key}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def write_cache(key, value, directory=None):
    folder = directory or CACHE_DIR
    try:
        folder.mkdir(parents=True, exist_ok=True)
        tmp = folder / f"{key}.tmp"
        tmp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        tmp.replace(folder / f"{key}.json")
        files = sorted(folder.glob("*.json"), key=lambda p: p.stat().st_mtime)
        for old in files[:-CACHE_LIMIT]:
            old.unlink()
    except OSError:
        pass  # brak cache nie psuje tłumaczenia


# ── całość ──

def translate_lines(lines, target="pl", backends=None, directory=None):
    """Tłumaczy linijki; zwraca słownik odpowiedzi API. ValueError — złe dane, NoBackend/BackendError — brak/awaria backendu."""
    lines = clean_lines(lines)
    target = clean_target(target)
    source = detect_language(lines)
    if not needs_translation(source, target):
        return {"skipped": True, "source": source, "lines": [], "backend": "none", "cached": False}
    key = cache_key(lines, target)
    cached = read_cache(key, directory)
    if cached and isinstance(cached.get("lines"), list) and len(cached["lines"]) == len(lines):
        return {"lines": cached["lines"], "source": cached.get("source", source), "backend": cached.get("backend", ""), "cached": True}
    backends = configured_backends() if backends is None else backends
    if not backends:
        raise NoBackend("Tłumaczenia nie są skonfigurowane na serwerze.")
    groups = make_batches(lines)
    if len(groups) > MAX_REQUESTS:
        raise ValueError("tekst jest za długi do przetłumaczenia")
    error = None
    for backend in backends:
        try:
            results = [backend.translate([lines[i] for i in group], source, target) for group in groups]
        except BackendError as e:
            error = e
            continue
        translated = merge(lines, groups, results)
        write_cache(key, {"lines": translated, "source": source, "backend": backend.name, "target": target, "at": int(time.time())}, directory)
        return {"lines": translated, "source": source, "backend": backend.name, "cached": False}
    raise error or BackendError("żaden backend nie odpowiedział")


def handle(handler, match, user):
    """Trasa POST /piterfy-api/translate (rejestrowana poniżej, wymaga tokenu Navidrome)."""
    def reply(code, body):
        handler.send(code, json.dumps(body, ensure_ascii=False, separators=(",", ":")), "application/json", {"Cache-Control": "no-cache"})
    try:
        data = handler.read_json(200000)
        if not isinstance(data, dict):
            raise ValueError("nieprawidłowe żądanie")
        reply(200, translate_lines(data.get("lines"), data.get("target", "pl")))
    except ValueError as e:
        reply(400, {"error": str(e)})
    except NoBackend as e:
        reply(503, {"error": str(e)})
    except BackendError as e:
        reply(502, {"error": "Tłumaczenie chwilowo niedostępne: " + str(e)[:120]})


try:
    import profile_api
    profile_api.register("POST", r"/piterfy-api/translate", handle)
except ImportError:  # testy bez serwera HTTP
    pass
