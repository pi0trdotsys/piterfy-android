/* Piterfy — fala dźwięku zamiast kreski postępu: czysta logika (bez DOM, testy: wave-core.test.cjs).
 * Serwer oddaje GET /piterfy-api/peaks/{id}: 200 {"v":1,"n":120..240,"peaks":[0..255],"duration":s}, 202 {"pending":true}
 * (jeszcze liczy), cokolwiek innego = brak fali (zostaje zwykły pasek). Tu: ocena odpowiedzi, dopasowanie liczby słupków do
 * szerokości, odstępy ponownych prób, „oddychanie” fali, czas pod palcem przy przeciąganiu i id utworu z adresu strumienia. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyWaveCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const MIN_PEAKS = 16, MAX_PEAKS = 2000;
  const RETRY_MS = [2000, 4000, 8000, 15000, 30000, 60000];
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** Odpowiedź serwera → {state:"ready", peaks:[0..1], duration} | {state:"pending"} | {state:"none"}. */
  function classify(status, body) {
    if (status === 202) return { state: "pending" };
    if (status !== 200 || !body || typeof body !== "object") return { state: "none" };
    if (body.pending === true) return { state: "pending" };
    const list = body.peaks;
    if (body.v !== 1 || !Array.isArray(list) || list.length < MIN_PEAKS || list.length > MAX_PEAKS) return { state: "none" };
    const peaks = list.map((v) => clamp((Number(v) || 0) / 255, 0, 1));
    const duration = Number(body.duration);
    return { state: "ready", peaks, duration: Number.isFinite(duration) && duration > 0 ? duration : 0 };
  }

  /** Ile ms czekać przed ponowną próbą po `attempt`-tym „pending” (od 0); null = dość prób, zostaje zwykły pasek. */
  function retryDelay(attempt) { return attempt >= 0 && attempt < RETRY_MS.length ? RETRY_MS[attempt] : null; }

  /** Układ słupków dla szerokości w px: {count, bar, gap}; wąskie paski dostają cieńsze słupki. */
  function layout(width, { bar = 3, gap = 2 } = {}) {
    const w = Math.max(0, Number(width) || 0);
    if (w < 40) return { count: 0, bar, gap };
    const unit = w < 160 ? Math.max(2, bar - 1) + Math.max(1, gap - 1) : bar + gap;
    const thin = w < 160;
    return { count: Math.max(1, Math.floor((w + (thin ? 1 : gap)) / unit)), bar: thin ? Math.max(2, bar - 1) : bar, gap: thin ? Math.max(1, gap - 1) : gap };
  }

  /** Spłaszcza szczyty do `count` słupków (maksimum z każdego przedziału, żeby szczyty nie znikały). */
  function resample(peaks, count) {
    const n = peaks.length;
    if (!count || !n) return [];
    const out = new Array(count);
    for (let i = 0; i < count; i += 1) {
      const from = Math.floor((i * n) / count), to = Math.max(from + 1, Math.floor(((i + 1) * n) / count));
      let max = 0;
      for (let j = from; j < to && j < n; j += 1) if (peaks[j] > max) max = peaks[j];
      out[i] = max;
    }
    return out;
  }

  /** Wysokość słupka (0–1) z lekkim „oddychaniem” (gdy gra i ruch dozwolony); ma minimalną wysokość, żeby cisza była widać. */
  function height(value, i, count, t, breathing) {
    const base = Math.max(0.08, clamp(value, 0, 1));
    if (!breathing || !count) return base;
    const wobble = Math.sin(t / 420 + (i / count) * Math.PI * 6) * 0.045 + Math.sin(t / 970 + i * 1.7) * 0.025;
    return clamp(base * (1 + wobble), 0.06, 1);
  }

  /** Czy słupek i jest już odtworzony (jego środek leży przed bieżącą pozycją). */
  function played(i, count, progress) { return count > 0 && (i + 0.5) / count <= clamp(progress, 0, 1); }

  /** Czas pod palcem: x w px od lewej krawędzi fali → sekundy. */
  function scrubTime(x, width, duration) {
    if (!(width > 0) || !(duration > 0)) return 0;
    return clamp(x / width, 0, 1) * duration;
  }

  function format(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
    const s = Math.floor(seconds), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    return (h ? h + ":" + String(m).padStart(2, "0") : String(m)) + ":" + String(r).padStart(2, "0");
  }

  /** Id utworu z adresu strumienia Navidrome (/rest/stream?id=…, /rest/getTranscodeStream?mediaId=…) albo "". */
  function songIdFromSrc(src, base = "http://localhost/") {
    try {
      const url = new URL(src, base);
      if (!/^\/rest\/(stream|getTranscodeStream|download)(\.view)?$/.test(url.pathname)) return "";
      return url.searchParams.get("id") || url.searchParams.get("mediaId") || "";
    } catch (e) { return ""; }
  }

  /** Czy dla tego utworu pytamy w ogóle o falę: bez podcastów-na-żywo i radia (brak czasu trwania = brak fali). */
  function wanted(duration) { return Number.isFinite(duration) && duration > 5 && duration < 6 * 3600; }

  return { MIN_PEAKS, classify, retryDelay, layout, resample, height, played, scrubTime, format, songIdFromSrc, wanted };
});
