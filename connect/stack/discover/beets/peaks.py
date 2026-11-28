"""Fala dźwięku do paska postępu: ~120–240 słupków głośności na utwór (RMS po 0–255), liczone w kontenerze beets.

Dlaczego tutaj: tylko kontener beets ma ffmpeg. Discover (trasa GET /piterfy-api/peaks/<id>, peaks_api.py) nie dekoduje
niczego sam — czyta gotowy plik z PEAKS_DIR albo zostawia prośbę w kolejce. Oba kontenery widzą ten sam folder
(`./discover/beets` to /config w beets i /app/beets w discoverze), więc PEAKS_DIR leży obok spectrum.json.

Układ PEAKS_DIR:
  <klucz>.json          wynik: {"v":1,"n":..,"peaks":[..],"duration":..}; klucz = skrót ścieżki + rozmiar + czas zmiany pliku,
                        więc podmiana pliku albo zmiana tagów unieważnia wynik bez żadnego sprzątania
  queue/<klucz>.req     prośba {"path": ścieżka względem /music, "at": czas}; jeden plik na prośbę, bez blokad (pisze discover,
                        kasuje worker), ponowne zgłoszenie tego samego pliku niczego nie dubluje

Moduł jest czysty i bez zależności (tylko stdlib): `bucket_count`, `Windower`, `rebin`, `normalise`, `cache_key` i obsługa
kolejki dają się testować bez ffmpeg (test_peaks.py). `compute` i `Worker` robią resztę: ffmpeg z niskim priorytetem
(`nice`), mono 8 kHz, strumieniowo, więc nawet dwugodzinna audycja nie zajmuje pamięci.
"""

import hashlib
import json
import math
import os
import subprocess
import sys
import threading
import time
from array import array
from operator import mul
from pathlib import Path

VERSION = 1
SAMPLE_RATE = 8000
WINDOW = 80                 # próbek w oknie (10 ms): drobne ziarno, z którego składamy słupki
BUCKETS_MIN, BUCKETS_MAX = 120, 240
SILENCE_RMS = 8.0           # poniżej tego (na skali ±32768) cały utwór uznajemy za ciszę: same zera zamiast wzmocnionego szumu
AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".alac", ".aiff", ".aif", ".ape", ".wv"}
DECODE_TIMEOUT_S = 900
REQUEST_MAX_AGE_S = 3 * 24 * 3600   # prośba starsza niż 3 dni to martwy wpis (np. plik zniknął)


# ── czysta logika ──

def bucket_count(duration):
    """Liczba słupków: mniej więcej jeden na sekundę, ale zawsze 120–240 (kontrakt trasy)."""
    try:
        seconds = float(duration)
    except (TypeError, ValueError):
        seconds = 0.0
    if not math.isfinite(seconds) or seconds < 0:
        seconds = 0.0
    return max(BUCKETS_MIN, min(BUCKETS_MAX, int(round(seconds))))


class Windower:
    """Zbiera surowe próbki 16-bitowe (little endian, mono) w okna po WINDOW próbek: suma kwadratów i liczba próbek."""

    def __init__(self):
        self.sums, self.counts = array("d"), array("I")
        self._tail = array("h")
        self._odd = b""

    def feed(self, data):
        data = self._odd + bytes(data)
        usable = len(data) - len(data) % 2
        self._odd = data[usable:]
        if not usable:
            return
        chunk = array("h")
        chunk.frombytes(data[:usable])
        if sys.byteorder == "big":
            chunk.byteswap()
        if self._tail:
            chunk = self._tail + chunk
            self._tail = array("h")
        full = len(chunk) - len(chunk) % WINDOW
        for start in range(0, full, WINDOW):
            window = chunk[start:start + WINDOW]
            self.sums.append(float(sum(map(mul, window, window))))
            self.counts.append(WINDOW)
        self._tail = chunk[full:]

    def finish(self):
        """Dokłada niepełne okno z końca i zwraca (sumy, liczby)."""
        if self._tail:
            window = self._tail
            self.sums.append(float(sum(map(mul, window, window))))
            self.counts.append(len(window))
            self._tail = array("h")
        return self.sums, self.counts

    @property
    def samples(self):
        return sum(self.counts) + len(self._tail)


def rebin(sums, counts, n):
    """RMS dla `n` słupków z okien (równomiernie wg liczby próbek). Dla bardzo krótkich nagrań słupek bierze najbliższe okno."""
    windows = len(sums)
    if not windows or n <= 0:
        return [0.0] * max(0, n)
    cum_sum, cum_count = [0.0], [0]
    for s, c in zip(sums, counts):
        cum_sum.append(cum_sum[-1] + s)
        cum_count.append(cum_count[-1] + c)
    total = cum_count[-1]
    out = []

    def at(position):
        """Skumulowana suma kwadratów i liczba próbek w punkcie `position` (indeks próbki), liniowo między oknami."""
        position = max(0.0, min(float(total), position))
        lo, hi = 0, windows
        while lo < hi:  # ostatnie okno, które zaczyna się nie po `position`
            mid = (lo + hi) // 2
            if cum_count[mid + 1] <= position:
                lo = mid + 1
            else:
                hi = mid
        if lo >= windows:
            return cum_sum[-1]
        share = (position - cum_count[lo]) / max(1, counts[lo])
        return cum_sum[lo] + sums[lo] * share

    for i in range(n):
        a, b = total * i / n, total * (i + 1) / n
        if b - a < 1:  # mniej niż próbka na słupek (nagranie krótsze niż n próbek) — bierzemy okno pod środkiem
            j = min(windows - 1, max(0, int(((a + b) / 2) * windows / max(1, total))))
            out.append(math.sqrt(sums[j] / max(1, counts[j])))
            continue
        energy = at(b) - at(a)
        out.append(math.sqrt(max(0.0, energy) / (b - a)))
    return out


def normalise(levels):
    """Poziomy RMS → liczby całkowite 0–255, najgłośniejszy słupek = 255. Cichy utwór dostaje same zera."""
    peak = max(levels) if levels else 0.0
    if peak < SILENCE_RMS:
        return [0] * len(levels)
    return [max(0, min(255, int(round(level / peak * 255)))) for level in levels]


def build(sums, counts, sample_rate=SAMPLE_RATE):
    """Gotowy wynik (słownik do zapisania) z okien: czas trwania liczymy z faktycznej liczby próbek."""
    total = sum(counts)
    duration = round(total / sample_rate, 2)
    n = bucket_count(duration)
    return {"v": VERSION, "n": n, "peaks": normalise(rebin(sums, counts, n)), "duration": duration}


def cache_key(rel_path, mtime, size):
    """Nazwa wyniku dla pliku: skrót ścieżki względem biblioteki + czas zmiany (w sekundach) + rozmiar."""
    text = f"{str(rel_path).lstrip('/')}\0{int(mtime)}\0{int(size)}"
    return hashlib.sha1(text.encode("utf-8", "surrogatepass")).hexdigest()[:24]


def valid(result):
    """Czy wczytany JSON to poprawny wynik (żeby uszkodzony albo stary plik nie trafił do klienta)."""
    if not isinstance(result, dict) or result.get("v") != VERSION:
        return False
    peaks, n = result.get("peaks"), result.get("n")
    return (isinstance(peaks, list) and peaks and isinstance(n, int) and n == len(peaks)
            and BUCKETS_MIN <= n <= BUCKETS_MAX and all(isinstance(p, int) and 0 <= p <= 255 for p in peaks))


# ── pliki: wynik i kolejka ──

def result_path(directory, key):
    return Path(directory) / f"{key}.json"


def load_result(directory, key):
    try:
        result = json.loads(result_path(directory, key).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return result if valid(result) else None


def save_result(directory, key, result):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    tmp = directory / f"{key}.tmp"
    tmp.write_text(json.dumps(result, separators=(",", ":")), encoding="utf-8")
    tmp.replace(result_path(directory, key))


def bad_path(directory, key):
    return Path(directory) / f"{key}.bad"


def is_bad(directory, key):
    """Plik, którego ffmpeg nie umiał zdekodować: znacznik zapobiega ponawianiu przy każdym pytaniu klienta."""
    return bad_path(directory, key).exists()


def mark_bad(directory, key):
    Path(directory).mkdir(parents=True, exist_ok=True)
    try:
        bad_path(directory, key).write_text("", encoding="utf-8")
    except OSError:
        pass


def queue_dir(directory):
    return Path(directory) / "queue"


def enqueue(directory, key, rel_path, now=None):
    """Zostawia prośbę o policzenie fali. Zwraca True, gdy dopisano nową; False, gdy ta sama już czeka."""
    folder = queue_dir(directory)
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"{key}.req"
    body = json.dumps({"path": str(rel_path), "at": time.time() if now is None else now}, ensure_ascii=False)
    try:
        with open(target, "x", encoding="utf-8") as handle:  # „x”: nie nadpisujemy — prośba może już być w trakcie
            handle.write(body)
        return True
    except FileExistsError:
        return False
    except OSError:
        return False


def is_queued(directory, key):
    return (queue_dir(directory) / f"{key}.req").exists()


def read_queue(directory, now=None):
    """Prośby od najstarszej: lista (klucz, ścieżka względem biblioteki). Przeterminowane i uszkodzone wpisy są kasowane."""
    now = time.time() if now is None else now
    items = []
    folder = queue_dir(directory)
    try:
        names = sorted(folder.glob("*.req"))
    except OSError:
        return []
    for path in names:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            rel, at = str(data["path"]), float(data.get("at") or 0)
        except (OSError, ValueError, KeyError, TypeError):
            path.unlink(missing_ok=True)
            continue
        if now - at > REQUEST_MAX_AGE_S:
            path.unlink(missing_ok=True)
            continue
        items.append((at, path.stem, rel))
    items.sort()
    return [(key, rel) for _, key, rel in items]


def done(directory, key):
    (queue_dir(directory) / f"{key}.req").unlink(missing_ok=True)


def stale_results(directory, valid_keys):
    """Nazwy wyników, których plik biblioteki już nie istnieje albo się zmienił. Przy pustej liście kluczy nic nie kasujemy
    (odmontowany dysk nie może skasować całej pamięci podręcznej)."""
    if not valid_keys:
        return []
    keep = set(valid_keys)
    try:
        return [p.name for pattern in ("*.json", "*.bad") for p in Path(directory).glob(pattern) if p.stem not in keep]
    except OSError:
        return []


# ── dekodowanie (ffmpeg) i praca w tle ──

def nice():
    os.nice(15)  # fala ma nie spowalniać odtwarzania ani importu na serwerze


def compute(path, timeout=DECODE_TIMEOUT_S):
    """Dekoduje plik ffmpegiem (mono 8 kHz, strumieniowo) i zwraca wynik albo rzuca ValueError/OSError."""
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-i", str(path), "-map", "0:a:0", "-ac", "1", "-ar", str(SAMPLE_RATE),
           "-f", "s16le", "-acodec", "pcm_s16le", "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, preexec_fn=nice if os.name == "posix" else None)
    windower, started = Windower(), time.time()
    try:
        while True:
            chunk = proc.stdout.read(1 << 16)
            if not chunk:
                break
            windower.feed(chunk)
            if time.time() - started > timeout:
                raise ValueError("dekodowanie trwa za długo")
    finally:
        proc.stdout.close()
        if proc.poll() is None:
            proc.kill()
        proc.wait()
    sums, counts = windower.finish()
    if not sums:
        raise ValueError("brak dźwięku w pliku")
    return build(sums, counts)


class Worker:
    """Pętla w tle (osobny wątek kontenera beets): najpierw prośby z kolejki, potem powoli zaległości całej biblioteki."""

    def __init__(self, music, directory, log=print, compute_fn=compute):
        self.music, self.directory, self.log, self.compute = Path(music), Path(directory), log, compute_fn
        self.todo = []              # zaległości: (klucz, ścieżka względem biblioteki)
        self.scanned_at = 0.0

    def key_for(self, rel):
        try:
            st = (self.music / rel).stat()
        except OSError:
            return None
        return cache_key(rel, st.st_mtime, st.st_size)

    def process(self, key, rel):
        """Liczy jeden plik i zapisuje wynik. Zwraca True, gdy wynik jest zapisany."""
        path = self.music / rel
        if not path.is_file():
            return False
        try:
            save_result(self.directory, key, self.compute(path))
            return True
        except (ValueError, OSError) as e:
            mark_bad(self.directory, key)
            self.log(f"Fala dźwięku się nie policzyła ({rel}): {str(e)[:120]}")
            return False

    def scan_library(self):
        """Przegląda bibliotekę: zaległości bez wyniku trafiają do `todo`, a wyniki starych wersji plików są kasowane."""
        present, todo = [], []
        for path in self.music.rglob("*"):
            try:
                if path.suffix.lower() not in AUDIO or not path.is_file():
                    continue
                rel = path.relative_to(self.music).as_posix()
                st = path.stat()
            except OSError:
                continue
            key = cache_key(rel, st.st_mtime, st.st_size)
            present.append(key)
            if rel.startswith("Podcasty/"):  # długie audycje liczymy tylko na prośbę
                continue
            if not result_path(self.directory, key).exists() and not is_bad(self.directory, key):
                todo.append((key, rel))
        # prośby o pliki, których już nie ma w bibliotece (przeniesione, skasowane), wypadają same przez process()
        for name in stale_results(self.directory, present):
            (self.directory / name).unlink(missing_ok=True)
        self.todo, self.scanned_at = todo, time.time()

    def step(self, rescan_every=900):
        """Jeden krok: prośba z kolejki, a gdy kolejka pusta — jedna zaległość. Zwraca True, gdy coś policzono (nie warto spać)."""
        for key, rel in read_queue(self.directory):
            if load_result(self.directory, key) is None and not is_bad(self.directory, key):
                real = self.key_for(rel)
                if real is not None and real != key:   # plik zmienił się po zgłoszeniu: liczymy pod aktualnym kluczem
                    done(self.directory, key)
                    key = real
                self.process(key, rel)
            done(self.directory, key)
            return True
        if not self.todo and time.time() - self.scanned_at > rescan_every:
            try:
                self.scan_library()
            except OSError as e:
                self.log(f"Przegląd biblioteki dla fal się nie udał: {e}")
                self.scanned_at = time.time()
        while self.todo:
            key, rel = self.todo.pop()
            if not result_path(self.directory, key).exists() and not is_bad(self.directory, key):
                self.process(key, rel)
                return True
        return False

    def run_forever(self, idle=3.0, busy=0.2):
        self.log("fale dźwięku: pilnuję kolejki i liczę zaległości")
        while True:
            try:
                time.sleep(busy if self.step() else idle)
            except Exception as e:  # wątek tła nie może paść od jednego złego pliku
                self.log(f"fale dźwięku: {e}")
                time.sleep(30)


def start_background(music="/music", directory=None, log=print):
    """Uruchamia Worker w wątku demona; wołane z import_loop.py."""
    directory = directory or os.environ.get("PEAKS_DIR") or "/config/peaks"
    worker = Worker(os.environ.get("MUSIC_DIR", music), directory, log)
    thread = threading.Thread(target=worker.run_forever, name="peaks", daemon=True)
    thread.start()
    return worker
