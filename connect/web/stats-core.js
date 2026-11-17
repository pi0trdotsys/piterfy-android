/* Piterfy — czysta logika „Statystyk dla nerdów” (bez DOM, testowana w Node: stats-core.test.cjs).
 * Serwer (connect/stack/discover/stats.py) liczy wszystko; tutaj tylko formatujemy liczby i układamy dane
 * pod wykresy oraz kartę Wrapped, żeby sam ekran (stats.js) zajmował się wyłącznie rysowaniem. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyStatsCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const NBSP = " ";
  const MONTHS = ["sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru"];

  const num = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);

  /** 12 345 (twarda spacja jako separator tysięcy, zawsze tak samo — niezależnie od ICU przeglądarki). */
  function fmtNum(value) {
    return String(Math.round(num(value))).replace(/\B(?=(\d{3})+(?!\d))/g, NBSP);
  }

  /** Liczba z przecinkiem dziesiętnym po polsku, bez zbędnego „,0”. */
  function fmtDec(value, digits) {
    const text = num(value).toFixed(digits == null ? 1 : digits);
    return (text.includes(".") ? text.replace(/\.?0+$/, "") : text).replace(".", ",");
  }

  function fmtBytes(bytes) {
    let value = num(bytes);
    const units = ["B", "KB", "MB", "GB", "TB", "PB"];
    let i = 0;
    while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
    const digits = i === 0 ? 0 : value >= 100 ? 0 : value >= 10 ? 1 : 2;
    return fmtDec(value, digits) + NBSP + units[i];
  }

  /** „12 d 5 h”, „5 h 12 min”, „12 min”. */
  function fmtDuration(seconds) {
    const total = Math.max(0, Math.round(num(seconds)));
    const d = Math.floor(total / 86400);
    const h = Math.floor((total % 86400) / 3600);
    const m = Math.floor((total % 3600) / 60);
    if (d) return d + NBSP + "d" + NBSP + h + NBSP + "h";
    if (h) return h + NBSP + "h" + NBSP + m + NBSP + "min";
    return m + NBSP + "min";
  }

  /** Minuty słuchania: „412 godz.” od 10 godzin, inaczej „95 min”. */
  function fmtListening(minutes) {
    const m = num(minutes);
    return m >= 600 ? fmtNum(m / 60) + NBSP + "godz." : fmtNum(m) + NBSP + "min";
  }

  function fmtPercent(part, total, digits) {
    return total > 0 ? fmtDec((num(part) / num(total)) * 100, digits == null ? 0 : digits) + "%" : "—";
  }

  /** „▲ 18%” / „▼ 7%” / „bez zmian” / „” (brak porównania). */
  function deltaText(pct) {
    if (pct == null || !Number.isFinite(pct)) return "";
    if (pct === 0) return "bez zmian";
    return (pct > 0 ? "▲ " : "▼ ") + Math.abs(pct) + "%";
  }

  /** „5 lis 2026” z „2026-11-05” (bez przesunięć strefy czasowej). */
  function fmtDay(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ""));
    return m ? Number(m[3]) + NBSP + MONTHS[Number(m[2]) - 1] + NBSP + m[1] : "—";
  }

  /** „16.11” z „2026-11-16”. */
  function fmtShortDay(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ""));
    return m ? Number(m[3]) + "." + m[2] : "";
  }

  /** Czas ISO (np. z ułamkami sekund) → „16 lis 2026, 14:05” w czasie lokalnym przeglądarki. */
  function fmtStamp(value) {
    const t = Date.parse(value);
    if (!Number.isFinite(t)) return "—";
    const d = new Date(t);
    const pad = (n) => String(n).padStart(2, "0");
    return d.getDate() + NBSP + MONTHS[d.getMonth()] + NBSP + d.getFullYear() + ", " + pad(d.getHours()) + ":" + pad(d.getMinutes());
  }

  /** Szerokość słupka w procentach względem największej wartości (najmniejszy widoczny: 2%, zero zostaje zerem). */
  function barPercent(value, max) {
    if (!(num(value) > 0) || !(num(max) > 0)) return 0;
    return Math.max(2, Math.round((num(value) / num(max)) * 100));
  }

  /** Wiersze wykresu słupkowego z par [etykieta, liczba]. */
  function bars(pairs) {
    const rows = (pairs || []).map((p) => ({ label: String(p[0]), n: num(p[1]) }));
    const max = rows.reduce((m, r) => Math.max(m, r.n), 0);
    return rows.map((r) => Object.assign({ pct: barPercent(r.n, max) }, r));
  }

  /** Struktura jakości plików: segmenty do paska skumulowanego (kolejność = kolejność na pasku). */
  function qualityBreakdown(q) {
    q = q || {};
    const segments = [
      { key: "ok", label: "Bez zastrzeżeń", n: num(q.ok), tone: "good" },
      { key: "suspect", label: "Podejrzane", n: num(q.suspect) + num(q.upsampled), tone: "warn" },
      { key: "fake", label: "Udawane", n: num(q.fake) + num(q.transcoded), tone: "bad" },
      { key: "pending", label: "Jeszcze nie zbadane", n: num(q.pending) + num(q.unknown), tone: "muted" },
    ];
    const total = segments.reduce((s, x) => s + x.n, 0);
    return { total, segments: segments.map((s) => Object.assign({ pct: total ? (s.n / total) * 100 : 0 }, s)) };
  }

  /** Oś czasu przyrostu biblioteki: [{label, n, bytes, pct}] z par [poniedziałek, liczba, bajty]. */
  function growthSeries(weeks) {
    const rows = (weeks || []).map((w) => ({ label: fmtShortDay(w[0]), n: num(w[1]), bytes: num(w[2]) }));
    const max = rows.reduce((m, r) => Math.max(m, r.n), 0);
    return rows.map((r) => Object.assign({ pct: barPercent(r.n, max) }, r));
  }

  /** Zajęcie dysku: części paska (biblioteka, reszta zajęta, wolne) i procent zajętego miejsca. */
  function diskBreakdown(disk, libraryBytes, trashBytes) {
    if (!disk || !(num(disk.total) > 0)) return null;
    const total = num(disk.total);
    const library = Math.min(num(libraryBytes), num(disk.used));
    const trash = Math.min(num(trashBytes), Math.max(0, num(disk.used) - library));
    const other = Math.max(0, num(disk.used) - library - trash);
    return {
      total, free: num(disk.free), usedPct: Math.round((num(disk.used) / total) * 100),
      parts: [
        { key: "library", label: "Biblioteka", bytes: library, tone: "accent" },
        { key: "trash", label: "Kosz discovera", bytes: trash, tone: "warn" },
        { key: "other", label: "Pozostałe", bytes: other, tone: "muted" },
        { key: "free", label: "Wolne", bytes: num(disk.free), tone: "free" },
      ].map((p) => Object.assign({ pct: (p.bytes / total) * 100 }, p)),
    };
  }

  const OUTCOME_LABELS = {
    done: "Pobrane i w bibliotece", pending: "W trakcie", trashed: "Pobrane, potem wyrzucone",
    notfound: "Nie ma w Soulseek", failed: "Pobieranie nie ruszyło", fake: "Fałszywa jakość",
  };

  function outcomeRows(outcomes) {
    return Object.keys(OUTCOME_LABELS).filter((k) => num((outcomes || {})[k]) > 0)
      .map((k) => ({ key: k, label: OUTCOME_LABELS[k], n: num(outcomes[k]) }));
  }

  /** Model karty Wrapped: wszystko, co rysujemy na obrazku, jako gotowe napisy. */
  function cardModel(period) {
    if (!period) return null;
    const compare = period.compare;
    const delta = compare && compare.plays != null
      ? { text: deltaText(compare.plays) + " odsłuchów", up: compare.plays > 0, flat: compare.plays === 0 }
      : null;
    const facts = [];
    facts.push(fmtNum(period.tracks) + NBSP + "różnych utworów od " + fmtNum(period.artists) + NBSP + "wykonawców");
    facts.push(fmtNum(period.active_days) + NBSP + "dni z muzyką" + (period.longest_streak > 1
      ? ", seria " + period.longest_streak + NBSP + "dni" : ""));
    if (period.new_artists) facts.push(fmtNum(period.new_artists) + NBSP + "nowych wykonawców");
    if (period.busiest_day) facts.push("Rekord: " + fmtDay(period.busiest_day.day) + " (" + fmtNum(period.busiest_day.plays) + NBSP + "odsłuchów)");
    return {
      kicker: "PITERFY WRAPPED",
      title: String(period.label || "") + (period.partial ? " · do dziś" : ""),
      big: fmtListening(period.minutes),
      bigCaption: "słuchania · " + fmtNum(period.plays) + NBSP + "odsłuchów",
      listener: period.listener ? { name: period.listener.name, blurb: period.listener.blurb, why: (period.listener.why || []).slice(0, 2) } : null,
      artists: (period.top_artists || []).slice(0, 3).map((a) => ({ name: a.name, plays: a.plays })),
      track: period.top_tracks && period.top_tracks[0]
        ? { title: period.top_tracks[0].title, artist: period.top_tracks[0].artist, plays: period.top_tracks[0].plays } : null,
      facts: facts.slice(0, 4),
      delta,
      fileName: "piterfy-wrapped-" + String(period.key || "okres") + ".png",
    };
  }

  /** Zawija napis do szerokości (mierzonej przez measure(tekst) → piksele), najwyżej maxLines linii (ostatnia ze „…”). */
  function wrapText(text, maxWidth, measure, maxLines) {
    const words = String(text || "").split(/\s+/).filter(Boolean);
    const lines = [];
    let line = "";
    for (const word of words) {
      const next = line ? line + " " + word : word;
      if (measure(next) <= maxWidth || !line) line = next;
      else { lines.push(line); line = word; }
    }
    if (line) lines.push(line);
    if (maxLines && lines.length > maxLines) {
      const kept = lines.slice(0, maxLines);
      let last = kept[maxLines - 1];
      while (last.length > 1 && measure(last + "…") > maxWidth) last = last.slice(0, -1);
      kept[maxLines - 1] = last.replace(/[\s,.;:–-]+$/, "") + "…";
      return kept;
    }
    return lines;
  }

  // ── „story”: slajdy Wrapped w pionie (9:16) ──
  const SLIDE_MS = 7000, TRACK_SLIDE_MS = 12000;
  const PREVIEW_SECONDS = 25, PREVIEW_FADE_IN = 0.8, PREVIEW_FADE_OUT = 0.6;
  const SOUNDTRACK_TRACKS = 5, SOUNDTRACK_SEGMENT = 45;

  /** Lista slajdów dla okresu; slajdy bez danych (gatunki, typ słuchacza, porównanie) są pomijane. */
  function storySlides(period) {
    if (!period) return [];
    const slides = [{ id: "intro", ms: SLIDE_MS }, { id: "time", ms: SLIDE_MS }];
    if ((period.top_artists || []).length) slides.push({ id: "artist", ms: SLIDE_MS });
    if ((period.top_tracks || []).length) slides.push({ id: "tracks", ms: TRACK_SLIDE_MS });
    if ((period.top_genres || []).length) slides.push({ id: "genres", ms: SLIDE_MS });
    if (period.listener) slides.push({ id: "listener", ms: SLIDE_MS });
    if (period.compare && (period.compare.plays != null || period.compare.minutes != null || period.compare.artists != null)) {
      slides.push({ id: "compare", ms: SLIDE_MS });
    }
    slides.push({ id: "finale", ms: 0 }); // finał zostaje na ekranie (bez automatycznego przejścia)
    return slides;
  }

  /** Okno podglądu utworu: od 35% długości (od początku przy krótkich), najwyżej maxLength s (domyślnie 25) i nie dalej niż koniec. */
  function previewWindow(seconds, maxLength) {
    const total = Math.max(0, num(seconds));
    const max = maxLength > 0 ? maxLength : PREVIEW_SECONDS;
    const start = total >= 60 ? Math.floor(total * 0.35) : 0;
    const length = total > 0 ? Math.min(max, total - start) : max;
    return { start, length: Math.max(1, length) };
  }

  /** Ścieżka dźwiękowa pokazu: do 5 utworów z top listy, każdy jako fragment (start, length ≤ 45 s) grany jeden po drugim. */
  function soundtrack(tracks) {
    return (tracks || []).filter((t) => t && t.id).slice(0, SOUNDTRACK_TRACKS).map((t) => {
      const w = previewWindow(t.seconds, SOUNDTRACK_SEGMENT);
      return { id: String(t.id), title: String(t.title || ""), artist: String(t.artist || ""), start: w.start, length: w.length };
    });
  }

  /** Głośność podglądu (0–1) po t sekundach od startu: wejście 0,8 s, wyjście 0,6 s przed końcem okna. */
  function previewGain(t, length) {
    const fadeIn = Math.min(1, Math.max(0, t / PREVIEW_FADE_IN));
    const fadeOut = Math.min(1, Math.max(0, (length - t) / PREVIEW_FADE_OUT));
    return Math.min(fadeIn, fadeOut);
  }

  /** Średni kolor nasyconych pikseli (RGBA z miniatury okładki) jako „#rrggbb”; null, gdy obraz jest szary/pusty. */
  function dominantColor(rgba) {
    let r = 0, g = 0, b = 0, weight = 0;
    for (let i = 0; i + 3 < rgba.length; i += 4) {
      if (rgba[i + 3] < 200) continue;
      const max = Math.max(rgba[i], rgba[i + 1], rgba[i + 2]), min = Math.min(rgba[i], rgba[i + 1], rgba[i + 2]);
      const sat = max ? (max - min) / max : 0;
      const light = max / 255;
      const w = sat * sat * (light > 0.15 && light < 0.98 ? 1 : 0.1);
      r += rgba[i] * w; g += rgba[i + 1] * w; b += rgba[i + 2] * w; weight += w;
    }
    if (weight < 0.5) return null;
    const hex = (v) => Math.round(Math.min(255, v / weight)).toString(16).padStart(2, "0");
    return "#" + hex(r) + hex(g) + hex(b);
  }

  /** Jasność akcentu do tekstu: rozjaśnia za ciemne kolory (min. luminancja ~0,45), żeby napisy były czytelne. */
  function readableAccent(hex, fallback) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ""));
    if (!m) return fallback;
    let [r, g, b] = [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
    for (let k = 0; k < 6 && 0.299 * r + 0.587 * g + 0.114 * b < 115; k++) { r += (255 - r) * 0.2; g += (255 - g) * 0.2; b += (255 - b) * 0.2; }
    return "#" + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, "0")).join("");
  }

  return {
    fmtNum, fmtDec, fmtBytes, fmtDuration, fmtListening, fmtPercent, deltaText, fmtDay, fmtShortDay, fmtStamp,
    barPercent, bars, qualityBreakdown, growthSeries, diskBreakdown, outcomeRows, cardModel, wrapText,
    storySlides, previewWindow, soundtrack, previewGain, dominantColor, readableAccent, SLIDE_MS, TRACK_SLIDE_MS,
  };
});
