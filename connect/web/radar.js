/* Piterfy — „Nowości od Twoich artystów” (radar nowości): półka na stronie startowej z nowymi wydaniami wykonawców, których
 * masz w bibliotece (serwer pyta Deezera: /piterfy-api/radar) i przyciskiem „Dociągnij”, który zleca serwerowi pobranie albumu
 * przez slskd (POST /piterfy-api/radar/fetch). Półka dokłada się przez window.PiterfyHomeExtras (hak z piterfy.js), więc
 * pojawia się razem ze stroną startową; bez nowości albo bez Last.fm/Deezera po stronie serwera po prostu jej nie ma.
 * Czysta logika (etykiety, stan przycisku, sortowanie, HTML) jest eksportowana dla testów Node: radar.test.cjs. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyRadar = api;
  if (typeof document !== "undefined" && root === window) api.boot();
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  const POLL_MS = 15000;
  const TITLE = "Nowości od Twoich artystów";
  const KICKER = "Nowe wydania wykonawców z Twojej biblioteki";

  const escapeHtml = (value) => String(value == null ? "" : value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

  function typeLabel(type) {
    return { album: "Album", ep: "EP", single: "Singiel" }[String(type || "").toLowerCase()] || "Wydanie";
  }

  /** „Dziś”, „Wczoraj”, „5 dni temu”, a starsze jako „12.11”; pusty napis, gdy data jest nieczytelna. */
  function dateLabel(released, now) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(released || ""));
    if (!m) return "";
    const day = Date.UTC(+m[1], +m[2] - 1, +m[3]);
    const today = now instanceof Date ? now : new Date();
    const days = Math.round((Date.UTC(today.getFullYear(), today.getMonth(), today.getDate()) - day) / 86400000);
    if (days <= 0) return "Dziś";
    if (days === 1) return "Wczoraj";
    if (days <= 14) return days + " dni temu";
    return m[3] + "." + m[2];
  }

  /** Napis i stan przycisku dla pozycji; `fill` to „czy serwer w ogóle dociąga” (DISCOVER_CHART_FILL). */
  function actionState(item, fill) {
    const have = Number(item && item.have) || 0;
    const total = Number(item && item.total) || 0;
    const status = item && item.status;
    if (status === "queued") return { kind: "busy", disabled: true, label: total ? "Pobieram " + have + "/" + total : "W kolejce…" };
    if (status === "done") return { kind: "done", disabled: true, label: total ? "Gotowe " + have + "/" + total : "Gotowe" };
    if (status === "failed") return { kind: "failed", disabled: false, label: "Spróbuj ponownie" };
    if (fill === false || (status === "disabled" && fill !== true)) return { kind: "off", disabled: true, label: "Dociąganie wyłączone" };
    return { kind: "new", disabled: false, label: "Dociągnij" };
  }

  /** Najnowsze najpierw; przy tej samej dacie po wykonawcy i tytule. */
  function sortItems(items) {
    return (Array.isArray(items) ? items : []).filter((i) => i && i.id != null && i.title).slice().sort((a, b) =>
      String(b.released || "").localeCompare(String(a.released || "")) ||
      String(a.artist || "").localeCompare(String(b.artist || ""), "pl") || String(a.title).localeCompare(String(b.title), "pl"));
  }

  const shouldPoll = (items) => (Array.isArray(items) ? items : []).some((i) => i && i.status === "queued");

  function cardHtml(item, fill, now) {
    const action = actionState(item, fill);
    const meta = [typeLabel(item.type), dateLabel(item.released, now)].filter(Boolean).join(" · ") +
      (item.tracks > 1 ? " · " + item.tracks + " utw." : "");
    const note = item.note && item.status !== "queued" ? '<span class="pf-radar-note">' + escapeHtml(item.note) + "</span>" : "";
    const cover = /^https:\/\//.test(item.cover || "")
      ? '<img src="' + escapeHtml(item.cover) + '" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">' : "";
    return '<div class="pf-card pf-radar-card" data-radar-id="' + escapeHtml(item.id) + '">' +
      '<span class="pf-card-art pf-radar-art">' + cover + '<span class="pf-radar-badge">' + escapeHtml(typeLabel(item.type)) + "</span></span>" +
      '<span class="pf-card-title" title="' + escapeHtml(item.title) + '">' + escapeHtml(item.title) + "</span>" +
      '<span class="pf-card-sub">' + escapeHtml(item.artist || "") + "</span>" +
      '<span class="pf-radar-meta">' + escapeHtml(meta) + "</span>" + note +
      '<button type="button" class="pf-radar-btn" data-radar-fetch data-kind="' + action.kind + '"' + (action.disabled ? " disabled" : "") + ">" +
      escapeHtml(action.label) + "</button></div>";
  }

  /** Cała półka albo pusty napis, gdy nie ma nowości. */
  function sectionHtml(items, fill, now) {
    const list = sortItems(items);
    if (!list.length) return "";
    return '<section class="pf-sec pf-radar"><div class="pf-sec-head"><div><span class="pf-sec-kicker">' + escapeHtml(KICKER) + "</span><h2>" +
      escapeHtml(TITLE) + '</h2></div></div><div class="pf-row pf-row-mixes">' + list.map((i) => cardHtml(i, fill, now)).join("") + "</div></section>";
  }

  const CSS =
    ".pf-radar-card{position:relative}" +
    ".pf-radar-art{background:var(--pf-h-surface)}" +
    ".pf-radar-art img{width:100%;height:100%;object-fit:cover;display:block}" +
    ".pf-radar-badge{position:absolute;left:8px;top:8px;padding:2px 8px;border-radius:999px;background:rgba(0,0,0,.62);color:#fff;" +
    "font:800 10px/1.5 Figtree,system-ui,sans-serif;letter-spacing:.06em;text-transform:uppercase;backdrop-filter:blur(6px)}" +
    ".pf-radar-meta{display:block;margin-top:2px;color:var(--pf-h-muted);font:500 12px/1.4 Figtree,system-ui,sans-serif}" +
    ".pf-radar-note{display:block;margin-top:4px;color:var(--pf-h-muted);font:500 12px/1.35 Figtree,system-ui,sans-serif}" +
    ".pf-radar-btn{margin-top:10px;align-self:flex-start;max-width:100%;padding:7px 14px;border:0;border-radius:999px;cursor:pointer;" +
    "background:var(--pf-h-green,#1db954);color:#000;font:800 13px/1.2 Figtree,system-ui,sans-serif;white-space:nowrap;overflow:hidden;" +
    "text-overflow:ellipsis;transition:transform .15s,background .2s,opacity .2s}" +
    ".pf-radar-btn:hover:not(:disabled){transform:scale(1.04)}" +
    ".pf-radar-btn:focus-visible{outline:2px solid var(--pf-h-green,#1db954);outline-offset:2px}" +
    ".pf-radar-btn:disabled{cursor:default}" +
    '.pf-radar-btn[data-kind="busy"],.pf-radar-btn[data-kind="off"]{background:var(--pf-h-surface);color:var(--pf-h-muted)}' +
    '.pf-radar-btn[data-kind="done"]{background:transparent;color:var(--pf-h-green,#1db954);box-shadow:inset 0 0 0 1.5px var(--pf-h-green,#1db954)}' +
    '.pf-radar-btn[data-kind="failed"]{background:var(--pf-h-hover);color:var(--pf-h-text)}' +
    '.pf-radar-btn[data-kind="busy"]{animation:pf-radar-pulse 1.6s ease-in-out infinite}' +
    "@keyframes pf-radar-pulse{50%{opacity:.55}}" +
    "@media (prefers-reduced-motion:reduce){.pf-radar-btn{animation:none!important;transition:none}}";

  function boot() {
    if (window.__pfRadarBooted) return;
    window.__pfRadarBooted = true;
    const state = { items: [], fill: true, loaded: false, loading: false, timer: 0, at: 0 };
    const headers = () => ({ "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") });

    if (!document.getElementById("pf-radar-css")) {
      const style = document.createElement("style");
      style.id = "pf-radar-css";
      style.textContent = CSS;
      document.head.appendChild(style);
    }

    async function load() {
      if (state.loading) return;
      state.loading = true;
      try {
        const response = await fetch("/piterfy-api/radar", { headers: headers(), cache: "no-store", signal: AbortSignal.timeout(10000) });
        if (!response.ok) throw new Error("radar " + response.status);
        const data = await response.json();
        state.items = Array.isArray(data.albums) ? data.albums : [];
        state.fill = data.fill !== false;
        state.loaded = true;
      } catch (e) { /* bez radaru strona startowa działa jak dotąd */ }
      state.loading = false;
      renderAll();
      schedule();
    }

    function schedule() {
      clearTimeout(state.timer);
      if (shouldPoll(state.items) && document.querySelector(".pf-radar")) state.timer = setTimeout(load, POLL_MS);
    }

    function renderAll() {
      document.querySelectorAll(".pf-radar").forEach((node) => {
        const html = sectionHtml(state.items, state.fill, new Date());
        if (!html) return node.remove();
        const holder = document.createElement("div");
        holder.innerHTML = html;
        node.replaceWith(holder.firstChild);
      });
      document.querySelectorAll('.pf-home[data-pf-page="start"]').forEach(place); // dane mogły dojść już po narysowaniu strony
    }

    function place(box) {
      if (!box || box.querySelector(".pf-radar")) return;
      const html = sectionHtml(state.items, state.fill, new Date());
      if (!html) return;
      const holder = document.createElement("div");
      holder.innerHTML = html;
      const section = holder.firstChild;
      // zaraz przed „Odkryte dla Ciebie”, a gdy jej nie ma — na końcu strony
      const before = Array.from(box.querySelectorAll(".pf-sec")).find((s) => /Odkryte dla Ciebie/.test((s.querySelector("h2") || {}).textContent || ""));
      if (before) box.insertBefore(section, before); else box.appendChild(section);
      schedule();
    }

    async function fetchAlbum(id) {
      const item = state.items.find((i) => String(i.id) === String(id));
      if (!item || item.status === "queued" || item.status === "done") return;
      Object.assign(item, { status: "queued", note: "" });
      renderAll();
      try {
        const response = await fetch("/piterfy-api/radar/fetch", {
          method: "POST", headers: Object.assign({ "Content-Type": "application/json" }, headers()), body: JSON.stringify({ id: String(id) }),
          signal: AbortSignal.timeout(10000),
        });
        const data = await response.json().catch(() => ({}));
        if (response.ok && data.album) Object.assign(item, data.album);
        else Object.assign(item, { status: "failed", note: data.error || "Nie udało się wysłać prośby." });
      } catch (e) {
        Object.assign(item, { status: "failed", note: "Nie udało się wysłać prośby — sprawdź połączenie." });
      }
      renderAll();
      schedule();
    }

    document.addEventListener("click", (event) => {
      const button = event.target.closest && event.target.closest(".pf-radar-btn[data-radar-fetch]");
      if (!button || button.disabled) return;
      event.preventDefault();
      const card = button.closest("[data-radar-id]");
      if (card) fetchAlbum(card.getAttribute("data-radar-id"));
    });

    window.PiterfyHomeExtras = window.PiterfyHomeExtras || [];
    window.PiterfyHomeExtras.push((box) => {
      place(box);
      if (!state.loaded || Date.now() - state.at > 60000) { state.at = Date.now(); load(); }
    });
    // strona startowa mogła już się narysować, zanim ten skrypt się załadował
    const home = document.querySelector('.pf-home[data-pf-page="start"]');
    if (home && home.querySelector(".pf-sec")) { state.at = Date.now(); load(); }
  }

  return { TITLE, KICKER, POLL_MS, escapeHtml, typeLabel, dateLabel, actionState, sortItems, shouldPoll, cardHtml, sectionHtml, boot };
});
