# Odtworzenie Piterfy na nowym serwerze

Repozytorium zawiera aplikację Android i kod serwera. Ten katalog składa wersję serwerową z plików z `connect/web`, `connect/server`, `connect/discovery` i lokalnej konfiguracji. Odtwarza Navidrome, wygląd Piterfy w przeglądarce, „Mój gust”, offline, Connect, DJ, Odkrywaj, podcasty, import beets oraz odbiornik Google Cast. Tunnel Cloudflare jest opcjonalny.

## Nowa instalacja

Na hoście Linux x86-64 z Pythonem 3.10 lub nowszym oraz Docker Compose v2 lub nowszym uruchom z katalogu głównego repozytorium:

```sh
python3 connect/stack/prepare.py "$HOME/piterfy-new"
cd "$HOME/piterfy-new"
docker compose config --quiet
docker compose up -d --build piterfy-navidrome piterfy-caddy
```

Wejdź na `http://localhost:8080/app/` z hosta lub przez przekierowanie SSH. Utwórz konto administratora Navidrome. W katalogu `library/` umieść muzykę. W `.env` ustaw `NAVIDROME_USER` i `NAVIDROME_PASSWORD` dla konta używanego przez Odkrywaj i DJ. Ustaw oddzielne dane panelu slskd (`SLSKD_WEB_*`) oraz dane Soulseek (`SOULSEEK_*`). Wtedy uruchom całość:

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail=50 piterfy-discover piterfy-dj
```

- Publiczny HTTPS. Ustaw `PITERFY_HOST` na publiczną domenę i `PITERFY_PUBLIC_URL` na jej adres HTTPS. Przy tunelu Cloudflare skonfiguruj trasę do `http://piterfy-caddy:80`, DNS i token w `.env`, po czym uruchom `docker compose --profile tunnel up -d`. Jeśli zmieniasz domenę istniejącego odbiornika Cast, zaktualizuj jego URL w Google Cast SDK Developer Console na `https://TWOJA-DOMENA/piterfy-ui/cast-receiver.html`. Użycie innego Application ID wymaga również konfiguracji nadawcy Android. Offline w przeglądarce wymaga HTTPS albo localhost.
- Powiadomienia. Ustaw `DISCOVER_MAIL_TO` i `SMTP_*` w `.env` lub uzupełnij `private/msmtprc`. Dla Telegrama ustaw `TELEGRAM_BOT_TOKEN` i `TELEGRAM_CHAT_ID`. Last.fm jest opcjonalny. Plik `private/liked_songs.csv` służy do opcjonalnego porównania ze Spotify.
- Jednorazowy import ze Spotify. Opcjonalny skrypt `ops/bulk_import_liked_songs.py` wymaga biblioteki Python `requests` i zmiennych `SLSKD_WEB_USERNAME`, `SLSKD_WEB_PASSWORD` w środowisku procesu. Zacznij od `python3 ops/bulk_import_liked_songs.py --limit 5 --dry-run`. Jeśli zmieniono port slskd, ustaw też `SLSKD_URL`.
- Podcasty. Edytuj `discover/podcasts.txt`. Proces Discover sam sprawdza kanały. Jeżeli chcesz odtworzyć dodatkowe odświeżenie o 01:00 ze starego hosta, ustaw w cronie `cd /ścieżka/do/instalacji && docker compose run --rm --no-deps piterfy-discover --podcasts`. Alert dyskowy uruchamiaj osobno przez `sh ops/check-disk.sh` z ustawionym `DISK_ALERT_TO` i skonfigurowanym kontem msmtp na hoście.
- „Mój gust”. Dane pojawią się po pierwszym przebiegu Discover. Możesz je wygenerować poleceniem `docker compose run --rm --no-deps piterfy-discover --taste`. Trasa `/gust/` i jej JSON są publicznie dostępne przez Caddy jak w dotychczasowym wdrożeniu. Uwzględnij to, konfigurując dostęp do nowej domeny.

## Przeniesienie istniejącej instancji

Przygotuj nowy katalog poleceniem powyżej, ale przed pierwszym uruchomieniem odtwórz prywatną kopię. Żeby uzyskać spójną kopię SQLite i plików audio, zatrzymaj procesy zapisujące dane na czas wykonywania kopii albo użyj spójnego snapshotu wolumenów i katalogów. Zachowaj właścicieli i uprawnienia plików. Nie uruchamiaj dwóch instancji automatycznego pobierania na tych samych kontach i katalogach.

| Z kopii źródłowej | Gdzie odtworzyć |
| --- | --- |
| Dane wolumenu Navidrome (`/data`, w tym `navidrome.db`) | Wolumen Compose `navidrome_data` nowego projektu. Baza zawiera m.in. konta, historię, ulubione i playlisty. |
| Muzyka i podcasty | `library/` z dotychczasową strukturą ścieżek, widoczną w kontenerze jako `/music` |
| Stan Discover, zdjęcia profilowe, Connect i ustawienia biblioteki | `discover/state/` |
| Baza DJ i zapowiedzi | `discover/dj-state/`. Katalog musi być zapisywalny dla UID/GID ustawionego w `.env`. |
| Baza beets i analiza widma | `discover/beets/library.db` i `discover/beets/spectrum.json` |
| Raporty, kosz i pobrania w toku | `reports/`, `discover-trash/`, `incoming/`, `slskd-incomplete/` oraz wolumen `slskd_data` |
| Dane „Mojego gustu” | `gust/data/` |
| Prywatna konfiguracja | Przenieś wartości starego `.env` do nowego, pozostawiając nowe ustawienia hosta i portów. Jeśli dawny `.dj-runtime.env` zawiera inne wartości integracji, uwzględnij je przy konfiguracji. Skopiuj `~/.msmtprc` i `liked_songs.csv` odpowiednio do `private/msmtprc` i `private/liked_songs.csv`. |

Wolumeny Caddy (`caddy_data`, `caddy_config`) kopiuj, jeśli były używane do przechowywania własnych certyfikatów lub konfiguracji. Token tunelu, rekordy DNS i rejestracja odbiornika Cast są zarządzane na kontach dostawców. Klucz podpisu Android jest potrzebny osobno, aby zbudować APK będący aktualizacją już zainstalowanej aplikacji.

Jeżeli kopia Navidrome to archiwum `navidrome-data.tgz` zawierające pliki z wnętrza `/data` (na najwyższym poziomie jest `navidrome.db`), odtwórz ją do pustego wolumenu przed startem Navidrome:

```sh
backup="/bezwzgledna/sciezka/do/kopii"
docker compose run --rm --no-deps --user 0 \
  -v "$backup:/backup:ro" --entrypoint tar piterfy-navidrome \
  -xzf /backup/navidrome-data.tgz -C /data
```

Po przywróceniu sprawdź logowanie i odtwarzanie utworu, potem `/gust/`, `/app/offline/`, `/piterfy-ui/cast-receiver.html?preview=1` oraz połączenie DJ i Connect z kontem Navidrome. Na końcu sprawdź rzeczywisty odbiornik Cast, tunel i powiadomienia.

## Utrzymanie źródeł

Zmiany w plikach serwera zapisuj w repozytorium przed wdrożeniem. Zasoby przeglądarki są w `connect/web`, backend Connect i DJ w `connect/server`, szablon poczty w `connect/discovery`, a konfiguracja Compose, Caddy i pozostałe procesy w tym katalogu. Starsze skrypty `connect/deploy_*.py` odnoszą się do pierwotnego układu produkcji. Na nowym hoście źródłem pełnego wdrożenia jest `prepare.py`.
