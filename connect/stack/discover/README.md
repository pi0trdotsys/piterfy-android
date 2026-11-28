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
    - Domyślna lista zawiera audycje o Ameryce (NPR, The Daily, Americast, American History Tellers, Throughline,
      „Ameryka dla Zaawansowanych”, AmerykaPL i inne). `last=N` po adresie RSS ogranicza liczbę trzymanych odcinków;
      dzienne serwisy mają ich tylko kilka.
    - **Nauka gustu i nowe podcasty na próbę.** `podcast_taste.py` ocenia każdy kanał z tego, co robisz z odcinkami: odsłuch,
      gwiazdka i ocena 4–5 liczą się na plus, ocena 1★ na mocny minus, a odcinek nietknięty od dwóch tygodni na lekki minus
      (świeższe zdarzenia ważą więcej, półokres 30 dni; ocena od trzech rozstrzygniętych odcinków). Z kanałów, które lubisz,
      powstaje profil: języki, kategorie Apple i słowa z tytułów. Raz w tygodniu discover szuka w publicznym katalogu iTunes
      (tylko ogólne hasła, kraj i język, nic o Tobie), sprawdza RSS kandydatów (świeży odcinek ≤ 90 dni, ≥ 10 odcinków, język
      z Twojego gustu, bez „explicit”, jeśli takich nie słuchasz) i dopisuje najlepsze, po jednym z kategorii, **na próbę**
      (domyślnie 2 na przebieg, najwyżej 8 naraz) do `podcasts-auto.json`; `podcasts.txt` zostaje Twój i nigdy nie jest zmieniany.
      Kanał na próbie trzyma 3 najnowsze odcinki. Po 21 dniach: jeśli odsłuchałeś choć jeden, zostaje na stałe (8 odcinków);
      jeśli nie, znika razem z nieodsłuchanymi odcinkami i trafia do `podcasts-block.json`, więc nie wróci. Kanał, który
      mocno Ci nie podszedł (np. dwie oceny 1★), znika od razu. O nowym, zatrzymanym i usuniętym kanale dostajesz mail i wiadomość
      na Telegramie (`DISCOVER_PODCAST_DISCOVER_NOTIFY=0` wyłącza).
      ```bash
      docker compose run --rm --no-deps piterfy-discover --podcast-discover --dry-run   # co by wybrał i dlaczego
      docker compose run --rm --no-deps piterfy-discover --podcast-discover             # dobierz od razu
      docker compose run --rm --no-deps piterfy-discover --podcast-keep "Nazwa"          # zostaw kanał na stałe
      docker compose run --rm --no-deps piterfy-discover --podcast-block "Nazwa"         # usuń i nie wracaj
      ```
11. **Tematyczne miksy.** `mixes.py` układa z całej biblioteki playlisty „Happy Mix”, „Dark Mix”, „Electronic Mix”,
    „Bass Boost”, „Chill Mix”, „Workout Mix”, „Rock Mix” i „Hip-Hop Mix”:
    - Utwór wchodzi do miksu według gatunku (tagi w bibliotece, a gdy jest eksport Spotify, także jego gatunki) albo
      nastroju z eksportu (energia, walencja, tempo). Wykluczenia mają pierwszeństwo: w „Happy Mix” nie ma death metalu,
      a w „Dark Mix” wesołego popu. „Bass Boost” bierze też szybką elektronikę (tag BPM od 124).
    - Miks odświeża się co 24 godziny i od razu po pobraniu nowych utworów. Za każdym razem losuje od nowa z całej
      biblioteki, więc rośnie razem z nią (od 40 do 100 utworów, ok. piątej części puli). Ulubione (★, ocena 4–5,
      dużo odsłuchań) i świeże odkrycia wracają częściej, a utwory z poprzedniej wersji są karane, żeby playlista się obracała.
    - Najwyżej 3 utwory jednego wykonawcy. Pomija utwory ocenione na 1★, podcasty oraz krótsze niż minuta i dłuższe niż 12 minut.
    - Miks bez co najmniej 12 pasujących utworów nie powstaje. Playlista usunięta w Navidrome nie wraca, a cudza
      playlista o tej samej nazwie nie jest nadpisywana.
    - Aplikacja pokazuje miksy w półce „Twoje miksy” na ekranie głównym (rozpoznaje je po początku komentarza „Piterfy Mix”).
    - Odświeżenie od ręki: `docker compose run --rm --no-deps piterfy-discover --mixes`.
12. **Listy przebojów.** `charts.py` układa „Top 100 Polska” z playlisty Deezera „Top Poland” (100 utworów, odświeżana codziennie,
    publiczne API bez klucza):
    - Playlista Navidrome może zawierać tylko utwory z biblioteki, więc zostają te z listy, które masz, **w kolejności listy**
      (komentarz: „w bibliotece X/100”). Utwory, których nie masz, są pomijane — nic się nie pobiera.
    - Dopasowanie: najpierw po ISRC (Deezer podaje go dla każdego utworu; zapamiętujemy go w `state.json`), potem po tytule
      i wykonawcy (bez „feat.”, „radio edit”, „remastered”; „Slowed”, „Remix” i inne wersje nie podszywają się pod oryginał;
      długość może się różnić o 12 s). Jeden utwór trafia do listy raz.
    - **Cel: `DISCOVER_CHART_TARGET` (100) utworów z listy w bibliotece.** Gdy z pierwszej setki jest ich mniej, lista sięga głębiej:
      do top 200, 300, 500 i 1000 (dalsze miejsca z Last.fm `geo.getTopTracks` dla Polski — wymaga `LASTFM_API_KEY`), aż będzie cel;
      playlista nazywa się wtedy „Top 200 Polska” itd. (nazwa zmienia się sama). Z włączonym dociąganiem, dopóki w bibliotece
      jest mniej niż cel, serwer dociąga do `DISCOVER_CHART_FILL_CATCHUP` (20) utworów na odświeżenie z całej listy, nie tylko z czołówki.
    - Lista odświeża się co 12 godzin i zaraz po pobraniu nowych utworów. Poniżej 3 pasujących utworów playlista nie
      powstaje, usunięta ręcznie nie wraca, a cudza playlista o tej samej nazwie nie jest nadpisywana.
    - Aplikacja i strona pokazują listy w półce „Listy przebojów” (rozpoznają je po początku komentarza „Piterfy Chart”).
    - **Dociąganie brakujących** (domyślnie wyłączone: `DISCOVER_CHART_FILL=1`): z `DISCOVER_CHART_FILL_TOP` (30) pierwszych
      miejsc listy brakujące utwory są szukane w Soulseek tym samym mechanizmem co odkrycia (te same reguły formatu, bitrate,
      kontroli fałszywej jakości, wolnego miejsca i listy zablokowanych wykonawców), najwyżej `DISCOVER_CHART_FILL_PER_RUN` (5)
      na jedno odświeżenie i najwyżej 2 od jednego wykonawcy. Po pobraniu lista powstaje od nowa z nowymi utworami. Pobrane
      utwory nie trafiają do playlisty „Odkrywaj”; jeśli ich nie odsłuchasz, po `DISCOVER_CLEANUP_DAYS` dniach idą do kosza jak
      inne odkrycia (ocena 1★ wyrzuca od razu, ★ zostawia). Pobieranie komercyjnej muzyki z Soulseek bywa nielegalne zależnie
      od kraju — włączasz to świadomie.
    - Podgląd bez zmian: `docker compose run --rm --no-deps piterfy-discover --charts --dry-run` pokazuje, ile utworów jest
      w bibliotece i co by się dociągnęło.

13. **Miksy według pochodzenia i dekady.** Do miksów z gatunków (punkt 11) dochodzą „French Music”, „Latin Music”, „Polska Muzyka”,
    „Polski Rap”, „German Music”, „Asia Pop”, „Brazil Music”, „Phonk Mix” oraz „Lata 80.”, „Lata 90.” i „Lata 2000.”:
    - Gatunki w tagach niewiele mówią o kraju wykonawcy, więc `origins.py` liczy pochodzenie z trzech słabych sygnałów — tagów
      wykonawcy z Last.fm (`artist.getTopTags`: „french”, „polish”, „latin”…, pamiętane w `state.json`, po `DISCOVER_ARTIST_TAG_BATCH`
      (400) wykonawców na odświeżenie, odświeżane co `DISCOVER_ARTIST_TAG_DAYS` (120) dni), języka z tagów plików (MusicBrainz
      `language`) i kraju z kodu ISRC — a utwór wchodzi do miksu od dwóch punktów (sam ISRC nie wystarcza). „Polski Rap” wymaga
      dodatkowo gatunku rap/hip-hop. Miks bez 12 pasujących utworów nie powstaje, jak inne miksy.
    - Dekady biorą rok wydania, bez względu na gatunek. Miksy mają te same zasady odświeżania i okładki co pozostałe.
14. **Soundtracki z gier.** `games.py` układa playlisty „Soundtrack: Minecraft”, „Soundtrack: The Witcher 3”, „Soundtrack: Elden Ring”
    i inne (18 najpopularniejszych gier, lista w `games.py`):
    - Utwór należy do gry, gdy album w bibliotece ma w tytule nazwę gry i słowo typu „soundtrack”/„score”, albo gdy tytuł i
      wykonawca zgadzają się z albumem soundtracku z Deezera (znajdowanym po nazwie gry; sequele, wersje „lofi”, „piano”, „remix”
      są pomijane). Playlista powstaje od `DISCOVER_GAME_MIN` (5) utworów w bibliotece.
    - Z `DISCOVER_CHART_FILL=1` serwer dociąga brakujące utwory najpopularniejszej gry, której w bibliotece jest mniej niż
      `DISCOVER_GAME_TARGET` (15): najwyżej `DISCOVER_GAME_FILL_PER_RUN` (10) na odświeżenie (co `DISCOVER_GAME_EVERY_HOURS`, 6),
      po jednej grze naraz, w kolejności popularności. Pliki szukane są po nazwie gry w ścieżce (np. „Witcher 3”), te same reguły
      jakości, formatu i miejsca co przy odkryciach; nie trafiają do „Odkrywaj”, a nieodsłuchane znikają po `DISCOVER_CLEANUP_DAYS` dniach.
    - Aplikacja i strona pokazują je w półce „Soundtracki z gier” (komentarz zaczyna się od „Piterfy Game”).
    - Podgląd: `docker compose run --rm --no-deps piterfy-discover --games --dry-run`; od ręki: `--games`.

- **Statystyki dla nerdów** (`stats.py`): co `DISCOVER_STATS_EVERY_HOURS` (domyślnie 6) godzin discover liczy trzy zestawienia
  i zapisuje je w `discover/state/stats.json`; aplikacja i strona WWW pobierają plik z `GET /piterfy-api/stats`
  (wymaga zalogowania do Navidrome, jak zdjęcia profilowe).
    - **Biblioteka**: liczby utworów, albumów i wykonawców, czas i rozmiar, formaty, bitrate plików stratnych, próbkowanie
      i głębia bitowa, hi-res, dekady, gatunki, rekordy oraz **rachunek jakości** z analizy widma (ile plików jest naprawdę
      bezstratnych, ile „udawanych”, lista do wymiany od najczęściej słuchanych).
    - **Serwer**: przyrost biblioteki tydzień po tygodniu (z daty dodania w Navidrome), miejsce na dysku (wolne
      jest odświeżane przy każdym pytaniu), kosz, czas ostatniego skanu i odkrywania, skuteczność Soulseek, trafność odkryć
      (polubione z ocenionych) oraz skuteczność według silnika rekomendacji (Last.fm albo Deezer) i puli (nowi albo
      znani wykonawcy). Podział na silnik i pulę liczy się dopiero od wersji z statystykami: `remember()` zapisuje te pola
      w historii od teraz, starsze wpisy ich nie mają.
    - **Wrapped**: podsumowanie każdego z ostatnich 13 miesięcy i dwóch lat z dziennej historii odsłuchów panelu gustu (także jako „story”: przesuwane slajdy z okładkami i 25-sekundowym fragmentem najczęściej słuchanego utworu)
      (czas słuchania, odsłuchy, top wykonawcy i utwory, nowi wykonawcy, seria dni, rekordowy dzień) wraz z porównaniem do
      poprzedniego miesiąca albo roku (przy trwającym okresie: do tej samej liczby dni) i **typem słuchacza**
      (Eksplorator, Wierny fan, Nocny marek, Maratończyk, Powtarzacz, Podróżnik w czasie, Na fali albo Równy rytm).
      „Nocny marek” wymaga scrobbli Last.fm (co najmniej 40 w okresie), bo Navidrome nie zna godzin słuchania.
      Wymaga co najmniej 15 odsłuchów, a porównanie pojawia się, gdy historia obejmuje poprzedni okres.
    - Odsłuchy są liczone dla konta, którego discover używa do Navidrome (tak samo jak panel gustu).
    - Odświeżenie od ręki: `docker compose run --rm --no-deps piterfy-discover --stats`.

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
| `discover/mixes.py` | tematyczne miksy (Happy Mix, Dark Mix, Electronic Mix, Bass Boost...): reguły gatunków i nastroju oraz dobór utworów |
| `discover/charts.py` | listy przebojów (Top 100 Polska): dopasowanie wpisów z Deezera do biblioteki (ISRC, tytuł i wykonawca) |
| `discover/origins.py` | miksy według pochodzenia (French Music, Latin Music, Polski Rap…): sygnały z tagów Last.fm, języka i ISRC |
| `discover/games.py` | soundtracki z gier jako playlisty: lista gier, wybór albumu w Deezerze i dopasowanie do biblioteki |
| `discover/podcasts.py`, `discover/podcasts.txt` | podcasty: moduł (RSS → pliki audio z tagami) i lista audycji |
| `library/Podcasty/` | odcinki podcastów, po jednym folderze na audycję |
| `discover/stats.py`, `discover/state/stats.json` | statystyki dla nerdów: liczy je discover co kilka godzin, oddaje `/piterfy-api/stats` |
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
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | | Telegram: razem z mailem dostają alarmy o awarii serwera (patrz „Powiadomienia o awarii”) |
| `HEALTH_NOTIFY` | `crit` | od jakiego poziomu stanu serwera wysyłać alarm: `crit` (tylko czerwony), `warn` (też ostrzeżenia, np. brak kopii zapasowych) albo `off` |
| `HEALTH_NOTIFY_REPEAT_H` / `HEALTH_NOTIFY_CONFIRM` | `6` / `2` | co ile godzin przypominać o trwającej awarii (`0` = bez przypomnień) i ile kolejnych kontroli (co ~10 min) musi potwierdzić zmianę stanu |
| `DISCOVER_PLAYLIST` | `Odkrywaj` | nazwa zbiorczej playlisty (pusta = bez niej) |
| `DISCOVER_PLAYLIST_SIZE` | `60` | ile ostatnich odkryć trzymać w zbiorczej playliście |
| `DISCOVER_DAILY_PLAYLIST` | `Odkrywaj` | początek nazwy playlisty dnia, dalej jest data (pusta = bez playlist dnia) |
| `DISCOVER_DAILY_KEEP_DAYS` | `7` | po ilu dniach playlista dnia znika (`0` = nigdy) |
| `DISCOVER_PODCAST_EVERY_HOURS` | `3` | co ile godzin sprawdza kanały podcastów |
| `DISCOVER_PODCAST_LATEST` | `20` | ile najnowszych odcinków audycji trzyma |
| `DISCOVER_PODCAST_KEEP_DAYS` | `30` | po ilu dniach od odsłuchania odcinek znika |
| `DISCOVER_PODCAST_DISCOVER_EVERY_DAYS` | `7` | co ile dni discover szuka nowych podcastów wg Twojego gustu (`0` = wyłączone) |
| `DISCOVER_PODCAST_DISCOVER_PER_RUN` / `_MAX` | `2` / `8` | ile nowych kanałów na próbę na jeden przebieg i ile kanałów dobranych automatycznie naraz |
| `DISCOVER_PODCAST_DISCOVER_TRIAL_DAYS` | `21` | po ilu dniach próby kanał zostaje (był odsłuch) albo odpada (nie było) |
| `DISCOVER_PODCAST_DISCOVER_QUERIES` | `10` | ile zapytań do iTunes na przebieg |
| `DISCOVER_PODCAST_DISCOVER_NOTIFY` | `1` | `1` = mail i Telegram o nowym, zatrzymanym i usuniętym kanale |
| `DISCOVER_MIXES` | `all` | które miksy tworzyć: `all`, lista nazw po `;` (np. `Happy Mix;Bass Boost`) albo pusta = bez miksów |
| `DISCOVER_MIX_EVERY_HOURS` | `24` | co ile godzin miksy dobierają utwory od nowa |
| `DISCOVER_MIX_SIZE` / `DISCOVER_MIX_SIZE_MAX` | `40` / `100` | najmniejsza i największa liczba utworów w miksie |
| `DISCOVER_MIX_MAX_PER_ARTIST` | `3` | najwyżej tylu utworów jednego wykonawcy w miksie |
| `DISCOVER_CHARTS` | `all` | które listy przebojów tworzyć: `all`, lista nazw po `;` (np. `Top 100 Polska`) albo pusta = bez list |
| `DISCOVER_CHART_EVERY_HOURS` | `12` | co ile godzin listy przebojów odświeżają się |
| `DISCOVER_CHART_MIN` | `3` | ile utworów z listy musi być w bibliotece, żeby powstała playlista |
| `DISCOVER_CHART_TARGET` | `100` | ile utworów z listy przebojów chcemy mieć w bibliotece (poniżej lista sięga głębiej: 200–1000) |
| `DISCOVER_CHART_FILL_CATCHUP` | `20` | ile utworów na odświeżenie dociągać, gdy w bibliotece jest mniej niż cel |
| `DISCOVER_CHART_FILL` | `0` | `1` = dociągaj przez slskd brakujące utwory z czołówki list przebojów (patrz wyżej) |
| `DISCOVER_CHART_FILL_TOP` / `DISCOVER_CHART_FILL_PER_RUN` | `30` / `5` | z ilu pierwszych miejsc listy i ile utworów na jedno odświeżenie |
| `DISCOVER_CHART_FILL_TIMEOUT_MIN` | `20` | ile minut czekać na pobrania przy odświeżeniu listy (reszta dociągnie się później) |
| `DISCOVER_ARTIST_TAG_BATCH` / `DISCOVER_ARTIST_TAG_DAYS` | `400` / `120` | ilu wykonawców na odświeżenie pytać Last.fm o tagi (miksy wg pochodzenia) i po ilu dniach je odświeżać |
| `DISCOVER_GAMES` | `all` | które soundtracki gier tworzyć: `all`, lista nazw po `;` albo pusta = bez soundtracków |
| `DISCOVER_GAME_EVERY_HOURS` | `6` | co ile godzin soundtracki odświeżają się (i dociąga się kolejna porcja) |
| `DISCOVER_GAME_TARGET` / `DISCOVER_GAME_MIN` / `DISCOVER_GAME_FILL_PER_RUN` | `15` / `5` / `10` | ile utworów soundtracku gry chcemy mieć, od ilu powstaje playlista i ile dociągać na odświeżenie (dociąganie wymaga `DISCOVER_CHART_FILL=1`) |
| `DISCOVER_STATS_EVERY_HOURS` | `6` | co ile godzin liczą się statystyki dla nerdów (`0` = wyłączone) |
| `DISCOVER_BLOCK` | | wykonawcy do pomijania, rozdzieleni `;` |
| `DISCOVER_DOWNLOAD_TIMEOUT_MIN` | `90` | ile minut czekać na pobrania (reszta dociągnie się później) |
| `LASTFM_API_KEY` / `LASTFM_SECRET` | | klucz API Last.fm: rekomendacje w discover i scrobblowanie w Navidrome |
| `LASTFM_USER` | | Twój login Last.fm; jego scrobble wchodzą do historii słuchania |

Jak oceniać w Navidrome:
- ★ (polub) albo ocena 4–5 oznacza „więcej takich”;
- ocena 1 oznacza „wyrzuć i rzadziej tego wykonawcę”;
- ocena 2 oznacza „rzadziej tego wykonawcę”, ale plik zostaje.

## Powiadomienia o awarii

Karta „Stan serwera” (Statystyki → Serwer) ma też wersję bez patrzenia na ekran: co ~10 minut pętla discovera ocenia ten sam stan (`health.report`) i gdy robi się **czerwony** (np. odmontowany dysk USB, zapełniona biblioteka, Last.fm lub Deezer nie odpowiada od wielu prób), wysyła wiadomość na mail (`DISCOVER_MAIL_TO`) i na Telegram (`TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID`) — każdy kanał działa, gdy jest skonfigurowany. Awaria musi utrzymać się przez dwie kolejne kontrole, żeby chwilowa czkawka nie budziła telefonu. Potem dostajesz jedną wiadomość o awarii, kolejną przy każdym nowym krytycznym alarmie, przypomnienie co 6 godzin, dopóki trwa, i „✅ znów w normie”, gdy się skończy. Gdy żaden kanał nie dotrze (np. chwilowy brak sieci), wiadomość jest ponawiana przy następnej kontroli. Stan zapamiętuje `state.json` (`health_alerts`), więc restart nie powtarza alarmu. `HEALTH_NOTIFY=warn` dorzuca ostrzeżenia (np. brak kopii zapasowych), `HEALTH_NOTIFY=off` wyłącza powiadomienia. Logika (`health_alerts.py`) jest czysta i ma testy.

## Przydatne komendy

```bash
docker compose run --rm --no-deps piterfy-discover --dry-run        # co by pobrał, bez pobierania
docker compose run --rm piterfy-discover --search "Artysta - Tytuł"  # jedno próbne wyszukiwanie w Soulseek
docker compose run --rm --no-deps piterfy-discover --test-mail      # próbny mail
docker compose run --rm --no-deps piterfy-discover --test-telegram  # próbna wiadomość na Telegram
docker compose run --rm --no-deps piterfy-discover --health-check   # stan serwera i ustawienia alarmów (nic nie wysyła)
docker compose run --rm --no-deps piterfy-discover --taste          # odśwież panel gustu od razu
docker compose run --rm --no-deps piterfy-discover --mixes          # odśwież miksy (Happy Mix, Dark Mix...) od razu
docker compose run --rm --no-deps piterfy-discover --charts         # odśwież listy przebojów (Top 100 Polska) od razu
docker compose run --rm --no-deps piterfy-discover --games          # odśwież soundtracki gier od razu (z --dry-run: tylko podgląd)
docker compose run --rm --no-deps piterfy-discover --stats          # policz statystyki dla nerdów od razu
docker compose run --rm piterfy-discover --now                      # przebieg poza harmonogramem
docker logs -f piterfy-discover                                      # co robi discover
docker logs -f piterfy-beets                                         # co importuje beets
ls reports/ && cat reports/discover-*.md | tail -40                  # ostatnie raporty
```

Jeśli usuniesz `discover/state/state.json`, skrypt zapomni historię i przy najbliższym sprawdzeniu ruszy od nowa.
