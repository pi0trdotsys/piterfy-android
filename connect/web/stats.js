/* Piterfy — „Statystyki dla nerdów” (#/statystyki): anatomia biblioteki, stan serwera i Wrapped.
 * Dane liczy serwer (stats.py) i oddaje je pod GET /piterfy-api/stats; ten plik tylko je rysuje (HTML/CSS bez
 * bibliotek, a karta Wrapped to <canvas> zapisywany jako PNG). Podłączenie do routera strony jest w piterfy.js
 * (PAGES.statystyki → PiterfyStats.load / render). Czysta logika: stats-core.js. */
(function () {
  "use strict";
  const core = window.PiterfyStatsCore;
  if (!core || window.PiterfyStats) return;
  const UI = "/piterfy-ui/";
  const VERSION = ((document.currentScript && document.currentScript.src.match(/[?&]v=([^&]+)/)) || [])[1];
  const reduced = () => window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const NBSP = " ";
  const TABS = [["library", "Biblioteka"], ["server", "Serwer"], ["wrapped", "Wrapped"]];
  const view = { tab: "library", period: null };
  let current = null;

  function addStyles() {
    if (document.getElementById("pf-stats-css")) return;
    const link = document.createElement("link");
    link.id = "pf-stats-css";
    link.rel = "stylesheet";
    link.href = UI + "stats.css" + (VERSION ? "?v=" + VERSION : "");
    document.head.appendChild(link);
  }

  function load() {
    addStyles();
    return fetch("/piterfy-api/stats", {
      cache: "no-cache",
      headers: { "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") },
    }).then((r) => {
      if (r.ok) return r.json();
      if (r.status === 401) return { error: "Zaloguj się ponownie w Piterfy." };
      if (r.status === 404) return { error: "Statystyki jeszcze się liczą — odśwież stronę za kilka minut." };
      return { error: "Serwer nie odpowiada (" + r.status + ")." };
    }).catch(() => ({ error: "Brak połączenia z serwerem." }));
  }

  // ── klocki ──
  const tile = (label, value, sub, count) =>
    '<div class="pf-st-tile"><span class="pf-st-label">' + esc(label) + '</span><b class="pf-st-value"' +
    (count != null ? ' data-count="' + esc(count) + '"' : "") + ">" + value + "</b>" +
    (sub ? '<span class="pf-st-sub">' + sub + "</span>" : "") + "</div>";

  const section = (title, body, note) =>
    '<section class="pf-sec pf-st-sec"><div class="pf-sec-head"><h2>' + esc(title) + "</h2></div>" +
    (note ? '<p class="pf-st-note">' + note + "</p>" : "") + body + "</section>";

  function barRows(rows, opts) {
    opts = opts || {};
    return '<div class="pf-st-bars">' + rows.map((r, i) =>
      '<div class="pf-st-barrow"><span class="pf-st-barlabel">' + esc(opts.label ? opts.label(r) : r.label) + "</span>" +
      '<span class="pf-st-bar"><i style="--w:' + r.pct + "%;--i:" + i + '"></i></span>' +
      '<span class="pf-st-barval">' + (opts.value ? opts.value(r) : core.fmtNum(r.n)) + "</span></div>").join("") + "</div>";
  }

  function stacked(parts, className) {
    return '<div class="pf-st-stack ' + (className || "") + '">' + parts.filter((p) => p.pct > 0).map((p, i) =>
      '<i class="pf-st-tone-' + p.tone + '" style="--w:' + p.pct.toFixed(2) + "%;--i:" + i + '" title="' + esc(p.label) + '"></i>').join("") + "</div>";
  }

  function legend(parts, fmt) {
    return '<ul class="pf-st-legend">' + parts.map((p) =>
      '<li><i class="pf-st-tone-' + p.tone + '"></i><span>' + esc(p.label) + "</span><b>" + fmt(p) + "</b></li>").join("") + "</ul>";
  }

  function columns(rows, valueFmt) {
    return '<div class="pf-st-cols">' + rows.map((r, i) =>
      '<div class="pf-st-col"><span class="pf-st-colval">' + (r.n ? valueFmt(r) : "") + '</span><span class="pf-st-colbar"><i style="--h:' + r.pct +
      "%;--i:" + i + '"></i></span><span class="pf-st-collabel">' + esc(r.label) + "</span></div>").join("") + "</div>";
  }

  function record(label, song, detail) {
    if (!song) return "";
    return '<div class="pf-st-rec"><span class="pf-st-label">' + esc(label) + "</span><b>" + esc(song.title || "—") + "</b><span>" +
      esc(song.artist || "") + (detail ? " · " + detail : "") + "</span></div>";
  }

  const mmss = (s) => Math.floor(s / 60) + ":" + String(Math.round(s % 60)).padStart(2, "0");
  const decadeLabel = (d) => d + "s";

  // ── Biblioteka ──
  function libraryHtml(l) {
    const q = core.qualityBreakdown(l.quality);
    const quality = l.quality || {};
    const lossyShare = l.tracks ? core.fmtPercent(l.lossless, l.tracks) : "—";
    const checked = quality.checked ? Math.round((quality.checked / Math.max(1, l.tracks)) * 100) : 0;
    const fmtRows = core.bars((l.formats || []).map((f) => [f[0].toUpperCase(), f[1]]));
    const sizeOf = {};
    (l.formats || []).forEach((f) => { sizeOf[f[0].toUpperCase()] = f[2]; });
    const replace = quality.replace || [];
    return '<div class="pf-st-tiles">' +
      tile("Utworów", core.fmtNum(l.tracks), core.fmtNum(l.albums) + NBSP + "albumów · " + core.fmtNum(l.artists) + NBSP + "wykonawców", l.tracks) +
      tile("Czas muzyki", core.fmtDuration(l.seconds), core.fmtDec(l.seconds / 86400, 1) + NBSP + "doby nieprzerwanego grania") +
      tile("Rozmiar", core.fmtBytes(l.bytes), l.tracks ? "średnio " + core.fmtBytes(l.bytes / l.tracks) + " na utwór" : "") +
      tile("Bezstratne", lossyShare, core.fmtNum(l.lossless) + NBSP + "plików · hi-res: " + core.fmtNum(l.hires)) +
      tile("Nieodsłuchane", core.fmtPercent(l.unplayed, l.tracks), core.fmtNum(l.unplayed) + NBSP + "utworów czeka") +
      tile("Polubione", core.fmtNum(l.starred), l.tracks ? core.fmtPercent(l.starred, l.tracks, 1) + " biblioteki" : "") + "</div>" +

      section("Rachunek jakości",
        stacked(q.segments, "pf-st-stack-lg") +
        legend(q.segments, (s) => core.fmtNum(s.n) + ' <small>' + core.fmtPercent(s.n, q.total, 1) + "</small>") +
        '<p class="pf-st-fact">Prawdziwie bezstratnych plików: <b>' + core.fmtNum(quality.genuine_lossless) + "</b> z " + core.fmtNum(l.lossless) +
        NBSP + "bezstratnych. Hi-res: <b>" + core.fmtNum(l.hires) + "</b>" +
        (l.hires_upsampled ? ", w tym " + core.fmtNum(l.hires_upsampled) + NBSP + "to płyta CD przeskalowana wyżej" : "") + ".</p>" +
        (replace.length ? '<details class="pf-st-replace"><summary>Do wymiany: ' + core.fmtNum(quality.replace_total) + " " +
          (quality.replace_total === 1 ? "plik" : "plików") + " z podrobioną jakością</summary><ul>" +
          replace.slice(0, 15).map((r) => '<li><a href="#/song/' + encodeURIComponent(r.id) + '/show"><b>' + esc(r.title) + "</b><span>" + esc(r.artist) +
            "</span></a><em>" + esc(String(r.format).toUpperCase()) + (r.source_kbps ? " ≈ MP3 " + esc(r.source_kbps) + NBSP + "kb/s" : "") +
            "</em></li>").join("") + "</ul></details>" : ""),
        "Analiza widma pliku wykrywa „udawany” FLAC i przekodowane MP3. Zbadano " + checked + "% biblioteki.") +

      section("Formaty",
        barRows(fmtRows, { value: (r) => core.fmtNum(r.n) + " <small>" + core.fmtBytes(sizeOf[r.label] || 0) + "</small>" })) +

      '<div class="pf-st-two">' +
      section("Bitrate plików stratnych", barRows(core.bars(l.lossy_bitrates || []), { label: (r) => r.label + NBSP + "kb/s" })) +
      section("Próbkowanie", barRows(core.bars(l.sample_rates || []), { label: (r) => r.label + NBSP + "kHz" }) +
        ((l.bit_depths || []).length ? '<p class="pf-st-fact">Głębia bitowa plików bezstratnych: ' +
          l.bit_depths.map((d) => "<b>" + d[0] + NBSP + "bit</b> × " + core.fmtNum(d[1])).join(" · ") + "</p>" : "")) +
      "</div>" +

      section("Dekady", columns(core.bars((l.decades || []).map((d) => [decadeLabel(d[0]), d[1]])), (r) => core.fmtNum(r.n)),
        l.years && l.years.average ? "Średni rok wydania: <b>" + l.years.average + "</b>" : "") +

      section("Gatunki", barRows(core.bars(l.top_genres || []))) +

      section("Rekordy",
        '<div class="pf-st-recs">' +
        record("Najstarszy", l.years && l.years.oldest, l.years && l.years.oldest && l.years.oldest.year) +
        record("Najnowszy", l.years && l.years.newest, l.years && l.years.newest && l.years.newest.year) +
        record("Najdłuższy", l.records && l.records.longest, l.records && l.records.longest && mmss(l.records.longest.seconds)) +
        record("Najkrótszy", l.records && l.records.shortest, l.records && l.records.shortest && mmss(l.records.shortest.seconds)) +
        record("Największy plik", l.records && l.records.biggest, l.records && l.records.biggest && core.fmtBytes(l.records.biggest.bytes)) + "</div>");
  }

  // ── Serwer ──
  const ENGINE_NAMES = { new: "Nowi wykonawcy", familiar: "Znani wykonawcy" };

  function rateTable(rows, nameOf) {
    const keys = Object.keys(rows || {});
    if (!keys.length) return "";
    return '<table class="pf-st-table"><thead><tr><th></th><th>Próby</th><th>Udane</th><th>Skuteczność</th></tr></thead><tbody>' +
      keys.map((k) => "<tr><th>" + esc(nameOf(k)) + "</th><td>" + core.fmtNum(rows[k].tried) + "</td><td>" + core.fmtNum(rows[k].got) +
        "</td><td>" + core.fmtPercent(rows[k].got, rows[k].tried) + "</td></tr>").join("") + "</tbody></table>";
  }

  function ring(fraction, text, label) {
    const deg = Math.round(Math.max(0, Math.min(1, fraction || 0)) * 360);
    return '<div class="pf-st-ring" style="--deg:' + deg + 'deg"><b>' + esc(text) + "</b><span>" + esc(label) + "</span></div>";
  }

  function serverHtml(s, l) {
    const acq = s.acquisition || {};
    const growth = core.growthSeries((s.growth || {}).weeks);
    const disk = core.diskBreakdown(s.disk, l && l.bytes, s.trash && s.trash.bytes);
    const outcomes = core.outcomeRows(acq.outcomes);
    const discover = s.discover || {};
    const engines = rateTable(acq.engines, (k) => k);
    const pools = rateTable(acq.pools, (k) => ENGINE_NAMES[k] || k);
    return '<div class="pf-st-tiles">' +
      tile("Nowe w 7 dni", "+" + core.fmtNum((s.growth || {}).added_7), core.fmtNum((s.growth || {}).added_30) + NBSP + "w ostatnich 30 dniach") +
      tile("Soulseek", core.fmtPercent(acq.got, acq.tried), core.fmtNum(acq.got) + NBSP + "z " + core.fmtNum(acq.tried) + NBSP + "prób zakończyło się plikiem") +
      tile("Trafność odkryć", discover.hit_rate == null ? "—" : Math.round(discover.hit_rate * 100) + "%",
        core.fmtNum(discover.liked) + NBSP + "polubionych, " + core.fmtNum(discover.trashed) + NBSP + "wyrzuconych") +
      tile("Ostatni skan", s.scanning ? "trwa…" : core.fmtStamp(s.last_scan), "biblioteki w Navidrome") +
      tile("Ostatnie odkrywanie", core.fmtStamp(s.last_run), acq.pending ? core.fmtNum(acq.pending) + NBSP + "w kolejce pobierania" : "kolejka pusta") + "</div>" +

      section("Przyrost biblioteki", columns(growth, (r) => core.fmtNum(r.n)),
        "Utworów dodanych w kolejnych tygodniach (od poniedziałku)" + ((s.growth || {}).first ? ". Najstarszy wpis: <b>" + core.fmtDay(s.growth.first) + "</b>" : "")) +

      section("Dysk",
        disk ? stacked(disk.parts, "pf-st-stack-lg") + legend(disk.parts, (p) => core.fmtBytes(p.bytes)) +
          '<p class="pf-st-fact">Zajęte <b>' + disk.usedPct + "%</b>, wolne <b>" + core.fmtBytes(disk.free) + "</b> z " + core.fmtBytes(disk.total) + ".</p>"
          : '<p class="pf-st-empty">Serwer nie podał jeszcze danych o dysku.</p>') +

      section("Pobieranie nowej muzyki",
        (outcomes.length ? barRows(core.bars(outcomes.map((o) => [o.label, o.n])), { label: (r) => r.label }) : '<p class="pf-st-empty">Brak historii pobierania.</p>') +
        '<div class="pf-st-two">' +
        (engines ? "<div><h3>Silnik rekomendacji</h3>" + engines + "</div>" : "") +
        (pools ? "<div><h3>Pula kandydatów</h3>" + pools + "</div>" : "") + "</div>" +
        (!engines && !pools ? '<p class="pf-st-note">Skuteczność według silnika i puli liczy się od najbliższego przebiegu discovera.</p>' : "") +
        '<div class="pf-st-rings">' + ring(discover.hit_rate, discover.hit_rate == null ? "—" : Math.round(discover.hit_rate * 100) + "%", "polubione z ocenionych") +
        ring(acq.soulseek_rate, acq.soulseek_rate == null ? "—" : Math.round(acq.soulseek_rate * 100) + "%", "znalezione w Soulseek") + "</div>",
        "Ile propozycji z odkrywania dotarło do biblioteki, a ile z nich zostało.");
  }

  // ── Wrapped ──
  function currentPeriod(w) {
    const periods = (w && w.periods) || [];
    return periods.find((p) => p.key === view.period) || periods[0] || null;
  }

  function deltaLine(label, pct) {
    if (pct == null) return "";
    return '<li class="' + (pct > 0 ? "up" : pct < 0 ? "down" : "flat") + '"><span>' + esc(label) + "</span><b>" + esc(core.deltaText(pct)) + "</b></li>";
  }

  function wrappedHtml(w) {
    const periods = (w && w.periods) || [];
    if (!periods.length) {
      return section("Wrapped", '<p class="pf-st-empty">Historia odsłuchów dopiero się zbiera' +
        (w && w.history_since ? " (od " + core.fmtDay(w.history_since) + ")" : "") +
        ". Wrapped pojawi się po pierwszych dniach słuchania.</p>");
    }
    const p = currentPeriod(w);
    const m = core.cardModel(p);
    const compare = p.compare;
    return '<div class="pf-st-periods" role="tablist">' + periods.map((x) =>
      '<button type="button" role="tab" data-st-period="' + esc(x.key) + '"' + (x.key === p.key ? ' aria-selected="true"' : "") + ">" + esc(x.label) + "</button>").join("") + "</div>" +
      '<div class="pf-st-wrapped"><div class="pf-st-card-wrap"><canvas class="pf-st-card" width="1080" height="1350" aria-label="Karta Wrapped: ' + esc(m.title) + '"></canvas>' +
      '<div class="pf-st-actions"><button type="button" class="pf-st-btn" data-st-act="story">▶ Odtwórz story</button>' +
      '<button type="button" class="pf-st-btn pf-st-btn-ghost" data-st-act="share">Udostępnij</button>' +
      '<button type="button" class="pf-st-btn pf-st-btn-ghost" data-st-act="download">Pobierz obrazek</button></div></div>' +
      '<div class="pf-st-wdetails">' +
      (p.listener ? '<div class="pf-st-listener"><span class="pf-st-label">Twój typ słuchacza</span><b>' + esc(p.listener.name) + "</b><p>" + esc(p.listener.blurb) + "</p><ul>" +
        p.listener.why.map((x) => "<li>" + esc(x) + "</li>").join("") + "</ul></div>" :
        '<div class="pf-st-listener"><span class="pf-st-label">Twój typ słuchacza</span><p>Za mało odsłuchów w tym okresie (potrzeba co najmniej 15).</p></div>') +
      (compare ? '<div class="pf-st-compare"><span class="pf-st-label">W porównaniu z ' + (p.kind === "month" ? "poprzednim miesiącem" : "poprzednim rokiem") +
        '</span><ul>' + deltaLine("Odsłuchy (" + core.fmtNum(compare.prev_plays) + " → " + core.fmtNum(p.plays) + ")", compare.plays) +
        deltaLine("Czas słuchania", compare.minutes) + deltaLine("Różni wykonawcy", compare.artists) + "</ul></div>" : "") +
      '<div class="pf-st-top"><span class="pf-st-label">Najczęściej</span>' +
      barRows(core.bars((p.top_artists || []).map((a) => [a.name, a.plays]))) + "</div>" +
      ((p.top_tracks || []).length ? '<ol class="pf-st-tracks">' + p.top_tracks.map((t) =>
        "<li><b>" + esc(t.title) + "</b><span>" + esc(t.artist) + "</span><em>" + core.fmtNum(t.plays) + "×</em></li>").join("") + "</ol>" : "") +
      '<ul class="pf-st-facts">' + m.facts.map((f) => "<li>" + esc(f) + "</li>").join("") + "</ul>" +
      (p.partial ? '<p class="pf-st-note">Okres trwa — liczby rosną z każdym dniem.</p>' : "") + "</div></div>";
  }

  // ── karta Wrapped (canvas) ──
  function themeColors() {
    const cs = getComputedStyle(document.documentElement);
    const get = (name, fallback) => (cs.getPropertyValue(name) || "").trim() || fallback;
    return { bg: get("--pf-bg", "#000000"), deep: get("--pf-deep", "#0e612b"), accent: get("--pf-accent", "#1db954"),
      accent2: get("--pf-accent-2", "#a4edc2"), text: get("--pf-text", "#ffffff"), muted: get("--pf-muted", "#b3b3b3") };
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function drawCard(canvas, model) {
    const ctx = canvas.getContext("2d");
    if (!ctx || !model) return;
    const W = canvas.width, H = canvas.height, P = 84, c = themeColors();
    const FONT = "Figtree, system-ui, -apple-system, Segoe UI, Roboto, sans-serif";
    const font = (weight, size) => weight + " " + size + "px " + FONT;
    const measure = (s) => ctx.measureText(s).width;

    const bg = ctx.createLinearGradient(0, 0, W, H);
    bg.addColorStop(0, c.deep);
    bg.addColorStop(0.55, c.bg);
    bg.addColorStop(1, c.bg);
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, W, H);
    // miękkie plamy koloru motywu
    [[W * 0.92, 90, 420, c.accent, 0.35], [-40, H * 0.62, 380, c.accent2, 0.16]].forEach((s) => {
      const g = ctx.createRadialGradient(s[0], s[1], 0, s[0], s[1], s[2]);
      g.addColorStop(0, s[3]); g.addColorStop(1, "transparent");
      ctx.globalAlpha = s[4]; ctx.fillStyle = g; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1;
    });
    // znak Piterfy: cztery słupki
    ctx.fillStyle = c.accent;
    [[0, 46], [26, 80], [52, 56], [78, 34]].forEach((b) => { roundRect(ctx, W - P - 104 + b[0], P + 4 + (80 - b[1]), 14, b[1], 7); ctx.fill(); });

    ctx.textBaseline = "alphabetic";
    ctx.fillStyle = c.accent; ctx.font = font(700, 28);
    if ("letterSpacing" in ctx) ctx.letterSpacing = "6px";
    ctx.fillText(model.kicker, P, P + 28);
    if ("letterSpacing" in ctx) ctx.letterSpacing = "0px";
    ctx.fillStyle = c.text; ctx.font = font(800, 68);
    ctx.fillText(model.title, P, P + 112);

    // duża liczba zawsze mieści się w szerokości karty (np. „412 godz.” przy 210 px byłoby za szerokie)
    let bigSize = 210;
    while (bigSize > 90) { ctx.font = font(800, bigSize); if (measure(model.big) <= W - P * 2) break; bigSize -= 10; }
    ctx.fillStyle = c.accent;
    ctx.fillText(model.big, P - 6, 112 + 84 + bigSize * 0.92 + 40);
    ctx.fillStyle = c.muted; ctx.font = font(600, 38);
    ctx.fillText(model.bigCaption, P, 112 + 84 + bigSize * 0.92 + 112);
    let y = 112 + 84 + bigSize * 0.92 + 112;
    if (model.delta) {
      ctx.font = font(700, 30);
      const w = measure(model.delta.text) + 52;
      ctx.fillStyle = model.delta.flat ? "rgba(255,255,255,0.14)" : model.delta.up ? "rgba(61,220,132,0.22)" : "rgba(255,120,120,0.22)";
      roundRect(ctx, P, y + 28, w, 58, 29); ctx.fill();
      ctx.fillStyle = model.delta.flat ? c.text : model.delta.up ? "#5ff0a3" : "#ff9a9a";
      ctx.fillText(model.delta.text, P + 26, y + 68);
      y += 86;
    }

    const label = (text, ly) => {
      ctx.fillStyle = c.muted; ctx.font = font(700, 24);
      if ("letterSpacing" in ctx) ctx.letterSpacing = "5px";
      ctx.fillText(text, P, ly);
      if ("letterSpacing" in ctx) ctx.letterSpacing = "0px";
    };
    y += 78;
    if (model.listener) {
      label("TWÓJ TYP SŁUCHACZA", y);
      ctx.fillStyle = c.text; ctx.font = font(800, 78);
      ctx.fillText(model.listener.name, P, y + 80);
      ctx.fillStyle = c.muted; ctx.font = font(500, 33);
      const lines = core.wrapText(model.listener.blurb, W - P * 2, measure, 2);
      lines.forEach((line, i) => ctx.fillText(line, P, y + 132 + i * 44));
      y += 132 + (lines.length - 1) * 44 + 70;
    }

    label("TOP WYKONAWCY", y);
    model.artists.forEach((a, i) => {
      const ry = y + 62 + i * 56;
      ctx.fillStyle = c.accent; ctx.font = font(800, 38);
      ctx.fillText(String(i + 1), P, ry);
      ctx.fillStyle = c.text; ctx.font = font(700, 40);
      let name = a.name;
      const room = W - P * 2 - 62 - 150;
      while (name.length > 1 && measure(name) > room) name = name.slice(0, -1);
      ctx.fillText(name === a.name ? name : name.trimEnd() + "…", P + 56, ry);
      ctx.fillStyle = c.muted; ctx.font = font(600, 32);
      ctx.textAlign = "right"; ctx.fillText(a.plays + "×", W - P, ry); ctx.textAlign = "left";
    });
    y += 62 + Math.max(0, model.artists.length - 1) * 56 + 70;

    if (model.track && y < H - 190) {
      label("UTWÓR OKRESU", y);
      ctx.fillStyle = c.text; ctx.font = font(800, 42);
      ctx.fillText(core.wrapText(model.track.title, W - P * 2, measure, 1)[0] || "", P, y + 54);
      ctx.fillStyle = c.muted; ctx.font = font(500, 32);
      ctx.fillText(core.wrapText(model.track.artist + " · " + model.track.plays + "×", W - P * 2, measure, 1)[0] || "", P, y + 98);
    }
    ctx.fillStyle = c.muted; ctx.globalAlpha = 0.8; ctx.font = font(600, 28);
    ctx.fillText("piterfy", P, H - 50);
    ctx.globalAlpha = 1;
  }

  function paintCard(box) {
    const canvas = box.querySelector(".pf-st-card");
    const p = current && currentPeriod(current.wrapped);
    if (!canvas || !p) return;
    drawCard(canvas, core.cardModel(p));
    // krój Figtree może dojechać po pierwszym rysunku — rysujemy raz jeszcze, gdy fonty są gotowe
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { if (canvas.isConnected) drawCard(canvas, core.cardModel(p)); });
  }

  function saveCard(box, share) {
    const canvas = box.querySelector(".pf-st-card");
    const p = current && currentPeriod(current.wrapped);
    if (!canvas || !p) return;
    const model = core.cardModel(p);
    canvas.toBlob((blob) => {
      if (!blob) return;
      const file = new File([blob], model.fileName, { type: "image/png" });
      if (share && navigator.canShare && navigator.canShare({ files: [file] })) {
        navigator.share({ files: [file], title: "Piterfy Wrapped · " + model.title }).catch(() => {});
        return;
      }
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = model.fileName;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
    }, "image/png");
  }

  // ── story (osobny plik, ładowany przy pierwszym użyciu) ──
  let storyLoading = null;
  function ensureStory() {
    if (window.PiterfyStory) return Promise.resolve();
    if (!storyLoading) {
      storyLoading = new Promise((resolve, reject) => {
        const s = document.createElement("script");
        s.src = UI + "stats-story.js" + (VERSION ? "?v=" + VERSION : "");
        s.onload = resolve;
        s.onerror = () => { storyLoading = null; reject(new Error("stats-story.js")); };
        document.head.appendChild(s);
      });
    }
    return storyLoading;
  }

  function playStory() {
    const p = current && currentPeriod(current.wrapped);
    if (!p) return;
    ensureStory().then(() => window.PiterfyStory.open(p)).catch(() => {});
  }

  // ── ekran ──
  function animateCounts(root) {
    root.querySelectorAll("[data-count]").forEach((el) => {
      const target = Number(el.dataset.count);
      if (!Number.isFinite(target) || reduced() || target < 20) return;
      const start = performance.now(), dur = 900;
      const tick = (now) => {
        const t = Math.min(1, (now - start) / dur);
        el.textContent = core.fmtNum(target * (1 - Math.pow(1 - t, 3)));
        if (t < 1 && el.isConnected) requestAnimationFrame(tick); else el.textContent = core.fmtNum(target);
      };
      requestAnimationFrame(tick);
    });
  }

  function bodyHtml(d) {
    if (view.tab === "server") return serverHtml(d.server || {}, d.library || {});
    if (view.tab === "wrapped") return wrappedHtml(d.wrapped);
    return libraryHtml(d.library || {});
  }

  function paintBody(box) {
    const body = box.querySelector(".pf-st-body");
    if (!body || !current) return;
    body.innerHTML = bodyHtml(current);
    body.dataset.tab = view.tab;
    box.querySelectorAll("[data-st-tab]").forEach((b) => b.setAttribute("aria-selected", b.dataset.stTab === view.tab ? "true" : "false"));
    animateCounts(body);
    if (view.tab === "wrapped") paintCard(box);
  }

  function bind(box) {
    if (box.dataset.stBound) return;
    box.dataset.stBound = "1";
    box.addEventListener("keydown", (e) => {
      const tab = e.target.closest && e.target.closest("[data-st-tab]");
      if (tab && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); tab.click(); }
    });
    box.addEventListener("click", (e) => {
      const t = e.target.closest("[data-st-tab], [data-st-period], [data-st-act], [data-st-retry]");
      if (!t) return;
      if (t.dataset.stRetry !== undefined) { box.innerHTML = skeleton(); load().then((d) => render(box, d)); return; }
      if (t.dataset.stTab) { e.preventDefault(); view.tab = t.dataset.stTab; paintBody(box); return; }
      if (t.dataset.stPeriod) { view.period = t.dataset.stPeriod; paintBody(box); return; }
      if (t.dataset.stAct === "story") { playStory(); return; }
      if (t.dataset.stAct) saveCard(box, t.dataset.stAct === "share");
    });
  }

  function skeleton() {
    return '<header class="pf-home-head"><h1 class="pf-hello">Statystyki</h1><span class="pf-page-meta">Liczę…</span></header>' +
      '<div class="pf-st-tiles">' + Array.from({ length: 6 }, () => '<div class="pf-st-tile pf-skeleton"></div>').join("") + "</div>";
  }

  function render(box, data) {
    addStyles();
    bind(box);
    if (!data || data.error) {
      current = null;
      box.innerHTML = '<header class="pf-home-head"><h1 class="pf-hello">Statystyki</h1></header>' +
        '<p class="pf-st-empty">' + esc((data && data.error) || "Nie udało się pobrać statystyk.") +
        '</p><button type="button" class="pf-st-btn" data-st-retry>Spróbuj ponownie</button>';
      return;
    }
    current = data;
    box.innerHTML = '<header class="pf-home-head"><h1 class="pf-hello">Statystyki</h1>' +
      '<span class="pf-page-meta">Dla nerdów · policzone ' + esc(core.fmtStamp(data.generated)) + "</span>" +
      '<nav class="pf-st-tabs" aria-label="Rodzaj statystyk">' + TABS.map((t) =>
        '<a role="tab" tabindex="0" data-st-tab="' + t[0] + '">' + t[1] + "</a>").join("") + "</nav></header>" +
      '<div class="pf-st-body"></div>';
    paintBody(box);
  }

  window.PiterfyStats = { load, render, skeleton, core };
})();
