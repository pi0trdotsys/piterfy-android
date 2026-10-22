# Piterfy 4.29.0-piterfy.5 — teksty utworów

- Nowy, czytelny widok tekstów: duże litery, zielony gradient i podświetlenie bieżącej linijki.
- Kliknięcie linijki przenosi odtwarzanie do jej początku. Ręczne przewijanie zatrzymuje śledzenie; przycisk pozwala do niego wrócić.
- Korekta synchronizacji co 0,5 sekundy, zapamiętywana dla utworu i konta.
- Teksty z biblioteki mają pierwszeństwo. Gdy ich brakuje, aplikacja szuka pasującego nagrania w LRCLIB. Zwykłe teksty bez czasu pozostają dostępne do czytania.
- Zapis tekstów offline na Androidzie, anulowanie wyszukiwania po zmianie utworu i czytelna informacja o braku tekstu.
- Odpowiadający widok został wdrożony również w przeglądarkowym Piterfy, z układem mobilnym.
- Dodano opcjonalny Piterfy Connect w wersji zapoznawczej: współdzielona kolejka, sterowanie i przekazywanie pozycji pomiędzy urządzeniami na tym samym koncie. Wymaga serwerowego rozszerzenia Connect; włącz go osobno na urządzeniach. Transfer radia i wspólna kolejność losowa nie są jeszcze obsługiwane.

## Instalacja

Wybierz **app-tempus-release.apk** dla standardowego Piterfy albo **app-degoogled-release.apk** dla wersji bez usług Google. APK są podpisane dotychczasowym kluczem i aktualizują ten sam wariant aplikacji (versionCode 53).

Wersja testowa z końcówką `.debug` jest osobną instalacją — APK wydania stabilnego jej nie zastępuje. Teksty otwiera się ikoną tekstu w pełnym odtwarzaczu; w przeglądarce przyciskiem **♫ Tekst**. Dostępność synchronizacji zależy od konkretnego nagrania i źródła tekstu.

## Sprawdzenie

Testy jednostkowe obu wariantów Androida, lint obu wariantów release, testy sesji Connect i parsera tekstów są wymagane w procesie budowania. Widok tekstów został sprawdzony na fizycznym telefonie i potwierdzony przez użytkownika; w przeglądarce sprawdzono przewijanie do linijki, korektę czasu i szerokość mobilną 390 px.
