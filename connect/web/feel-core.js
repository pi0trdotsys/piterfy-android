/* Piterfy — „czucie” interfejsu: czysta logika (bez DOM, testy: feel-core.test.cjs).
 * Trzy sprawy: (1) ustawienia haptyki i ruchu z localStorage (pf-haptics, pf-motion) → co ma być włączone,
 * (2) wzory wibracji dla navigator.vibrate, (3) strażnik kontrastu akcentu z okładki: kolor, który wolno położyć na ciemnym
 * tle (pasek, fala), oraz czarny/biały tekst czytelny (WCAG AA) na samym akcencie. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyFeelCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const MOTION_MODES = ["system", "full", "reduced"];
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** Tryb ruchu z zapisanej wartości: "system" | "full" | "reduced" albo null, gdy nic (poprawnego) nie zapisano. */
  function motionPref(raw) { return MOTION_MODES.includes(raw) ? raw : null; }

  /**
   * Czy ruch ma być pełny ("full") czy ograniczony ("reduced").
   * Piterfy od zawsze NIE słucha systemowego „ogranicz ruch” (część komputerów ma animacje wyłączone w systemie, a w Piterfy
   * mają działać) — dlatego bez zapisanego wyboru ruch jest pełny, chyba że ustawiono dawne pf-calm=1. Systemowe
   * ustawienie obowiązuje dopiero po jawnym wyborze „system”.
   */
  function resolveMotion(stored, systemReduced, calm) {
    const mode = motionPref(stored);
    if (mode === "reduced") return "reduced";
    if (mode === "full") return "full";
    if (mode === "system") return systemReduced ? "reduced" : "full";
    return calm ? "reduced" : "full";
  }

  /** Haptyka jest domyślnie włączona; wyłączają ją "0", "false", "off", "no" (oraz false). */
  function hapticsOn(raw) {
    if (raw === false) return false;
    return !["0", "false", "off", "no", "nie"].includes(String(raw == null ? "" : raw).trim().toLowerCase());
  }

  /** Wzory wibracji (ms) dla navigator.vibrate: drobne „pyknięcia”, sukces, ostrzeżenie, mocne uderzenie. */
  const PATTERNS = { tick: 4, tap: 9, success: [10, 45, 16], warn: [26, 55, 26], heavy: 26 };
  function pattern(kind) { return PATTERNS[kind] || PATTERNS.tap; }

  /** Rodzaj wibracji dla klikniętego przycisku wg jego etykiety / klas (play, polub, usuń…); null = bez wibracji. */
  function kindForLabel(label) {
    const t = String(label || "").toLowerCase();
    if (!t) return null;
    if (/usu[ńn]|remove|delete|wyczy[śs][ćc]|clear/.test(t)) return "warn";
    if (/polub|\blove\b|favou?rite|ulubion|\bstar\b|serce|\bheart\b/.test(t)) return "success";
    if (/zamknij|close/.test(t)) return "tick";
    return "tap";
  }

  // ── kolor i kontrast ──────────────────────────────────────────────────────────────────────────────────────

  function hslToRgb(h, s, l) {
    h = ((h % 360) + 360) % 360; s = clamp(s, 0, 1); l = clamp(l, 0, 1);
    const k = (n) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
    const f = (n) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
    return [Math.round(f(0) * 255), Math.round(f(8) * 255), Math.round(f(4) * 255)];
  }

  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min, l = (max + min) / 2;
    let h = 0;
    if (d) { h = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4; h = (h * 60 + 360) % 360; }
    const s = d ? d / (1 - Math.abs(2 * l - 1)) : 0;
    return [h, s, l];
  }

  /** Kolor z tekstu CSS: hsl(h s% l%), hsl(h, s%, l%), rgb(r g b), rgb(r, g, b), #rgb, #rrggbb → [r,g,b] albo null. */
  function parseColor(text) {
    const t = String(text || "").trim().toLowerCase();
    let m = t.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/);
    if (m) {
      const hex = m[1].length === 3 ? m[1].split("").map((c) => c + c).join("") : m[1];
      return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16));
    }
    m = t.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/);
    if (m) return [m[1], m[2], m[3]].map((v) => clamp(Math.round(Number(v)), 0, 255));
    m = t.match(/^hsla?\(\s*([\d.]+)(?:deg)?[\s,]+([\d.]+)%[\s,]+([\d.]+)%/);
    if (m) return hslToRgb(Number(m[1]), Number(m[2]) / 100, Number(m[3]) / 100);
    return null;
  }

  /** Luminancja względna WCAG. */
  function luminance(rgb) {
    const [r, g, b] = rgb.map((v) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  /** Współczynnik kontrastu WCAG (1–21). */
  function contrast(a, b) {
    const la = luminance(a), lb = luminance(b), hi = Math.max(la, lb), lo = Math.min(la, lb);
    return (hi + 0.05) / (lo + 0.05);
  }

  /** Czarny albo biały tekst — ten, który jest czytelniejszy na danym kolorze (zawsze co najmniej ~4,6:1, czyli AA). */
  function onAccent(rgb) {
    return contrast(rgb, [0, 0, 0]) >= contrast(rgb, [255, 255, 255]) ? "#000000" : "#ffffff";
  }

  /**
   * Akcent bezpieczny do położenia na tle `against` (domyślnie ciemny panel #121212): jeśli kontrast jest mniejszy niż `min`
   * (3:1 — próg dla elementów interfejsu wg WCAG), rozjaśnia kolor na ciemnym tle albo przyciemnia na jasnym.
   * Odcień i nasycenie zostają, zmienia się tylko jasność.
   */
  function guardAccent(rgb, against = [18, 18, 18], min = 3) {
    if (contrast(rgb, against) >= min) return rgb.slice();
    const [h, s, l] = rgbToHsl(rgb[0], rgb[1], rgb[2]);
    const lighten = luminance(against) < 0.4;
    let best = rgb.slice();
    for (let step = 1; step <= 100; step += 1) {
      const nl = clamp(l + (lighten ? 1 : -1) * step * 0.01, 0, 1);
      best = hslToRgb(h, s, nl);
      if (contrast(best, against) >= min) break;
    }
    return best;
  }

  const css = (rgb) => "rgb(" + rgb.join(" ") + ")";

  /**
   * Zmienne CSS dla akcentu z okładki (tekst z library.js, np. "hsl(210 70% 50%)"): --pf-now-accent-safe (akcent z gwarantowanym
   * kontrastem względem tła) i --pf-on-accent (czarny/biały tekst na akcencie). null, gdy koloru nie da się odczytać.
   */
  function accentVars(accentText, against) {
    const rgb = parseColor(accentText);
    if (!rgb) return null;
    return { "--pf-now-accent-safe": css(guardAccent(rgb, against)), "--pf-on-accent": onAccent(rgb) };
  }

  return { MOTION_MODES, motionPref, resolveMotion, hapticsOn, PATTERNS, pattern, kindForLabel,
    hslToRgb, rgbToHsl, parseColor, luminance, contrast, onAccent, guardAccent, accentVars };
});
