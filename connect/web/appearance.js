/* Piterfy — „Wygląd”: ustawienia odczuć i układu w jednym oknie z podglądem na żywo.
 * Okno „Wygląd Piterfy” (menu użytkownika, library.js) ma już motywy, ikony, Liquid Glass i kolory okładki; tu dochodzą:
 *   • podgląd na żywo: makieta „Teraz gra” z listą, która reaguje na każde ustawienie (szkło, czerń, tekst, gęstość, ruch),
 *   • ruch (jak w systemie / pełny / ograniczony), wibracje, prawdziwa czerń (OLED), rozmiar tekstu 85–130 %, gęstość list.
 * Logika zapisu i nakładania jest w prefs-core.js (testy: prefs-core.test.cjs); rysuje appearance.css. Zmiana zapisuje klucz w
 * localStorage, nakłada się od razu na <html>, wysyła zdarzenie „pf-prefs-changed” i woła PiterfyFeel.apply() (moduł odczuć).
 * Biblioteka library.js woła PiterfyPrefs.preview(okno) i PiterfyPrefs.section(okno) przy każdym rysowaniu okna. */
(() => {
  "use strict";
  if (window.PiterfyPrefs) return;
  const core = window.PiterfyPrefsCore;
  if (!core) return;
  const root = document.documentElement;
  const VERSION = ((document.currentScript && document.currentScript.src.match(/[?&]v=([^&]+)/)) || [])[1];
  const systemQuery = typeof matchMedia === "function" ? matchMedia("(prefers-reduced-motion: reduce)") : { matches: false };

  function ensureStyles() {
    if (document.getElementById("pf-appearance-css")) return;
    const link = document.createElement("link");
    link.id = "pf-appearance-css"; link.rel = "stylesheet";
    link.href = "/piterfy-ui/appearance.css" + (VERSION ? "?v=" + VERSION : "");
    document.head.appendChild(link);
  }

  const explicit = (key) => { try { return localStorage.getItem(key) != null; } catch (e) { return false; } };
  let state = core.read(localStorage);

  /** Stary przełącznik pf-calm dla reszty modułów — tylko gdy ruch został kiedyś wybrany (inaczej zostaje, jak był). */
  function syncCalm() {
    if (!explicit(core.KEYS.motion)) return;
    try {
      if (core.calmFlag(state.motion, systemQuery.matches) === "1") localStorage.setItem(core.KEYS.calm, "1");
      else localStorage.removeItem(core.KEYS.calm);
    } catch (e) { /* bez zapisu działa do odświeżenia */ }
  }

  function apply() {
    core.apply(root, state);
    if (explicit(core.KEYS.motion) && state.motion === "system") root.setAttribute("data-pf-motion", "system"); // jawny wybór „jak w systemie”
    syncCalm();
  }

  function announce() {
    try { window.dispatchEvent(new Event("pf-prefs-changed")); } catch (e) { /* stare przeglądarki */ }
    try { if (window.PiterfyFeel && typeof window.PiterfyFeel.apply === "function") window.PiterfyFeel.apply(); } catch (e) { /* moduł odczuć opcjonalny */ }
  }

  function set(patch) {
    state = core.write(localStorage, patch);
    apply();
    announce();
    document.querySelectorAll(".pf-pv").forEach(paintPreview);
  }

  function reset() {
    Object.keys(core.KEYS).forEach((name) => { if (name !== "calm" && name !== "lite") { try { localStorage.removeItem(core.KEYS[name]); } catch (e) { /* ignore */ } } });
    try { localStorage.removeItem(core.KEYS.calm); } catch (e) { /* ignore */ }
    state = core.read(localStorage);
    root.removeAttribute("data-pf-motion");
    apply();
    announce();
  }

  const el = (tag, text, parent, cls) => {
    const node = document.createElement(tag);
    if (text) node.textContent = text;
    if (cls) node.className = cls;
    if (parent) parent.appendChild(node);
    return node;
  };

  const canVibrate = () => typeof navigator !== "undefined" && typeof navigator.vibrate === "function";
  function tap() {
    try {
      if (window.PiterfyFeel && typeof window.PiterfyFeel.tap === "function") { window.PiterfyFeel.tap(); return; }
      if (state.haptics && canVibrate()) navigator.vibrate(10);
    } catch (e) { /* wibracje są dodatkiem */ }
  }

  // ── podgląd na żywo: makieta „Teraz gra” + lista ──
  const SVG = {
    prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h2v14H6zM20 5v14L9.5 12z" fill="currentColor"/></svg>',
    next: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 5h2v14h-2zM4 5l10.5 7L4 19z" fill="currentColor"/></svg>',
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5h4v14H7zM13 5h4v14h-4z" fill="currentColor"/></svg>',
  };

  function paintPreview(box) {
    const play = box.querySelector(".pf-pv-play");
    if (play) {
      const on = box.dataset.playing === "1";
      play.innerHTML = on ? SVG.pause : SVG.play;
      play.setAttribute("aria-label", on ? "Pauza (podgląd)" : "Odtwórz (podgląd)");
    }
    box.dataset.calm = core.isCalm(state.motion, systemQuery.matches) ? "1" : "0";
    const caption = box.querySelector(".pf-pv-caption");
    if (caption) {
      caption.textContent = "Tekst " + state.textsize + "% · " + (state.density === "compact" ? "gęste listy" : "wygodne listy") +
        (state.oled ? " · prawdziwa czerń" : "") + (root.hasAttribute("data-pf-glass") ? " · szkło" : "");
    }
  }

  function preview(dialog) {
    if (!dialog) return;
    ensureStyles();
    const box = el("div", "", dialog, "pf-pv");
    box.setAttribute("role", "group"); box.setAttribute("aria-label", "Podgląd wyglądu");
    box.dataset.playing = "1";
    const stage = el("div", "", box, "pf-pv-stage");
    el("i", "", stage, "pf-pv-blob pf-pv-blob-a"); el("i", "", stage, "pf-pv-blob pf-pv-blob-b");
    const card = el("div", "", stage, "pf-pv-card");
    el("span", "", card, "pf-pv-art");
    const meta = el("div", "", card, "pf-pv-meta");
    el("b", "Dawn FM", meta, "pf-pv-title"); el("span", "The Weeknd", meta, "pf-pv-artist");
    const eq = el("span", "", card, "pf-pv-eq"); eq.setAttribute("aria-hidden", "true");
    for (let i = 0; i < 4; i++) el("i", "", eq);
    const line = el("p", "", card, "pf-pv-lyric");
    el("span", "Take my breath ", line, "pf-pv-sung"); el("span", "and never let me go", line);
    const bar = el("div", "", card, "pf-pv-bar"); el("i", "", bar);
    const times = el("div", "", card, "pf-pv-times"); el("span", "1:12", times); el("span", "3:05", times);
    const controls = el("div", "", card, "pf-pv-ctl");
    const prev = el("button", "", controls, "pf-pv-btn"); prev.type = "button"; prev.innerHTML = SVG.prev; prev.setAttribute("aria-label", "Poprzedni (podgląd)");
    const play = el("button", "", controls, "pf-pv-btn pf-pv-play"); play.type = "button";
    const next = el("button", "", controls, "pf-pv-btn"); next.type = "button"; next.innerHTML = SVG.next; next.setAttribute("aria-label", "Następny (podgląd)");
    [prev, next].forEach((b) => b.addEventListener("click", tap));
    play.addEventListener("click", () => { box.dataset.playing = box.dataset.playing === "1" ? "0" : "1"; paintPreview(box); tap(); });
    const list = el("ul", "", stage, "pf-pv-list");
    [["Blinding Lights", "The Weeknd", "3:20"], ["Starboy", "The Weeknd", "3:50"], ["Save Your Tears", "The Weeknd", "3:35"]].forEach((row) => {
      const li = el("li", "", list);
      el("span", "", li, "pf-pv-cover");
      const text = el("span", "", li, "pf-pv-text"); el("b", row[0], text); el("small", row[1], text);
      el("em", row[2], li);
    });
    el("p", "", box, "pf-pv-caption");
    paintPreview(box);
  }

  // ── sekcja z ustawieniami ──
  function toggle(dialog, title, hint, checked, onChange) {
    const label = el("label", "", dialog, "pf-ap-auto pf-pr-toggle");
    const box = el("input", "", label); box.type = "checkbox"; box.checked = checked;
    const text = el("span", "", label); el("strong", title, text); el("small", hint, text);
    box.addEventListener("change", () => onChange(box.checked));
    return label;
  }

  function segmented(dialog, title, hint, options, current, onPick) {
    const row = el("div", "", dialog, "pf-ap-auto pf-pr-seg");
    row.style.cursor = "default";
    const text = el("span", "", row); el("strong", title, text); el("small", hint, text);
    const group = el("span", "", row, "pf-pr-group"); group.setAttribute("role", "radiogroup"); group.setAttribute("aria-label", title);
    options.forEach(([value, label]) => {
      const b = el("button", label, group, "pf-pr-opt" + (value === current ? " pf-sel" : ""));
      b.type = "button"; b.setAttribute("role", "radio"); b.setAttribute("aria-checked", value === current ? "true" : "false");
      b.addEventListener("click", () => {
        group.querySelectorAll(".pf-pr-opt").forEach((x) => { x.classList.remove("pf-sel"); x.setAttribute("aria-checked", "false"); });
        b.classList.add("pf-sel"); b.setAttribute("aria-checked", "true");
        onPick(value);
      });
    });
    return row;
  }

  function section(dialog) {
    if (!dialog) return;
    ensureStyles();
    el("h3", "Odczucia i układ", dialog);
    // Tryb Lite (lite.js, lite.css): prosty wygląd i duże przyciski dla słabych urządzeń; zmiana odświeża stronę, bo ciężkie moduły się nie ładują
    toggle(dialog, "Tryb Lite — dla słabych urządzeń", "Prosty wygląd, duże przyciski sterowania muzyką, bez rozmyć i animacji. Strona odświeży się po zmianie.",
      core.readLite(localStorage) === true || root.hasAttribute("data-pf-lite"), (on) => {
        core.writeLite(localStorage, on);
        announce();
        setTimeout(() => { try { location.reload(); } catch (e) { /* ręczne odświeżenie */ } }, 250);
      });
    segmented(dialog, "Ruch", "Jak w systemie, zawsze pełny albo ograniczony (część widoków zmienia się po odświeżeniu)",
      [["system", "System"], ["full", "Pełny"], ["reduced", "Ograniczony"]], state.motion, (value) => set({ motion: value }));
    const haptics = toggle(dialog, "Wibracje", canVibrate() ? "Delikatne „kliknięcia” przy odtwarzaniu, polubieniu i kolejce" : "Działają na telefonie z Androidem — to urządzenie nie wibruje",
      state.haptics, (on) => { set({ haptics: on }); if (on) tap(); });
    if (!canVibrate()) haptics.classList.add("pf-pr-muted");
    toggle(dialog, "Prawdziwa czerń", "Panele i karty bez szarości — oszczędza baterię na ekranach OLED", state.oled, (on) => set({ oled: on }));
    const range = el("label", "", dialog, "pf-glass-range pf-pr-range");
    el("span", "Rozmiar tekstu", range);
    const slider = el("input", "", range);
    slider.type = "range"; slider.min = String(core.TEXT_MIN); slider.max = String(core.TEXT_MAX); slider.step = String(core.TEXT_STEP);
    slider.value = String(state.textsize); slider.setAttribute("aria-label", "Rozmiar tekstu w procentach");
    const value = el("output", state.textsize + "%", range);
    slider.addEventListener("input", () => { set({ textsize: Number(slider.value) }); value.textContent = state.textsize + "%"; });
    segmented(dialog, "Gęstość list", "Wygodne albo gęstsze wiersze i karty",
      [["comfortable", "Wygodne"], ["compact", "Gęste"]], state.density, (v) => set({ density: v }));
    const resetButton = el("button", "Przywróć domyślne odczucia i układ", dialog, "pf-library-button pf-pr-reset");
    resetButton.type = "button";
    resetButton.addEventListener("click", () => {
      reset();
      // okno rysuje się od nowa (library.js), żeby przełączniki pokazały stan domyślny
      const dlg = resetButton.closest("dialog");
      if (dlg && window.PiterfyAppearance && window.PiterfyAppearance.open) { dlg.close(); window.PiterfyAppearance.open(); }
    });
  }

  window.addEventListener("storage", (event) => {
    if (!event.key || Object.values(core.KEYS).includes(event.key)) { state = core.read(localStorage); apply(); document.querySelectorAll(".pf-pv").forEach(paintPreview); }
  });
  if (systemQuery.addEventListener) systemQuery.addEventListener("change", () => { syncCalm(); document.querySelectorAll(".pf-pv").forEach(paintPreview); });
  // szkło włącza glass.js (atrybut na <html>) — podpis podglądu odświeżamy, gdy się zmieni
  new MutationObserver(() => document.querySelectorAll(".pf-pv").forEach(paintPreview)).observe(root, { attributes: true, attributeFilter: ["data-pf-glass"] });

  ensureStyles();
  apply();
  window.PiterfyPrefs = { get: () => Object.assign({}, state), set, reset, apply, preview, section };
})();
