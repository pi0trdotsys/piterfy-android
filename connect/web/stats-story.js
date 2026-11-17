/* Piterfy — Wrapped jako „story” (9:16): przesuwane slajdy z animacją, okładkami i muzyką w tle.
 * Każdy slajd rysuje ta sama funkcja drawSlide(t): na ekranie t rośnie z czasem (animacje), a przy zapisie PNG
 * i nagrywaniu wideo rysujemy ten sam obraz — więc to, co widać, jest dokładnie tym, co się udostępnia.
 * Czysta logika (lista slajdów, okno podglądu, kolor z okładki): stats-core.js. Otwiera to stats.js
 * (przycisk „Odtwórz story” na karcie Wrapped), a plik ładuje się dopiero przy pierwszym użyciu. */
(function () {
  "use strict";
  const core = window.PiterfyStatsCore;
  if (!core || window.PiterfyStory) return;
  const W = 1080, H = 1920, P = 84;
  const FONT = "Figtree, system-ui, -apple-system, Segoe UI, Roboto, sans-serif";
  const END = 60000; // „koniec animacji”: tak rysujemy slajd do PNG
  const VIDEO_MS = 5000, VIDEO_TRACK_MS = 6500, VIDEO_FINALE_MS = 4000;
  const NAME = "piterfy-wrapped";
  const PLAYER_AUDIO = "audio.music-player-audio, .react-jinke-music-player-main audio";
  const reduced = () => (() => { try { return localStorage.getItem("pf-calm") === "1"; } catch (e) { return false; } })();
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
  const ease = (x) => 1 - Math.pow(1 - clamp(x, 0, 1), 3);
  const prog = (t, delay, dur) => ease((t - delay) / dur);
  const NBSP = " ";
  const svg = (d) => '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + d + "</svg>";
  const ICON = {
    close: svg('<path d="M6 6l12 12M18 6L6 18"/>'),
    save: svg('<path d="M12 4v11m0 0l-4-4m4 4l4-4M5 19h14"/>'),
    on: svg('<path d="M4 9v6h4l5 4V5L8 9H4z" fill="currentColor"/><path d="M16.5 8.5a5 5 0 010 7M19 6a8.5 8.5 0 010 12"/>'),
    off: svg('<path d="M4 9v6h4l5 4V5L8 9H4z" fill="currentColor"/><path d="M17 9l4 6m0-6l-4 6"/>'),
  };

  function plural(n, one, few, many) {
    const a = Math.abs(n), last = a % 10, tens = a % 100;
    if (a === 1) return one;
    return last >= 2 && last <= 4 && !(tens >= 12 && tens <= 14) ? few : many;
  }

  function params(extra) {
    const get = (k) => { try { return localStorage.getItem(k) || ""; } catch (e) { return ""; } };
    return new URLSearchParams(Object.assign({ u: get("username"), t: get("subsonic-token"), s: get("subsonic-salt"), v: "1.16.1", c: NAME }, extra));
  }
  const coverUrl = (id) => "/rest/getCoverArt?" + params({ id, size: 600 });
  // Navidrome przy transkodowaniu (mp3 128) rozumie timeOffset i nie wymaga przewijania strumienia bez Range
const streamUrl = (id, offset) => "/rest/stream?" + params({ id, format: "mp3", maxBitRate: 128, timeOffset: Math.round(offset || 0) });

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

  // ── okładki: ładowanie, kolor dominujący, rozmyte tło ──
  function makeCovers(ids, onChange) {
    const entries = new Map();
    const load = (id) => {
      if (!id || entries.has(id)) return;
      const entry = { img: null, color: null, blur: null, ready: false, failed: false };
      entries.set(id, entry);
      entry.done = new Promise((resolve) => {
        const img = new Image();
        img.onload = () => {
          entry.img = img; entry.ready = true;
          try {
            const c = document.createElement("canvas");
            c.width = c.height = 16;
            const x = c.getContext("2d", { willReadFrequently: true });
            x.drawImage(img, 0, 0, 16, 16);
            entry.color = core.dominantColor(x.getImageData(0, 0, 16, 16).data);
          } catch (e) { entry.color = null; }
          if (onChange) onChange();
          resolve(entry);
        };
        img.onerror = () => { entry.failed = true; resolve(entry); };
        img.src = coverUrl(id);
      });
    };
    ids.forEach(load);
    const get = (id) => entries.get(id) || null;
    // rozmycie bez ctx.filter (Safari go nie ma): malutka kopia rozciągnięta z wygładzaniem
    const blurOf = (entry) => {
      if (!entry || !entry.ready) return null;
      if (!entry.blur) {
        const c = document.createElement("canvas");
        c.width = 36; c.height = 64;
        const x = c.getContext("2d");
        const s = Math.max(c.width / entry.img.width, c.height / entry.img.height);
        x.drawImage(entry.img, (c.width - entry.img.width * s) / 2, (c.height - entry.img.height * s) / 2, entry.img.width * s, entry.img.height * s);
        entry.blur = c;
      }
      return entry.blur;
    };
    return { get, blurOf, all: () => Promise.all([...entries.values()].map((e) => e.done)) };
  }

  // ── rysowanie slajdów ──
  function makeRenderer(period, covers) {
    const theme = themeColors();
    const mark = (ctx, color, t, x, y, scale, alpha) => {
      ctx.save(); ctx.globalAlpha = alpha; ctx.fillStyle = color;
      [[0, 46], [26, 80], [52, 56], [78, 34]].forEach((b, i) => {
        const wobble = 1 + 0.12 * Math.sin(t / 260 + i * 1.7);
        const h = b[1] * scale * wobble;
        roundRect(ctx, x + b[0] * scale, y + (80 * scale - h), 14 * scale, h, 7 * scale); ctx.fill();
      });
      ctx.restore();
    };

    function draw(ctx, slide, tt) {
      const t = reduced() ? END : tt;
      const accentOf = (id) => core.readableAccent((covers.get(id) || {}).color, theme.accent);
      let accent = theme.accent, bgCover = null;
      if (slide.id === "artist") { bgCover = (period.top_artists[0] || {}).cover; accent = accentOf(bgCover); }
      if (slide.id === "tracks") { bgCover = (period.top_tracks[0] || {}).cover; accent = accentOf(bgCover); }

      // tło: gradient motywu, plamy koloru, a przy okładkach rozmyta okładka pod przyciemnieniem
      const g = ctx.createLinearGradient(0, 0, W, H);
      g.addColorStop(0, theme.deep); g.addColorStop(0.55, theme.bg); g.addColorStop(1, theme.bg);
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      const blur = bgCover ? covers.blurOf(covers.get(bgCover)) : null;
      if (blur) {
        ctx.save(); ctx.globalAlpha = 0.85 * prog(t, 0, 700);
        ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = "high";
        ctx.drawImage(blur, 0, 0, W, H); ctx.restore();
        const shade = ctx.createLinearGradient(0, 0, 0, H);
        shade.addColorStop(0, "rgba(0,0,0,0.35)"); shade.addColorStop(0.5, "rgba(0,0,0,0.55)"); shade.addColorStop(1, "rgba(0,0,0,0.82)");
        ctx.fillStyle = shade; ctx.fillRect(0, 0, W, H);
      }
      const drift = Math.sin(t / 2600) * 30;
      [[W * 0.9 + drift, 260, 560, accent, 0.3], [-60, H * 0.7 - drift, 520, theme.accent2, 0.14]].forEach((s) => {
        const r = ctx.createRadialGradient(s[0], s[1], 0, s[0], s[1], s[2]);
        r.addColorStop(0, s[3]); r.addColorStop(1, "transparent");
        ctx.save(); ctx.globalAlpha = s[4]; ctx.fillStyle = r; ctx.fillRect(0, 0, W, H); ctx.restore();
      });

      ctx.textBaseline = "alphabetic"; ctx.textAlign = "left";
      const spacing = (px) => { if ("letterSpacing" in ctx) ctx.letterSpacing = px + "px"; };
      const text = (value, x, y, o) => {
        o = o || {};
        const a = o.a == null ? 1 : o.a;
        if (a <= 0) return;
        ctx.save();
        ctx.globalAlpha = a; ctx.fillStyle = o.color || theme.text; ctx.font = (o.weight || 700) + " " + (o.size || 40) + "px " + FONT;
        ctx.textAlign = o.align || "left"; spacing(o.spacing || 0);
        ctx.fillText(value, x, y + (o.dy || 0));
        ctx.restore();
      };
      const measure = (value, size, weight) => { ctx.save(); ctx.font = (weight || 700) + " " + size + "px " + FONT; spacing(0); const w = ctx.measureText(value).width; ctx.restore(); return w; };
      const fit = (value, size, min, weight, max) => { let s = size; while (s > min && measure(value, s, weight) > (max || W - P * 2)) s -= 6; return s; };
      const wrap = (value, size, weight, maxLines, max) => core.wrapText(value, max || W - P * 2, (s) => measure(s, size, weight), maxLines);
      const label = (value, a, y) => text(value, P, y == null ? 250 : y, { size: 28, weight: 700, color: accent, spacing: 6, a, dy: (1 - a) * 24 });
      const coverBox = (id, x, y, size, a, radius) => {
        const entry = covers.get(id);
        ctx.save(); ctx.globalAlpha = a;
        ctx.shadowColor = "rgba(0,0,0,0.5)"; ctx.shadowBlur = 40; ctx.shadowOffsetY = 18;
        roundRect(ctx, x, y, size, size, radius == null ? 22 : radius);
        ctx.fillStyle = "rgba(255,255,255,0.1)"; ctx.fill();
        ctx.shadowColor = "transparent";
        if (entry && entry.ready) {
          ctx.clip();
          const s = Math.max(size / entry.img.width, size / entry.img.height);
          ctx.drawImage(entry.img, x + (size - entry.img.width * s) / 2, y + (size - entry.img.height * s) / 2, entry.img.width * s, entry.img.height * s);
        }
        ctx.restore();
      };
      const ellipsize = (value, size, weight, max) => {
        let out = value;
        while (out.length > 1 && measure(out, size, weight) > max) out = out.slice(0, -1);
        return out === value ? out : out.trimEnd() + "…";
      };

      // napis w jednej linii: najpierw zmniejszamy czcionkę, a jeśli i tak za długi, ucinamy wielokropkiem
      const oneLine = (value, size, min, weight) => {
        const px = fit(value, size, min, weight);
        return { text: ellipsize(value, px, weight, W - P * 2), size: px };
      };

      switch (slide.id) {
        case "intro": {
          const a0 = prog(t, 100, 700), a1 = prog(t, 350, 800), a2 = prog(t, 800, 700);
          text("PITERFY WRAPPED", P, 640, { size: 32, weight: 700, color: accent, spacing: 8, a: a0, dy: (1 - a0) * 30 });
          const fitted = oneLine(period.label || "", 170, 80, 800), title = fitted.text, size = fitted.size;
          text(title, P - 4, 640 + 40 + size * 0.95, { size, weight: 800, a: a1, dy: (1 - a1) * 60 });
          text(period.partial ? "Twoja muzyka w liczbach · do dziś" : "Twoja muzyka w liczbach", P, 640 + 40 + size * 0.95 + 90,
            { size: 42, weight: 600, color: theme.muted, a: a2, dy: (1 - a2) * 30 });
          mark(ctx, accent, t, W / 2 - 170, 1380, 4.2, prog(t, 600, 900));
          break;
        }
        case "time": {
          const a0 = prog(t, 100, 600), k = ease(t / 1900), a2 = prog(t, 1300, 700), a3 = prog(t, 1800, 700);
          label("POSŁUCHANE", a0, 620);
          const minutes = Math.max(0, Number(period.minutes) || 0);
          const big = minutes >= 600 ? core.fmtNum((minutes / 60) * k) + NBSP + "godz." : core.fmtNum(minutes * k) + NBSP + "min";
          const full = core.fmtListening(minutes);
          const size = fit(full, 230, 100, 800);
          text(big, P - 6, 620 + 60 + size * 0.95, { size, weight: 800, color: accent, a: a0 });
          const base = 620 + 60 + size * 0.95;
          const days = Number(period.active_days) || 0;
          text(core.fmtNum(period.plays) + NBSP + plural(period.plays, "odsłuchanie", "odsłuchania", "odsłuchań") + " w " + days + NBSP + plural(days, "dniu", "dniach", "dniach"),
            P, base + 100, { size: 44, weight: 700, a: a2, dy: (1 - a2) * 30 });
          const albums = Math.round(minutes / 45);
          if (albums >= 2) text("To mniej więcej " + core.fmtNum(albums) + NBSP + plural(albums, "album", "albumy", "albumów") + " pod rząd.", P, base + 168,
            { size: 36, weight: 500, color: theme.muted, a: a3, dy: (1 - a3) * 30 });
          break;
        }
        case "artist": {
          const list = period.top_artists || [], top = list[0];
          label("NAJCZĘŚCIEJ GRAŁ", prog(t, 100, 600), 240);
          const a1 = prog(t, 250, 900);
          const size = 640;
          coverBox(top.cover, (W - size) / 2, 320 + (1 - a1) * 90, size, a1, 36);
          const lines = wrap(top.name, 110, 800, 2);
          const ls = fit(lines[0], 110, 70, 800);
          const a2 = prog(t, 750, 700);
          lines.forEach((line, i) => text(line, W / 2, 1100 + i * (ls + 8), { size: ls, weight: 800, align: "center", a: a2, dy: (1 - a2) * 40 }));
          const y2 = 1100 + (lines.length - 1) * (ls + 8);
          text(core.fmtNum(top.plays) + NBSP + plural(top.plays, "odsłuchanie", "odsłuchania", "odsłuchań"), W / 2, y2 + 80, { size: 44, weight: 700, color: accent, align: "center", a: prog(t, 1000, 700) });
          list.slice(1, 5).forEach((a, i) => {
            const ai = prog(t, 1300 + i * 150, 600), y = y2 + 200 + i * 84;
            text(String(i + 2), P + 30, y, { size: 38, weight: 800, color: accent, a: ai, align: "center", dy: (1 - ai) * 24 });
            text(ellipsize(a.name, 40, 700, W - P * 2 - 220), P + 80, y, { size: 40, weight: 700, a: ai, dy: (1 - ai) * 24 });
            text(a.plays + "×", W - P, y, { size: 34, weight: 600, color: theme.muted, align: "right", a: ai, dy: (1 - ai) * 24 });
          });
          break;
        }
        case "tracks": {
          const tracks = period.top_tracks || [];
          label("TOP 5 UTWORÓW", prog(t, 100, 600), 230);
          const first = tracks[0], a1 = prog(t, 250, 800);
          coverBox(first.cover, P, 290 + (1 - a1) * 60, 330, a1, 26);
          const tx = P + 330 + 44, room = W - P - tx;
          const titleLines = wrap(first.title || "", 56, 800, 3, room);
          titleLines.forEach((line, i) => text(line, tx, 360 + i * 64, { size: 56, weight: 800, a: a1, dy: (1 - a1) * 30 }));
          const ty = 360 + (titleLines.length - 1) * 64;
          text(ellipsize(first.artist || "", 38, 600, room), tx, ty + 62, { size: 38, weight: 600, color: theme.muted, a: a1 });
          text(core.fmtNum(first.plays) + "×", tx, ty + 130, { size: 52, weight: 800, color: accent, a: a1 });
          tracks.slice(1, 5).forEach((tr, i) => {
            const ai = prog(t, 700 + i * 170, 650), y = 720 + i * 210;
            coverBox(tr.cover, P, y + (1 - ai) * 40, 150, ai, 18);
            text(String(i + 2), P + 190, y + 56, { size: 36, weight: 800, color: accent, a: ai });
            const room2 = W - P - (P + 250) - 130;
            text(ellipsize(tr.title || "", 42, 800, room2), P + 250, y + 62, { size: 42, weight: 800, a: ai, dy: (1 - ai) * 30 });
            text(ellipsize(tr.artist || "", 32, 600, room2), P + 250, y + 112, { size: 32, weight: 600, color: theme.muted, a: ai, dy: (1 - ai) * 30 });
            text(tr.plays + "×", W - P, y + 62, { size: 38, weight: 700, color: theme.muted, align: "right", a: ai });
          });
          break;
        }
        case "genres": {
          const genres = period.top_genres || [];
          label("GATUNKI", prog(t, 100, 600), 240);
          const a1 = prog(t, 250, 700);
          const fitted = oneLine(String(genres[0][0]), 150, 70, 800), topName = fitted.text, size = fitted.size;
          text(topName, P - 4, 420 + size * 0.8, { size, weight: 800, color: accent, a: a1, dy: (1 - a1) * 50 });
          text("numer jeden w tym okresie", P, 420 + size * 0.8 + 70, { size: 40, weight: 600, color: theme.muted, a: prog(t, 600, 700) });
          const max = Math.max(...genres.map((x) => x[1])) || 1;
          genres.forEach((gx, i) => {
            const ai = prog(t, 900 + i * 160, 700), y = 800 + i * 190;
            text(ellipsize(String(gx[0]), 44, 700, W - P * 2 - 170), P, y, { size: 44, weight: 700, a: ai, dy: (1 - ai) * 24 });
            const pct = period.plays ? Math.round((gx[1] / period.plays) * 100) : 0;
            text(pct + "%", W - P, y, { size: 44, weight: 800, color: accent, align: "right", a: ai });
            ctx.save(); ctx.globalAlpha = ai;
            ctx.fillStyle = "rgba(255,255,255,0.12)"; roundRect(ctx, P, y + 28, W - P * 2, 34, 17); ctx.fill();
            ctx.fillStyle = i === 0 ? accent : theme.accent2;
            const w = Math.max(34, (W - P * 2) * (gx[1] / max) * ease((t - 900 - i * 160) / 1100));
            roundRect(ctx, P, y + 28, w, 34, 17); ctx.fill(); ctx.restore();
          });
          break;
        }
        case "listener": {
          const l = period.listener;
          label("TWÓJ TYP SŁUCHACZA", prog(t, 100, 600), 380);
          const a1 = prog(t, 300, 800);
          const fitted = oneLine(l.name || "", 180, 80, 800), size = fitted.size;
          text(fitted.text, P - 4, 380 + 50 + size * 0.9, { size, weight: 800, a: a1, dy: (1 - a1) * 60 });
          const base = 380 + 50 + size * 0.9;
          const lines = wrap(l.blurb || "", 50, 500, 4);
          const a2 = prog(t, 900, 800);
          lines.forEach((line, i) => text(line, P, base + 120 + i * 68, { size: 50, weight: 500, color: theme.muted, a: a2, dy: (1 - a2) * 30 }));
          const y0 = base + 120 + lines.length * 68 + 90;
          (l.why || []).slice(0, 3).forEach((why, i) => {
            const ai = prog(t, 1400 + i * 220, 600), y = y0 + i * 120;
            ctx.save(); ctx.globalAlpha = ai; ctx.fillStyle = "rgba(255,255,255,0.1)";
            roundRect(ctx, P, y - 50, W - P * 2, 92, 46); ctx.fill();
            ctx.fillStyle = accent; ctx.beginPath(); ctx.arc(P + 44, y - 4, 10, 0, Math.PI * 2); ctx.fill(); ctx.restore();
            text(ellipsize(why, 36, 600, W - P * 2 - 130), P + 78, y + 8, { size: 36, weight: 600, a: ai });
          });
          break;
        }
        case "compare": {
          const c = period.compare || {};
          const label2 = "W PORÓWNANIU Z POPRZEDNIM" + NBSP + (period.kind === "month" ? "MIESIĄCEM" : "ROKIEM");
          label(label2, prog(t, 100, 600), 300);
          const rows = [["odsłuchań", c.plays, c.plays != null && c.prev_plays != null ? "było " + core.fmtNum(c.prev_plays) + " → jest " + core.fmtNum(period.plays) : ""],
            ["czasu słuchania", c.minutes, c.prev_minutes != null ? "było " + core.fmtListening(c.prev_minutes) + " → jest " + core.fmtListening(period.minutes) : ""],
            ["różnych wykonawców", c.artists, ""]].filter((r) => r[1] != null);
          rows.forEach((r, i) => {
            const ai = prog(t, 400 + i * 380, 800), y = 520 + i * 360, pct = r[1];
            const color = pct > 0 ? "#5ff0a3" : pct < 0 ? "#ff9a9a" : theme.text;
            const shown = Math.round(pct * ease((t - 400 - i * 380) / 1100));
            const txt = pct === 0 ? "bez zmian" : (pct > 0 ? "▲ " : "▼ ") + Math.abs(shown) + "%";
            text(txt, P - 4, y + 120, { size: 150, weight: 800, color, a: ai, dy: (1 - ai) * 50 });
            text(r[0], P, y + 190, { size: 44, weight: 700, a: ai });
            if (r[2]) text(r[2], P, y + 244, { size: 34, weight: 500, color: theme.muted, a: ai });
          });
          break;
        }
        case "finale": {
          label("NA KONIEC", prog(t, 100, 600), 250);
          const cells = [];
          const days = Number(period.active_days) || 0;
          cells.push([days, plural(days, "dzień z muzyką", "dni z muzyką", "dni z muzyką"), "z " + (period.days_elapsed || days)]);
          if ((period.longest_streak || 0) > 1) cells.push([period.longest_streak, "dni z rzędu", "najdłuższa seria"]);
          if (period.new_artists) cells.push([period.new_artists, plural(period.new_artists, "nowy wykonawca", "nowych wykonawców", "nowych wykonawców"), "odkrycia okresu"]);
          cells.push([period.artists || 0, plural(period.artists || 0, "wykonawca", "wykonawców", "wykonawców"), core.fmtNum(period.tracks) + NBSP + plural(period.tracks || 0, "utwór", "utwory", "utworów")]);
          cells.slice(0, 4).forEach((cell, i) => {
            const col = i % 2, row = Math.floor(i / 2), ai = prog(t, 300 + i * 220, 800);
            const x = P + col * ((W - P * 2) / 2), y = 380 + row * 400;
            ctx.save(); ctx.globalAlpha = ai; ctx.fillStyle = "rgba(255,255,255,0.08)";
            roundRect(ctx, x, y + (1 - ai) * 40, (W - P * 2) / 2 - 24, 340, 32); ctx.fill(); ctx.restore();
            const shown = Math.round(cell[0] * ease((t - 300 - i * 220) / 1200));
            text(core.fmtNum(shown), x + 36, y + 150 + (1 - ai) * 40, { size: 120, weight: 800, color: accent, a: ai });
            text(cell[1], x + 36, y + 224 + (1 - ai) * 40, { size: 38, weight: 700, a: ai });
            text(cell[2], x + 36, y + 276 + (1 - ai) * 40, { size: 30, weight: 500, color: theme.muted, a: ai });
          });
          if (period.busiest_day) {
            const ab = prog(t, 1300, 800);
            text("REKORDOWY DZIEŃ", P, 1240, { size: 26, weight: 700, color: theme.muted, spacing: 5, a: ab });
            text(core.fmtDay(period.busiest_day.day) + " · " + core.fmtNum(period.busiest_day.plays) + NBSP + plural(period.busiest_day.plays, "odsłuchanie", "odsłuchania", "odsłuchań"),
              P, 1304, { size: 46, weight: 800, a: ab, dy: (1 - ab) * 24 });
          }
          mark(ctx, accent, t, W / 2 - 78, 1420, 1.9, prog(t, 1600, 800));
          text("piterfy", W / 2, 1650, { size: 40, weight: 700, color: theme.muted, align: "center", a: prog(t, 1700, 600) });
          text(period.label || "", W / 2, 1710, { size: 30, weight: 500, color: theme.muted, align: "center", a: prog(t, 1800, 600) });
          break;
        }
        default: break;
      }
    }
    return { draw };
  }

  // ── ekran story ──
  function open(period) {
    if (!period || document.querySelector(".pf-story")) return;
    const slides = core.storySlides(period);
    if (!slides.length) return;
    const ids = [...new Set([].concat((period.top_artists || []).slice(0, 1).map((a) => a.cover), (period.top_tracks || []).map((tr) => tr.cover)).filter(Boolean))];
    let index = 0, t0 = performance.now(), pausedAt = null, holdPaused = false, userPaused = false, raf = 0, closed = false;
    let muted = false;
    try { muted = localStorage.getItem("pf-story-muted") === "1"; } catch (e) { /* bez zapamiętywania */ }
    const opener = document.activeElement;

    const root = document.createElement("div");
    root.className = "pf-story";
    root.setAttribute("role", "dialog"); root.setAttribute("aria-modal", "true"); root.setAttribute("aria-label", "Wrapped: " + (period.label || ""));
    root.innerHTML =
      '<div class="pf-story-stage" tabindex="-1">' +
      '<canvas class="pf-story-canvas" width="' + W + '" height="' + H + '"></canvas>' +
      '<div class="pf-story-bars">' + slides.map(() => "<span><i></i></span>").join("") + "</div>" +
      '<div class="pf-story-top">' +
      '<button type="button" class="pf-story-btn" data-sa="mute" aria-label="Wycisz muzykę" title="Wycisz / włącz muzykę (M)"></button>' +
      '<button type="button" class="pf-story-btn" data-sa="save" aria-label="Zapisz ten slajd jako obrazek" title="Zapisz slajd jako PNG">' + ICON.save + '</button>' +
      '<button type="button" class="pf-story-btn" data-sa="close" aria-label="Zamknij" title="Zamknij (Esc)">' + ICON.close + '</button></div>' +
      '<button type="button" class="pf-story-nav prev" data-sa="prev" aria-label="Poprzedni slajd" tabindex="-1">‹</button>' +
      '<button type="button" class="pf-story-nav next" data-sa="next" aria-label="Następny slajd" tabindex="-1">›</button>' +
      '<div class="pf-story-end" hidden><button type="button" class="pf-st-btn" data-sa="share">Udostępnij</button>' +
      '<button type="button" class="pf-st-btn pf-st-btn-ghost" data-sa="video">Pobierz wideo</button>' +
      '<button type="button" class="pf-st-btn pf-st-btn-ghost" data-sa="again">Jeszcze raz</button></div>' +
      '<p class="pf-story-now" hidden></p><p class="pf-story-live" role="status" aria-live="polite"></p></div>';
    document.body.appendChild(root);
    document.documentElement.classList.add("pf-story-open");
    const stage = root.querySelector(".pf-story-stage");
    const canvas = root.querySelector(".pf-story-canvas");
    const ctx = canvas.getContext("2d");
    const segs = [...root.querySelectorAll(".pf-story-bars i")];
    const endBox = root.querySelector(".pf-story-end");
    const live = root.querySelector(".pf-story-live");
    const muteBtn = root.querySelector('[data-sa="mute"]');
    const videoBtn = root.querySelector('[data-sa="video"]');
    if (!(window.MediaRecorder && canvas.captureStream)) videoBtn.hidden = true;

    const covers = makeCovers(ids, () => {});
    const renderer = makeRenderer(period, covers);
    const slide = () => slides[index];

    // ── muzyka: każdy slajd gra utwór o swoim temacie (core.soundtrackPlan); zmiana slajdu = płynne przejście ──
    const plan = core.soundtrackPlan(period);
    const segments = plan.segments;
    const music = { cur: -1, pos: 0, last: 0, playing: false, finished: -1, pagePaused: null, players: new Map(), fading: [] };
    const nowChip = root.querySelector(".pf-story-now");
    const segmentOf = (i) => { const sl = slides[i]; return sl && plan.bySlide[sl.id] != null ? plan.bySlide[sl.id] : -1; };
    const pageAudio = () => document.querySelector(PLAYER_AUDIO);
    function pausePage() {
      const a = pageAudio();
      if (a && !a.paused && !music.pagePaused) { music.pagePaused = a; try { a.pause(); } catch (e) { /* zostaje */ } }
    }
    function restorePage() {
      const a = music.pagePaused;
      music.pagePaused = null;
      if (a) { try { const p = a.play(); if (p && p.catch) p.catch(() => {}); } catch (e) { /* zostaje */ } }
    }
    function dispose(el) {
      try { el.pause(); } catch (e) { /* bez wpływu */ }
      el.removeAttribute("src");
      try { el.load(); } catch (e) { /* bez wpływu */ }
    }
    function playerFor(i) {
      let entry = music.players.get(i);
      if (!entry) {
        const el = new Audio();
        el.preload = "auto";
        // błąd strumienia = segment kończy się od razu (cisza do zmiany slajdu)
        el.addEventListener("error", () => { if (music.cur === i) music.pos = 1e9; });
        entry = { el, used: false };
        music.players.set(i, entry);
      }
      return entry;
    }
    function load(i) {
      const seg = segments[i];
      if (!seg) return null;
      const entry = playerFor(i);
      if (!entry.el.getAttribute("src")) { entry.el.src = streamUrl(seg.id, seg.start); entry.el.volume = 0; }
      return entry;
    }
    function paintNow() {
      const seg = segments[music.cur];
      const show = !!seg && !muted && !closed;
      nowChip.hidden = !show;
      if (show) nowChip.textContent = "♪ " + seg.title + (seg.artist ? " — " + seg.artist : "");
    }
    const musicWanted = () => segments.length > 0 && music.cur >= 0 && !muted && !holdPaused && !userPaused && !closed && !document.hidden;
    /** Slajd się zmienił: jeśli ma inny utwór, poprzedni cichnie, nowy startuje od początku swojego okna. */
    function selectSegment(i) {
      if (i !== music.cur) {
        const old = music.players.get(music.cur);
        if (old && music.playing) music.fading.push({ el: old.el, entry: old }); // wycisza się w tickMusic
        music.cur = i; music.pos = 0; music.playing = false; music.finished = -1;
        const entry = music.players.get(i);
        if (entry && entry.used) { dispose(entry.el); music.players.delete(i); } // ponowne wejście: utwór od początku
        if (i >= 0) load(i);
      }
      const ahead = segmentOf(index + 1);
      if (ahead >= 0 && ahead !== music.cur) load(ahead); // następny utwór buforuje się z wyprzedzeniem
      for (const [k, entry] of [...music.players]) {
        if (k !== music.cur && k !== ahead && !music.fading.some((f) => f.entry === entry)) { dispose(entry.el); music.players.delete(k); }
      }
    }
    function syncMusic() {
      const entry = music.cur >= 0 && music.finished !== music.cur ? load(music.cur) : null;
      if (musicWanted() && entry) {
        if (!music.playing) {
          pausePage();
          music.playing = true; music.last = performance.now(); entry.used = true;
          const p = entry.el.play();
          if (p && p.catch) p.catch(() => { music.playing = false; }); // przeglądarka zablokowała start: cisza, pokaz idzie dalej
        }
      } else if (music.playing) {
        music.playing = false;
        const cur = music.players.get(music.cur);
        if (cur) cur.el.pause();
      }
      if (!musicWanted()) endFades();
      paintNow();
    }
    function endFades() {
      music.fading.forEach((f) => { try { f.el.pause(); } catch (e) { /* bez wpływu */ } });
      music.fading = [];
    }
    function stopMusicNow() {
      music.playing = false; music.fading = [];
      music.players.forEach((entry) => dispose(entry.el));
      music.players.clear();
    }
    function tickMusic(now) {
      const dt = Math.min(0.25, Math.max(0, (now - music.last) / 1000));
      if (music.fading.length) {
        music.fading = music.fading.filter((f) => {
          const v = f.el.volume - dt / 0.6;
          if (v > 0.01) { f.el.volume = clamp(v, 0, 1); return true; }
          try { f.el.pause(); } catch (e) { /* bez wpływu */ }
          return false;
        });
      }
      if (music.playing && music.cur >= 0) {
        music.pos += dt;
        const seg = segments[music.cur], entry = music.players.get(music.cur);
        if (entry) {
          entry.el.volume = clamp(core.previewGain(music.pos, seg.length), 0, 1);
          if (music.pos >= seg.length || entry.el.ended) { entry.el.pause(); music.playing = false; music.finished = music.cur; paintNow(); }
        }
      }
      music.last = now;
    }

    // ── czas, rysowanie, przejścia ──
    const elapsed = (now) => (pausedAt == null ? now : pausedAt) - t0;
    function setIndex(next, now) {
      next = clamp(next, 0, slides.length - 1);
      const before = index;
      index = next; t0 = now; if (pausedAt != null) pausedAt = now;
      if (segments.length) { selectSegment(segmentOf(index)); if (index !== before) syncMusic(); }
      endBox.hidden = slide().id !== "finale";
      live.textContent = "Slajd " + (index + 1) + " z " + slides.length;
      segs.forEach((s, i) => { s.style.transform = "scaleX(" + (i < index ? 1 : 0) + ")"; });
    }
    function frame(now) {
      if (closed) return;
      raf = requestAnimationFrame(frame);
      if (document.hidden) return;
      const e = elapsed(now), s = slide();
      if (!holdPaused && !userPaused && s.ms && e >= s.ms) { if (index < slides.length - 1) { setIndex(index + 1, now); return; } }
      tickMusic(now);
      ctx.clearRect(0, 0, W, H);
      renderer.draw(ctx, s, e);
      segs.forEach((seg, i) => {
        if (i === index) seg.style.transform = "scaleX(" + (s.ms ? clamp(e / s.ms, 0, 1) : 1) + ")";
      });
    }
    const next = () => { const now = performance.now(); if (index < slides.length - 1) setIndex(index + 1, now); };

    // ── muzyka: przycisk wyciszenia ──
    function paintMute() {
      muteBtn.innerHTML = muted ? ICON.off : ICON.on;
      muteBtn.setAttribute("aria-pressed", muted ? "true" : "false");
      muteBtn.hidden = !(period.top_tracks || []).length;
    }
    function toggleMute() {
      muted = !muted;
      try { localStorage.setItem("pf-story-muted", muted ? "1" : "0"); } catch (e) { /* bez zapamiętywania */ }
      paintMute();
      syncMusic();
    }

    // ── eksport: PNG slajdu i wideo ──
    function renderTo(canvasEl, slideObj, t) {
      const c = canvasEl.getContext("2d");
      c.clearRect(0, 0, W, H);
      renderer.draw(c, slideObj, t);
    }
    function blobOf(canvasEl, type) { return new Promise((resolve) => canvasEl.toBlob(resolve, type || "image/png")); }
    function deliver(blob, fileName, share, title) {
      if (!blob) return;
      const file = new File([blob], fileName, { type: blob.type });
      if (share && navigator.canShare && navigator.canShare({ files: [file] })) {
        navigator.share({ files: [file], title }).catch(() => {});
        return;
      }
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = fileName;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
    }
    function exportSlide(share) {
      covers.all().then(() => {
        const off = document.createElement("canvas");
        off.width = W; off.height = H;
        const draw = () => { renderTo(off, slide(), END); return blobOf(off); };
        const make = () => draw().then((blob) => deliver(blob, NAME + "-" + (period.key || "okres") + "-" + (index + 1) + ".png", share, "Piterfy Wrapped · " + (period.label || "")));
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(make); else make();
      });
    }
    let recording = null;
    function exportVideo() {
      if (recording) { recording.cancel(); return; }
      const types = ["video/mp4;codecs=avc1", "video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"];
      const mime = types.find((m) => window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(m));
      if (!mime) return;
      const off = document.createElement("canvas");
      off.width = W; off.height = H;
      const stream = off.captureStream(30);
      const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 6000000 });
      const chunks = [];
      rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
      const plan = slides.map((s) => ({ s, ms: s.id === "finale" ? VIDEO_FINALE_MS : s.id === "tracks" ? VIDEO_TRACK_MS : VIDEO_MS }));
      const total = plan.reduce((a, p) => a + p.ms, 0);
      let cancelled = false, begun = 0, timer = 0;
      const label0 = videoBtn.textContent;
      recording = { cancel() { cancelled = true; } };
      const finish = () => {
        clearInterval(timer); recording = null; videoBtn.textContent = label0;
        if (!cancelled) deliver(new Blob(chunks, { type: mime.split(";")[0] }), NAME + "-" + (period.key || "okres") + "." + (mime.startsWith("video/mp4") ? "mp4" : "webm"), false, "");
      };
      rec.onstop = finish;
      covers.all().then(() => {
        if (cancelled) { recording = null; return; }
        rec.start(250); begun = performance.now();
        timer = setInterval(() => {
          const e = performance.now() - begun;
          if (cancelled || e >= total) { clearInterval(timer); try { rec.stop(); } catch (err) { finish(); } return; }
          let acc = 0, cur = plan[plan.length - 1], local = 0;
          for (const p of plan) { if (e < acc + p.ms) { cur = p; local = e - acc; break; } acc += p.ms; }
          renderTo(off, cur.s, local);
          videoBtn.textContent = "Nagrywam… " + Math.max(0, Math.ceil((total - e) / 1000)) + " s (kliknij, by przerwać)";
        }, 33);
      });
    }

    // ── sterowanie ──
    let press = null;
    // gdyby przeglądarka zablokowała autostart dźwięku, pierwszy dotyk w pokazie go uruchomi
    stage.addEventListener("pointerdown", () => { if (!music.playing) syncMusic(); }, true);
    stage.addEventListener("pointerdown", (e) => {
      if (e.target.closest("button")) return;
      press = { x: e.clientX, y: e.clientY, at: performance.now(), held: false, id: e.pointerId };
      press.timer = setTimeout(() => {
        if (!press) return;
        press.held = true; holdPaused = true; pausedAt = performance.now(); syncMusic();
      }, 240);
    });
    const release = (e, cancelled) => {
      if (!press) return;
      clearTimeout(press.timer);
      const p = press; press = null;
      const now = performance.now();
      if (p.held) {
        holdPaused = false;
        if (pausedAt != null) { t0 += now - pausedAt; pausedAt = userPaused ? now : null; }
        syncMusic();
        return;
      }
      if (cancelled) return;
      const dx = e.clientX - p.x, dy = e.clientY - p.y;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) { dx < 0 ? next() : setIndex(index - 1, now); return; }
      if (Math.abs(dx) > 14 || Math.abs(dy) > 14) return;
      const rect = stage.getBoundingClientRect();
      if (e.clientX - rect.left < rect.width / 3) setIndex(index - 1, now); else next();
    };
    stage.addEventListener("pointerup", (e) => release(e, false));
    stage.addEventListener("pointercancel", (e) => release(e, true));
    stage.addEventListener("contextmenu", (e) => e.preventDefault());

    root.addEventListener("click", (e) => {
      const b = e.target.closest("[data-sa]");
      if (!b) { if (e.target === root) close(); return; }
      const act = b.dataset.sa, now = performance.now();
      if (act === "close") close();
      else if (act === "next") next();
      else if (act === "prev") setIndex(index - 1, now);
      else if (act === "mute") toggleMute();
      else if (act === "save") exportSlide(false);
      else if (act === "share") exportSlide(true);
      else if (act === "video") exportVideo();
      else if (act === "again") setIndex(0, now);
    });
    const onKey = (e) => {
      if (closed) return;
      const now = performance.now();
      if (e.key === "Escape") { e.preventDefault(); close(); }
      else if (e.key === "ArrowRight") { e.preventDefault(); next(); }
      else if (e.key === "ArrowLeft") { e.preventDefault(); setIndex(index - 1, now); }
      else if (e.key === " " && !e.target.closest("button")) {
        e.preventDefault();
        userPaused = !userPaused;
        if (userPaused) pausedAt = now; else { t0 += now - pausedAt; pausedAt = null; }
        syncMusic();
      } else if (e.key === "m" || e.key === "M") toggleMute();
    };
    document.addEventListener("keydown", onKey, true);
    const onHide = () => syncMusic();
    document.addEventListener("visibilitychange", onHide);

    function close() {
      if (closed) return;
      closed = true;
      cancelAnimationFrame(raf);
      if (recording) recording.cancel();
      document.removeEventListener("keydown", onKey, true);
      document.removeEventListener("visibilitychange", onHide);
      stopMusicNow();
      restorePage();
      root.remove();
      document.documentElement.classList.remove("pf-story-open");
      if (opener && opener.focus && opener.isConnected) { try { opener.focus(); } catch (e) { /* bez wpływu */ } }
    }

    paintMute();
    setIndex(0, performance.now());
    syncMusic();
    stage.focus({ preventScroll: true });
    raf = requestAnimationFrame(frame);
    return { close };
  }

  /** Rysuje jeden slajd (po załadowaniu okładek) na podanym <canvas> 1080 × 1920; do podglądu i testów. */
  function render(period, slideId, t, canvasEl) {
    const slide = core.storySlides(period).find((x) => x.id === slideId);
    if (!slide) return Promise.resolve(false);
    const ids = [...new Set([].concat((period.top_artists || []).slice(0, 1).map((x) => x.cover), (period.top_tracks || []).map((x) => x.cover)).filter(Boolean))];
    const covers = makeCovers(ids, null);
    return covers.all().then(() => {
      canvasEl.width = W; canvasEl.height = H;
      makeRenderer(period, covers).draw(canvasEl.getContext("2d"), slide, t == null ? END : t);
      return true;
    });
  }

  window.PiterfyStory = { open, render };
})();
