/* Piterfy — „Radio z utworu / wykonawcy”: nieskończona kolejka podobnych utworów z Twojej biblioteki.
 * Radio startuje z utworu (przycisk w pasku odtwarzacza i w oknie „Teraz gra”) albo z wykonawcy/albumu (pigułka pod nagłówkiem),
 * podmienia kolejkę na nasiono + podobne (Subsonic getSimilarSongs2, Navidrome zwraca tylko utwory z biblioteki), a gdy po bieżącym
 * zostają ≤3 utwory, dokłada kolejne (podobne do bieżącego i dwóch poprzednich) bez przerywania grania. Gdy użytkownik zacznie
 * grać coś innego, radio się wyłącza. Logika decyzji: radio-core.js (testy Node). */
(() => {
  "use strict";
  if (window.PiterfyRadio) return;
  const SELF = document.currentScript;
  const VERSION = ((SELF && SELF.src.match(/[?&]v=([^&]+)/)) || [])[1];

  function boot(core) {
    const state = { on: false, label: "", known: new Set(), changedAt: 0, busy: false, cooldownUntil: 0, lastTrack: "", token: 0 };
    const account = () => localStorage.getItem("username") || "";
    const MEMORY_KEY = "pf-radio-memory";
    const loadMemory = () => { try { return JSON.parse(localStorage.getItem(MEMORY_KEY) || "[]"); } catch (e) { return []; } };
    let memory = loadMemory();
    const saveMemory = () => { try { localStorage.setItem(MEMORY_KEY, JSON.stringify(memory)); } catch (e) { /* bez zapisu działa do odświeżenia */ } };
    const isPodcast = (song) => (window.PiterfyMediaKinds ? window.PiterfyMediaKinds.isPodcast(song) : core.podcastLike(song));

    // ── dostęp do odtwarzacza (jak w connect.js / library.js) ──
    let storeCache = null;
    function findStore() {
      if (storeCache) return storeCache;
      const root = document.getElementById("root");
      if (!root) return null;
      const key = Object.keys(root).find((k) => k.startsWith("__reactContainer$"));
      const legacy = root._reactRootContainer;
      const stack = [legacy ? (legacy._internalRoot || legacy).current : key && root[key]];
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

    async function subsonic(command, extra) {
      const params = new URLSearchParams(Object.assign({
        u: account(), t: localStorage.getItem("subsonic-token") || "", s: localStorage.getItem("subsonic-salt") || "",
        v: "1.16.1", c: "PiterfyRadio", f: "json",
      }, extra));
      const response = await fetch("/rest/" + command + "?" + params, { signal: AbortSignal.timeout(12000), cache: "no-store" });
      if (!response.ok) throw new Error("Nie można pobrać biblioteki.");
      const result = (await response.json())["subsonic-response"];
      if (!result || result.status !== "ok") throw new Error("Nie można pobrać biblioteki.");
      return result;
    }
    const list = (value) => (Array.isArray(value) ? value : value ? [value] : []);
    const similar = async (id, count) => list(((await subsonic("getSimilarSongs2", { id, count })).similarSongs2 || {}).song);
    const topSongs = async (artist, count) => list(((await subsonic("getTopSongs", { artist, count })).topSongs || {}).song);

    // ── drobne UI: toast (styl .pf-toast z piterfy.css), chip „Radio: …”, zdarzenie dla innych modułów ──
    function toast(message) {
      const node = document.createElement("div");
      node.className = "pf-toast"; node.setAttribute("role", "status"); node.textContent = message;
      document.body.appendChild(node);
      setTimeout(() => node.classList.add("pf-toast-out"), 3700);
      setTimeout(() => node.remove(), 4000);
    }
    const css = document.createElement("style");
    css.id = "pf-radio-css";
    css.textContent =
      "#pf-radio-chip{position:fixed;left:20px;bottom:215px;z-index:1250;max-width:min(280px,calc(100vw - 40px));display:inline-flex;gap:8px;align-items:center;" +
      "background:#181818;color:#fff;border:1px solid #1db954;border-radius:24px;padding:10px 14px;min-height:40px;font:600 13px system-ui;cursor:pointer;" +
      "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}#pf-radio-chip:hover{background:#242424}#pf-radio-chip[hidden]{display:none}" +
      "@media(max-width:600px){#pf-radio-chip{left:12px;bottom:312px}}" +
      ".pf-radio-row{margin:12px 16px 0;display:flex}.pf-radio-pill{display:inline-flex;gap:8px;align-items:center;min-height:40px;padding:8px 18px;border:0;border-radius:999px;" +
      "background:var(--pf-accent,#1db954);color:#04140a;font:700 14px system-ui;cursor:pointer;transition:transform .35s cubic-bezier(.34,1.56,.64,1)}" +
      ".pf-radio-pill:hover{transform:scale(1.04)}.pf-radio-pill:active{transform:scale(.96)}.pf-radio-pill svg,.pf-radio-btn svg{width:20px;height:20px;flex:none}" +
      ".pf-radio-btn{cursor:pointer;display:inline-flex;align-items:center}.pf-radio-btn[aria-pressed=true]{color:var(--pf-accent,#1db954)}";
    document.head.appendChild(css);
    const ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2.2" fill="currentColor"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2"/></svg>';

    let chip = null;
    function paintChip() {
      if (!state.on) { if (chip) { chip.remove(); chip = null; } return; }
      if (!chip) {
        chip = document.createElement("button");
        chip.id = "pf-radio-chip"; chip.type = "button";
        chip.addEventListener("click", () => stop("Radio wyłączone"));
        document.body.appendChild(chip);
      }
      chip.textContent = "◉ " + state.label + " ✕";
      chip.title = "Wyłącz radio";
      chip.setAttribute("aria-label", state.label + ". Wyłącz radio");
    }
    function changed() {
      paintChip();
      document.querySelectorAll(".pf-radio-btn").forEach((b) => b.setAttribute("aria-pressed", state.on ? "true" : "false"));
      window.dispatchEvent(new CustomEvent("pf-radio", { detail: { on: state.on, label: state.label } }));
    }

    // ── start i wyłączenie ──
    async function begin(label, seed, similarSongs) {
      const built = core.build(seed, similarSongs, { memory, isPodcast, rng: Math.random });
      if (built.length < 3) throw new Error("W bibliotece jest za mało podobnych utworów na radio.");
      const queue = built.slice(0, 25);
      if (!window.PiterfyLibrary || !window.PiterfyLibrary.play) throw new Error("Odtwarzacz nie jest jeszcze gotowy.");
      state.token += 1;
      await window.PiterfyLibrary.play(queue, 0);
      state.on = true; state.label = "Radio: " + label; state.cooldownUntil = 0;
      state.known = new Set(queue.map((s) => String(s.id)));
      state.changedAt = performance.now(); state.lastTrack = String(queue[0].id);
      memory = core.remember(memory, queue.map((s) => s.id)); saveMemory();
      changed();
      toast(state.label + " — dobieram podobne z Twojej biblioteki");
    }

    function stop(message) {
      if (!state.on) return;
      state.on = false; state.token += 1; state.known = new Set();
      changed();
      if (message) toast(message);
    }

    async function start(request) {
      try {
        const remote = window.PiterfyPlayback && window.PiterfyPlayback.current && window.PiterfyPlayback.current();
        if (remote && remote.remote) throw new Error("Najpierw wybierz „Przenieś tutaj” w Connect.");
        if (request.songId) {
          const [seedResult, others] = await Promise.all([subsonic("getSong", { id: request.songId }), similar(request.songId, 50)]);
          await begin(request.label || (seedResult.song && seedResult.song.artist) || "utwór", seedResult.song, others);
        } else if (request.artistId) {
          const name = request.artistName || ((await subsonic("getArtist", { id: request.artistId })).artist || {}).name || "";
          const [near, top] = await Promise.all([similar(request.artistId, 50).catch(() => []), name ? topSongs(name, 20).catch(() => []) : []]);
          const seed = top.find((s) => !isPodcast(s) && Number(s.userRating) !== 1) || null;
          await begin(request.label || name || "wykonawca", seed, core.interleave([top.slice(1), near]));
        } else if (request.albumId) {
          const album = (await subsonic("getAlbum", { id: request.albumId })).album || {};
          const seed = list(album.song).find((s) => !isPodcast(s));
          if (!seed) throw new Error("Ten album nie ma utworów na radio.");
          await begin(request.label || album.name || "album", seed, await similar(seed.id, 50));
        } else throw new Error("Nie wiem, od czego zacząć radio.");
      } catch (error) {
        toast(error && error.message ? error.message : "Nie udało się uruchomić radia.");
      }
    }

    function fromCurrent() {
      const now = window.PiterfyPlayback && window.PiterfyPlayback.current && window.PiterfyPlayback.current();
      const store = findStore();
      const song = store && store.getState().player.current && store.getState().player.current.song;
      const id = (now && now.id) || (song && (song.id || song.mediaFileId));
      if (!id) { toast("Najpierw włącz jakiś utwór."); return Promise.resolve(); }
      return start({ songId: id, label: (now && now.artist) || (song && song.artist) || "utwór" });
    }

    // ── nieskończoność: dokładanie kolejnych podobnych ──
    async function topUp(store, player, index) {
      const token = state.token;
      state.busy = true;
      try {
        const queue = player.queue;
        const inQueue = new Set(queue.map((q) => String(q.trackId)));
        const seeds = core.seedsForTopUp(queue.map((q) => q.trackId), index);
        const lists = await Promise.all(seeds.map((id) => similar(id, 25).catch(() => [])));
        const merged = core.interleave(lists);
        let fresh = core.clean(merged, { exclude: inQueue, memory, isPodcast });
        if (!fresh.length) fresh = core.clean(merged, { exclude: inQueue, isPodcast });  // mała biblioteka: dopuszczamy powtórki z pamięci
        if (token !== state.token || !state.on) return;
        if (!fresh.length) { state.cooldownUntil = performance.now() + 60000; toast("Radio nie znalazło więcej podobnych utworów"); return; }
        const last = queue[queue.length - 1];
        const batch = core.order(fresh, Math.random, core.artistKey(last && last.song)).slice(0, core.BATCH);
        const records = await window.PiterfyLibrary.prefetch(batch);
        if (token !== state.token || !state.on || !records.length) return;
        const data = {};
        records.forEach((r) => { data[r.id] = r; });
        store.dispatch({ type: "PLAYER_ADD_TRACKS", data });
        records.forEach((r) => state.known.add(String(r.id)));
        memory = core.remember(memory, records.map((r) => r.id)); saveMemory();
        state.changedAt = performance.now();
      } catch (error) {
        state.cooldownUntil = performance.now() + 15000;  // chwilowy błąd: spróbujemy za chwilę
      } finally {
        state.busy = false;
      }
    }

    function tick() {
      if (!state.on || document.hidden) return;
      const store = findStore();
      if (!store) return;
      const player = store.getState().player;
      const current = player.current;
      if (!current || !current.uuid) return;
      if (core.shouldStop(current.trackId, state.known, performance.now() - state.changedAt)) { stop("Radio wyłączone — gra coś innego"); return; }
      const trackId = String(current.trackId);
      if (!state.known.has(trackId)) return;  // gra obcy utwór (karencja przed wyłączeniem): nie dokładamy nic do cudzej kolejki
      if (trackId !== state.lastTrack) {  // zapamiętujemy zagrane, żeby się nie powtarzać
        state.lastTrack = trackId;
        memory = core.remember(memory, [trackId]); saveMemory();
      }
      const index = player.queue.findIndex((q) => q.uuid === current.uuid);
      if (!state.busy && performance.now() >= state.cooldownUntil && core.needMore(player.queue.length, index)) topUp(store, player, index);
    }
    setInterval(tick, 2500);
    ["play", "loadstart"].forEach((type) => document.addEventListener(type, () => setTimeout(tick, 400), true));

    // ── wejścia: przycisk w pasku odtwarzacza i pigułka pod nagłówkiem albumu/wykonawcy ──
    let injectTimer = 0;
    function inject() {
      const content = document.querySelector(".react-jinke-music-player-main .music-player-panel .player-content");
      if (content && !content.querySelector(".pf-radio-btn")) {
        const button = document.createElement("span");
        button.className = "group pf-radio-btn"; button.setAttribute("role", "button"); button.tabIndex = 0;
        button.setAttribute("aria-label", "Radio z tego utworu"); button.title = "Radio z tego utworu (podobne utwory bez końca)";
        button.setAttribute("aria-pressed", state.on ? "true" : "false");
        button.innerHTML = ICON;
        const run = (event) => { event.preventDefault(); event.stopPropagation(); if (state.on) stop("Radio wyłączone"); else fromCurrent(); };
        button.addEventListener("click", run);
        button.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") run(event); });
        content.appendChild(button);
      }
      const match = /^#\/(artist|album)\/([^/?]+)\/show/.exec(location.hash);
      const hero = match && document.querySelector("#main-content .pf-hero");
      const key = match ? match[1] + ":" + match[2] : "";
      document.querySelectorAll(".pf-radio-row").forEach((row) => { if (row.dataset.pfRadioFor !== key || !hero) row.remove(); });
      if (hero && !document.querySelector('.pf-radio-row[data-pf-radio-for="' + key + '"]')) {
        const row = document.createElement("div");
        row.className = "pf-radio-row"; row.dataset.pfRadioFor = key;
        const pill = document.createElement("button");
        pill.type = "button"; pill.className = "pf-radio-pill";
        pill.innerHTML = ICON + "<span>" + (match[1] === "artist" ? "Radio wykonawcy" : "Radio z albumu") + "</span>";
        pill.addEventListener("click", () => start(match[1] === "artist" ? { artistId: decodeURIComponent(match[2]) } : { albumId: decodeURIComponent(match[2]) }));
        row.appendChild(pill);
        hero.insertAdjacentElement("afterend", row);
      }
    }
    const schedule = () => { clearTimeout(injectTimer); injectTimer = setTimeout(inject, 250); };
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    window.addEventListener("hashchange", schedule);
    schedule();

    window.PiterfyRadio = { start, fromCurrent, stop: () => stop("Radio wyłączone"), active: () => state.on, label: () => state.label };
  }

  if (window.PiterfyRadioCore) boot(window.PiterfyRadioCore);
  else {
    const script = document.createElement("script");
    script.src = "/piterfy-ui/radio-core.js" + (VERSION ? "?v=" + VERSION : "");
    script.onload = () => { if (window.PiterfyRadioCore) boot(window.PiterfyRadioCore); };
    document.head.appendChild(script);
  }
})();
