/* Piterfy — „Liquid Glass”: czysta logika ustawienia (bez DOM, testy: glass-core.test.cjs).
 * Ustawienie to { on, level }: włącznik i siła efektu 0–100. Siła zamienia się na zmienne CSS, którymi
 * glass.css steruje rozmyciem, przezroczystością i odblaskami, niezależnie od motywu kolorystycznego. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyGlassCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const KEY = "pf-glass";
  const DEFAULT_LEVEL = 60;
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** Ustawienie z dowolnej wartości: { on: boolean, level: 0–100 (liczba całkowita) }. */
  function normalize(raw) {
    const on = !!(raw && raw.on);
    const stored = raw ? raw.level : undefined;
    let level = stored == null || stored === "" ? DEFAULT_LEVEL : Number(stored);
    if (!Number.isFinite(level)) level = DEFAULT_LEVEL;
    return { on, level: Math.round(clamp(level, 0, 100)) };
  }

  function parse(text) {
    try { return normalize(JSON.parse(text || "{}")); } catch (e) { return normalize({}); }
  }

  /**
   * Zmienne CSS dla danej siły (rosną razem z siłą, poza wypełnieniem, które maleje: im mocniej, tym bardziej przezroczyste):
   * blur — rozmycie tła dużych powierzchni, blur-soft — małych, sat — nasycenie tła, fill — ile koloru panelu zostaje
   * (w %), fill-strong — to samo dla okien i menu, edge — jasność krawędzi, sheen — połysk, glow — poświata tła.
   */
  function vars(level) {
    const L = clamp(Number(level) || 0, 0, 100);
    const r = (v, d) => Number(v.toFixed(d));
    return {
      "--pf-glass-blur": r(6 + L * 0.24, 1) + "px",
      "--pf-glass-blur-soft": r(3 + L * 0.12, 1) + "px",
      "--pf-glass-sat": String(r(1.05 + L * 0.011, 2)),
      "--pf-glass-fill": Math.round(62 - L * 0.42) + "%",
      "--pf-glass-fill-strong": Math.round(80 - L * 0.4) + "%",
      "--pf-glass-edge": String(r(0.16 + L * 0.0032, 3)),
      "--pf-glass-sheen": String(r(0.05 + L * 0.0016, 3)),
      "--pf-glass-glow": String(r(0.1 + L * 0.002, 3)),
    };
  }

  /** Nakłada ustawienie na element główny: atrybut data-pf-glass i zmienne CSS (albo zdejmuje je przy wyłączeniu). */
  function apply(rootElement, prefs) {
    const p = normalize(prefs);
    const values = vars(p.level);
    if (p.on) {
      rootElement.setAttribute("data-pf-glass", "1");
      Object.keys(values).forEach((name) => rootElement.style.setProperty(name, values[name]));
    } else {
      rootElement.removeAttribute("data-pf-glass");
      Object.keys(values).forEach((name) => rootElement.style.removeProperty(name));
    }
    return p;
  }

  return { KEY, DEFAULT_LEVEL, normalize, parse, vars, apply };
});
