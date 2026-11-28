/* Piterfy — szybkość odczuwalna (czysta logika, bez DOM i sieci; testy: speed-core.test.cjs).
 *   • kolory okładek: średni kolor obrazka (próbka pikseli) i trwały podręczny zbiór „id okładki → kolor” (localStorage), z którego
 *     szkielety i kafelki biorą tło, zanim obrazek się załaduje,
 *   • stale-while-revalidate dla bezpiecznych, tylko-do-odczytu zapytań Subsonic, które Piterfy wysyła sam (klient „Piterfy…”):
 *     które zapytania wolno trzymać (cacheable), co je unieważnia (invalidates), jak długo (swrPlan) i gdzie (SwrStore).
 * Zapytań samego interfejsu Navidrome (klient „NavidromeUI”) ani niczego z efektami ubocznymi nie dotykamy. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfySpeedCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  // ── kolory ──
  const clamp255 = (v) => Math.min(255, Math.max(0, Math.round(v)));
  const hex2 = (n) => clamp255(n).toString(16).padStart(2, "0");
  const toHex = (c) => "#" + hex2(c.r) + hex2(c.g) + hex2(c.b);
  function fromHex(text) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(text || "").trim());
    if (!m) return null;
    const n = parseInt(m[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
  }

  /**
   * Średni kolor z pikseli RGBA (Uint8ClampedArray / tablica). Ważony: żywe, średnio jasne piksele liczą się bardziej niż
   * prawie czarne, prawie białe i szare, więc okładka z dużą czarną ramką dalej daje kolor motywu. Zwraca {r,g,b} albo null.
   */
  function averageColour(data) {
    if (!data || data.length < 4) return null;
    let r = 0, g = 0, b = 0, w = 0, plain = { r: 0, g: 0, b: 0, n: 0 };
    for (let i = 0; i + 3 < data.length; i += 4) {
      const pr = data[i], pg = data[i + 1], pb = data[i + 2], pa = data[i + 3];
      if (pa < 128) continue;
      const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
      const lum = (0.2126 * pr + 0.7152 * pg + 0.0722 * pb) / 255;
      const sat = max === 0 ? 0 : (max - min) / max;
      const mid = Math.max(0, 1 - Math.abs(lum - 0.5) * 1.6);   // 1 dla średniej jasności, 0 dla prawie czerni i bieli
      const weight = 0.05 + sat * mid;
      r += pr * weight; g += pg * weight; b += pb * weight; w += weight;
      plain.r += pr; plain.g += pg; plain.b += pb; plain.n += 1;
    }
    if (!plain.n) return null;
    return w > 0 ? { r: r / w, g: g / w, b: b / w } : { r: plain.r / plain.n, g: plain.g / plain.n, b: plain.b / plain.n };
  }

  /** Kolor tła placeholdera: kolor okładki przyciemniony ku ciemnemu panelowi (tak, żeby nie raził przed załadowaniem obrazka). */
  function placeholderColour(hex, towards) {
    const c = fromHex(hex), base = fromHex(towards || "#161616");
    if (!c || !base) return "";
    const mix = (a, b) => a * 0.72 + b * 0.28;
    return toHex({ r: mix(c.r, base.r), g: mix(c.g, base.g), b: mix(c.b, base.b) });
  }

  /** Id okładki z adresu getCoverArt („…/rest/getCoverArt?id=al-12&size=300…”); null, gdy to nie okładka. */
  function coverId(src) {
    const m = /\/rest\/getCoverArt(?:\.view)?\?([^#]*)/.exec(String(src || ""));
    if (!m) return null;
    try { return new URLSearchParams(m[1]).get("id") || null; } catch (e) { return null; }
  }

  const COLOUR_KEY = "pf-cover-colours";
  const COLOUR_MAX = 400;

  /** Podręczny zbiór kolorów okładek na magazynie z getItem/setItem; najstarsze wypadają po przekroczeniu COLOUR_MAX. */
  function ColourCache(storage, now) {
    const clock = typeof now === "function" ? now : () => Date.now();
    let map = {};
    try { const raw = JSON.parse(storage.getItem(COLOUR_KEY) || "{}"); if (raw && typeof raw === "object") map = raw; } catch (e) { map = {}; }
    const save = () => { try { storage.setItem(COLOUR_KEY, JSON.stringify(map)); } catch (e) { /* bez zapisu działa do odświeżenia */ } };
    return {
      get(id) { const row = map[id]; return row && fromHex(row[0]) ? row[0] : null; },
      set(id, hex) {
        if (!id || !fromHex(hex)) return false;
        const old = map[id];
        map[id] = [String(hex).toLowerCase(), clock()];
        const ids = Object.keys(map);
        if (ids.length > COLOUR_MAX) {
          ids.sort((a, b) => map[a][1] - map[b][1]).slice(0, ids.length - COLOUR_MAX).forEach((k) => delete map[k]);
        }
        if (!old || old[0] !== map[id][0]) save();
        return true;
      },
      /** Ostatnio zapamiętane kolory (od najnowszych), do kolorowania szkieletów. */
      recent(n) { return Object.keys(map).sort((a, b) => map[b][1] - map[a][1]).slice(0, n).map((k) => map[k][0]); },
      size: () => Object.keys(map).length,
    };
  }

  // ── stale-while-revalidate ──
  const FRESH_MS = 20 * 1000;          // młodsze niż 20 s: odpowiedź z podręcznego zbioru bez pytania serwera
  const STALE_MS = 6 * 3600 * 1000;    // do 6 h: odpowiedź od razu + odświeżenie w tle
  const ERROR_MS = 7 * 86400 * 1000;   // gdy serwer nie odpowiada: nawet tygodniowa odpowiedź jest lepsza niż pusta strona
  const MAX_ENTRY = 250000;            // pojedyncza odpowiedź większa niż ~250 KB nie trafia do podręcznego zbioru
  const MAX_TOTAL = 1500000;           // cały zbiór ~1,5 MB (localStorage ma ~5 MB na wszystko)
  const STORE_KEY = "pf-swr-v1";

  // tylko odczyty; bez getRandomSongs i getSimilarSongs2 (losowe — mają się zmieniać), bez getPlayQueue i getBookmarks (muszą być świeże)
  const READ_ONLY = new Set(["getAlbumList2", "getPlaylists", "getPlaylist", "getStarred2", "getAlbum", "getArtist", "getArtistInfo2",
    "getTopSongs", "getGenres", "search3", "getMusicFolders", "getSong"]);
  const MUTATIONS = new Set(["star", "unstar", "setRating", "scrobble", "createPlaylist", "updatePlaylist", "deletePlaylist",
    "createBookmark", "deleteBookmark", "savePlayQueue", "createPodcastChannel", "deletePodcastChannel"]);

  function parseUrl(url) {
    try { return new URL(String(url), "http://x.invalid"); } catch (e) { return null; }
  }
  const commandOf = (u) => { const m = u && /^\/rest\/([A-Za-z0-9]+)(?:\.view)?$/.exec(u.pathname); return m ? m[1] : null; };

  /**
   * Klucz podręcznego zbioru dla zapytania, które wolno trzymać, albo null. Warunki: GET, /rest/<odczyt z listy>, klient „Piterfy…”
   * (czyli nie sam interfejs Navidrome), a przy getAlbumList2 nie „random”. Dane logowania (u/t/s) nie wchodzą do klucza, ale login tak.
   */
  function cacheable(url, method) {
    if (String(method || "GET").toUpperCase() !== "GET") return null;
    const u = parseUrl(url);
    const cmd = u && commandOf(u);
    if (!cmd || !READ_ONLY.has(cmd)) return null;
    const p = u.searchParams;
    if (!/^Piterfy/.test(p.get("c") || "")) return null;
    if (cmd === "getAlbumList2" && p.get("type") === "random") return null;
    const rest = [];
    p.forEach((value, name) => { if (!["t", "s", "u", "v", "c", "f", "_"].includes(name)) rest.push([name, value]); });
    rest.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0));
    return (p.get("u") || "") + "|" + cmd + "?" + rest.map(([k, v]) => k + "=" + v).join("&");
  }

  /** Czy zapytanie coś zmienia (wtedy czyścimy cały zbiór): mutacje Subsonic od dowolnego klienta oraz zapis w natywnym /api/. */
  function invalidates(url, method) {
    const m = String(method || "GET").toUpperCase();
    const u = parseUrl(url);
    if (!u) return false;
    const cmd = commandOf(u);
    if (cmd && MUTATIONS.has(cmd)) return true;
    return /^\/api\//.test(u.pathname) && m !== "GET" && m !== "HEAD";
  }

  /** Co zrobić z wpisem o wieku `age` ms: "network" (brak/za stary), "cache" (świeży), "stale" (zwróć i odśwież w tle). */
  function swrPlan(entry, now) {
    if (!entry || typeof entry.t !== "number") return "network";
    const age = now - entry.t;
    if (age < 0) return "network";
    if (age < FRESH_MS) return "cache";
    if (age < STALE_MS) return "stale";
    return "network";
  }

  /** Wpis awaryjny, gdy serwer nie odpowiada. */
  const usableOnError = (entry, now) => !!entry && typeof entry.t === "number" && now - entry.t >= 0 && now - entry.t < ERROR_MS;

  /** Magazyn odpowiedzi (JSON jako tekst) z limitem rozmiaru, usuwaniem najstarszych i bezpiecznym zapisem. */
  function SwrStore(storage, now) {
    const clock = typeof now === "function" ? now : () => Date.now();
    let map = {};
    try { const raw = JSON.parse(storage.getItem(STORE_KEY) || "{}"); if (raw && typeof raw === "object") map = raw; } catch (e) { map = {}; }
    const size = () => Object.keys(map).reduce((sum, k) => sum + (map[k].b ? map[k].b.length : 0) + k.length, 0);
    const save = () => { try { storage.setItem(STORE_KEY, JSON.stringify(map)); } catch (e) { map = {}; try { storage.removeItem(STORE_KEY); } catch (e2) { /* ignore */ } } };
    return {
      get: (key) => (map[key] && typeof map[key].b === "string" ? map[key] : null),
      put(key, body) {
        if (typeof body !== "string" || body.length > MAX_ENTRY) return false;
        map[key] = { t: clock(), b: body };
        let total = size();
        if (total > MAX_TOTAL) {
          Object.keys(map).sort((a, b) => map[a].t - map[b].t).some((k) => {
            if (k === key) return false;
            total -= (map[k].b ? map[k].b.length : 0) + k.length; delete map[k];
            return total <= MAX_TOTAL;
          });
        }
        save();
        return true;
      },
      clear() { map = {}; try { storage.removeItem(STORE_KEY); } catch (e) { /* ignore */ } },
      keys: () => Object.keys(map),
      size,
    };
  }

  return {
    COLOUR_MAX, FRESH_MS, STALE_MS, ERROR_MS, MAX_ENTRY, MAX_TOTAL, READ_ONLY, MUTATIONS,
    toHex, fromHex, averageColour, placeholderColour, coverId, ColourCache, cacheable, invalidates, swrPlan, usableOnError, SwrStore,
  };
});
