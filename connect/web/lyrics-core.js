/* Pure timing functions, shared by the browser UI and its tests. */
(function(root) {
  const api = {
    parse(lrc) {
      const lines = []; let offset = 0;
      for (const raw of lrc.split(/\r?\n/)) {
        const tag = raw.match(/\[offset:([+-]?\d+)\]/i);
        if (tag) offset = Number(tag[1]);
        const times = [...raw.matchAll(/\[(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
        if (!times.length) continue;
        const last = times[times.length - 1];
        const value = raw.slice(last.index + last[0].length).trim();
        times.forEach(t => lines.push({ start: Number(t[1]) * 60000 + Number(t[2]) * 1000 + Number(((t[3] || '') + '000').slice(0, 3)), value }));
      }
      return { lines: lines.sort((a, b) => a.start - b.start), offset, synced: !!lines.length };
    },
    active(lines, position, offset = 0) {
      let active = -1;
      lines.forEach((line, i) => { if (Number.isFinite(line.start) && line.start <= position + offset) active = i; });
      return active;
    },
    /** Postęp „karaoke” linii `index` (0–1): od jej początku do początku następnej; długie przerwy skracamy do LINE_MAX_MS, ostatnia linia trwa LAST_LINE_MS. */
    progress(lines, index, position, offset = 0) {
      const line = lines[index];
      if (!line || !Number.isFinite(line.start)) return 0;
      const next = lines[index + 1];
      const span = next && Number.isFinite(next.start) ? Math.min(next.start - line.start, api.LINE_MAX_MS) : api.LAST_LINE_MS;
      if (!(span > 0)) return 1;
      return Math.min(1, Math.max(0, (position + offset - line.start) / span));
    },
    /** Wygląd linii wg odległości od aktywnej (0 = aktywna): skala, przezroczystość i rozmycie w px (to samo liczy PiterfyLyricsKaraoke.kt). */
    style(distance) {
      const d = Math.min(Math.abs(Math.round(Number(distance) || 0)), api.STYLE_ALPHA.length - 1);
      return { scale: d === 0 ? 1 : api.INACTIVE_SCALE, alpha: api.STYLE_ALPHA[d], blur: api.STYLE_BLUR[d] };
    },
    /**
     * Zawija tekst do szerokości `maxWidth` (zachłannie po słowach; słowo szersze niż linia jest łamane po znakach).
     * `measure(tekst)` zwraca szerokość w tych samych jednostkach co `maxWidth` (na stronie ctx.measureText, w testach atrapa).
     */
    wrap(text, maxWidth, measure) {
      const words = String(text || '').split(/\s+/).filter(Boolean);
      const lines = []; let line = '';
      const push = () => { if (line) lines.push(line); line = ''; };
      for (const word of words) {
        const candidate = line ? line + ' ' + word : word;
        if (measure(candidate) <= maxWidth) { line = candidate; continue; }
        push();
        if (measure(word) <= maxWidth) { line = word; continue; }
        let piece = '';  // słowo dłuższe niż linia (np. tekst bez spacji): łamiemy po znakach
        for (const ch of [...word]) {
          if (piece && measure(piece + ch) > maxWidth) { lines.push(piece); piece = ''; }
          piece += ch;
        }
        line = piece;
      }
      push();
      return lines;
    },
    /**
     * Dobiera największy rozmiar czcionki (od `max` w dół do `min`, krok `step`), przy którym zawinięty cytat mieści się
     * w ramce `width` × `height`. `measureAt(rozmiar, tekst)` mierzy szerokość tekstu danym rozmiarem. Gdy nic się nie mieści,
     * zwraca `min` i ucina linie do tylu, ile się mieści (ostatnia kończy się „…”).
     */
    fitCard(text, measureAt, { width, height, max = 104, min = 44, step = 4, lineHeight = 1.22 }) {
      for (let size = max; size >= min; size -= step) {
        const lines = api.wrap(text, width, t => measureAt(size, t));
        const total = lines.length * size * lineHeight;
        if (lines.length && total <= height) return { size, lines, height: total, truncated: false };
      }
      const lines = api.wrap(text, width, t => measureAt(min, t));
      const fit = Math.max(1, Math.floor(height / (min * lineHeight)));
      const cut = lines.slice(0, fit);
      if (lines.length > fit) cut[fit - 1] = cut[fit - 1].replace(/[\s.,;:!?-]*$/, '') + '…';
      return { size: min, lines: cut, height: cut.length * min * lineHeight, truncated: lines.length > fit };
    },
    LINE_MAX_MS: 9000,
    LAST_LINE_MS: 4000,
    INACTIVE_SCALE: 0.86,
    STYLE_ALPHA: [1, 0.56, 0.42, 0.36, 0.3, 0.26],
    STYLE_BLUR: [0, 0.5, 1.4, 2.4, 3.2, 4],
    structured(entries) {
      if (!Array.isArray(entries)) return null;
      const selected = entries.filter(x => x && Array.isArray(x.line) && x.line.some(line => typeof line?.value === 'string' && (!x.synced || Number.isFinite(line.start)))).sort((a, b) => Number(!!b.synced) - Number(!!a.synced))[0];
      if (!selected) return null;
      const lines = selected.line.filter(x => typeof x?.value === 'string' && (!selected.synced || Number.isFinite(x.start)));
      if (selected.synced) lines.sort((a, b) => a.start - b.start);
      return lines.length ? { lines, offset: Number(selected.offset) || 0, synced: !!selected.synced } : null;
    }
  };
  if (typeof module !== 'undefined') module.exports = api;
  else root.PiterfyLyricsTiming = api;
})(typeof window === 'undefined' ? {} : window);
