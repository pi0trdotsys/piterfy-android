/* Piterfy — „Liquid Glass”: ustawienie (włącznik i siła), pigułka zaznaczenia w panelu bocznym i połysk podążający za kursorem.
 * Wygląd robi glass.css (ładowany z boot.js tylko przy włączonym szkle), a tu jest to, czego sam CSS nie umie: zapis ustawienia
 * (localStorage pf-glass, logika w glass-core.js), sekcja w oknie „Wygląd Piterfy” (wywołuje ją library.js) i animacje z JS. */
(() => {
  "use strict";
  if (window.PiterfyGlass) return;
  const core = window.PiterfyGlassCore;
  if (!core) return;
  const root = document.documentElement;
  const VERSION = ((document.currentScript && document.currentScript.src.match(/[?&]v=([^&]+)/)) || [])[1];
  const calm = () => { try { return localStorage.getItem("pf-calm") === "1"; } catch (e) { return false; } };

  let state;
  const read = () => { try { return core.parse(localStorage.getItem(core.KEY)); } catch (e) { return core.normalize({}); } };
  const write = () => { try { localStorage.setItem(core.KEY, JSON.stringify(state)); } catch (e) { /* bez zapisu działa do odświeżenia */ } };

  /** glass.css ładuje boot.js, gdy szkło było włączone przy starcie; włączone później doładowujemy tutaj. */
  function ensureStyles() {
    if (document.getElementById("pf-glass-css")) return;
    const link = document.createElement("link");
    link.id = "pf-glass-css"; link.rel = "stylesheet";
    link.href = "/piterfy-ui/glass.css" + (VERSION ? "?v=" + VERSION : "");
    document.head.appendChild(link);
  }

  function apply() {
    if (state.on) ensureStyles();
    core.apply(root, state);
    pillSync();
  }

  // ── połysk podążający za kursorem (zmienne --pf-mx / --pf-my na elemencie pod kursorem) ──
  const SHEEN = ".pf-card, .pf-tile, .pf-chips a, .pf-banner, .MuiMenuItem-root, .music-player-panel, .MuiDrawer-paper, header.MuiAppBar-root, " +
    ".MuiPopover-paper, .MuiDialog-paper, dialog, .pf-toast, .pf-library-button, #pf-lyrics-button, #pf-canvas-button";
  let sheenFrame = 0, sheenEvent = null;
  document.addEventListener("pointermove", (event) => {
    if (!state.on || calm() || event.pointerType === "touch") return;
    sheenEvent = event;
    if (sheenFrame) return;
    sheenFrame = requestAnimationFrame(() => {
      sheenFrame = 0;
      const target = sheenEvent && sheenEvent.target && sheenEvent.target.closest && sheenEvent.target.closest(SHEEN);
      if (!target) return;
      const box = target.getBoundingClientRect();
      if (!box.width || !box.height) return;
      target.style.setProperty("--pf-mx", (((sheenEvent.clientX - box.left) / box.width) * 100).toFixed(1) + "%");
      target.style.setProperty("--pf-my", (((sheenEvent.clientY - box.top) / box.height) * 100).toFixed(1) + "%");
    });
  }, { passive: true });

  // ── pigułka zaznaczenia w panelu bocznym: płynnie (sprężyście) przesuwa się do aktywnej pozycji ──
  let pill = null, pillTimer = 0;
  function pillSync() {
    clearTimeout(pillTimer);
    pillTimer = setTimeout(pillMove, 30);
  }
  function pillMove() {
    const paper = document.querySelector(".MuiDrawer-docked .MuiDrawer-paper, .MuiDrawer-modal .MuiDrawer-paper");
    const active = paper && paper.querySelector('a.MuiMenuItem-root[aria-current="page"], a.pf-start[aria-current="page"]');
    if (!state.on || !paper || !active) { if (pill) pill.classList.remove("pf-on"); return; }
    if (!pill || pill.parentNode !== paper) {
      pill = document.createElement("div");
      pill.className = "pf-glass-pill"; pill.setAttribute("aria-hidden", "true");
      paper.insertBefore(pill, paper.firstChild);
    }
    const p = paper.getBoundingClientRect(), a = active.getBoundingClientRect();
    if (!pill.classList.contains("pf-on")) {  // pierwsze ustawienie bez przejazdu przez cały panel
      pill.style.transition = "none";
      pill.style.transform = "translateY(" + (a.top - p.top) + "px)";
      pill.style.height = a.height + "px";
      void pill.offsetWidth;
      pill.style.transition = "";
    }
    pill.style.transform = "translateY(" + (a.top - p.top + paper.scrollTop) + "px)";
    pill.style.height = a.height + "px";
    pill.classList.add("pf-on");
  }
  window.addEventListener("hashchange", pillSync);
  window.addEventListener("resize", pillSync);
  new MutationObserver((records) => {
    if (!state.on) return;
    if (records.some((r) => r.type === "attributes" || [...r.addedNodes].some((n) => n.nodeType === 1 && n.matches && n.matches(".MuiDrawer-paper, .MuiDrawer-paper *")))) pillSync();
  }).observe(document.body || document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ["aria-current"] });

  // ── sekcja w oknie „Wygląd Piterfy” (wołana z library.js przy każdym rysowaniu okna) ──
  function section(dialog) {
    if (!dialog) return;
    const label = document.createElement("label");
    label.className = "pf-ap-auto pf-glass-toggle";
    const box = document.createElement("input");
    box.type = "checkbox"; box.checked = state.on;
    const text = document.createElement("span");
    const title = document.createElement("strong"); title.textContent = "Liquid Glass";
    const hint = document.createElement("small");
    hint.textContent = "Szklany, rozmyty interfejs z połyskiem i płynnymi animacjami — działa z każdym motywem";
    text.append(title, hint); label.append(box, text); dialog.appendChild(label);
    const range = document.createElement("label");
    range.className = "pf-glass-range"; range.hidden = !state.on;
    const caption = document.createElement("span"); caption.textContent = "Siła efektu";
    const slider = document.createElement("input");
    slider.type = "range"; slider.min = "0"; slider.max = "100"; slider.step = "5"; slider.value = String(state.level);
    slider.setAttribute("aria-label", "Siła efektu Liquid Glass");
    const value = document.createElement("output"); value.textContent = state.level + "%";
    range.append(caption, slider, value); dialog.appendChild(range);
    box.addEventListener("change", () => { set({ on: box.checked }); range.hidden = !box.checked; });
    slider.addEventListener("input", () => { set({ level: Number(slider.value) }); value.textContent = state.level + "%"; });
  }

  // ── „Kolory z okładki”: interfejs bierze żywy kolor grającej okładki (domyślnie włączone; localStorage pf-cover-colors=0 wyłącza) ──
  const coverOn = () => { try { return localStorage.getItem("pf-cover-colors") !== "0"; } catch (e) { return true; } };
  const applyCover = () => { if (coverOn()) root.setAttribute("data-pf-cover", "1"); else root.removeAttribute("data-pf-cover"); };
  function coverSection(dialog) {
    if (!dialog) return;
    const label = document.createElement("label");
    label.className = "pf-ap-auto pf-glass-toggle";
    const box = document.createElement("input");
    box.type = "checkbox"; box.checked = coverOn();
    const text = document.createElement("span");
    const title = document.createElement("strong"); title.textContent = "Kolory z okładki";
    const hint = document.createElement("small");
    hint.textContent = "Tło, pasek odtwarzacza i akcenty płynnie przybierają barwę grającej okładki";
    text.append(title, hint); label.append(box, text); dialog.appendChild(label);
    box.addEventListener("change", () => {
      try { localStorage.setItem("pf-cover-colors", box.checked ? "1" : "0"); } catch (e) { /* bez zapisu działa do odświeżenia */ }
      applyCover();
    });
  }

  function set(patch) {
    state = core.normalize(Object.assign({}, state, patch));
    write();
    apply();
  }

  state = read();
  window.PiterfyGlass = { get: () => Object.assign({}, state), set, section, coverSection, apply };
  applyCover();
  window.addEventListener("storage", (event) => { if (event.key === core.KEY) { state = read(); apply(); } });
  apply();
})();
