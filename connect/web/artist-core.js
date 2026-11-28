/* Piterfy — strona wykonawcy (czysta logika, bez DOM i sieci; testy: artist-core.test.cjs).
 *   • „Popularne z Twojej biblioteki”: ranking utworów (Last.fm przez getTopSongs, a bez niego po własnych odsłuchaniach z albumów),
 *   • oś czasu dyskografii: albumy z biblioteki pogrupowane po latach + wydania z radaru nowości, których jeszcze nie masz,
 *   • przewijanie: przesunięcie paralaksy i postęp zwijania nagłówka. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyArtistCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  const str = (v) => (v == null ? "" : String(v));
  const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : 0; };

  /** Nazwa do porównań: małe litery, bez akcentów i znaków specjalnych, pojedyncze spacje. */
  function normName(text) {
    return str(text).normalize("NFKD").replace(/[̀-ͯ]/g, "").replace(/ł/gi, "l").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  }

  /** Czy wydanie z radaru jest tego wykonawcy (nazwy równe po normalizacji albo jedna zaczyna się od drugiej na granicy słowa: „X” ~ „X & Y”). */
  function sameArtist(a, b) {
    const x = normName(a), y = normName(b);
    if (!x || !y) return false;
    return x === y || x.startsWith(y + " ") || y.startsWith(x + " ");
  }

  /** Utwory „popularne”: bez duplikatów (id i tytuł), bez podcastów, od najczęściej słuchanych; remis = kolejność z wejścia (Last.fm). */
  function rankPopular(songs, limit, isPodcast) {
    const seenId = new Set(), best = new Map();
    (Array.isArray(songs) ? songs : []).forEach((song, order) => {
      if (!song || song.id == null || seenId.has(str(song.id))) return;
      if (typeof isPodcast === "function" && isPodcast(song)) return;
      seenId.add(str(song.id));
      const key = normName(song.title);
      const row = { song, order, plays: num(song.playCount) };
      const old = best.get(key);
      if (!old || row.plays > old.plays) best.set(key, row);
    });
    return Array.from(best.values()).sort((a, b) => b.plays - a.plays || a.order - b.order).slice(0, limit || 5).map((r) => r.song);
  }

  /** Albumy, z których warto złożyć „popularne”, gdy Last.fm nic nie dał: najpierw najczęściej słuchane, potem najnowsze. */
  function fallbackAlbums(albums, n) {
    return (Array.isArray(albums) ? albums : []).filter((a) => a && a.id != null)
      .map((a, order) => ({ a, order })).sort((x, y) => num(y.a.playCount) - num(x.a.playCount) || num(y.a.year) - num(x.a.year) || x.order - y.order)
      .slice(0, n || 4).map((r) => r.a);
  }

  const yearOf = (text) => { const m = /^(\d{4})/.exec(str(text)); return m ? Number(m[1]) : 0; };
  const DAY = 86400000;

  /**
   * Oś czasu: [{year, items:[…]}] od najnowszego roku. Pozycje:
   *   {kind:"album", id, title, coverId, sub, badge}      — z biblioteki (badge „Nowość”, gdy dodane w ciągu `freshDays` dni),
   *   {kind:"new", id, title, cover (https), sub, type, released, status, have, total, note} — z radaru, jeszcze bez albumu w bibliotece.
   * Wydanie z radaru, które ma już ten sam tytuł w bibliotece, nie dubluje się.
   */
  function timeline(albums, radarItems, artistName, now, freshDays) {
    const fresh = (freshDays || 45) * DAY;
    const items = [];
    const have = new Set();
    (Array.isArray(albums) ? albums : []).forEach((a) => {
      if (!a || a.id == null) return;
      have.add(normName(a.name));
      const created = Date.parse(a.created || "") || 0;
      const year = num(a.year) || yearOf(a.created);
      items.push({
        kind: "album", id: str(a.id), title: str(a.name), coverId: str(a.coverArt || ("al-" + a.id)), year, sortKey: str(a.created || year),
        sub: [year || "", num(a.songCount) ? num(a.songCount) + " utw." : ""].filter(Boolean).join(" · "), badge: created && now - created < fresh ? "Nowość" : "",
      });
    });
    (Array.isArray(radarItems) ? radarItems : []).forEach((r) => {
      if (!r || r.id == null || !sameArtist(r.artist, artistName) || have.has(normName(r.title))) return;
      items.push({
        kind: "new", id: str(r.id), title: str(r.title), cover: /^https:\/\//.test(str(r.cover)) ? str(r.cover) : "", year: yearOf(r.released), sortKey: str(r.released),
        type: str(r.type), released: str(r.released), status: str(r.status), have: num(r.have), total: num(r.total), note: str(r.note), badge: "Nowość", sub: "",
      });
    });
    const groups = new Map();
    items.forEach((item) => { const y = item.year || 0; if (!groups.has(y)) groups.set(y, []); groups.get(y).push(item); });
    return Array.from(groups.keys()).sort((a, b) => (b || -1) - (a || -1)).map((year) => ({
      year,
      items: groups.get(year).sort((a, b) => str(b.sortKey).localeCompare(str(a.sortKey)) || str(a.title).localeCompare(str(b.title), "pl")),
    }));
  }

  /** Czy oś czasu ma sens: co najmniej dwie pozycje (przy jednym albumie sama siatka Navidrome wystarcza). */
  const worthShowing = (groups) => (Array.isArray(groups) ? groups : []).reduce((sum, g) => sum + g.items.length, 0) >= 2;

  /** Przesunięcie paralaksy tła w px (0 przy braku przewinięcia, ograniczone). */
  function parallax(scroll, factor, max) {
    const y = Math.max(0, num(scroll)) * (factor == null ? 0.35 : factor);
    return Math.round(Math.min(y, max == null ? 180 : max) * 10) / 10;
  }

  /** Zwinięcie nagłówka 0–1: 0 na górze, 1, gdy przewinięto ~80 % jego wysokości. */
  function collapse(scroll, heroHeight) {
    const h = num(heroHeight);
    if (h <= 0) return 0;
    return Math.round(Math.min(1, Math.max(0, num(scroll) / (h * 0.8))) * 1000) / 1000;
  }

  /** Czy pokazać pasek z nazwą: gdy nagłówek (jego dolna krawędź) zjechał pod górną krawędź treści. */
  const showBar = (heroBottom, topEdge) => num(heroBottom) < num(topEdge) + 24;

  /** Ciemniejszy odcień koloru okładki na gradient tła strony (mix z czernią). Zwraca "#rrggbb" albo "" przy błędnym wejściu. */
  function shade(hex, amount) {
    const m = /^#?([0-9a-f]{6})$/i.exec(str(hex).trim());
    if (!m) return "";
    const n = parseInt(m[1], 16), keep = 1 - Math.min(1, Math.max(0, num(amount)));
    const ch = (v) => Math.round(v * keep).toString(16).padStart(2, "0");
    return "#" + ch((n >> 16) & 255) + ch((n >> 8) & 255) + ch(n & 255);
  }

  return { normName, sameArtist, rankPopular, fallbackAlbums, timeline, worthShowing, parallax, collapse, showBar, shade };
});
