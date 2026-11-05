# piterfy-discover

Codziennie sam ściąga przez slskd nowe utwory podobne do tego, czego słuchasz, i uczy się z Twoich reakcji.

## Jak to działa

```
Navidrome + Last.fm ──► discover ──► slskd ──► incoming/ ──► beets ──► library/ ──► Navidrome
   (co słuchasz)      (co pobrać)   (Soulseek)            (tagi, ReplayGain)       (playlisty)
```

1. **Profil słuchania.** Odsłuchy z Navidrome z ostatnich 30 dni (świeższe ważą więcej), do tego scrobble z Last.fm
   ze wszystkich urządzeń, polubione utwory i odkrycia, które Ci się spodobały.
2. **Rekomendacje.** Z Last.fm (podobne utwory i wykonawcy), a bez klucza Last.fm z Deezera (podobni wykonawcy).
   Około 70% utworów pochodzi od nowych wykonawców, najwyżej 2 od jednego wykonawcy. Pomija to, co już masz,
   i wszystko, co kiedyś pobrał.
3. **Pobieranie.** Przez slskd wybiera najlepszy plik (FLAC albo MP3 od 220 kbps, bez wersji live i remiksów,
   z pasującą długością). Jeśli źródło zawiedzie, próbuje u innego użytkownika. Między wyszukiwaniami robi
   przerwy, bo za szybkie wyszukiwanie kończy się czasową blokadą na serwerze Soulseek.
4. **beets.** Przenosi pobrane pliki z `incoming/` do `library/`: poprawia tagi według MusicBrainz (także kilku
   wykonawców jako osobne wpisy), wyrównuje głośność (ReplayGain) i układa pliki w foldery `Wykonawca/Album/Tytuł`.
   Istniejącej biblioteki nie rusza.
5. **Playlisty.** Każdy dzień ma własną playlistę, np. „Odkrywaj 12.10” (znika po 7 dniach), a zbiorcza „Odkrywaj”
   trzyma ostatnie 60 utworów.
6. **Twoje reakcje.** Odkrycie z gwiazdką, oceną 4–5★ albo co najmniej 3 odsłuchami staje się punktem wyjścia
   do kolejnych rekomendacji, a jego wykonawca pojawia się częściej. Odkrycie z oceną 1★ od razu, a nieodsłuchane
   po 30 dniach trafia do `discover-trash/`; jego wykonawca pojawia się rzadziej. Z kosza pliki znikają po kolejnych 30 dniach.

   **Fałszywa jakość.** Beets analizuje widmo każdego pliku (`beets/spectrum.py`). Jeśli odkrycie okaże się
   FLAC-iem zrobionym z MP3 albo MP3 podbitym z niższego bitrate, a nie dałeś mu ★ ani oceny, to przy
   następnym przebiegu idzie do kosza. Discover od razu pobiera je ponownie od innej osoby z Soulseek,
   tym razem najchętniej jako MP3. Po 3 fałszywych kopiach jednego utworu przestaje go szukać.
7. **Maile** (przez to samo konto co `msmtp` na serwerze):
   - podsumowanie każdego przebiegu: co pobrał i dlaczego, co polubiłeś, co poszło do kosza, ile jest miejsca;
   - alarm, gdy slskd od 30 minut nie jest połączony z Soulseekiem (i drugi mail, gdy połączenie wróci);
   - alarm, gdy przebieg się nie udał;
   - ostrzeżenie, gdy na dysku zostaje mniej niż 20 GB (wtedy nic nie pobiera).
8. **Panel gustu** (`/gust/`, przycisk „Mój gust” w Navidrome) odświeża się co noc o północy (`taste.py`).
   Discover zapisuje liczniki odsłuchów z Navidrome; z różnic między kolejnymi nocami powstaje historia słuchania.
   Panel pokazuje:
   - rytm dni, wykonawców ze zdjęciami, utwory „na zapętleniu”;
   - mapę nastroju (cechy audio z eksportu Spotify), gatunki i dekady;
   - zegar słuchania (scrobble Last.fm);
   - skuteczność odkryć;
   - porównanie z polubionymi ze Spotify.

   Przez pierwszy tydzień okno 30 dni jest szacowane z daty ostatniego odsłuchu.
9. **Wygląd Piterfy (Navidrome).** Caddy dopisuje do strony Navidrome krótki skrypt, który ładuje
   `navidrome-ui/piterfy.js` (a ten `piterfy.css`) z wersją zmieniającą się co 10 minut. Cloudflare każe
   przeglądarce trzymać pliki .js i .css przez 4 godziny, więc bez tego poprawki docierałyby z opóźnieniem.
   Co dodaje:
   - wygląd Noir w stylu Spotify, nazwę i logo Piterfy zamiast Navidrome;
   - stronę startową `#/start`, od której aplikacja się otwiera:
     - powitanie i kafelki szybkiego dostępu;
     - „Odkryte dla Ciebie” (playlisty Odkrywaj) i „Świeże odkrycia”;
     - ostatnio słuchane, ulubieni wykonawcy z panelu gustu i baner „Mój gust”;
     - Twoje playlisty, najczęściej słuchane i nowe w bibliotece;
     - zielony przycisk na karcie otwiera album lub playlistę i od razu ją odtwarza;
   - logo i pozycję „Start” w panelu bocznym;
   - nagłówki albumów z rozmytą okładką w tle;
   - przycisk „Mój gust” (otwiera się w nowej karcie, muzyka gra dalej);
   - plakietkę rzeczywistej jakości na pasku odtwarzania, np. `FLAC 16/44,1`, `FLAC 24/96`, `MP3 320`:
     - zielona — prawdziwy plik bezstratny; szara — stratny;
     - pomarańczowa `≈ MP3 128` — fałszywa kopia (FLAC zrobiony z MP3 albo MP3 podbite z niższego bitrate);
     - żółta `≈ MP3 320?` / `≈ CD` — niepewne (może MP3 320, może mastering) albo „hi-res” zrobione z CD;
     - po najechaniu: poziom jak w Spotify (Bezstratna, Bardzo wysoka…), szczegóły pliku, wynik analizy
       widma i to, czy serwer transkoduje (wtedy plakietka pokazuje to, co faktycznie słyszysz);
   - zdjęcie profilowe: menu użytkownika (prawy górny róg) → „Dodaj/Zmień zdjęcie profilowe”. Przeglądarka
     przycina je do kwadratu 512 × 512, a zapisuje `profile_api.py` (mały serwer w kontenerze discover,
     Caddy kieruje do niego `/piterfy-api/`) w `discover/state/avatars/`.

   Działa na ciemnych motywach. Przy jasnym motywie wygląd sam się wyłącza, a zostają tylko przycisk i strona startowa.
   Aplikacje mobilne (API Subsonic) tego nie widzą.

10. **Podcasty.** Navidrome ich nie obsługuje, więc `podcasts.py` ściąga je jako zwykłe pliki audio:
    - Audycje wpisujesz w `discover/podcasts.txt` (`Nazwa | adres RSS`); zmiana działa po restarcie discover
      albo przy następnym sprawdzeniu (co 3 godziny).
    - Z każdej audycji trzyma 20 najnowszych odcinków i dociąga nowe. Odcinki lądują w `library/Podcasty/<audycja>/`,
      z tagami (audycja = album, gatunek „Podcast”, numer, data, opis) i okładką audycji (`cover.jpg`). W Piterfy
      i w aplikacji na Androida wyglądają jak album, a pobieranie offline działa tak jak dla muzyki.
    - Odsłuchany odcinek znika po 30 dniach od ostatniego odsłuchania (chyba że dasz mu gwiazdkę).
      Odcinka, którego nie odsłuchałeś, discover nie rusza. Usunięty odcinek nie wraca.
    - Podcasty nie liczą się do „Mojego gustu”, rekomendacji discover, analizy jakości ani inteligentnego odtwarzania.
    - Starsze odcinki na życzenie (najpierw podgląd, potem `--yes`):
      ```bash
      docker compose run --rm --no-deps piterfy-discover --podcast-fetch "Easy German" --year 2024
      docker compose run --rm --no-deps piterfy-discover --podcast-fetch "Raport" --match "Raport o książkach" --yes
      ```
      `--year RRRR` bierze odcinki z danego roku, `--match REGEX` pasujące tytuły (np. serię). Bez `--year` i `--match`
      weźmie całą audycję — podgląd pokaże, ile to odcinków i ile GB.
    - Sprawdzenie od ręki: `docker compose run --rm --no-deps piterfy-discover --podcasts`.

## Pliki

| Na serwerze | Co to jest |
|---|---|
| `discover/discover.py` | skrypt; kontener czyta go wprost z folderu, więc po podmianie pliku wystarczy restart |
| `discover/state/state.json` | historia pobranych utworów, Twoje reakcje, playlisty dnia, stan alarmów |
| `discover/beets/` | obraz beets (`Dockerfile`), `config.yaml`, pętla importu, baza beets, `import.log`, `failed/` |
| `incoming/` | tu slskd zapisuje pobrania; beets co minutę przenosi je do `library/` |
| `discover-trash/` | kosz: odrzucone odkrycia; po `DISCOVER_TRASH_DAYS` dniach są kasowane |
| `reports/discover-*.md` | raport z każdego przebiegu (ta sama treść co w mailu) |
| `discover/taste.py` | generator panelu gustu (uruchamia go discover co noc) |
| `discover/podcasts.py`, `discover/podcasts.txt` | podcasty: moduł (RSS → pliki audio z tagami) i lista audycji |
| `library/Podcasty/` | odcinki podcastów, po jednym folderze na audycję |
| `discover/profile_api.py` | serwer zdjęć profilowych i jakości utworów (port 8090 w sieci Dockera; uruchamia go discover) |
| `discover/beets/spectrum.py` | analiza widma: wykrywa FLAC-i zrobione z MP3 i podbite MP3; liczy ją pętla importu beets |
| `discover/beets/spectrum.json` | wyniki analizy widma dla każdego pliku biblioteki |
| `discover/state/avatars/` | zapisane zdjęcia profilowe |
| `gust/index.html`, `gust/data/taste.json` | panel gustu; `data/spotify-baseline.json` to dawny panel ze Spotify, do porównań |
| `discover/caddy/` | obraz Caddy z dodatkiem `replace-response`; `Caddyfile` jest w `~/piterfy-stack/Caddyfile` |
| `discover/navidrome-ui/` | wygląd Navidrome i przycisk „Mój gust”; zmiany widać od razu po odświeżeniu strony |

## Ustawienia (`.env`)

Po zmianie uruchom `docker compose up -d piterfy-discover`.

| Zmienna | Domyślnie | Co robi |
|---|---|---|
| `DISCOVER_EVERY_DAYS` | `3` | co ile dni (`1` = codziennie) |
| `DISCOVER_HOUR` | `4` | od której godziny może ruszyć |
| `DISCOVER_TRACKS` | `20` | ile utworów na przebieg |
| `DISCOVER_FAMILIAR` | `0.3` | jaka część od wykonawców, których już masz (0 = same nowe) |
| `DISCOVER_MAX_PER_ARTIST` | `2` | maks. utworów od jednego wykonawcy na przebieg |
| `DISCOVER_LOOKBACK_DAYS` | `30` | z ilu dni brać historię słuchania |
| `DISCOVER_FORMATS` | `flac,mp3` | dozwolone formaty w kolejności preferencji (`mp3,flac` zajmuje 3–4× mniej miejsca) |
| `DISCOVER_MIN_BITRATE` | `220` | minimalny bitrate dla mp3 itp. |
| `DISCOVER_CLEANUP_DAYS` | `30` | po ilu dniach nieodsłuchane odkrycie idzie do kosza (`0` = nigdy) |
| `DISCOVER_TRASH_DAYS` | `30` | po ilu dniach kosz kasuje pliki (`0` = nigdy) |
| `DISCOVER_MIN_FREE_GB` | `20` | poniżej tylu GB wolnego miejsca nic nie pobiera |
| `DISCOVER_MAIL_TO` | | adres na podsumowania i alarmy (pusty = bez maili) |
| `DISCOVER_PLAYLIST` | `Odkrywaj` | nazwa zbiorczej playlisty (pusta = bez niej) |
| `DISCOVER_PLAYLIST_SIZE` | `60` | ile ostatnich odkryć trzymać w zbiorczej playliście |
| `DISCOVER_DAILY_PLAYLIST` | `Odkrywaj` | początek nazwy playlisty dnia, dalej jest data (pusta = bez playlist dnia) |
| `DISCOVER_DAILY_KEEP_DAYS` | `7` | po ilu dniach playlista dnia znika (`0` = nigdy) |
| `DISCOVER_PODCAST_EVERY_HOURS` | `3` | co ile godzin sprawdza kanały podcastów |
| `DISCOVER_PODCAST_LATEST` | `20` | ile najnowszych odcinków audycji trzyma |
| `DISCOVER_PODCAST_KEEP_DAYS` | `30` | po ilu dniach od odsłuchania odcinek znika |
| `DISCOVER_BLOCK` | | wykonawcy do pomijania, rozdzieleni `;` |
| `DISCOVER_DOWNLOAD_TIMEOUT_MIN` | `90` | ile minut czekać na pobrania (reszta dociągnie się później) |
| `LASTFM_API_KEY` / `LASTFM_SECRET` | | klucz API Last.fm: rekomendacje w discover i scrobblowanie w Navidrome |
| `LASTFM_USER` | | Twój login Last.fm; jego scrobble wchodzą do historii słuchania |

Jak oceniać w Navidrome:
- ★ (polub) albo ocena 4–5 oznacza „więcej takich”;
- ocena 1 oznacza „wyrzuć i rzadziej tego wykonawcę”;
- ocena 2 oznacza „rzadziej tego wykonawcę”, ale plik zostaje.

## Przydatne komendy

```bash
docker compose run --rm --no-deps piterfy-discover --dry-run        # co by pobrał, bez pobierania
docker compose run --rm piterfy-discover --search "Artysta - Tytuł"  # jedno próbne wyszukiwanie w Soulseek
docker compose run --rm --no-deps piterfy-discover --test-mail      # próbny mail
docker compose run --rm --no-deps piterfy-discover --taste          # odśwież panel gustu od razu
docker compose run --rm piterfy-discover --now                      # przebieg poza harmonogramem
docker logs -f piterfy-discover                                      # co robi discover
docker logs -f piterfy-beets                                         # co importuje beets
ls reports/ && cat reports/discover-*.md | tail -40                  # ostatnie raporty
```

Jeśli usuniesz `discover/state/state.json`, skrypt zapomni historię i przy najbliższym sprawdzeniu ruszy od nowa.
