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
