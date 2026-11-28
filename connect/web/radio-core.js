/* Piterfy — „Radio z utworu / wykonawcy”: czysta logika (bez DOM, testy: radio-core.test.cjs).
 * Radio buduje nieskończoną kolejkę podobnych utworów z biblioteki. Tu są tylko decyzje: co odfiltrować, w jakiej kolejności
 * ułożyć (lekkie tasowanie, ten sam wykonawca nie dwa razy pod rząd), kiedy dociągnąć kolejne i co zapamiętać, żeby się nie
 * powtarzać. Pobieranie podobnych (Subsonic getSimilarSongs2) i dokładanie do kolejki robi radio.js. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyRadioCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const MEMORY_SIZE = 100;   // tyle ostatnich utworów pamiętamy (bez powtórek)
  const TOP_UP_AT = 3;       // dociągamy, gdy po bieżącym zostaje najwyżej tyle utworów
  const BATCH = 10;          // tyle utworów dokładamy za jednym razem
  const JITTER = 0.35;       // jak mocno miesza kolejność podobieństwa (ułamek długości listy)

  const text = (v) => String(v == null ? "" : v);
  /** Klucz wykonawcy do reguły „nie dwa razy pod rząd”: małe litery, bez interpunkcji i akcentów. */
  function artistKey(song) {
    const raw = text(song && (song.albumArtist || song.artist)).normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase();
    return raw.replace(/\bfeat\.?.*$|\bft\.?.*$/, "").replace(/[^a-z0-9]+/g, " ").trim();
  }

  /** Odcinek podcastu (po typie, gatunku albo ścieżce „Podcasty/”) — nie wchodzi do radia. */
  function podcastLike(song) {
    if (!song) return false;
    const type = text(song.type || song.mediaType).toLowerCase();
    return type === "podcast" || type === "podcastepisode" || /(^|\/)podcasty?\//i.test(text(song.path)) || /podcast/i.test(text(song.genre));
  }

  /**
   * Oczyszcza listę podobnych: bez pustych, podcastów, ocenionych na 1★, nasiona, już zapamiętanych i duplikatów.
   * `options`: { exclude: zbiór/tablica id do pominięcia, memory: tablica id, isPodcast: funkcja }.
   */
  function clean(songs, options) {
    const opts = options || {};
    const skip = new Set([].concat(opts.exclude ? [...opts.exclude] : [], opts.memory || []).map(String));
    const isPodcast = opts.isPodcast || podcastLike;
    const seen = new Set(), result = [];
    for (const song of songs || []) {
      if (!song || song.id == null) continue;
      const id = String(song.id);
      if (skip.has(id) || seen.has(id) || isPodcast(song) || Number(song.userRating) === 1) continue;
      seen.add(id);
      result.push(song);
    }
    return result;
  }

  /**
   * Układa utwory: kolejność od serwisu (podobieństwo) z lekkim zaszumieniem, żeby radio za każdym razem było trochę inne,
   * a potem reguła: ten sam wykonawca nie dwa razy pod rząd (jeśli w najbliższych 6 pozycjach jest ktoś inny).
   * `previousArtist` to wykonawca utworu, po którym zaczyna się ta partia.
   */
  function order(songs, rng, previousArtist) {
    const random = rng || Math.random;
    const spread = Math.max(2, songs.length * JITTER);
    const pool = songs.map((song, i) => ({ song, key: i + random() * spread })).sort((a, b) => a.key - b.key).map((x) => x.song);
    const out = [];
    let last = previousArtist || "";
    while (pool.length) {
      // z najbliższych pozycji bierzemy innego wykonawcę niż poprzedni, a spośród nich tego z największą liczbą utworów
      // jeszcze w puli (żeby na końcu partii nie został jednolity ogon jednego wykonawcy)
      const left = new Map();
      for (const s of pool) left.set(artistKey(s), (left.get(artistKey(s)) || 0) + 1);
      let pick = -1;
      for (let i = 0; i < Math.min(6, pool.length); i++) {
        const key = artistKey(pool[i]);
        if (key !== last && (pick < 0 || left.get(key) > left.get(artistKey(pool[pick])))) pick = i;
      }
      if (pick < 0) pick = 0;
      const [song] = pool.splice(pick, 1);
      out.push(song);
      last = artistKey(song);
    }
    return out;
  }

  /** Kolejka na start: nasiono (jeśli jest) pierwsze, potem podobne po oczyszczeniu i ułożeniu. */
  function build(seed, similar, options) {
    const opts = options || {};
    const exclude = new Set(opts.exclude ? [...opts.exclude] : []);
    if (seed && seed.id != null) exclude.add(String(seed.id));
    const rest = order(clean(similar, { ...opts, exclude }), opts.rng, seed ? artistKey(seed) : "");
    const first = seed && seed.id != null && !(opts.isPodcast || podcastLike)(seed) ? [seed] : [];
    return first.concat(rest);
  }

  /** Czy dociągnąć kolejne: po bieżącym utworze zostaje najwyżej `threshold` pozycji. */
  function needMore(queueLength, currentIndex, threshold) {
    if (!(queueLength > 0) || currentIndex < 0) return false;
    return queueLength - 1 - currentIndex <= (threshold == null ? TOP_UP_AT : threshold);
  }

  /** Pamięć ostatnich utworów: nowe na początku, bez duplikatów, najwyżej `max`. */
  function remember(memory, ids, max) {
    const limit = max || MEMORY_SIZE;
    const fresh = (ids || []).map(String);
    return fresh.concat((memory || []).map(String).filter((id) => !fresh.includes(id))).slice(0, limit);
  }

  /**
   * Czy radio ma się wyłączyć: gra utwór, którego radio nie dołożyło (użytkownik uruchomił coś innego), a od naszej
   * ostatniej zmiany kolejki minęła karencja. `known` — zbiór id dołożonych przez radio.
   */
  function shouldStop(currentTrackId, known, sinceChangeMs, graceMs) {
    if (currentTrackId == null) return false;
    if (sinceChangeMs < (graceMs == null ? 5000 : graceMs)) return false;
    return !known.has(String(currentTrackId));
  }

  /** Id, po których pytamy o podobne przy dociąganiu: bieżący i dwa poprzednie utwory kolejki (bez powtórek). */
  function seedsForTopUp(queueTrackIds, currentIndex) {
    const out = [];
    for (let i = currentIndex; i >= 0 && out.length < 3; i--) {
      const id = queueTrackIds[i];
      if (id != null && !out.includes(String(id))) out.push(String(id));
    }
    return out;
  }

  /** Łączy listy podobnych z kilku zapytań, zachowując porządek „na zmianę” (pierwsze z każdej listy najpierw). */
  function interleave(lists) {
    const out = [];
    const longest = Math.max(0, ...lists.map((l) => l.length));
    for (let i = 0; i < longest; i++) for (const list of lists) if (i < list.length) out.push(list[i]);
    return out;
  }

  return { MEMORY_SIZE, TOP_UP_AT, BATCH, artistKey, podcastLike, clean, order, build, needMore, remember, shouldStop, seedsForTopUp, interleave };
});
