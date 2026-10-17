# Piterfy na Androida

Aplikacja Piterfy na telefon: odtwarzanie z serwera Piterfy (Navidrome), pobieranie offline,
Android Auto i Chromecast. To fork [Tempus](https://github.com/eddyizm/tempus) (GPL-3.0) z marką
i wyglądem Piterfy. Licencja zostaje ta sama (`LICENSE`); kod źródłowy zmian jest w tym repozytorium.

## Co zmieniono względem Tempus

Zmiany Piterfy są opisane poniżej; wiele miejsc oznaczono komentarzem `Piterfy:`.

| Zmiana | Gdzie |
|---|---|
| Identyfikator `eu.prosinski.piterfy` (instaluje się obok Tempus), wersja `4.29.0-piterfy.N` | `app/build.gradle` |
| Nazwa „Piterfy” we wszystkich językach, w „O aplikacji” dopisek „Na bazie Tempus” | `app/src/main/res/values*/strings.xml` |
| Ikona, logo, ekran startowy: zielone koło ze słupkami na czarnym tle | `res/drawable/ic_launcher_*.xml`, `ic_splash_logo.xml`, `logo.xml` (też w `src/degoogled`) |
| Paleta Material 3 z zieleni Piterfy `#1DB954` (jasna, ciemna i czarna AMOLED) | `res/values/colors.xml` |
| Domyślnie: ciemny motyw, czarne tło, zielony akcent, bez kolorów z tapety | `util/Preferences.kt` |
| Kafelek „Szmaragd” na ekranie wyboru motywu = zieleń Piterfy | `ui/login/LoginThemeFragment.kt`, `res/layout/fragment_login_theme.xml` |
| Nowy serwer podpowiada nazwę „Piterfy” i adres `https://piterfy.prosinski.eu` | `ui/dialog/ServerSignupDialog.java`, `res/values/strings.xml` |
| Sprawdzanie wydań `pi0trdotsys/piterfy-android` domyślnie włączone; uwzględnia numer rewizji Piterfy | `util/Preferences.kt` |
| **Poprawka:** adres serwera zapisany jako `HTTPS://…` (np. przez klawiaturę) psuł wszystkie okładki — Glide rozpoznaje tylko `https://`; bez okładki system nie pokazywał odtwarzacza na ekranie blokady i w Hyper Island. Adres jest teraz ujednolicany przy każdym użyciu, a Glide rozpoznaje protokół bez względu na wielkość liter | `util/Preferences.kt` (`normalizeServerUrl`), `App.java`, `glide/IPv6StringLoader.java` |
| Pola adresu serwera jako adres URL (klawiatura nie zmienia liter), ekran powitalny podpowiada Piterfy | `res/layout/fragment_login_server.xml`, `dialog_server_signup.xml`, `ui/login/LoginServerFragment.kt` |
| **Poprawka:** prośba systemu o okładkę przed końcem startu aplikacji wywracała dostawcę okładek | `provider/AlbumArtContentProvider.java`, `App.java` (`isReady`) |
| **Podcasty:** zakładka „Podcasty” pokazuje audycje z biblioteki (albumy z gatunkiem „Podcast”, które zapisuje `podcasts.py` na serwerze) zamiast pola RSS z Tempus, bo Navidrome nie obsługuje podcastów z API Subsonic. Odcinki w audycji są od najnowszego | `ui/fragment/HomeTabPiterfyPodcastFragment.java`, `ui/fragment/HomeFragment.java`, `ui/fragment/AlbumPageFragment.java`, `subsonic/api/albumsonglist/*` (`getAlbumListByGenre`) |
| Tryb nocny przy starcie aplikacji domyślnie ciemny (spójnie z resztą motywu) | `App.java` |

## Budowanie

Wymaga Android SDK (`ANDROID_HOME`) i JDK 21. Wariant `tempus` = Piterfy z Android Auto i Chromecastem.

```bash
./gradlew assembleTempusDebug      # wersja testowa: app/build/outputs/apk/tempus/debug/
./gradlew assembleTempusRelease    # wersja docelowa (wymaga klucza do podpisu — patrz niżej)
```

Instalacja na telefonie przez USB (debugowanie USB włączone w opcjach programisty):

```bash
adb install -r app/build/outputs/apk/tempus/debug/app-tempus-debug.apk
```

## Nowe wersje Tempus

```bash
git fetch upstream --tags
git merge v4.30.0          # albo inny tag; konflikty zwykle tylko w miejscach oznaczonych „Piterfy:”
```

## Wydanie 4.29.0-piterfy.2

- Noir: czerń, spokojniejsze powierzchnie, Figtree, czytelniejsze tytuły i zaokrąglone karty.
- Kompaktowy przycisk „Mój gust” zastępuje duży baner (około 75% mniej wysokości).
- Strona główna: trzy najnowsze playlisty „Odkrywaj” z serwera, odtwarzanie, pobieranie i „Mój gust”.
- Podcasty: osobna zakładka, pamięć katalogu, odświeżanie, komunikaty błędów z ponowieniem, filtr „Pobrane”, wznawianie i „Dokończ odcinek”.
- Wyszukiwanie: opóźnienie podpowiedzi 300 ms, anulowanie poprzednich zapytań, historia poza wątkiem UI, pełna kolejka pobierana dopiero po „Odtwórz wszystkie wyniki”.
- „Best Of”: usunięto obserwator, który uruchamiał powtarzające się pobieranie.
- Odtwarzacz pokazuje bieżące wyjście (np. Bluetooth · MAJOR IV), także po zmianie urządzenia.
- Osobna informacja o analizie źródła przez `/piterfy-api/quality/<id>`. Nie jest to pomiar bieżącego strumienia; brak rozszerzenia na serwerze nie blokuje odtwarzania.
- Aktualizacje: publiczne wydania GitHub bez logowania, tylko wersje z sufiksem `-piterfy.N`. Przy wcześniejszym ręcznym wyłączeniu sprawdzania aktualizacji należy je włączyć w ustawieniach.

„Mój gust” otwiera `/gust/` w przeglądarce dla skonfigurowanego serwera. Mobilny układ tej strony jest wdrażany na serwerze osobno. Analiza jakości korzysta wyłącznie z HTTPS i tokenu przechowywanego w pamięci.

## Radio

18 gotowych stacji polskich i zagranicznych (BBC World Service, LBC, NDR). Są dodawane raz jako stacje lokalne; możesz je edytować i usuwać. Lista źródeł: `RADIO-SOURCES.md`.

## Podpis i publikowanie

Prywatny klucz jest w `.signing/piterfy-release.p12`, a hasła w `.signing/keystore.properties`. Cały katalog jest wykluczony z Git. Zachowaj jego bezpieczną kopię: kolejne aktualizacje muszą być podpisane tym samym kluczem. Kopia do CI jest przechowywana jako zaszyfrowane GitHub Actions Secrets.

`./gradlew assembleTempusRelease assembleDegoogledRelease` używa tego podpisu, jeśli lokalna konfiguracja istnieje. Bez niej powstaje APK bez podpisu. Workflow dla tagów `v*-piterfy.*` testuje, sprawdza lint i buduje oba warianty przed publikacją. Debug (`eu.prosinski.piterfy.debug`) i wydanie stabilne (`eu.prosinski.piterfy`) to osobne instalacje; stabilna wymaga pierwszego logowania.

Wersja standardowa i degoogled także mają osobne identyfikatory. Ekran aktualizacji otwiera stronę wydania, gdzie wybierasz APK swojego wariantu.

## Wygląd w stylu Spotify (warstwa wizualna)

Szkielet Tempus (fragmenty, logika, odtwarzanie) bez zmian; przerobiona jest tylko warstwa wizualna:

| Element | Gdzie |
|---|---|
| Font Figtree z wagami 400–800 (plik zmienny wymaga rodziny z `fontVariationSettings`, inaczej wszystko było „regular”) | `res/font/figtree_family.xml` |
| Dolna nawigacja: bez „pigułki”, aktywny element biały, pełny domek, czarny gradient | `PiterfyBottomNav` (`piterfy_styles.xml`), `piterfy_nav_*` |
| Nagłówek: awatar (zdjęcie z `/piterfy-api/avatar/…` albo zielone kółko z inicjałem), zakładki jako chipsy, zielony gradient u góry | `fragment_toolbar.xml`, `piterfy_chips_tabs.xml`, `ToolbarFragment.initAvatar` |
| Strona główna: kafelki szybkiego dostępu 2×4, półka „Odkryte dla Ciebie” z kwadratowymi okładkami i zielonym przyciskiem play (dłuższe przytrzymanie = pobierz offline) | `ui/components/PiterfyHomePanel.java` |
| Kolor „z okładki”: karta mini-odtwarzacza i gradient pełnego odtwarzacza | `util/PiterfyAmbient.java`, `PlayerBottomSheetFragment.paintAmbient` |
| Pełny odtwarzacz: duży biały przycisk play, cienki biały pasek postępu, serce zielone po polubieniu, losowo/powtarzaj zielone gdy włączone | `inner_fragment_player_controller_layout.xml`, `PlayerControllerFragment.tintModeButtons` |
| Strona albumu i playlisty: gradient z okładki, tytuł do lewej, zielony przycisk „Odtwórz” | `fragment_album_page.xml`, `fragment_playlist_page.xml` |
| Okrągłe zdjęcia wykonawców | `Artist*Adapter.java` (`circleCrop`) |
