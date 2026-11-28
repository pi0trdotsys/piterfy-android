/* Piterfy — strona startowa zależna od kontekstu: półka „Kontynuuj”, powitanie i kolejność półek wg pory dnia.
 * Półka pokazuje ostatnio słuchane odcinki (z pierścieniem postępu), albumy i kolejkę — od miejsca, w którym skończyłeś. Źródła:
 * lokalna historia (pf-resume-<login>, zapisywana co kilka sekund z odtwarzacza), zakładki Subsonic (getBookmarks) oraz kolejka
 * zapisana na serwerze przez inne urządzenie, np. telefon (getPlayQueue). Start robi PiterfyLibrary.play(utwory, indeks, sekunda).
 * Rano wyżej idą spokojniejsze miksy, wieczorem ciemniejsze i radio. Dokłada się przez window.PiterfyHomeExtras (hak z piterfy.js);
 * logika jest w continue-core.js (testy: continue-core.test.cjs). Testowo godzinę można podmienić: window.__pfHour = 21. */
(() => {
  "use strict";
  if (window.__pfContinueBooted) return;
  const core = window.PiterfyContinueCore;
  if (!core) return;
  window.__pfContinueBooted = true;

  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const user = () => { try { return localStorage.getItem("username") || ""; } catch (e) { return ""; } };
  const KEY = () => "pf-resume-" + user();
  const hourNow = () => (typeof window.__pfHour === "number" ? window.__pfHour : new Date().getHours());

  const params = (extra) => new URLSearchParams(Object.assign({
    u: user(), t: localStorage.getItem("subsonic-token") || "", s: localStorage.getItem("subsonic-salt") || "", v: "1.16.1", c: "Piterfy", f: "json",
  }, extra));
  const cover = (id, size) => (id ? "/rest/getCoverArt?" + params({ id, size: size || 300 }) : "");
  async function rest(command, extra) {
    const response = await fetch("/rest/" + command + "?" + params(extra), { cache: "no-store", signal: AbortSignal.timeout(8000) });
    const result = ((await response.json())["subsonic-response"]) || {};
    if (result.status !== "ok") throw new Error("Subsonic " + command);
    return result;
  }
  const list = (v) => (Array.isArray(v) ? v : v ? [v] : []);
  const isPodcast = (song) => !!(window.PiterfyMediaKinds && window.PiterfyMediaKinds.isPodcast(song));

  // ── lokalna historia ──
  function readStore() {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY()) || "{}");
      return { list: Array.isArray(raw.list) ? raw.list : [], hidden: raw.hidden && typeof raw.hidden === "object" ? raw.hidden : {} };
    } catch (e) { return { list: [], hidden: {} }; }
  }
  function writeStore(store) {
    try { localStorage.setItem(KEY(), JSON.stringify(store)); } catch (e) { /* bez zapisu działa do odświeżenia */ }
  }

  // ── odtwarzacz: migawka tak jak w connect.js (kolejka z magazynu Redux + element audio) ──
  let storeCache = null;
  function findStore() {
    if (storeCache) return storeCache;
    const rootEl = document.getElementById("root");
    if (!rootEl) return null;
    const key = Object.keys(rootEl).find((k) => k.startsWith("__reactContainer$"));
    const legacy = rootEl._reactRootContainer;
    const stack = [legacy ? (legacy._internalRoot || legacy).current : key && rootEl[key]];
    for (let n = 0; stack.length && n < 20000; n++) {
      const fiber = stack.pop();
      if (!fiber) continue;
      const candidate = fiber.memoizedProps && fiber.memoizedProps.store;
      if (candidate && candidate.getState && candidate.getState().player && candidate.dispatch) return (storeCache = candidate);
      if (fiber.child) stack.push(fiber.child);
      if (fiber.sibling) stack.push(fiber.sibling);
    }
    return null;
  }
  const audioEl = () => document.querySelector("audio.music-player-audio, .react-jinke-music-player-main audio");

  function snapshot() {
    const remote = window.PiterfyPlayback && window.PiterfyPlayback.current && window.PiterfyPlayback.current();
    if (remote && remote.remote) return null;   // gra inne urządzenie (Connect) — nie nadpisujemy historii
    const store = findStore();
    const player = store && store.getState().player;
    const audio = audioEl();
    if (!player || !audio || !player.queue || player.queue.some((x) => x.isRadio)) return null;
    const items = player.queue.map((x) => ({
      id: x.trackId, title: (x.song && x.song.title) || x.name || "", artist: (x.song && x.song.artist) || x.singer || "", album: (x.song && x.song.album) || "",
      albumId: (x.song && x.song.albumId) || "", duration: Number((x.song && x.song.duration) || x.duration || 0), type: isPodcast(x.song) ? "podcast" : "music",
    }));
    const index = player.queue.findIndex((x) => x.uuid === (player.current && player.current.uuid));
    if (index < 0) return null;
    return { items, index, position: Math.round((audio.currentTime || 0) * 1000), playing: !audio.paused && !audio.ended };
  }

  let lastWrite = { key: "", position: -1, at: 0 };
  function record(force) {
    const snap = snapshot();
    if (!snap) return;
    const entry = core.entryFromSnapshot(snap, Date.now());
    if (!entry || (entry.position < 3 && entry.index === 0 && !force)) return;
    const moved = entry.key !== lastWrite.key || Math.abs(entry.position - lastWrite.position) >= 1;
    if (!moved || (!snap.playing && !force && entry.key === lastWrite.key)) return;
    const store = readStore();
    store.list = core.upsert(core.prune(store.list, Date.now()), entry, core.MAX_ENTRIES);
    delete store.hidden[entry.key];
    writeStore(store);
    lastWrite = { key: entry.key, position: entry.position, at: Date.now() };
    schedulePaint();
  }
  setInterval(() => { if (!document.hidden) record(false); }, 5000);
  document.addEventListener("pause", () => setTimeout(() => record(true), 200), true);
  document.addEventListener("visibilitychange", () => { if (document.hidden) record(true); });
  window.addEventListener("pagehide", () => record(true));

  // ── źródła zdalne (zakładki, kolejka z innego urządzenia) ──
  const remoteState = { entries: [], at: 0, loading: false };
  async function loadRemote() {
    if (remoteState.loading || Date.now() - remoteState.at < 60000) return false;
    remoteState.loading = true;
    try {
      const [bookmarks, queue] = await Promise.all([
        rest("getBookmarks").then((r) => list(r.bookmarks && r.bookmarks.bookmark), () => []),
        rest("getPlayQueue").then((r) => r.playQueue || null, () => null),
      ]);
      const entries = bookmarks.map((b) => core.entryFromBookmark(b, isPodcast)).filter(Boolean);
      const fromQueue = core.entryFromPlayQueue(queue);
      if (fromQueue && queue.changedBy !== "Piterfy") entries.push(fromQueue);   // własna kolejka i tak jest w lokalnej historii
      const changed = JSON.stringify(entries) !== JSON.stringify(remoteState.entries);
      remoteState.entries = entries;
      return changed;
    } finally {
      remoteState.at = Date.now();
      remoteState.loading = false;
    }
  }

  function currentEntries() {
    const store = readStore();
    const merged = core.merge(store.list, remoteState.entries, Date.now(), 8);
    return merged.filter((e) => !(store.hidden[e.key] >= e.at));
  }

  // ── rysowanie ──
  const PLAY = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"></path></svg>';
  const RADIO = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2"/></svg>';

  function cardHtml(entry) {
    const p = core.progress(entry);
    const ring = core.ringDash(p, 15);
    const left = core.remainingLabel(entry);
    const sub = entry.kind === "episode" ? [entry.sub, left].filter(Boolean).join(" · ") : entry.sub;
    const badge = '<span class="pf-ct-ring" aria-hidden="true"><svg viewBox="0 0 36 36"><circle class="pf-ct-ring-bg" cx="18" cy="18" r="15"/>' +
      (p > 0 ? '<circle class="pf-ct-ring-fg" cx="18" cy="18" r="15" stroke-dasharray="' + ring.circumference + '" stroke-dashoffset="' + ring.offset + '" transform="rotate(-90 18 18)"/>' : "") +
      "</svg>" + PLAY + "</span>";
    const kicker = { episode: "Odcinek", album: "Album", queue: entry.source === "queue" ? "Z innego urządzenia" : "Kolejka" }[entry.kind] || "";
    return '<div class="pf-card pf-ct-card" role="button" tabindex="0" data-ct-key="' + esc(entry.key) + '" aria-label="Kontynuuj: ' + esc(entry.title) + '">' +
      '<span class="pf-card-art"><img src="' + esc(cover(entry.coverId)) + '" alt="" loading="lazy" onerror="this.remove()">' + badge +
      '<span class="pf-ct-kicker">' + esc(kicker) + "</span>" +
      '<button type="button" class="pf-ct-remove" data-ct-remove="' + esc(entry.key) + '" aria-label="Ukryj: ' + esc(entry.title) + '" title="Ukryj z tej listy">×</button></span>' +
      '<span class="pf-card-title">' + esc(entry.title) + '</span><span class="pf-card-sub">' + esc(sub) + "</span></div>";
  }

  function radioCardHtml(entry) {
    return '<div class="pf-card pf-ct-card pf-ct-radio" role="button" tabindex="0" data-ct-radio="' + esc((entry.items || [])[entry.index || 0]) + '" aria-label="Radio z: ' + esc(entry.title) + '">' +
      '<span class="pf-card-art"><span class="pf-ct-radio-art">' + RADIO + '</span><span class="pf-ct-kicker">Radio</span></span>' +
      '<span class="pf-card-title">Radio z ostatniego utworu</span><span class="pf-card-sub">' + esc(entry.kind === "queue" ? entry.sub : entry.title) + "</span></div>";
  }

  function shelfHtml(entries, per) {
    if (!entries.length) return "";
    const cards = entries.map(cardHtml);
    if (window.PiterfyRadio && window.PiterfyRadio.start) {
      const radio = radioCardHtml(entries[0]);
      cards.splice(per === "evening" || per === "night" ? Math.min(1, cards.length) : cards.length, 0, radio);   // wieczorem radio zaraz za pierwszą pozycją
    }
    return '<section class="pf-sec pf-ct" data-pf-ct><div class="pf-sec-head"><div><span class="pf-sec-kicker">Tam, gdzie skończyłeś</span><h2>Kontynuuj</h2></div></div>' +
      '<div class="pf-row">' + cards.join("") + "</div></section>";
  }

  const title = (section) => ((section.querySelector("h2") || {}).textContent || "").trim();

  /** Powitanie, miksy i półki wg pory dnia; półka „Kontynuuj” pierwsza. Zmienia tylko kolejność w istniejącym DOM. */
  function arrange(box) {
    const hour = hourNow();
    const greeting = core.greeting(hour);
    const hello = box.querySelector(".pf-hello");
    if (hello && !hello.classList.contains("pf-skeleton")) {
      if (hello.textContent !== greeting.text) hello.textContent = greeting.text;
      let sub = box.querySelector(".pf-hello-sub");
      if (!sub) { sub = document.createElement("p"); sub.className = "pf-hello-sub"; hello.insertAdjacentElement("afterend", sub); }
      if (sub.textContent !== greeting.sub) sub.textContent = greeting.sub;
    }
    // miksy: karty w rzędzie „Twoje miksy” wg wag pory dnia
    const mixes = Array.from(box.querySelectorAll(".pf-sec")).find((s) => title(s) === "Twoje miksy");
    const row = mixes && mixes.querySelector(".pf-row");
    if (row) {
      const cards = Array.from(row.children);
      const names = cards.map((c) => ((c.querySelector(".pf-card-title") || {}).textContent || ""));
      const order = core.orderMixes(names, greeting.period);
      if (order.join("\n") !== names.join("\n")) {
        const used = new Set();
        order.forEach((name) => { const i = names.findIndex((n, idx) => n === name && !used.has(idx)); used.add(i); row.appendChild(cards[i]); });
      }
    }
    // półki: ta sama liczba „miejsc” w DOM (baner „Mój gust” zostaje na swoim), zmienia się tylko to, co w nich leży
    const sections = Array.from(box.children).filter((n) => n.classList && n.classList.contains("pf-sec"));
    const titles = sections.map(title);
    const wanted = core.orderSections(titles, greeting.period);
    if (wanted.join("\n") !== titles.join("\n")) {
      const slots = sections.map((s) => { const marker = document.createComment("pf-slot"); s.replaceWith(marker); return marker; });
      const used = new Set();
      wanted.forEach((name, slot) => {
        const i = titles.findIndex((t, idx) => t === name && !used.has(idx)); used.add(i);
        slots[slot].replaceWith(sections[i]);
      });
    }
  }

  function paint(box) {
    if (!box || !box.isConnected) return;
    const per = core.period(hourNow());
    const html = shelfHtml(currentEntries(), per);
    const old = box.querySelector(".pf-ct");
    if (old) old.remove();
    if (html) {
      const holder = document.createElement("div");
      holder.innerHTML = html;
      const shelf = holder.firstChild;
      const first = Array.from(box.children).find((n) => n.classList && n.classList.contains("pf-sec"));
      if (first) box.insertBefore(shelf, first);
      else box.appendChild(shelf);
    }
    arrange(box);
  }

  let paintTimer = 0;
  function schedulePaint() {
    clearTimeout(paintTimer);
    paintTimer = setTimeout(() => document.querySelectorAll('.pf-home[data-pf-page="start"]').forEach(paint), 300);
  }

  // ── akcje ──
  function toast(message) {
    const node = document.createElement("div");
    node.className = "pf-toast"; node.setAttribute("role", "status"); node.textContent = message;
    document.body.appendChild(node);
    setTimeout(() => node.classList.add("pf-toast-out"), 3700);
    setTimeout(() => node.remove(), 4000);
  }

  async function resume(entry) {
    try {
      if (!window.PiterfyLibrary || !window.PiterfyLibrary.play) throw new Error("Odtwarzacz nie jest jeszcze gotowy — spróbuj za chwilę.");
      const index = Math.min(Math.max(0, entry.index || 0), entry.items.length - 1);
      await window.PiterfyLibrary.play(entry.items.map((id) => ({ id })), index, entry.position || 0);
      if (window.PiterfyFeel && window.PiterfyFeel.tap) window.PiterfyFeel.tap();
    } catch (error) {
      toast(error && error.message ? error.message : "Nie udało się wznowić odtwarzania.");
    }
  }

  document.addEventListener("click", (event) => {
    const target = event.target;
    if (!target || !target.closest) return;
    const remove = target.closest("[data-ct-remove]");
    if (remove) {
      event.preventDefault(); event.stopPropagation();
      const key = remove.getAttribute("data-ct-remove");
      const store = readStore();
      store.list = store.list.filter((e) => e.key !== key);
      store.hidden[key] = Date.now();
      writeStore(store);
      schedulePaint();
      return;
    }
    const radio = target.closest("[data-ct-radio]");
    if (radio) { if (window.PiterfyRadio) window.PiterfyRadio.start({ songId: radio.getAttribute("data-ct-radio") }); return; }
    const card = target.closest(".pf-ct-card[data-ct-key]");
    if (!card) return;
    const entry = currentEntries().find((e) => e.key === card.getAttribute("data-ct-key"));
    if (entry) resume(entry);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const card = event.target && event.target.closest && event.target.closest(".pf-ct-card");
    if (card && event.target === card) { event.preventDefault(); card.click(); }
  });

  // ── CSS ──
  const style = document.createElement("style");
  style.id = "pf-continue-css";
  style.textContent =
    ".pf-hello-sub{margin:-8px 0 0;color:var(--pf-h-muted,#b3b3b3);font:500 14px/1.3 Figtree,system-ui,sans-serif}" +
    ".pf-ct-card{cursor:pointer;position:relative}.pf-ct-card:focus-visible{outline:2px solid var(--pf-h-green,#1db954);outline-offset:2px}" +
    ".pf-ct .pf-card-art{background:var(--pf-ph,#282828)}" +
    ".pf-ct-kicker{position:absolute;left:8px;top:8px;padding:2px 8px;border-radius:999px;background:rgba(0,0,0,.62);color:#fff;font:800 10px/1.5 Figtree,system-ui,sans-serif;" +
    "letter-spacing:.06em;text-transform:uppercase;backdrop-filter:blur(6px)}" +
    ".pf-ct-ring{position:absolute;right:8px;bottom:8px;width:40px;height:40px;display:grid;place-items:center;border-radius:50%;background:rgba(0,0,0,.66);color:#fff;" +
    "backdrop-filter:blur(6px);transition:transform var(--pf-dur-fast,160ms) var(--pf-ease-spring,cubic-bezier(.34,1.56,.64,1))}" +
    ".pf-ct-ring svg{position:absolute;inset:2px;width:36px;height:36px;fill:none;stroke-width:3}" +
    ".pf-ct-ring svg:last-child{position:static;width:18px;height:18px;fill:currentColor}" +
    ".pf-ct-ring-bg{stroke:rgba(255,255,255,.22)}.pf-ct-ring-fg{stroke:var(--pf-h-green,#1db954);stroke-linecap:round}" +
    ".pf-ct-card:hover .pf-ct-ring{transform:scale(1.1)}" +
    ".pf-ct-remove{position:absolute;right:6px;top:6px;width:26px;height:26px;border:0;border-radius:50%;background:rgba(0,0,0,.62);color:#fff;font:700 16px/1 system-ui;cursor:pointer;" +
    "opacity:0;transition:opacity var(--pf-dur-fast,160ms)}.pf-ct-card:hover .pf-ct-remove,.pf-ct-remove:focus-visible{opacity:1}" +
    "@media(hover:none){.pf-ct-remove{opacity:.85}}" +
    ".pf-ct-radio-art{position:absolute;inset:0;display:grid;place-items:center;background:linear-gradient(135deg,#7b2ff7,#1db954);color:#fff}.pf-ct-radio-art svg{width:42%;height:42%}";
  document.head.appendChild(style);

  // ── hak strony startowej ──
  window.PiterfyHomeExtras = window.PiterfyHomeExtras || [];
  window.PiterfyHomeExtras.push((box) => {
    paint(box);
    loadRemote().then((changed) => { if (changed) paint(box); }, () => { /* bez zdalnych źródeł zostaje historia lokalna */ });
  });
  const home = document.querySelector('.pf-home[data-pf-page="start"]');   // strona mogła się narysować przed załadowaniem skryptu
  if (home && home.querySelector(".pf-sec")) { paint(home); loadRemote().then((changed) => { if (changed) paint(home); }, () => {}); }

  window.PiterfyContinue = { entries: currentEntries, remote: () => remoteState.entries.slice(), reload: () => { remoteState.at = 0; return loadRemote(); }, record, hide: (key) => { const s = readStore(); s.hidden[key] = Date.now(); writeStore(s); schedulePaint(); } };
})();
