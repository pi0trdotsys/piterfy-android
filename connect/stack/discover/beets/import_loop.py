"""Co minutę przenosi gotowe pobrania ze slskd (/incoming) do biblioteki (/music) przez beets.

Plik uznaje za gotowy, gdy przez minutę nie zmienił rozmiaru — slskd kopiuje pliki z folderu
niedokończonych pobrań i nie chcemy złapać pliku w połowie kopiowania. Plików, których beets nie
potrafi zaimportować (np. uszkodzonych), nie próbuje w kółko: odkłada je do /config/failed.

Między importami analizuje widmo nowych plików biblioteki (spectrum.py) — wykrywa fałszywe FLAC-i.
"""

import shutil
import subprocess
import time
from datetime import datetime
from pathlib import Path

AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".alac", ".aiff"}
INCOMING = Path("/incoming")
FAILED = Path("/config/failed")


def log(message):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}", flush=True)


def audio_files():
    sizes = {}
    for path in INCOMING.rglob("*"):
        try:
            if path.suffix.lower() in AUDIO and path.is_file():
                sizes[path] = path.stat().st_size
        except OSError:  # plik zniknął w trakcie przeglądania
            pass
    return sizes


def remove_empty_dirs():
    for folder in sorted((p for p in INCOMING.rglob("*") if p.is_dir()), reverse=True):
        try:
            folder.rmdir()
        except OSError:
            pass


log("beets: pilnuję /incoming")
previous = {}
while True:
    current = audio_files()
    ready = [path for path, size in current.items() if previous.get(path) == size]
    previous = current
    if ready:
        log(f"Importuję {len(ready)} plików")
        if subprocess.run(["beet", "import", *map(str, ready)]).returncode != 0:
            log("beets zakończył się błędem — spróbuję ponownie za minutę")  # np. błąd w config.yaml
            previous = {}
            time.sleep(60)
            continue
        for path in ready:
            if path.exists():  # beets go nie przeniósł
                FAILED.mkdir(parents=True, exist_ok=True)
                shutil.move(path, FAILED / path.name)
                log(f"Nie zaimportowany, odłożony do /config/failed: {path.name}")
        remove_empty_dirs()
    try:
        import spectrum  # tu, a nie na górze: błąd analizy nie może zatrzymać importu muzyki
        # ok. 40 s analizy na minutę: całą bibliotekę przelicza za pierwszym razem, potem tylko nowe pliki
        spectrum.scan(seconds=40, log=log)
    except Exception as e:  # analiza nie może zatrzymać importu
        log(f"Analiza widma się nie udała: {e}")
    time.sleep(60)
