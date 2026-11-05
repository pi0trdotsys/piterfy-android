"""Porządkuje tagi w istniejącej bibliotece, nie przenosząc plików:

1. uzupełnia brakujące tagi (wykonawca, tytuł, album, rok, numer) z nazwy pliku i folderu,
   np. „[2020] Europa/03 - Taco Hemingway - Europa.mp3”;
2. skleja albumy rozbite na kawałki — ten sam album i wykonawca, ale różna data wydania,
   identyfikator MusicBrainz albo pisownia (np. „Mlody Matczak” i „Młody Matczak”).

Domyślnie tylko pokazuje, co by zmienił. Z --apply zapisuje tagi, a oryginały zmienianych plików
najpierw kopiuje do /config/backup. Pliki zostają na miejscu, więc Navidrome rozpoznaje je
po ścieżce — liczniki odsłuchów, oceny i playlisty zostają.

  python /config/fix_library.py            podgląd
  python /config/fix_library.py --apply    zapis
"""

import argparse
import re
import shutil
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from mediafile import MediaFile, UnreadableFileError

MUSIC = Path("/music")
BACKUP = Path("/config/backup")
AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".aiff"}
TRACK_PREFIX = re.compile(r"^\s*(\d{1,3})\s*(?:[-._)]\s*|\s+)")
YEAR = re.compile(r"[\[(]\s*((?:19|20)\d{2})\s*[\])]")
# Foldery składanek, list przebojów i setów DJ-skich — ich nazwa to nie album utworu, a numer to pozycja na liście
COMPILATION = re.compile(r"\b(?:top\s*\d+|charts?|hits|collection|compilation|playlist|various|va|mash\s?up|"
                         r"temazos|mixtape|djs?|mp3|320\s?kbps|pmedia)\b", re.I)
# „Tytuł - Edit”: druga część to wersja utworu, nie tytuł
VERSION_ONLY = re.compile(r"^(?:radio |extended |original |club |album |single |clean |dirty )?"
                          r"(?:edit|mix|remix|version|remaster(?:ed)?|instrumental|acoustic|live|clean|explicit)$", re.I)
EXTENSION_LEFTOVER = re.compile(r"\.(?:ogg|mp3|m4a|flac|opus|webm|wav)$", re.I)
YOUTUBE_ID = re.compile(r"-([A-Za-z0-9_-]{11})$")
VIDEO_JUNK = re.compile(r"\s*[\[(][^\])]*\b(?:official|video|teledysk|lyrics?|audio|hd|hq|clip|visuali[sz]er)\b[^\])]*[\])]",
                        re.I)
DJ_KEY_BPM = re.compile(r"\s+\d{1,2}[AB]\s+\d{2,3}$")  # „… (Instrumental) 7A 135” — tonacja i tempo od DJ-a
UNDERSCORED = re.compile(r"^(?P<artist>[^_]+)_(?P<album>[^_]+)_(?P<track>\d{1,3})_(?P<title>[^_].*)$")
ARTIST_SPLIT = re.compile(r"\s+(?:feat\.?|ft\.?|featuring|vs\.?|x|&)\s+|\s*[,;]\s*", re.I)
SPECIAL_LETTERS = str.maketrans({"ł": "l", "ø": "o", "đ": "d", "æ": "ae", "œ": "oe", "þ": "th"})


def norm(text):
    text = unicodedata.normalize("NFKD", str(text or "").casefold().translate(SPECIAL_LETTERS))
    text = "".join(c for c in text if not unicodedata.combining(c)).replace("&", " and ")
    return " ".join(re.sub(r"[\W_]+", " ", text).split())


def primary(artist):
    return ARTIST_SPLIT.split(artist or "", maxsplit=1)[0].strip()


def related(a, b):
    a, b = norm(a), norm(b)
    return bool(a and b) and (a in b or b in a)


def clean_stem(stem):
    """Nazwa pliku bez śmieci: identyfikatora YouTube, „(official video)”, tonacji i tempa od DJ-a."""
    stem, junk = EXTENSION_LEFTOVER.sub("", stem), False
    youtube = YOUTUBE_ID.search(stem)
    if youtube and re.search(r"\d", youtube.group(1)) and re.search(r"[A-Z]", youtube.group(1)):
        stem, junk = stem[:youtube.start()], True
    cleaned = VIDEO_JUNK.sub("", stem)
    if cleaned != stem:
        stem, junk = cleaned, True
    if junk:  # zgrania z YouTube: „To co nam było (official video) 2013 NOWOŚĆ” — od roku dalej to opis filmu
        stem = re.sub(r"\s+(?:19|20)\d{2}\b.*$", "", stem)
    return " ".join(DJ_KEY_BPM.sub("", stem).split()).strip(" -_")


def plausible_artist(name):
    return len(name) <= 60 and "(feat" not in name.lower() and not re.search(r"\d{5,}|_", name)


def guess_from_path(path, known_artist=""):
    """Tagi, które da się pewnie odczytać z nazwy pliku i folderu (wyłącznie do uzupełnienia braków)."""
    guess, stem, track = {}, clean_stem(path.stem), None
    match = TRACK_PREFIX.match(stem)
    if match:
        rest = stem[match.end():]
        explicit = not match.group(0).strip().isdigit()  # „03 - ”, „03. ” — a nie „21 ” z „21 Savage - a lot”
        if explicit or " - " not in rest:
            track, stem = int(match.group(1)), rest

    underscored = None if " - " in stem else UNDERSCORED.match(stem)  # „Wykonawca_Album_01_Tytuł”
    if underscored:
        guess.update(artist=underscored["artist"], album=underscored["album"], title=underscored["title"])
        guess["track"] = int(underscored["track"])
    else:
        parts = [p.strip() for p in stem.split(" - ") if p.strip()]
        if len(parts) >= 2 and VERSION_ONLY.match(parts[-1]):  # wersja dokleja się do tytułu
            parts = parts[:-2] + [" - ".join(parts[-2:])]
        if len(parts) >= 2 and plausible_artist(parts[0]):
            guess["artist"], guess["title"] = parts[0], " - ".join(parts[1:])
        elif len(parts) == 1:
            guess["title"] = parts[0]

    # Folder bierzemy za album tylko, gdy wygląda na album: „[2020] Europa” albo „Wykonawca - Album (rok)”
    folder = path.parent.name if path.parent != MUSIC else ""
    year = YEAR.search(folder)
    name = " ".join(YEAR.sub(" ", folder).split()).strip(" -_")
    fparts = [p.strip() for p in name.split(" - ") if p.strip()]
    artist = guess.get("artist") or known_artist
    if not folder or COMPILATION.search(folder):
        pass
    elif len(fparts) >= 2 and artist and related(fparts[0], artist):
        guess.setdefault("artist", fparts[0])
        guess.update(albumartist=fparts[0], album=" - ".join(fparts[1:]))
    elif len(fparts) == 1 and year and folder.lstrip().startswith(("[", "(")):
        guess["album"] = name
    else:
        return guess
    if "album" in guess:
        if year:
            guess["year"] = int(year.group(1))
        if track and track < 100:
            guess["track"] = track
    return guess


def read_tags(path):
    mf = MediaFile(path)
    return mf, {"artist": mf.artist or "", "albumartist": mf.albumartist or "", "album": mf.album or "",
                "title": mf.title or "", "year": mf.year or 0, "month": mf.month or 0, "day": mf.day or 0,
                "track": mf.track or 0, "mb_albumid": mf.mb_albumid or ""}


def best(values):
    """Najczęstsza wersja; przy remisie ta z polskimi znakami i dłuższa (np. „Młody” zamiast „Mlody”)."""
    counts = Counter(values)
    return max(counts, key=lambda v: (counts[v], sum(ord(c) > 127 for c in v), len(v)))


def main():
    parser = argparse.ArgumentParser(description="Porządkuje tagi w bibliotece bez przenoszenia plików.")
    parser.add_argument("--apply", action="store_true", help="zapisz zmiany (bez tego tylko podgląd)")
    args = parser.parse_args()

    files = {}
    for path in sorted(p for p in MUSIC.rglob("*") if p.suffix.lower() in AUDIO and p.is_file()):
        try:
            files[path] = read_tags(path)
        except (UnreadableFileError, OSError) as e:
            print(f"! nie da się odczytać {path.relative_to(MUSIC)}: {e}")
    changes = defaultdict(dict)

    # 1. brakujące tagi z nazwy pliku i folderu
    for path, (_, tags) in files.items():
        guess = guess_from_path(path, tags["artist"])
        if tags["title"] and guess.get("title") and norm(guess.get("artist")) == norm(tags["title"]):
            guess["artist"], guess["title"] = guess["title"], tags["title"]  # plik „Tytuł - Wykonawca”
        for field in ("artist", "title", "album", "albumartist", "year", "track"):
            if guess.get(field) and not tags[field]:
                if field == "albumartist" and tags["artist"] and not related(tags["artist"], guess[field]):
                    continue  # folder należy do kogoś innego niż wykonawca z tagów
                changes[path][field] = tags[field] = guess[field]
    filled = len(changes)

    # 2. albumy rozbite na kawałki
    groups = defaultdict(list)
    for path, (_, tags) in files.items():
        artist = tags["albumartist"] or primary(tags["artist"])
        if norm(tags["album"]) and norm(artist):
            groups[(norm(tags["album"]), norm(artist))].append(path)
    merged = []
    for paths in groups.values():
        tags = [files[p][1] for p in paths]
        effective_artist = [t["albumartist"] or primary(t["artist"]) for t in tags]
        dates = [(t["year"], t["month"], t["day"]) for t in tags]
        mbids = [t["mb_albumid"] for t in tags]
        if len(paths) < 2 or (len(set(t["album"] for t in tags)) == 1 and len(set(effective_artist)) == 1
                              and len(set(dates)) == 1 and len(set(mbids)) == 1):
            continue
        album, albumartist = best([t["album"] for t in tags]), best(effective_artist)
        # najczęstszy rok, a w nim najdokładniejsza data — „2020” i „2020-05-28” to ta sama płyta
        known_dates, date = [x for x in dates if x[0]], None
        if known_dates:
            year = Counter(x[0] for x in known_dates).most_common(1)[0][0]
            same_year = [x for x in known_dates if x[0] == year]
            date = Counter([x for x in same_year if x[1]] or same_year).most_common(1)[0][0]
        known_mbids = [m for m in mbids if m]
        mbid = Counter(known_mbids).most_common(1)[0][0] if known_mbids else ""
        for path, t in zip(paths, tags):
            wanted = {"album": album, "albumartist": albumartist, "mb_albumid": mbid}
            if date:
                wanted.update(year=date[0], month=date[1], day=date[2])
            for field, value in wanted.items():
                if t[field] != value:
                    changes[path][field] = value
        merged.append((albumartist, album, len(paths)))

    print(f"Plików w bibliotece: {len(files)}")
    print(f"\n1. Uzupełnione brakujące tagi: {filled} plików")
    shown = 0
    for path, change in changes.items():
        if shown < 25 and set(change) & {"artist", "title", "track"}:
            print(f"   {path.relative_to(MUSIC)}\n      → " + ", ".join(f"{k}: {v}" for k, v in change.items()))
            shown += 1
    print(f"\n2. Sklejone albumy: {len(merged)}")
    for albumartist, album, count in sorted(merged)[:40]:
        print(f"   {albumartist} — {album} ({count} utw.)")
    print(f"\nRazem do zmiany: {len(changes)} plików.")

    if not args.apply:
        print("\nTo był podgląd. Żeby zapisać: python /config/fix_library.py --apply")
        return
    for path, change in changes.items():
        backup = BACKUP / path.relative_to(MUSIC)
        if not backup.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, backup)
        mf = files[path][0]
        for field, value in change.items():
            setattr(mf, field, value if value else None)
        mf.save()
    print(f"\nZapisano {len(changes)} plików. Oryginały: /config/backup (na serwerze: discover/beets/backup).")


if __name__ == "__main__":
    main()
