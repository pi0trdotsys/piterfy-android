"""Soundtracki z najpopularniejszych gier jako playlisty: „Soundtrack: Minecraft”, „Soundtrack: The Witcher 3”…

Moduł jest czysty (bez sieci i bez Navidrome), żeby dało się go testować. Dostaje wyniki wyszukiwania albumów w Deezerze
i utwory z biblioteki, a zwraca wybrany album soundtracku oraz utwory z biblioteki, które do niego należą.

Utwór z biblioteki należy do gry na dwa sposoby: album ma w tytule nazwę gry i słowo typu „soundtrack”/„score” (masz już
płytę), albo tytuł i wykonawca zgadzają się z utworem z albumu z Deezera (`charts.match`).
"""

import re
from dataclasses import dataclass

import charts
import mixes

COMMENT_PREFIX = "Piterfy Game"  # po tym początku komentarza aplikacja rozpoznaje playlisty-soundtracki
PLAYLIST_PREFIX = "Soundtrack: "
MIN_ALBUM_TRACKS = 8
_OST_WORDS = ("soundtrack", "sound track", "ost", "score", "original game", "game music", "music from")
_REJECT = ("lofi", "lo fi", "piano", "remix", "8 bit", "8bit", "cover", "tribute", "karaoke", "live", "orchestral version",
           "lullaby", "for relaxing", "meditation", "instrumental version", "arrangement", "rework", "sped up", "slowed")


@dataclass(frozen=True)
class Game:
    title: str      # nazwa w nazwie playlisty
    short: str      # krótka nazwa do wyszukiwania plików w Soulseek (ma być w ścieżce pliku)
    query: str      # zapytanie o album w Deezerze
    words: tuple    # słowa kluczowe: co najmniej jedno musi być w tytule albumu (z Deezera i z biblioteki)
    reject: tuple = ()  # wyrażenia, które wykluczają album z Deezera (np. sequel: „silksong”, „ii”, „radio”)

    @property
    def playlist(self):
        return PLAYLIST_PREFIX + self.title


# kolejność = popularność (od najpopularniejszej); dla każdej gry dociągamy po kolei
GAMES = (
    Game("Minecraft", "Minecraft", "Minecraft Volume Alpha", ("minecraft",), reject=("beta", "legends", "dungeons", "story mode")),
    Game("The Witcher 3", "Witcher 3", "The Witcher 3 Wild Hunt Original Game Soundtrack", ("witcher", "wiedzmin"),
         reject=("songs of the past", "gwent")),
    Game("Skyrim", "Skyrim", "The Elder Scrolls V Skyrim Original Game Soundtrack", ("skyrim", "elder scrolls"),
         reject=("beyond skyrim", "bruma", "special edition remix")),
    Game("Elden Ring", "Elden Ring", "ELDEN RING ORIGINAL SOUND TRACK", ("elden ring",), reject=("nightreign",)),
    Game("Cyberpunk 2077", "Cyberpunk 2077", "Cyberpunk 2077 Original Score", ("cyberpunk",),
         reject=("radio", "more music", "phantom liberty", "edgerunners")),
    Game("Red Dead Redemption 2", "Red Dead Redemption", "Red Dead Redemption 2 Original Score", ("red dead",)),
    Game("The Legend of Zelda: Breath of the Wild", "Zelda Breath of the Wild", "The Legend of Zelda Breath of the Wild Original Soundtrack",
         ("zelda",), reject=("tears of the kingdom",)),
    Game("Super Mario Odyssey", "Super Mario Odyssey", "Super Mario Odyssey Original Soundtrack", ("mario",)),
    Game("DOOM", "DOOM", "DOOM Original Game Soundtrack Mick Gordon", ("doom",), reject=("eternal", "dark ages")),
    Game("Hollow Knight", "Hollow Knight", "Hollow Knight Original Soundtrack", ("hollow knight",), reject=("silksong",)),
    Game("Undertale", "Undertale", "UNDERTALE Soundtrack", ("undertale",)),
    Game("Hades", "Hades", "Hades Original Soundtrack Darren Korb", ("hades",), reject=("hades ii", "2", "ii")),
    Game("Dark Souls", "Dark Souls", "Dark Souls Original Soundtrack", ("dark souls",),
         reject=("2", "ii", "3", "iii", "remastered", "scholar")),
    Game("God of War", "God of War", "God of War Original Soundtrack Bear McCreary", ("god of war",), reject=("ragnarok", "ragnarök")),
    Game("Final Fantasy VII", "Final Fantasy VII", "Final Fantasy VII Original Soundtrack", ("final fantasy",),
         reject=("remake", "rebirth", "crisis core")),
    Game("Halo", "Halo", "Halo Original Soundtrack Marty O'Donnell", ("halo",), reject=("infinite", "wars", "reach")),
    Game("Persona 5", "Persona 5", "Persona 5 Original Soundtrack", ("persona",), reject=("royal", "dancing", "strikers", "tactica")),
    Game("Stardew Valley", "Stardew Valley", "Stardew Valley Original Soundtrack", ("stardew",)),
)


def by_names(names):
    """Gry o podanych nazwach (bez względu na wielkość liter); `names` puste = wszystkie."""
    wanted = {mixes.normalize(n) for n in names if n and n.strip()}
    return [g for g in GAMES if not wanted or mixes.normalize(g.title) in wanted]


def _has_word(text, words):
    """Czy któreś ze słów kluczowych występuje w tekście jako osobne słowo (po znormalizowaniu)."""
    padded = " " + charts.fold(text) + " "
    return any(f" {charts.fold(w)} " in padded for w in words)


def pick_album(game, results):
    """Z wyników wyszukiwania albumów w Deezerze (w kolejności trafności): pierwszy, który jest soundtrackiem tej gry —
    słowo kluczowe w tytule, bez wersji „lofi”, „piano”, „remix”, bez sequeli z `game.reject` i z co najmniej
    MIN_ALBUM_TRACKS utworami. Zwraca słownik albumu albo None."""
    for album in results or []:
        title = charts.fold(album.get("title"))
        padded = f" {title} "
        if not _has_word(title, game.words) or any(f" {charts.fold(bad)} " in padded for bad in _REJECT + game.reject):
            continue
        if int(album.get("nb_tracks") or 0) < MIN_ALBUM_TRACKS:
            continue
        return album
    return None


def album_entries(tracks):
    """Wpisy do `charts.match` z utworów albumu z Deezera: [{"rank" (numer na płycie), "id", "title", "artist", ...}]."""
    entries = []
    for i, t in enumerate(tracks or [], 1):
        entries.append({"rank": int(t.get("track_position") or i), "id": f"dz:{t.get('id')}", "title": t.get("title", ""),
                        "artist": (t.get("artist") or {}).get("name", ""), "isrc": t.get("isrc") or "",
                        "duration": t.get("duration")})
    return entries


def album_songs(game, songs):
    """Utwory z biblioteki z albumu, którego tytuł zawiera słowo kluczowe gry i oznaczenie soundtracku."""
    result = []
    for song in songs:
        album = charts.fold(song.get("album"))
        if _has_word(album, game.words) and any(f" {w} " in f" {album} " for w in _OST_WORDS):
            result.append(song)
    return result


def collect(game, entries, songs):
    """Utwory z biblioteki należące do gry (z płyty w bibliotece albo dopasowane do albumu z Deezera), w kolejności płyty:
    zwraca (lista utworów, lista trafień `charts.match` z albumu)."""
    hits = charts.match(entries, songs)
    found = {s["id"]: s for s in album_songs(game, songs)}
    for _, song, _ in hits:
        found.setdefault(song["id"], song)
    ordered = sorted(found.values(), key=lambda s: (charts.fold(s.get("album")), int(s.get("discNumber") or 1),
                                                    int(s.get("trackNumber") or 0), charts.fold(s.get("title"))))
    return ordered, hits


def comment(game, matched, total, now):
    return (f"{COMMENT_PREFIX}: {game.title} · w bibliotece {matched}/{total} · "
            f"aktualizacja {now:%Y-%m-%d}. Odświeżana automatycznie.")
