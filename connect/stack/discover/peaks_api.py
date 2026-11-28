"""GET /piterfy-api/peaks/<id utworu> — fala dźwięku do paska postępu (liczy ją kontener beets, patrz beets/peaks.py).

Odpowiedzi (klient przy czymkolwiek innym niż 200 zostaje przy zwykłym pasku):
  200  {"v":1,"id":"<id>","n":120..240,"peaks":[0..255,…],"duration":<sekundy>}   gotowe; do zapamiętania na dobę
  202  {"pending":true}                                                           w kolejce, zapytaj ponownie za kilkanaście sekund
  404  {"error":"…"}                                                              nie ma takiego utworu/pliku albo ffmpeg go nie czyta
Trasa nigdy nie dekoduje dźwięku: czyta wynik z PEAKS_DIR albo dopisuje prośbę do kolejki. Autoryzacja tokenem Navidrome jak
przy pozostałych trasach (profile_api.register).
"""

import json
import os
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "beets"))  # peaks.py leży w katalogu kontenera beets
import peaks  # noqa: E402

RETRY_AFTER_S = 15
CACHE_OK = {"Cache-Control": "private, max-age=86400"}
CACHE_NO = {"Cache-Control": "no-cache"}


def peaks_dir():
    """Folder wyników: PEAKS_DIR albo `peaks` obok spectrum.json (ten sam katalog widzi kontener beets)."""
    env = os.environ.get("PEAKS_DIR")
    if env:
        return Path(env)
    return Path(os.environ.get("SPECTRUM_FILE") or "/app/beets/spectrum.json").parent / "peaks"


def answer(directory, music_dir, rel_path, song_id, now=None):
    """(kod, ciało, nagłówki) dla pliku z biblioteki. Czysta względem sieci: dysk i katalog wyników dostaje z argumentów."""
    if not rel_path:
        return 404, {"error": "Nie znam tego utworu."}, CACHE_NO
    try:
        st = (Path(music_dir) / rel_path).stat()
    except OSError:
        return 404, {"error": "Nie ma pliku tego utworu w bibliotece."}, CACHE_NO
    key = peaks.cache_key(rel_path, st.st_mtime, st.st_size)
    result = peaks.load_result(directory, key)
    if result is not None:
        return 200, {**result, "id": str(song_id)}, CACHE_OK
    if peaks.is_bad(directory, key):
        return 404, {"error": "Tego pliku nie da się odczytać do fali dźwięku."}, CACHE_NO
    peaks.enqueue(directory, key, rel_path, now)
    return 202, {"pending": True}, {**CACHE_NO, "Retry-After": str(RETRY_AFTER_S)}


def register_routes(profile_api, music_dir, log=print):
    """Dopisuje GET /piterfy-api/peaks/<id> do serwera profile_api."""

    def route(handler, match, user):
        song_id = urllib.parse.unquote(match.group(1))
        song = profile_api.navidrome_get(handler.navidrome_url, "/api/song/" + urllib.parse.quote(song_id, safe=""),
                                         handler.headers.get("X-ND-Authorization", ""))
        if not isinstance(song, dict) or not song.get("path"):
            return handler.send(404, json.dumps({"error": "Nie znam tego utworu albo sesja wygasła."}), "application/json", CACHE_NO)
        code, body, headers = answer(peaks_dir(), music_dir, profile_api.library_key(song), song_id)
        handler.send(code, json.dumps(body, separators=(",", ":")), "application/json", headers)

    profile_api.register("GET", r"/piterfy-api/peaks/([^/]+)", route)
