/* Piterfy — strona wykonawcy (#/artist/<id>/show): nagłówek z gradientem z koloru okładki, paralaksa i zwijany pasek z nazwą,
 * „Popularne z Twojej biblioteki” oraz oś czasu dyskografii z oznaczonymi nowościami z radaru (GET /piterfy-api/radar).
 * Nagłówek (karta z klasą pf-hero) robi już piterfy.js (heroCover); tu dokładamy atrybuty, kolory, przewijanie i dwa bloki pod nim
 * (pigułkę „Radio wykonawcy” dokłada radio.js tuż pod nagłówkiem — bloki lądują zaraz za nią). Logika: artist-core.js. */
(() => {
  "use strict";
  if (window.__pfArtistBooted) return;
  const core = window.PiterfyArtistCore;
  if (!core) return;
  window.__pfArtistBooted = true;

  const ROUTE = /^#\/artist\/([^/?]+)\/show/;
  const VERSION = ((document.currentScript && document.currentScript.src.match(/[?&]v=([^&]+)/)) || [])[1];
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const list = (v) => (Array.isArray(v) ? v : v ? [v] : []);
  const user = () => { try { return localStorage.getItem("username") || ""; } catch (e) { return ""; } };
  const isPodcast = (song) => !!(window.PiterfyMediaKinds && window.PiterfyMediaKinds.isPodcast(song));

  const params = (extra) => new URLSearchParams(Object.assign({
    u: user(), t: localStorage.getItem("subsonic-token") || "", s: localStorage.getItem("subsonic-salt") || "", v: "1.16.1", c: "Piterfy", f: "json",
  }, extra));
  const cover = (id, size) => (id ? "/rest/getCoverArt?" + params({ id, size: size || 300 }) : "");
  async function rest(command, extra) {
    const response = await fetch("/rest/" + command + "?" + params(extra), { signal: AbortSignal.timeout(10000) });
    const result = ((await response.json())["subsonic-response"]) || {};
    if (result.status !== "ok") throw new Error("Subsonic " + command);
    return result;
  }
  const headers = () => ({ "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") });
  const clock = (sec) => Math.floor(sec / 60) + ":" + String(Math.floor(sec % 60)).padStart(2, "0");

  if (!document.getElementById("pf-artist-css")) {
    const link = document.createElement("link");
    link.id = "pf-artist-css"; link.rel = "stylesheet";
    link.href = "/piterfy-ui/artist.css" + (VERSION ? "?v=" + VERSION : "");
    document.head.appendChild(link);
  }

  function toast(message) {
    const node = document.createElement("div");
    node.className = "pf-toast"; node.setAttribute("role", "status"); node.textContent = message;
    document.body.appendChild(node);
    setTimeout(() => node.classList.add("pf-toast-out"), 3700);
    setTimeout(() => node.remove(), 4000);
  }

  // ── dane ──
  const cache = new Map();   // id → { at, promise }
  async function fetchData(id) {
    const artist = ((await rest("getArtist", { id })).artist) || {};
    const albums = list(artist.album);
    const name = artist.name || "";
    const radarP = fetch("/piterfy-api/radar", { headers: headers(), cache: "no-store", signal: AbortSignal.timeout(8000) })
      .then((r) => (r.ok ? r.json() : {}), () => ({})).catch(() => ({}));
    const topP = name ? rest("getTopSongs", { artist: name, count: 20 }).then((r) => list(r.topSongs && r.topSongs.song), () => []) : Promise.resolve([]);
    const [radar, top] = await Promise.all([radarP, topP]);
    let popular = core.rankPopular(top, 5, isPodcast);
    if (popular.length < 3) {   // bez Last.fm: własne odsłuchania z najczęściej słuchanych albumów
      const picks = core.fallbackAlbums(albums, 4);
      const songs = (await Promise.all(picks.map((a) => rest("getAlbum", { id: a.id }).then((r) => list(r.album && r.album.song), () => [])))).reduce((all, x) => all.concat(x), []);
      const own = core.rankPopular(songs, 5, isPodcast).filter((s) => Number(s.playCount) > 0);
      if (own.length > popular.length) popular = own;
    }
    return { id, name, albums, popular, radar: list(radar.albums), fill: radar.fill !== false };
  }
  function data(id) {
    const hit = cache.get(id);
    if (hit && Date.now() - hit.at < 120000) return hit.promise;
    const promise = fetchData(id);
    cache.set(id, { at: Date.now(), promise });
    promise.catch(() => cache.delete(id));
    return promise;
  }

  // ── bloki pod nagłówkiem ──
  const PLAY = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"></path></svg>';
  const RADIO = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2"/></svg>';
  const radarApi = () => window.PiterfyRadar || null;

  function popularHtml(d) {
    if (!d.popular.length) return "";
    const rows = d.popular.map((s, i) => '<button type="button" class="pf-ap-row" data-ap-play="' + i + '" aria-label="Odtwórz: ' + esc(s.title) + '">' +
      '<span class="pf-ap-n"><b>' + (i + 1) + "</b>" + PLAY + "</span>" +
      '<img src="' + esc(cover(s.coverArt || s.albumId || s.id, 80)) + '" alt="" loading="lazy" onerror="this.remove()">' +
      '<span class="pf-ap-text"><span class="pf-ap-title">' + esc(s.title) + '</span><span class="pf-ap-album">' + esc(s.album || "") + "</span></span>" +
      '<span class="pf-ap-meta">' + (Number(s.playCount) > 0 ? Number(s.playCount) + " ×" : "") + "</span>" +
      '<span class="pf-ap-time">' + (s.duration ? clock(s.duration) : "") + "</span></button>").join("");
    return '<section class="pf-ap-pop"><div class="pf-ap-head"><h2>Popularne z Twojej biblioteki</h2>' +
      '<button type="button" class="pf-ap-all" data-ap-all>' + PLAY + "<span>Odtwórz</span></button></div>" + rows + "</section>";
  }

  function newCard(item, fill) {
    const api = radarApi();
    const state = api ? api.actionState(item, fill) : { kind: item.status === "queued" ? "busy" : "new", disabled: item.status === "queued", label: item.status === "queued" ? "W kolejce…" : "Dociągnij" };
    const meta = [api ? api.typeLabel(item.type) : "Wydanie", api ? api.dateLabel(item.released, new Date()) : ""].filter(Boolean).join(" · ");
    return '<div class="pf-tl-card pf-tl-new" data-tl-radar="' + esc(item.id) + '"><span class="pf-tl-art">' +
      (item.cover ? '<img src="' + esc(item.cover) + '" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">' : "") +
      '<b class="pf-tl-badge">Nowość · nie masz</b></span><span class="pf-tl-title" title="' + esc(item.title) + '">' + esc(item.title) + "</span>" +
      '<span class="pf-tl-sub">' + esc(meta) + "</span>" +
      '<button type="button" class="pf-tl-fetch" data-kind="' + state.kind + '"' + (state.disabled ? " disabled" : "") + ">" + esc(state.label) + "</button></div>";
  }

  function albumCard(item) {
    return '<a class="pf-tl-card" href="#/album/' + esc(item.id) + '/show"><span class="pf-tl-art">' +
      '<img src="' + esc(cover(item.coverId, 240)) + '" alt="" loading="lazy" onerror="this.remove()">' +
      (item.badge ? '<b class="pf-tl-badge">' + esc(item.badge) + "</b>" : "") + '</span><span class="pf-tl-title" title="' + esc(item.title) + '">' + esc(item.title) +
      '</span><span class="pf-tl-sub">' + esc(item.sub) + "</span></a>";
  }

  function timelineHtml(d) {
    const groups = core.timeline(d.albums, d.radar, d.name, Date.now());
    if (!core.worthShowing(groups)) return "";
    const total = groups.reduce((sum, g) => sum + g.items.length, 0);
    return '<section class="pf-tl-sec"><div class="pf-ap-head"><h2>Dyskografia</h2><span class="pf-ap-count">' + total + " wydań</span></div>" +
      '<div class="pf-tl" role="list">' + groups.map((g) => '<div class="pf-tl-year" role="listitem"><span class="pf-tl-label">' + (g.year || "Inne") +
        '</span><div class="pf-tl-items">' + g.items.map((i) => (i.kind === "new" ? newCard(i, d.fill) : albumCard(i))).join("") + "</div></div>").join("") + "</div></section>";
  }

  // ── nagłówek: kolory, paralaksa, pasek ──
  const view = { id: "", card: null, holder: null, bar: null, data: null, raf: 0, pollTimer: 0, polls: 0 };
  const colours = window.PiterfySpeedCore ? window.PiterfySpeedCore.ColourCache(localStorage) : null;

  function applyColour(hex) {
    const main = document.getElementById("main-content");
    if (!hex || !main || !view.card) return;
    view.card.style.setProperty("--pf-artist-colour", hex);
    main.style.setProperty("--pf-tint", core.shade(hex, 0.62));
    main.style.setProperty("--pf-tint-2", core.shade(hex, 0.42));
    if (view.bar) view.bar.style.setProperty("--pf-artist-colour", hex);
  }

  function colourFromImage(img) {
    const core2 = window.PiterfySpeedCore;
    if (!core2 || !img) return;
    const key = "artist:" + view.id;
    const known = colours && colours.get(key);
    if (known) { applyColour(known); return; }
    const paint = () => {
      try {
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = 12;
        const context = canvas.getContext("2d", { willReadFrequently: true });
        context.drawImage(img, 0, 0, 12, 12);
        const average = core2.averageColour(context.getImageData(0, 0, 12, 12).data);
        if (!average) return;
        const hex = core2.toHex(average);
        if (colours) colours.set(key, hex);
        applyColour(hex);
      } catch (e) { /* obraz z innego źródła — zostaje domyślny gradient */ }
    };
    if (img.complete && img.naturalWidth) paint(); else img.addEventListener("load", paint, { once: true });
  }

  function scrollTop() {
    const main = document.getElementById("main-content");
    return Math.max(window.scrollY || 0, (document.scrollingElement && document.scrollingElement.scrollTop) || 0, main ? main.scrollTop : 0);
  }

  function update() {
    view.raf = 0;
    const card = view.card;
    if (!card || !card.isConnected) return;
    const y = scrollTop();
    card.style.setProperty("--pf-scroll", Math.min(y, 700) + "px");
    card.style.setProperty("--pf-collapse", String(core.collapse(y, card.offsetHeight)));
    const appbar = document.querySelector("header.MuiAppBar-root");
    const top = appbar ? appbar.getBoundingClientRect().bottom : 0;
    const main = document.getElementById("main-content");
    const bar = view.bar;
    if (bar && main) {
      const box = main.getBoundingClientRect();
      bar.style.top = top + "px"; bar.style.left = box.left + "px"; bar.style.width = box.width + "px";
      bar.classList.toggle("pf-on", core.showBar(card.getBoundingClientRect().bottom, top));
    }
  }
  const schedule = () => { if (!view.raf) view.raf = requestAnimationFrame(update); };
  document.addEventListener("scroll", schedule, { capture: true, passive: true });
  window.addEventListener("resize", schedule);

  function makeBar(name, img) {
    const bar = document.createElement("div");
    bar.className = "pf-artist-bar"; bar.setAttribute("aria-hidden", "false");
    bar.innerHTML = (img ? '<img src="' + esc(img) + '" alt="">' : "") + '<span class="pf-artist-bar-name">' + esc(name) + "</span>" +
      '<span class="pf-artist-bar-actions"><button type="button" data-bar-play>' + PLAY + "<span>Odtwórz</span></button>" +
      '<button type="button" data-bar-radio>' + RADIO + "<span>Radio</span></button>" +
      '<button type="button" data-bar-top aria-label="Na górę" title="Na górę">↑</button></span>';
    document.body.appendChild(bar);
    return bar;
  }

  function teardown() {
    cancelAnimationFrame(view.raf); clearTimeout(view.pollTimer);
    document.querySelectorAll(".pf-artist-extras, .pf-artist-bar").forEach((n) => n.remove());
    const main = document.getElementById("main-content");
    if (main) { main.style.removeProperty("--pf-tint"); main.style.removeProperty("--pf-tint-2"); }
    if (view.card && view.card.isConnected) {
      ["--pf-scroll", "--pf-collapse", "--pf-artist-colour"].forEach((n) => view.card.style.removeProperty(n));
      view.card.removeAttribute("data-pf-artist-hero");
    }
    Object.assign(view, { id: "", card: null, holder: null, bar: null, data: null, raf: 0, pollTimer: 0, polls: 0 });
  }

  function renderExtras() {
    if (!view.holder || !view.data) return;
    view.holder.innerHTML = popularHtml(view.data) + timelineHtml(view.data);
  }

  function place() {
    if (!view.holder || !view.card || !view.card.isConnected) return;
    if (view.holder.isConnected && view.holder.previousElementSibling && (view.holder.previousElementSibling === view.card || view.holder.previousElementSibling.classList.contains("pf-radio-row"))) return;
    const radio = document.querySelector('.pf-radio-row[data-pf-radio-for^="artist:"]');
    (radio || view.card).insertAdjacentElement("afterend", view.holder);
  }

  function pollRadar() {
    clearTimeout(view.pollTimer);
    if (!view.data || !view.data.radar.some((i) => i.status === "queued") || ++view.polls > 20) return;
    const id = view.id;
    view.pollTimer = setTimeout(async () => {
      try {
        const response = await fetch("/piterfy-api/radar", { headers: headers(), cache: "no-store", signal: AbortSignal.timeout(8000) });
        const fresh = response.ok ? await response.json() : null;
        if (fresh && view.id === id && view.data) { view.data.radar = list(fresh.albums); renderExtras(); }
      } catch (e) { /* spróbujemy za chwilę */ }
      pollRadar();
    }, 15000);
  }

  function begin(id, card) {
    teardown();
    Object.assign(view, { id, card });
    card.setAttribute("data-pf-artist-hero", "1");
    const img = card.querySelector("img");
    colourFromImage(img);
    const holder = document.createElement("div");
    holder.className = "pf-artist-extras"; holder.setAttribute("data-pf-artist-for", id);
    view.holder = holder;
    place();
    data(id).then((d) => {
      if (view.id !== id) return;
      view.data = d;
      const name = d.name || ((card.querySelector(".MuiTypography-h5, .MuiTypography-h6") || {}).textContent || "");
      view.bar = makeBar(name, img && img.currentSrc);
      colours && colours.get("artist:" + id) && applyColour(colours.get("artist:" + id));
      renderExtras(); place(); schedule(); pollRadar();
    }, () => { /* bez danych zostaje sam nagłówek Navidrome */ });
    schedule();
  }

  let timer = 0;
  function inject() {
    const match = ROUTE.exec(location.hash);
    if (!match) { if (view.id) teardown(); return; }
    const id = decodeURIComponent(match[1]);
    const card = document.querySelector("#main-content .MuiCard-root.pf-hero");
    if (!card) return;
    if (view.id !== id || view.card !== card) begin(id, card);
    else { place(); schedule(); }
  }
  const later = () => { clearTimeout(timer); timer = setTimeout(inject, 250); };
  new MutationObserver(later).observe(document.body, { childList: true, subtree: true });
  window.addEventListener("hashchange", later);
  later();

  // ── akcje ──
  async function play(songs, index) {
    try {
      if (!window.PiterfyLibrary || !window.PiterfyLibrary.play) throw new Error("Odtwarzacz nie jest jeszcze gotowy — spróbuj za chwilę.");
      await window.PiterfyLibrary.play(songs, index);
      if (window.PiterfyFeel && window.PiterfyFeel.tap) window.PiterfyFeel.tap();
    } catch (error) { toast(error && error.message ? error.message : "Nie udało się odtworzyć."); }
  }

  async function fetchAlbum(id) {
    const item = view.data && view.data.radar.find((i) => String(i.id) === String(id));
    if (!item || item.status === "queued" || item.status === "done") return;
    Object.assign(item, { status: "queued", note: "" });
    renderExtras();
    try {
      const response = await fetch("/piterfy-api/radar/fetch", {
        method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, headers()), body: JSON.stringify({ id: String(id) }), signal: AbortSignal.timeout(10000),
      });
      const result = await response.json().catch(() => ({}));
      if (response.ok && result.album) Object.assign(item, result.album);
      else Object.assign(item, { status: "failed", note: result.error || "Nie udało się wysłać prośby." });
    } catch (e) {
      Object.assign(item, { status: "failed", note: "Nie udało się wysłać prośby — sprawdź połączenie." });
    }
    renderExtras(); view.polls = 0; pollRadar();
  }

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (!target || !target.closest || !view.id) return;
    const row = target.closest("[data-ap-play]");
    if (row && view.data) { play(view.data.popular, Number(row.getAttribute("data-ap-play"))); return; }
    if (target.closest("[data-ap-all]") && view.data) { play(view.data.popular, 0); return; }
    if (target.closest("[data-bar-play]") && view.data && view.data.popular.length) { play(view.data.popular, 0); return; }
    if (target.closest("[data-bar-radio]")) { if (window.PiterfyRadio) window.PiterfyRadio.start({ artistId: view.id }); return; }
    if (target.closest("[data-bar-top]")) { window.scrollTo({ top: 0, behavior: "smooth" }); const m = document.getElementById("main-content"); if (m) m.scrollTo({ top: 0, behavior: "smooth" }); return; }
    const fetchButton = target.closest(".pf-tl-fetch");
    if (fetchButton && !fetchButton.disabled) {
      event.preventDefault();
      const card = fetchButton.closest("[data-tl-radar]");
      if (card) fetchAlbum(card.getAttribute("data-tl-radar"));
    }
  });
})();
