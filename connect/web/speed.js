/* Piterfy — szybkość odczuwalna: okładki i dane pojawiają się, zanim cokolwiek dojdzie z sieci.
 *   • Okładki: każdy obrazek na naszych stronach (kafelki, karty, wiersze utworów) ma od początku tło w kolorze swojej okładki
 *     (średni kolor próbkowany po załadowaniu i zapamiętany w localStorage pf-cover-colours), a sam obrazek wjeżdża płynnym
 *     pojawieniem się zamiast „wskakiwać”. Szkielety strony startowej dostają kolory ostatnio widzianych okładek.
 *   • Dane: stale-while-revalidate dla bezpiecznych zapytań tylko-do-odczytu (getAlbumList2, getPlaylists, getStarred2…), które
 *     Piterfy wysyła sam (klient „Piterfy…”): drugie wejście na stronę startową rysuje się od razu z zapamiętanych danych, a
 *     świeże dochodzą w tle (do 20 s odpowiedź jest „świeża”, do 6 h „stara, ale od razu”; gdy serwer nie odpowiada — do 7 dni).
 *     Zapytania samego Navidrome i wszystko z efektami ubocznymi omijamy; każda mutacja (gwiazdka, scrobble, playlisty, zapis w
 *     /api/) czyści cały podręczny zbiór. Logika jest w speed-core.js (testy: speed-core.test.cjs).
 * Wyłączenie: localStorage pf-swr=0 (dane) albo pf-speed=0 (całość). */
(() => {
  "use strict";
  if (window.__pfSpeed) return;
  const core = window.PiterfySpeedCore;
  if (!core) return;
  const flag = (name) => { try { return localStorage.getItem(name); } catch (e) { return null; } };
  if (flag("pf-speed") === "0") return;
  window.__pfSpeed = true;

  let storage = null;
  try { storage = window.localStorage; storage.getItem("pf-speed"); } catch (e) { storage = null; }
  if (!storage) return;
  const swr = core.SwrStore(storage);
  const colours = core.ColourCache(storage);

  // ── stale-while-revalidate ──
  const nativeFetch = window.fetch.bind(window);
  const inflight = new Map();
  const counts = { cache: 0, stale: 0, network: 0, fallback: 0 };   // do diagnostyki: PiterfySpeed.stats()
  const okBody = (text) => /"status"\s*:\s*"ok"/.test(text.slice(0, 400));
  const hit = (entry) => new Response(entry.b, { status: 200, headers: { "Content-Type": "application/json", "X-Pf-Cache": "hit" } });

  function refresh(key, input, init) {
    if (inflight.has(key)) return inflight.get(key).then((r) => r.clone());
    const before = swr.get(key);
    const pending = nativeFetch(input, init).then(async (response) => {
      if (response.ok) {
        const text = await response.clone().text();
        if (okBody(text) && swr.put(key, text) && before && before.b !== text) {
          try { window.dispatchEvent(new CustomEvent("pf-swr-updated", { detail: { key } })); } catch (e) { /* zdarzenie jest dodatkiem */ }
        }
      }
      return response;
    });
    inflight.set(key, pending);
    const done = () => inflight.delete(key);
    pending.then(done, done);
    return pending.then((response) => response.clone());   // każdy wołający dostaje własną kopię (oryginał zostaje nietknięty)
  }

  if (flag("pf-swr") !== "0") {
    window.fetch = function (input, init) {
      let url, method;
      try {
        url = typeof input === "string" ? input : (input && input.url) || String(input);
        method = (init && init.method) || (input && typeof input === "object" && input.method) || "GET";
      } catch (e) { return nativeFetch(input, init); }
      if (core.invalidates(url, method)) {
        swr.clear();
        const request = nativeFetch(input, init);
        request.then(() => swr.clear(), () => {});   // jeszcze raz po odpowiedzi: odświeżenie w tle mogło się z nią ścigać
        return request;
      }
      const key = init && init.cache === "no-store" ? null : core.cacheable(url, method);
      if (!key) return nativeFetch(input, init);
      const now = Date.now();
      const entry = swr.get(key);
      const plan = core.swrPlan(entry, now);
      if (plan === "cache") { counts.cache += 1; return Promise.resolve(hit(entry)); }
      if (plan === "stale") { counts.stale += 1; refresh(key, input, init).catch(() => {}); return Promise.resolve(hit(entry)); }
      counts.network += 1;
      return refresh(key, input, init).then(
        (response) => (!response.ok && core.usableOnError(entry, now) ? (counts.fallback += 1, hit(entry)) : response),
        (error) => { if (core.usableOnError(entry, now)) { counts.fallback += 1; return hit(entry); } throw error; });
    };
  }

  // ── kolory okładek ──
  const IMG = ".pf-card-art img, .pf-tile-art img, .pf-song-main img, .pf-page-art img";
  const tintOf = (hex) => core.placeholderColour(hex);

  function paintHost(img, hex) {
    const tint = tintOf(hex);
    if (!tint) return;
    if (img.closest(".pf-song-main")) { img.style.background = tint; return; }   // w wierszu utworu tło ma sam obrazek
    const host = img.parentElement;
    if (host) { host.style.setProperty("--pf-ph", tint); host.classList.add("pf-ph"); }
  }

  function sample(img, id) {
    if (!id || colours.get(id) || !img.naturalWidth) return;
    try {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = 12;
      const context = canvas.getContext("2d", { willReadFrequently: true });
      context.drawImage(img, 0, 0, 12, 12);
      const average = core.averageColour(context.getImageData(0, 0, 12, 12).data);
      if (average) { const hex = core.toHex(average); colours.set(id, hex); paintHost(img, hex); }
    } catch (e) { /* obraz z innego źródła albo zablokowany canvas — zostaje szary placeholder */ }
  }

  function prepare(img) {
    if (img.dataset.pfPh) return;
    img.dataset.pfPh = "1";
    const id = core.coverId(img.getAttribute("src"));
    const cached = id && colours.get(id);
    if (cached) paintHost(img, cached);
    if (img.complete && img.naturalWidth) { sample(img, id); return; }   // już załadowany: bez pojawiania się
    if (!img.closest(".pf-song-main")) {
      img.classList.add("pf-img-fade");
      setTimeout(() => img.classList.add("pf-img-in"), 8000);          // zabezpieczenie: obrazek nigdy nie zostaje niewidoczny
    }
    img.addEventListener("load", () => { img.classList.add("pf-img-in"); sample(img, id); }, { once: true });
  }

  function tintSkeletons() {
    const palette = colours.recent(16);
    if (!palette.length) return;
    document.querySelectorAll(".pf-card-art.pf-skeleton:not([data-pf-sk]), .pf-tile.pf-skeleton:not([data-pf-sk])").forEach((el, i) => {
      el.dataset.pfSk = "1";
      el.style.setProperty("--pf-sk", tintOf(palette[i % palette.length]));
    });
  }

  let frame = 0;
  function scan() {
    document.querySelectorAll("#main-content " + IMG.split(",").map((s) => s.trim() + ":not([data-pf-ph])").join(", #main-content ")).forEach(prepare);
    tintSkeletons();
  }
  // rAF stoi w karcie w tle, więc obok niego krótki timeout — co pierwsze, to uruchamia skan (raz na serię zmian)
  const schedule = () => {
    if (frame) return;
    frame = 1;
    const run = () => { if (!frame) return; frame = 0; scan(); };
    requestAnimationFrame(run);
    setTimeout(run, 150);
  };

  const style = document.createElement("style");
  style.id = "pf-speed-css";
  style.textContent =
    ".pf-ph{background:var(--pf-ph)!important}" +
    ".pf-img-fade{opacity:0;transition:opacity var(--pf-dur-normal,240ms) var(--pf-ease-out,ease)}" +
    ".pf-img-fade.pf-img-in{opacity:1}" +
    ".pf-card-art.pf-skeleton[data-pf-sk],.pf-tile.pf-skeleton[data-pf-sk]{background:linear-gradient(90deg,rgba(255,255,255,.03) 25%,rgba(255,255,255,.15) 50%,rgba(255,255,255,.03) 75%),var(--pf-sk)!important;" +
    "background-size:200% 100%,auto!important}";
  document.head.appendChild(style);

  new MutationObserver(schedule).observe(document.body || document.documentElement, { childList: true, subtree: true });
  schedule();

  window.PiterfySpeed = {
    clear: () => swr.clear(),
    stats: () => ({ entries: swr.keys().length, bytes: swr.size(), colours: colours.size(), counts: Object.assign({}, counts) }),
  };
})();
