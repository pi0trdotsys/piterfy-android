# Wygląd Piterfy: motywy, ikony i dni okolicznościowe

`catalog.py` to jedno źródło prawdy: motywy (kolory), ikony (kształty wektorowe) i dni okolicznościowe (reguła daty, ikona, motyw).
`generate.py` zamienia je w pliki dla obu platform i można go uruchamiać wielokrotnie:

```bash
python3 tools/appearance/generate.py                               # zapisz pliki w repozytorium
PREVIEW_DIR=/tmp python3 tools/appearance/generate.py --preview    # dodatkowo arkusze podglądu icons.svg i themes.svg
```

Generowane: wektory ikon i adaptacyjne ikony (`res/drawable/ic_launcher_{bg,fg}_*.xml`, `res/mipmap-anydpi-v26/ic_launcher_*.xml`),
aliasy launchera w `AndroidManifest.xml` (między znacznikami `piterfy-icons`), nakładki motywów i teksty (`res/values*/piterfy_themes.xml`,
`piterfy_appearance.xml`), `appearance/PiterfyCatalog.kt` i `PiterfyOccasionData.kt`, blok CSS motywów w `connect/web/piterfy.css`
(znaczniki `piterfy-themes`) i dane dla przeglądarki w `connect/web/library.js` (znaczniki `piterfy-appearance-data`).

Nie edytuj wygenerowanych plików ręcznie. Kalendarz okazji (±2 dni, Wielkanoc liczona algorytmem) jest napisany osobno i przetestowany
po obu stronach: `PiterfyOccasions.kt` (`PiterfyOccasionsTest`) i `appearanceCore` w `library.js` (`appearance-core.test.cjs`).
