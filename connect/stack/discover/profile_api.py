"""Mały serwer HTTP Piterfy w wątku discovera; Caddy kieruje do niego /piterfy-api/*.

Zdjęcia profilowe — Navidrome nie ma własnych (obrazki przyjmuje tylko dla wykonawców, playlist i radia):
GET    /piterfy-api/avatar/<użytkownik>  zdjęcie (404, gdy go nie ma)
PUT    /piterfy-api/avatar               zapisuje zdjęcie zalogowanego użytkownika
DELETE /piterfy-api/avatar               usuwa je
Przeglądarka przycina zdjęcie do kwadratu 512 × 512 i wysyła JPEG, więc tu nie trzeba bibliotek do obrazków.

Rzeczywista jakość utworu (plakietka na pasku odtwarzania):
GET    /piterfy-api/quality/<id utworu>  wynik analizy widma pliku (spectrum.py w kontenerze beets)

Wszystko poza pobraniem zdjęcia wymaga tokenu sesji Navidrome (nagłówek X-ND-Authorization),
który sprawdza sam Navidrome.
"""
import base64
import hashlib
import json
import os
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(os.environ.get("PROFILE_API_PORT") or 8090)
MAX_BYTES = 2 * 1024 * 1024
SPECTRUM_FILE = Path(os.environ.get("SPECTRUM_FILE") or "/app/beets/spectrum.json")
QUALITY_FIELDS = ("verdict", "cutoff", "source_kbps", "sr", "codec")


def navidrome_get(navidrome_url, path, auth):
    """JSON z natywnego API Navidrome z tokenem użytkownika albo None (zły token, brak danych, błąd)."""
    if not auth.startswith("Bearer "):
        return None
    req = urllib.request.Request(navidrome_url + path, headers={"X-ND-Authorization": auth})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read() or b"null")
    except (urllib.error.URLError, OSError, ValueError):  # 401 przy nieważnym tokenie to też URLError (HTTPError)
        return None


_spectrum = {"mtime": None, "data": {}}


def spectrum_index():
    """Wyniki analizy widma; plik wczytujemy ponownie dopiero, gdy kontener beets go zmieni."""
    try:
        mtime = SPECTRUM_FILE.stat().st_mtime
        if mtime != _spectrum["mtime"]:
            _spectrum.update(data=json.loads(SPECTRUM_FILE.read_text(encoding="utf-8")), mtime=mtime)
    except (OSError, ValueError):
        pass
    return _spectrum["data"]


def library_key(song):
    """Ścieżka pliku względem biblioteki — tak jak klucze w spectrum.json."""
    path, library = song.get("path") or "", (song.get("libraryPath") or "").rstrip("/")
    if library and path.startswith(library + "/"):
        path = path[len(library) + 1:]
    return path.lstrip("/")


def username_from_token(navidrome_url, auth):
    """Nazwa użytkownika z tokenu sesji — tylko jeśli Navidrome go przyjmuje (sprawdza podpis i ważność)."""
    if navidrome_get(navidrome_url, "/api/song?_start=0&_end=1", auth) is None:
        return None
    try:
        payload = auth.split(" ", 1)[1].split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except (IndexError, ValueError):
        return None
    return claims.get("sub") or None


class Handler(BaseHTTPRequestHandler):
    server_version = "piterfy-profile"
    navidrome_url = ""
    avatars = Path("/data/avatars")

    def log_message(self, fmt, *args):
        pass

    def avatar_path(self, username):
        # nazwa pliku ze skrótu nazwy użytkownika — żadnych ukośników ani kropek z zewnątrz w ścieżce
        return self.avatars / (hashlib.sha256(username.encode()).hexdigest()[:32] + ".jpg")

    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        body = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        if code != 304:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def route(self):
        return urllib.parse.urlsplit(self.path).path

    def do_GET(self):
        quality = re.fullmatch(r"/piterfy-api/quality/([^/]+)", self.route())
        if quality:
            return self.quality(urllib.parse.unquote(quality.group(1)))
        m = re.fullmatch(r"/piterfy-api/avatar/([^/]+)", self.route())
        if not m:
            return self.send(404, "Nie ma takiej strony.")
        path = self.avatar_path(urllib.parse.unquote(m.group(1)))
        if not path.is_file():
            return self.send(404, "Brak zdjęcia profilowego.", headers={"Cache-Control": "no-cache"})
        st = path.stat()
        etag = f'"{int(st.st_mtime)}-{st.st_size}"'
        headers = {"ETag": etag, "Cache-Control": "no-cache"}
        if self.headers.get("If-None-Match") == etag:
            return self.send(304, headers=headers)
        self.send(200, path.read_bytes(), "image/jpeg", headers)

    do_HEAD = do_GET

    def quality(self, song_id):
        song = navidrome_get(self.navidrome_url, "/api/song/" + urllib.parse.quote(song_id, safe=""),
                             self.headers.get("X-ND-Authorization", ""))
        if not isinstance(song, dict) or not song.get("path"):
            return self.send(404, "Nie znam tego utworu albo sesja wygasła.")
        key = library_key(song)
        if key.startswith("Podcasty/"):  # odcinki podcastów nie przechodzą analizy widma
            return self.send(200, json.dumps({"analyzed": True, "podcast": True}), "application/json",
                             {"Cache-Control": "no-cache"})
        entry = spectrum_index().get(key) or {}
        body = {"analyzed": bool(entry), **{k: entry[k] for k in QUALITY_FIELDS if k in entry}}
        self.send(200, json.dumps(body), "application/json", {"Cache-Control": "no-cache"})

    def authorized_user(self):
        user = username_from_token(self.navidrome_url, self.headers.get("X-ND-Authorization", ""))
        if not user:
            self.close_connection = True
            self.send(401, "Zaloguj się ponownie w Piterfy.")
        return user

    def do_PUT(self):
        if self.route() != "/piterfy-api/avatar":
            return self.send(404, "Nie ma takiej strony.")
        user = self.authorized_user()
        if not user:
            return
        length = int(self.headers.get("Content-Length") or 0)
        if not 0 < length <= MAX_BYTES:
            self.close_connection = True
            return self.send(413, "Zdjęcie może mieć najwyżej 2 MB.")
        data = self.rfile.read(length)
        if not data.startswith(b"\xff\xd8\xff"):
            return self.send(415, "Przyjmuję tylko JPEG.")
        path = self.avatar_path(user)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(path)
        self.send(204)

    def do_DELETE(self):
        if self.route() != "/piterfy-api/avatar":
            return self.send(404, "Nie ma takiej strony.")
        user = self.authorized_user()
        if user:
            self.avatar_path(user).unlink(missing_ok=True)
            self.send(204)


def start(navidrome_url, avatars_dir, log=print):
    """Uruchamia serwer w tle (wątek demona). Błąd startu nie może zatrzymać discovera."""
    Handler.navidrome_url = navidrome_url
    Handler.avatars = Path(avatars_dir)
    try:
        server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError as e:
        log(f"Serwer zdjęć profilowych nie wystartował: {e}")
        return None
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="profile-api", daemon=True).start()
    log(f"Zdjęcia profilowe: serwer na porcie {PORT}.")
    return server
