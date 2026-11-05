#!/usr/bin/env python3
"""
bulk_import_liked_songs.py

Czyta eksport Spotify "Liked Songs" (CSV z Exportify) i dla kazdego utworu:
  1. loguje sie do REST API slskd,
  2. odpytuje siec Soulseek (wyszukiwanie po "Artist Name(s) - Track Name"),
  3. spord wynikow wybiera najlepszy plik audio (preferuje flac > mp3 wysokiej
     jakosci, odrzuca pliki o czasie trwania mocno odbiegajacym od oryginalu),
  4. zleca pobranie do katalogu /downloads w kontenerze slskd (czyli
     incoming/ na hoscie; beets przenosi utwory do library/).

WAZNE - zanim odpalisz na calej liscie:
  - Nie moglem przetestowac dokladnego ksztaltu API na Twojej zywej instancji
    slskd (0.26.0.0), wiec najpierw zrob suchy/maly test:
        python3 bulk_import_liked_songs.py --limit 5 --dry-run
    a potem bez --dry-run na tych samych 5, sprawdz w slskd web UI (zakladka
    "Downloads") czy transfery faktycznie ruszyly, dopiero potem pelna lista.
  - To dziala W TLE i moze trwac naprawde dlugo (wyszukiwanie + kolejkowanie
    kazdego utworu to realnie kilka-kilkanascie sekund oczekiwania na
    odpowiedzi rowniorzednych). Odpalaj przez `nohup ... &` albo `screen`/`tmux`,
    zeby przezylo rozlaczenie SSH.
  - Wyniki (trafienia/braki) laduja w reports/*.json - braki to naturalny
    "wanted list" do recznego dogrania pozniej.

Wymagania: pip install requests
"""

import argparse
import csv
import json
import os
import random
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

LOG_LOCK = threading.Lock()
RESULTS_LOCK = threading.Lock()

SLSKD_URL = os.environ.get("SLSKD_URL", "http://localhost:5030")
SLSKD_USERNAME = os.environ.get("SLSKD_WEB_USERNAME", "admin")
SLSKD_PASSWORD = os.environ.get("SLSKD_WEB_PASSWORD", "")

AUDIO_EXT = (".flac", ".mp3", ".m4a", ".ogg", ".opus", ".wav")
EXT_RANK = {".flac": 0, ".wav": 1, ".m4a": 2, ".ogg": 3, ".opus": 3, ".mp3": 4}

SEARCH_TIMEOUT_S = 35
SEARCH_POLL_INTERVAL_S = 1.5
BETWEEN_TRACKS_SLEEP_S = 2.0

REPORT_DIR = Path("reports")
REPORT_DIR.mkdir(exist_ok=True)


def log(msg):
    with LOG_LOCK:
        print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def request_with_retry(method, url, retries=6, backoff_s=3, **kwargs):
    """Jak requests.request, ale ponawia z rosnacym odczekaniem na 429 (slskd
    ogranicza czestotliwosc zapytan, zwlaszcza przy kilku rownoleglych watkach)."""
    last_exc = None
    for attempt in range(retries):
        r = requests.request(method, url, **kwargs)
        if r.status_code == 429:
            wait = backoff_s * (attempt + 1) + random.uniform(0, 1)
            log(f"  -> 429 (rate limit) od slskd, czekam {wait:.1f}s i ponawiam ({attempt + 1}/{retries})")
            time.sleep(wait)
            continue
        try:
            r.raise_for_status()
        except requests.HTTPError as exc:
            last_exc = exc
            raise
        return r
    if last_exc:
        raise last_exc
    r.raise_for_status()
    return r


def get_token():
    r = requests.post(
        f"{SLSKD_URL}/api/v0/session",
        json={"username": SLSKD_USERNAME, "password": SLSKD_PASSWORD},
        timeout=15,
    )
    r.raise_for_status()
    data = r.json()
    token = data.get("token") or data.get("Token")
    if not token:
        raise RuntimeError(f"Nie znalazlem tokenu w odpowiedzi /session: {data}")
    return token


def auth_headers(token):
    return {"Authorization": f"Bearer {token}"}


def load_tracks(csv_path, limit=None):
    tracks = []
    with open(csv_path, encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            artist = (row.get("Artist Name(s)", "") or "").split(";")[0].strip()
            title = (row.get("Track Name", "") or "").strip()
            dur_ms = row.get("Duration (ms)", "")
            try:
                dur_s = int(float(dur_ms)) // 1000 if dur_ms else None
            except ValueError:
                dur_s = None
            if artist and title:
                tracks.append({"artist": artist, "title": title, "duration_s": dur_s})
            if limit and len(tracks) >= limit:
                break
    return tracks


def start_search(token, query_text):
    r = request_with_retry(
        "POST",
        f"{SLSKD_URL}/api/v0/searches",
        headers=auth_headers(token),
        json={"searchText": query_text},
        timeout=15,
    )
    data = r.json()
    return data.get("id") or data.get("Id")


def wait_for_search(token, search_id, timeout_s=SEARCH_TIMEOUT_S):
    """Czeka az stan wyszukiwania oznaczy zakonczenie, ale niezaleznie od tego
    zawsze zwraca ostatnio widziany stan (nigdy None) - nawet jesli nasz wlasny
    limit czasu minie zanim slskd oznaczy wyszukiwanie jako 'Completed', bo
    odpowiedzi czesto juz wtedy istnieja i warto je sprawdzic."""
    deadline = time.time() + timeout_s
    last_data = None
    while time.time() < deadline:
        r = request_with_retry("GET", f"{SLSKD_URL}/api/v0/searches/{search_id}", headers=auth_headers(token), timeout=15)
        last_data = r.json()
        state = (last_data.get("state") or last_data.get("State") or "").lower()
        if "completed" in state or "timedout" in state or "cancelled" in state:
            return last_data
        time.sleep(SEARCH_POLL_INTERVAL_S)
    return last_data


def get_responses(token, search_id):
    r = request_with_retry(
        "GET", f"{SLSKD_URL}/api/v0/searches/{search_id}/responses", headers=auth_headers(token), timeout=15
    )
    return r.json()


def pick_best_file(responses, track):
    """Zwraca (username, file_dict) najlepszego trafienia, albo (None, None)."""
    best = None
    best_score = -1
    target_dur = track["duration_s"]
    artist_l = track["artist"].lower()
    title_l = track["title"].lower()

    for resp in responses:
        username = resp.get("username") or resp.get("Username")
        files = resp.get("files") or resp.get("Files") or []
        for f in files:
            filename = (f.get("filename") or f.get("Filename") or "").lower()
            ext = os.path.splitext(filename)[1]
            if ext not in AUDIO_EXT:
                continue
            if title_l not in filename:
                continue  # zbyt ryzykowne dopasowanie bez tytulu w nazwie pliku

            score = 100 - EXT_RANK.get(ext, 9)
            if artist_l in filename:
                score += 20

            length_s = f.get("length") or f.get("Length")
            if target_dur and length_s:
                diff = abs(int(length_s) - target_dur)
                if diff > 15:
                    continue  # zbyt duza roznica dlugosci - prawdopodobnie zle nagranie/remix
                score += max(0, 10 - diff)

            bitrate = f.get("bitRate") or f.get("BitRate") or 0
            score += min(bitrate / 64, 10) if bitrate else 0

            if score > best_score:
                best_score = score
                best = (username, f)

    return best if best else (None, None)


def enqueue_download(token, username, file_obj):
    payload = [{
        "filename": file_obj.get("filename") or file_obj.get("Filename"),
        "size": file_obj.get("size") or file_obj.get("Size"),
    }]
    request_with_retry(
        "POST",
        f"{SLSKD_URL}/api/v0/transfers/downloads/{username}",
        headers=auth_headers(token),
        json=payload,
        timeout=15,
    )
    return True


def process_track(token, i, total, t, dry_run):
    query = f"{t['artist']} {t['title']}"
    time.sleep(random.uniform(0, 4))  # jitter - rozjezdza starty rownoleglych watkow
    log(f"[{i}/{total}] Szukam: {query}")
    try:
        search_id = start_search(token, query)
        wait_for_search(token, search_id)  # nie przerywamy nawet gdy stan nie osiagnal "Completed"

        responses = get_responses(token, search_id)
        if not responses:
            log(f"[{i}/{total}]   -> zero odpowiedzi z sieci Soulseek")
            return ("miss", {**t, "reason": "no_responses"})

        username, file_obj = pick_best_file(responses, t)
        if not file_obj:
            log(f"[{i}/{total}]   -> brak pasujacego pliku wsrod wynikow")
            return ("miss", {**t, "reason": "no_match", "n_responses": len(responses)})

        fname = file_obj.get("filename") or file_obj.get("Filename")
        log(f"[{i}/{total}]   -> wybrano: {fname} (od {username})")

        if not dry_run:
            enqueue_download(token, username, file_obj)
            log(f"[{i}/{total}]   -> zlecono pobranie")

        return ("hit", {**t, "username": username, "filename": fname})

    except requests.RequestException as exc:
        log(f"[{i}/{total}]   -> BLAD API: {exc}")
        return ("miss", {**t, "reason": f"api_error: {exc}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv_path", nargs="?", default="private/liked_songs.csv")
    ap.add_argument("--limit", type=int, default=None, help="przetworz tylko N pierwszych utworow (test)")
    ap.add_argument("--dry-run", action="store_true", help="szukaj, ale nie zlecaj pobierania")
    ap.add_argument("--start-at", type=int, default=0, help="pomin pierwsze N utworow (wznowienie)")
    ap.add_argument("--workers", type=int, default=1, help="liczba rownoleglych wyszukiwan (np. 4)")
    args = ap.parse_args()

    if not SLSKD_PASSWORD:
        sys.exit("Ustaw zmienna SLSKD_WEB_PASSWORD (export SLSKD_WEB_PASSWORD=...) przed uruchomieniem.")

    tracks = load_tracks(args.csv_path, limit=(args.start_at + args.limit) if args.limit else None)
    tracks = tracks[args.start_at:]
    total = len(tracks)
    log(f"Wczytano {total} utworow do przetworzenia (start-at={args.start_at}, workers={args.workers}).")

    token = get_token()
    log("Zalogowano do slskd API.")

    hits, misses = [], []

    def worker(idx_track):
        i, t = idx_track
        kind, data = process_track(token, i, total, t, args.dry_run)
        with RESULTS_LOCK:
            (hits if kind == "hit" else misses).append(data)
        time.sleep(BETWEEN_TRACKS_SLEEP_S)

    indexed = list(enumerate(tracks, 1))
    if args.workers <= 1:
        for it in indexed:
            worker(it)
    else:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(worker, it) for it in indexed]
            for f in as_completed(futures):
                f.result()  # re-podnosi ewentualny nieoczekiwany wyjatek watku

    stamp = time.strftime("%Y%m%d-%H%M%S")
    (REPORT_DIR / f"hits-{stamp}.json").write_text(json.dumps(hits, ensure_ascii=False, indent=2), encoding="utf-8")
    (REPORT_DIR / f"misses-{stamp}.json").write_text(json.dumps(misses, ensure_ascii=False, indent=2), encoding="utf-8")

    log(f"Gotowe. Trafienia: {len(hits)}, braki: {len(misses)}. Raporty w {REPORT_DIR}/")


if __name__ == "__main__":
    main()
