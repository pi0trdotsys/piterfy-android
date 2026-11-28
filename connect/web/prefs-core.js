/* Piterfy — ustawienia wyglądu i „odczucia” zapisane w localStorage (czysta logika, bez DOM; testy: prefs-core.test.cjs).
 *
 * Klucze (wspólne dla strony „Wygląd”, boot.js i modułu odczuć PiterfyFeel):
 *   pf-haptics   "1" | "0"                         wibracje na telefonie (domyślnie włączone)
 *   pf-motion    "system" | "full" | "reduced"     ruch: jak w systemie / zawsze pełny / ograniczony (domyślnie system)
 *   pf-oled      "1" | "0"                         prawdziwa czerń (panele i karty bez szarości)
 *   pf-density   "comfortable" | "compact"         gęstość list i kart
 *   pf-textsize  85–130                            rozmiar tekstu w procentach (domyślnie 100)
 *   pf-lite      "1" | "0" | brak                  tryb Lite dla słabych urządzeń (osobno od reszty: readLite/writeLite, logika w lite-core.js)
 * Wszystko poza wibracjami i ruchem jest nakładane na <html> (atrybuty data-pf-* i zmienna --pf-text-scale), a rysuje to appearance.css.
 *
 * Ruch: stary przełącznik pf-calm=1 (czytają go lyrics, morph, stats…) zostaje źródłem prawdy dla reszty modułów, więc wybór
 * „ruchu” zapisuje się też do niego (calmFlag) — ale tylko gdy pf-motion zostało kiedyś wybrane; bez niego pf-calm zostaje, jak był. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyPrefsCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  const KEYS = { haptics: "pf-haptics", motion: "pf-motion", oled: "pf-oled", density: "pf-density", textsize: "pf-textsize", calm: "pf-calm", lite: "pf-lite" };
  const MOTIONS = ["system", "full", "reduced"];
  const DENSITIES = ["comfortable", "compact"];
  const TEXT_MIN = 85, TEXT_MAX = 130, TEXT_STEP = 5;
  const DEFAULTS = { haptics: true, motion: "system", oled: false, density: "comfortable", textsize: 100 };

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** Rozmiar tekstu: liczba całkowita % w zakresie 85–130, zaokrąglona do kroku 5 (śmieci wracają do 100). */
  function normalizeTextSize(value) {
    if (value == null || value === "") return DEFAULTS.textsize;
    const n = Number(value);
    if (!Number.isFinite(n)) return DEFAULTS.textsize;
    return clamp(Math.round(n / TEXT_STEP) * TEXT_STEP, TEXT_MIN, TEXT_MAX);
  }

  /** Ustawienia z dowolnego obiektu (wartości tekstowe z localStorage albo już typowane). */
  function normalize(raw) {
    const r = raw && typeof raw === "object" ? raw : {};
    const flag = (v, dflt) => (v == null || v === "" ? dflt : v === true || v === "1" || v === 1);
    const motion = MOTIONS.includes(r.motion) ? r.motion : DEFAULTS.motion;
    const density = DENSITIES.includes(r.density) ? r.density : DEFAULTS.density;
    return { haptics: flag(r.haptics, DEFAULTS.haptics), motion, oled: flag(r.oled, DEFAULTS.oled), density, textsize: normalizeTextSize(r.textsize) };
  }

  /** Czyta ustawienia z magazynu z metodą getItem (localStorage albo atrapa w testach); błędy dostępu dają domyślne. */
  function read(storage) {
    const get = (key) => { try { return storage ? storage.getItem(key) : null; } catch (e) { return null; } };
    return normalize({
      haptics: get(KEYS.haptics), motion: get(KEYS.motion), oled: get(KEYS.oled), density: get(KEYS.density), textsize: get(KEYS.textsize),
    });
  }

  /** Zapisuje zmiany (tylko znane klucze, w postaci tekstu); zwraca ustawienia po zmianie. */
  function write(storage, patch) {
    const next = normalize(Object.assign(read(storage), patch));
    const set = (key, value) => { try { if (storage) storage.setItem(key, String(value)); } catch (e) { /* bez zapisu działa do odświeżenia */ } };
    const has = (name) => patch && Object.prototype.hasOwnProperty.call(patch, name);
    if (has("haptics")) set(KEYS.haptics, next.haptics ? "1" : "0");
    if (has("motion")) set(KEYS.motion, next.motion);
    if (has("oled")) set(KEYS.oled, next.oled ? "1" : "0");
    if (has("density")) set(KEYS.density, next.density);
    if (has("textsize")) set(KEYS.textsize, next.textsize);
    return next;
  }

  /** Tryb Lite: true (włączony), false (wyłączony) albo null (nie wybrano — decyduje podpowiedź przy słabym urządzeniu). */
  function readLite(storage) {
    let v = null;
    try { v = storage ? storage.getItem(KEYS.lite) : null; } catch (e) { return null; }
    return v === "1" ? true : v === "0" ? false : null;
  }

  /** Zapisuje wybór trybu Lite (true / false) albo go usuwa (null); zwraca wartość po zapisie. */
  function writeLite(storage, value) {
    try {
      if (!storage) return null;
      if (value === true) storage.setItem(KEYS.lite, "1");
      else if (value === false) storage.setItem(KEYS.lite, "0");
      else storage.removeItem(KEYS.lite);
    } catch (e) { /* bez zapisu działa do odświeżenia */ }
    return readLite(storage);
  }

  /** Czy cokolwiek z wyglądu odbiega od domyślnego — wtedy boot.js doładowuje ten plik jeszcze przed pierwszym rysunkiem. */
  function needsEarlyApply(storage) {
    const p = read(storage);
    return p.oled || p.density !== DEFAULTS.density || p.textsize !== DEFAULTS.textsize;
  }

  /** Mnożnik tekstu (0.85–1.3). */
  const textScale = (percent) => normalizeTextSize(percent) / 100;

  /**
   * Wartość dla starego przełącznika pf-calm: "1" (ruch ograniczony), "0" (pełny) albo null (nie ruszaj / usuń).
   * `systemReduced` to wynik matchMedia("(prefers-reduced-motion: reduce)").
   */
  function calmFlag(motion, systemReduced) {
    if (motion === "reduced") return "1";
    if (motion === "full") return "0";
    return systemReduced ? "1" : "0";
  }

  /** Czy ruch ma być ograniczony przy takim wyborze (z uwzględnieniem ustawienia systemu). */
  const isCalm = (motion, systemReduced) => calmFlag(motion, systemReduced) === "1";

  /** Zmienne i atrybuty dla <html>: lista [nazwa, wartość|null] (null = usuń). */
  function attributes(prefs) {
    const p = normalize(prefs);
    return {
      attrs: [
        ["data-pf-oled", p.oled ? "1" : null],
        ["data-pf-density", p.density === "compact" ? "compact" : null],
        ["data-pf-text", p.textsize !== DEFAULTS.textsize ? String(p.textsize) : null],
        ["data-pf-motion", p.motion === "system" ? null : p.motion],
      ],
      vars: [["--pf-text-scale", p.textsize !== DEFAULTS.textsize ? String(textScale(p.textsize)) : null]],
    };
  }

  /** Nakłada ustawienia na element główny; zwraca znormalizowane ustawienia. */
  function apply(rootElement, prefs) {
    const p = normalize(prefs);
    const { attrs, vars } = attributes(p);
    attrs.forEach(([name, value]) => { if (value == null) rootElement.removeAttribute(name); else rootElement.setAttribute(name, value); });
    vars.forEach(([name, value]) => { if (value == null) rootElement.style.removeProperty(name); else rootElement.style.setProperty(name, value); });
    return p;
  }

  return { KEYS, DEFAULTS, MOTIONS, DENSITIES, TEXT_MIN, TEXT_MAX, TEXT_STEP, normalize, normalizeTextSize, read, write, readLite, writeLite, needsEarlyApply, textScale, calmFlag, isCalm, attributes, apply };
});
