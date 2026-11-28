/* Piterfy — tryb Lite: prosty, szybki interfejs dla słabych urządzeń (np. iPad mini 4, Safari 15) z DUŻYMI przyciskami sterowania muzyką.
 *
 * Ten moduł ładuje się zawsze (loader w piterfy.js), ale robi co innego zależnie od atrybutu <html data-pf-lite>:
 *   • bez Lite — tylko na urządzeniu, które wygląda na słabe i gdy nikt jeszcze nie wybrał trybu, pokazuje JEDNĄ podpowiedź
 *     „włączyć tryb Lite?” (nigdy nie włącza go sam),
 *   • z Lite — buduje własny duży odtwarzacz (dolny pasek + pełny ekran „Teraz gra”), a natywny panel odtwarzacza Navidrome
 *     zostaje w DOM, tylko schowany poza ekranem (sterujemy nim jego własnymi przyciskami i elementem <audio>, więc cała logika kolejki,
 *     scrobblingu, DJ-a i Connect działa bez zmian), oraz odchudza stronę startową (mniej półek).
 * Atrybut data-pf-lite-player pojawia się dopiero po zbudowaniu odtwarzacza; gdyby coś poszło nie tak, zostaje natywny panel
 * (powiększony przez lite.css), więc muzyką da się sterować zawsze. Logika bez DOM: lite-core.js (testy: lite-core.test.cjs). */
(() => {
  "use strict";
  if (window.PiterfyLite) return;
  const core = window.PiterfyLiteCore;
  if (!core) return;
  const root = document.documentElement;
  const isLite = root.hasAttribute("data-pf-lite");
  const NS = "http://www.w3.org/2000/svg";

  const read = (key) => { try { return localStorage.getItem(key); } catch (e) { return null; } };
  const write = (key, value) => { try { localStorage.setItem(key, value); } catch (e) { /* bez zapisu działa do odświeżenia */ } };
  const environment = () => ({
    ua: navigator.userAgent, touchPoints: navigator.maxTouchPoints, deviceMemory: navigator.deviceMemory, cores: navigator.hardwareConcurrency,
  });
  const reload = () => { try { location.reload(); } catch (e) { /* ręczne odświeżenie */ } };

  function setLite(on) {
    write(core.KEY, on ? "1" : "0");
    reload();
  }

  const el = (tag, cls, parent, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    if (parent) parent.appendChild(node);
    return node;
  };

  const ICON = {
    play: "M8 5v14l11-7z",
    pause: "M7 5h4v14H7zM13 5h4v14h-4z",
    prev: "M6 5h2.4v14H6zM20 5v14L9.5 12z",
    next: "M15.6 5H18v14h-2.4zM4 5l10.5 7L4 19z",
    shuffle: "M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5",
    repeat: "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3",
    heart: "M12 21s-7.5-4.6-9.6-9.3C.8 8.3 3 5 6.2 5c2 0 3.6 1.1 4.6 2.7h.4C12.2 6.1 13.8 5 15.8 5 19 5 21.2 8.3 19.6 11.7 17.5 16.4 12 21 12 21z",
    queue: "M4 6h12M4 12h12M4 18h8M19 13v6M16 16h6",
    close: "M6 6l12 12M18 6 6 18",
    down: "M6 9l6 6 6-6",
  };
  const FILLED = { play: true, pause: true, prev: true, next: true };

  function icon(name) {
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(NS, "path");
    path.setAttribute("d", ICON[name]);
    if (FILLED[name]) path.setAttribute("fill", "currentColor");
    else { path.setAttribute("fill", "none"); path.setAttribute("stroke", "currentColor"); path.setAttribute("stroke-width", "2"); path.setAttribute("stroke-linecap", "round"); path.setAttribute("stroke-linejoin", "round"); }
    svg.appendChild(path);
    return svg;
  }

  function button(cls, label, name, parent) {
    const b = el("button", cls, parent);
    b.type = "button";
    b.setAttribute("aria-label", label);
    b.appendChild(icon(name));
    return b;
  }

  // ── podpowiedź w pełnej wersji ──────────────────────────────────────────
  function hint() {
    if (document.getElementById("pf-lite-hint")) return;
    const style = el("style", "", document.head);
    style.id = "pf-lite-hint-css";
    style.textContent = "#pf-lite-hint{position:fixed;left:12px;right:12px;bottom:12px;z-index:2147483000;max-width:560px;margin:0 auto;padding:16px;border-radius:14px;" +
      "background:#1f1f1f;color:#fff;font:15px/1.45 -apple-system,system-ui,sans-serif;border:1px solid rgba(255,255,255,.18)}" +
      "#pf-lite-hint p{margin:0 0 12px}#pf-lite-hint small{display:block;margin-top:4px;color:#b3b3b3}" +
      "#pf-lite-hint button{min-height:48px;padding:0 20px;margin-right:10px;border:0;border-radius:24px;font:700 16px -apple-system,system-ui,sans-serif;background:#2a2a2a;color:#fff}" +
      "#pf-lite-hint button.pf-lite-yes{background:#1db954;color:#000}";
    const box = el("div", "", document.body);
    box.id = "pf-lite-hint";
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-label", "Tryb Lite");
    const p = el("p", "", box, "Wygląda na to, że to wolne urządzenie — włączyć tryb Lite?");
    el("small", "", p, "Prostszy wygląd, duże przyciski sterowania muzyką, bez rozmyć i animacji. Zmienisz to w „Wygląd Piterfy”.");
    const yes = el("button", "pf-lite-yes", box, "Włącz Lite");
    yes.type = "button";
    const no = el("button", "", box, "Nie, dziękuję");
    no.type = "button";
    const done = () => { write(core.HINT_KEY, "1"); box.remove(); style.remove(); };
    yes.addEventListener("click", () => { write(core.HINT_KEY, "1"); setLite(true); });
    no.addEventListener("click", done);
    yes.focus();
  }

  if (!isLite) {
    const run = () => {
      try {
        if (core.shouldSuggest(read(core.KEY), read(core.HINT_KEY), environment())) setTimeout(hint, 2500);
      } catch (e) { /* podpowiedź jest dodatkiem */ }
    };
    if (document.body) run(); else document.addEventListener("DOMContentLoaded", run);
    window.PiterfyLite = { on: false, setLite, hint };
    return;
  }

  // ── Lite: duży odtwarzacz ───────────────────────────────────────────────
  const store = () => { try { return window.PiterfyLibrary && window.PiterfyLibrary.store && window.PiterfyLibrary.store(); } catch (e) { return null; } };
  const playerState = () => { const s = store(); try { return s ? s.getState().player : null; } catch (e) { return null; } };
  const audioEl = () => document.querySelector("audio.music-player-audio, .react-jinke-music-player-main audio");
  const nativeControl = (selector) => document.querySelector(".react-jinke-music-player-main " + selector + ", .react-jinke-music-player-mobile " + selector);
  const iOS = core.parseBrowser(navigator.userAgent, navigator.maxTouchPoints).ios; // na iOS głośność jest tylko sprzętowa

  const ui = {};
  let liked = false, likedFor = "", seeking = false, lastUuid = "", open = false;

  function subsonic(command, extra) {
    const params = new URLSearchParams(Object.assign({
      u: read("username") || "", t: read("subsonic-token") || "", s: read("subsonic-salt") || "", v: "1.16.1", c: "PiterfyLite", f: "json",
    }, extra));
    return fetch("/rest/" + command + "?" + params).then((r) => r.json()).then((j) => j["subsonic-response"] || {}).catch(() => ({}));
  }

  function build() {
    const wrap = el("div", "pf-lite-np", document.body);
    wrap.hidden = true;
    wrap.setAttribute("data-open", "0");
    ui.wrap = wrap;

    // dolny pasek
    const bar = el("div", "pf-lite-bar", wrap);
    const expand = el("button", "pf-lite-expand", bar);
    expand.type = "button";
    expand.setAttribute("aria-label", "Otwórz odtwarzacz");
    ui.barCover = el("span", "pf-lite-cover", expand);
    const meta = el("span", "pf-lite-meta", expand);
    ui.barTitle = el("b", "pf-lite-title", meta);
    ui.barArtist = el("span", "pf-lite-artist", meta);
    ui.barPlay = button("pf-lite-play pf-lite-play-bar", "Odtwórz", "play", bar);
    ui.barProgress = el("i", "pf-lite-bar-progress", bar);
    ui.expand = expand;
    expand.addEventListener("click", () => setOpen(true));

    // pełny ekran „Teraz gra”
    const sheet = el("div", "pf-lite-sheet", wrap);
    sheet.setAttribute("role", "dialog");
    sheet.setAttribute("aria-label", "Teraz gra");
    ui.sheet = sheet;
    const close = button("pf-lite-close", "Zwiń odtwarzacz", "down", sheet);
    close.addEventListener("click", () => setOpen(false));
    ui.art = el("div", "pf-lite-art", sheet);
    const info = el("div", "pf-lite-info", sheet);
    ui.title = el("b", "pf-lite-title", info);
    ui.artist = el("span", "pf-lite-artist", info);

    const seek = el("div", "pf-lite-seek", sheet);
    ui.cur = el("span", "pf-lite-time", seek, "0:00");
    const slider = el("div", "pf-lite-slider", seek);
    slider.setAttribute("role", "slider");
    slider.setAttribute("aria-label", "Postęp utworu");
    slider.setAttribute("aria-valuemin", "0");
    slider.tabIndex = 0;
    el("i", "pf-lite-rail", slider);
    ui.fill = el("i", "pf-lite-fill", slider);
    ui.slider = slider;
    ui.dur = el("span", "pf-lite-time", seek, "0:00");

    const main = el("div", "pf-lite-main", sheet);
    ui.prev = button("pf-lite-btn pf-lite-prev", "Poprzedni", "prev", main);
    ui.play = button("pf-lite-play pf-lite-play-big", "Odtwórz", "play", main);
    ui.next = button("pf-lite-btn pf-lite-next", "Następny", "next", main);

    const sub = el("div", "pf-lite-sub", sheet);
    ui.shuffle = button("pf-lite-btn pf-lite-small", "Losowo", "shuffle", sub);
    ui.repeat = button("pf-lite-btn pf-lite-small pf-lite-repeat", "Powtarzanie", "repeat", sub);
    el("b", "pf-lite-one", ui.repeat, "1");
    ui.like = button("pf-lite-btn pf-lite-small pf-lite-like", "Polub utwór", "heart", sub);
    ui.queue = button("pf-lite-btn pf-lite-small", "Kolejka", "queue", sub);

    if (!iOS) {
      const vol = el("label", "pf-lite-vol", sheet);
      el("span", "", vol, "Głośność");
      ui.vol = el("input", "", vol);
      ui.vol.type = "range"; ui.vol.min = "0"; ui.vol.max = "100"; ui.vol.step = "1"; ui.vol.setAttribute("aria-label", "Głośność");
      ui.vol.addEventListener("input", () => { const a = audioEl(); if (a) a.volume = Number(ui.vol.value) / 100; });
    }
    const off = el("button", "pf-lite-off", sheet, "Wyłącz tryb Lite (pełna wersja)");
    off.type = "button";
    off.addEventListener("click", () => setLite(false));

    // sterowanie
    const toggle = () => { const a = audioEl(); if (!a) return; if (a.paused) { const p = a.play(); if (p && p.catch) p.catch(() => {}); } else a.pause(); };
    ui.play.addEventListener("click", toggle);
    ui.barPlay.addEventListener("click", toggle);
    ui.prev.addEventListener("click", () => { const c = nativeControl(".prev-audio"); if (c) c.click(); });
    ui.next.addEventListener("click", () => { const c = nativeControl(".next-audio"); if (c) c.click(); });
    ui.queue.addEventListener("click", () => { const c = nativeControl(".audio-lists-btn"); if (c) { setOpen(false); c.click(); } });
    ui.shuffle.addEventListener("click", () => setMode(core.toggleShuffle((playerState() || {}).mode)));
    ui.repeat.addEventListener("click", () => setMode(core.nextRepeat((playerState() || {}).mode)));
    ui.like.addEventListener("click", toggleLike);
    bindSlider(slider);
    document.addEventListener("keydown", (event) => { if (open && event.key === "Escape") setOpen(false); });
    return wrap;
  }

  function setMode(mode) {
    const s = store();
    if (!s) return;
    try { s.dispatch({ type: "PLAYER_SET_MODE", data: { mode } }); } catch (e) { /* tryb zostaje bez zmiany */ }
    paintModes();
  }

  function setOpen(value) {
    open = value;
    ui.wrap.setAttribute("data-open", value ? "1" : "0");
    root.toggleAttribute("data-pf-lite-open", value);
    if (value) { syncMeta(true); paintAll(); } else if (ui.expand) ui.expand.focus();
  }

  function bindSlider(slider) {
    const at = (event) => { const r = slider.getBoundingClientRect(); return core.fractionAt(event.clientX, r.left, r.width); };
    const move = (event) => { const f = at(event); paintProgress(f * (audioEl() ? audioEl().duration || 0 : 0), audioEl() ? audioEl().duration : 0); };
    slider.addEventListener("pointerdown", (event) => {
      seeking = true;
      try { slider.setPointerCapture(event.pointerId); } catch (e) { /* bez przechwytywania działa na ekranie */ }
      move(event);
      event.preventDefault();
    });
    slider.addEventListener("pointermove", (event) => { if (seeking) move(event); });
    const finish = (event) => {
      if (!seeking) return;
      seeking = false;
      const a = audioEl();
      if (a && Number.isFinite(a.duration) && a.duration > 0) a.currentTime = at(event) * a.duration;
    };
    slider.addEventListener("pointerup", finish);
    slider.addEventListener("pointercancel", () => { seeking = false; paintAll(); });
    slider.addEventListener("keydown", (event) => {
      const a = audioEl();
      if (!a) return;
      const step = event.key === "ArrowRight" || event.key === "ArrowUp" ? 5 : event.key === "ArrowLeft" || event.key === "ArrowDown" ? -5 : 0;
      if (step) { a.currentTime = Math.max(0, Math.min(a.duration || 0, a.currentTime + step)); event.preventDefault(); }
    });
  }

  // ── stan ────────────────────────────────────────────────────────────────
  function current() {
    const p = playerState();
    return p && p.current ? p.current : null;
  }

  function coverUrl(size) {
    const c = current();
    return c && c.cover ? core.withCoverSize(String(c.cover), size) : "";
  }

  function paintCover(node, size) {
    const url = coverUrl(size);
    const value = url ? 'url("' + url.replace(/"/g, "%22") + '")' : "";
    if (node.dataset.src !== value) { node.dataset.src = value; node.style.backgroundImage = value; }
  }

  function syncMeta(force) {
    const c = current();
    const uuid = c ? String(c.uuid || c.name || "") : "";
    if (!force && uuid === lastUuid) return;
    lastUuid = uuid;
    const title = c ? (c.song && c.song.title) || c.name || "" : "";
    const artist = c ? (c.song && c.song.artist) || c.singer || "" : "";
    [ui.title, ui.barTitle].forEach((n) => { n.textContent = title; });
    [ui.artist, ui.barArtist].forEach((n) => { n.textContent = artist; });
    paintCover(ui.barCover, 96);
    if (open || force) paintCover(ui.art, 360);
    ui.wrap.hidden = !c && !(audioEl() && audioEl().src);
    root.toggleAttribute("data-pf-lite-active", !ui.wrap.hidden);
    // polubienie: stan z rekordu, potem dopytanie serwera (raz na utwór)
    const id = c && c.song && c.song.id ? String(c.song.id) : "";
    if (id && id !== likedFor) {
      likedFor = id;
      liked = !!(c.song && c.song.starred);
      paintLike();
      subsonic("getSong", { id }).then((r) => { if (likedFor === id && r.song) { liked = !!r.song.starred; paintLike(); } });
    }
  }

  function toggleLike() {
    const c = current();
    const id = c && c.song && c.song.id;
    if (!id) return;
    liked = !liked;
    paintLike();
    subsonic(liked ? "star" : "unstar", { id }).then((r) => { if (r.status && r.status !== "ok") { liked = !liked; paintLike(); } });
  }

  function paintLike() {
    ui.like.setAttribute("aria-pressed", liked ? "true" : "false");
    ui.like.setAttribute("aria-label", liked ? "Usuń z polubionych" : "Polub utwór");
    ui.like.classList.toggle("pf-on", liked);
  }

  function paintModes() {
    const p = playerState() || {};
    const rep = core.repeatState(p.mode);
    ui.shuffle.classList.toggle("pf-on", core.shuffleOn(p.mode));
    ui.shuffle.setAttribute("aria-pressed", core.shuffleOn(p.mode) ? "true" : "false");
    ui.repeat.classList.toggle("pf-on", rep.on);
    ui.repeat.classList.toggle("pf-one", rep.one);
    ui.repeat.setAttribute("aria-pressed", rep.on ? "true" : "false");
    ui.repeat.setAttribute("aria-label", core.repeatLabel(p.mode));
  }

  function paintPlaying() {
    const a = audioEl();
    const playing = !!a && !a.paused && !a.ended;
    [ui.play, ui.barPlay].forEach((b) => {
      const path = b.querySelector("path");
      if (path) { path.setAttribute("d", playing ? ICON.pause : ICON.play); }
      b.setAttribute("aria-label", playing ? "Pauza" : "Odtwórz");
    });
  }

  function paintProgress(position, duration) {
    const f = core.progress(position, duration);
    ui.fill.style.transform = "scaleX(" + f.toFixed(4) + ")";
    ui.slider.style.setProperty("--p", f.toFixed(4));
    ui.barProgress.style.transform = "scaleX(" + f.toFixed(4) + ")";
    ui.cur.textContent = core.formatTime(position);
    ui.dur.textContent = Number.isFinite(duration) && duration > 0 ? "−" + core.formatTime(duration - position) : "0:00";
    ui.slider.setAttribute("aria-valuemax", String(Math.round(Number.isFinite(duration) ? duration : 0)));
    ui.slider.setAttribute("aria-valuenow", String(Math.round(position || 0)));
    ui.slider.setAttribute("aria-valuetext", core.formatTime(position));
  }

  function paintAll() {
    const a = audioEl();
    paintPlaying();
    paintModes();
    if (!seeking) paintProgress(a ? a.currentTime : 0, a ? a.duration : 0);
    if (ui.vol && a) ui.vol.value = String(Math.round((a.volume == null ? 1 : a.volume) * 100));
  }

  const MEDIA = ["play", "playing", "pause", "ended", "emptied", "durationchange", "loadedmetadata", "volumechange", "seeked", "loadstart"];

  function listen() {
    let last = 0;
    document.addEventListener("timeupdate", (event) => {
      if (!(event.target && event.target.tagName === "AUDIO")) return;
      const now = Date.now();
      if (now - last < 450 || seeking) return;   // dwa razy na sekundę wystarczy; mniej pracy dla słabego procesora
      last = now;
      syncMeta(false);
      const a = event.target;
      paintProgress(a.currentTime, a.duration);
    }, true);
    MEDIA.forEach((name) => document.addEventListener(name, (event) => {
      if (!(event.target && event.target.tagName === "AUDIO")) return;
      syncMeta(false);
      paintAll();
    }, true));
    subscribe();
  }

  let subscribed = false;
  function subscribe() {
    const s = store();
    if (subscribed || !s || !s.subscribe) return;
    try { s.subscribe(() => { syncMeta(false); paintModes(); }); subscribed = true; } catch (e) { /* zdarzenia audio wystarczą */ }
  }

  // ── strona startowa: mniej półek ────────────────────────────────────────
  function trimHome(box) {
    const limit = core.homeLimits(window.innerWidth);
    const sections = Array.prototype.slice.call(box.querySelectorAll(".pf-sec"));
    sections.slice(limit.shelves).forEach((section) => section.remove());
    box.querySelectorAll(".pf-row").forEach((row) => {
      Array.prototype.slice.call(row.children, limit.perShelf).forEach((card) => card.remove());
    });
    box.querySelectorAll("img").forEach((img) => { img.loading = "lazy"; img.decoding = "async"; });
  }

  function start() {
    try {
      build();
      listen();
      syncMeta(true);
      paintAll();
      root.setAttribute("data-pf-lite-player", "1");
      window.PiterfyHomeExtras = window.PiterfyHomeExtras || [];
      window.PiterfyHomeExtras.push((box) => { try { trimHome(box); } catch (e) { /* strona zostaje pełna */ } });
      // gdy store pojawi się później (library.js ładuje się asynchronicznie), spróbuj jeszcze raz
      let tries = 0;
      const wait = setInterval(() => {
        tries += 1;
        if (store() || tries > 40) {
          clearInterval(wait);
          if (store()) { subscribe(); syncMeta(true); paintAll(); }
        }
      }, 500);
    } catch (e) {
      root.removeAttribute("data-pf-lite-player");  // zostaje natywny panel (powiększony przez lite.css)
      if (window.console) console.warn("Piterfy Lite: odtwarzacz się nie zbudował", e);
    }
  }

  window.PiterfyLite = { on: true, setLite, setOpen: (v) => ui.wrap && setOpen(v), refresh: () => { syncMeta(true); paintAll(); } };
  if (document.body) start(); else document.addEventListener("DOMContentLoaded", start);
})();
