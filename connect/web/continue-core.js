/* Piterfy — strona startowa zależna od kontekstu (czysta logika, bez DOM i zegara; testy: continue-core.test.cjs).
 *   • pora dnia: powitanie z podpisem, kolejność półek i miksów (rano spokojniejsze, wieczorem ciemniejsze i radio),
 *   • „Kontynuuj”: ostatnio słuchane odcinki, albumy i kolejka od miejsca, w którym skończyłeś — z lokalnej historii
 *     (pf-resume), zakładek Subsonic (getBookmarks) i kolejki zapisanej na serwerze przez inne urządzenie (getPlayQueue).
 * Wszystkie funkcje dostają godzinę i czas jako argumenty, więc testy są deterministyczne. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyContinueCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  // ── pora dnia ──
  const PERIODS = ["morning", "afternoon", "evening", "night"];

  /** 5–12 rano, 12–18 popołudnie, 18–23 wieczór, reszta noc. */
  function period(hour) {
    const h = ((Math.floor(Number(hour)) % 24) + 24) % 24;
    if (!Number.isFinite(h)) return "afternoon";
    if (h >= 5 && h < 12) return "morning";
    if (h >= 12 && h < 18) return "afternoon";
    if (h >= 18 && h < 23) return "evening";
    return "night";
  }

  const GREETINGS = {
    morning: { text: "Dzień dobry", sub: "Na spokojny początek dnia" },
    afternoon: { text: "Dzień dobry", sub: "Muzyka na resztę dnia" },
    evening: { text: "Dobry wieczór", sub: "Na wieczór: ciemniejsze brzmienia i radio" },
    night: { text: "Dobrej nocy", sub: "Cicho i na wyciszenie" },
  };

  function greeting(hour) {
    const p = period(hour);
    return Object.assign({ period: p }, GREETINGS[p]);
  }

  // Wagi miksów wg nazwy (część nazwy bez wielkości liter): im wyżej, tym wcześniej w rzędzie „Twoje miksy”.
  const MIX_WEIGHTS = {
    morning: { chill: 4, happy: 3, "lata 80": 1, "lata 90": 1, french: 2, latin: 1, brazil: 2, "asia pop": 1, polska: 1, workout: 1, dark: -3, phonk: -3, bass: -2 },
    afternoon: { workout: 3, rock: 3, happy: 3, electronic: 2, "hip-hop": 2, polska: 2, "polski rap": 2, bass: 1, chill: 1 },
    evening: { dark: 4, electronic: 3, bass: 3, phonk: 3, "hip-hop": 2, "polski rap": 2, chill: 1, happy: -1, workout: -1 },
    night: { chill: 4, dark: 3, electronic: 2, "lata 80": 1, phonk: 1, workout: -3, bass: -2, happy: -1 },
  };

  function mixScore(name, per) {
    const text = String(name || "").toLowerCase();
    const weights = MIX_WEIGHTS[per] || {};
    return Object.keys(weights).reduce((sum, key) => (text.includes(key) ? sum + weights[key] : sum), 0);
  }

  /** Stabilna kolejność: wyższy wynik pierwszy, remisy zostają w pierwotnym porządku. Zwraca nową tablicę nazw. */
  function orderMixes(names, per) {
    return (Array.isArray(names) ? names : []).map((name, index) => ({ name, index, score: mixScore(name, per) }))
      .sort((a, b) => b.score - a.score || a.index - b.index).map((x) => x.name);
  }

  // Priorytety półek strony startowej (tytuł h2). „Kontynuuj” zawsze pierwsza; nie wymienione zostają za wymienionymi w dawnej kolejności.
  const SECTION_PRIORITY = {
    morning: ["Kontynuuj", "Twoje miksy", "Świeże odkrycia", "Odkryte dla Ciebie", "Nowości od Twoich artystów", "Ostatnio słuchane"],
    afternoon: ["Kontynuuj", "Ostatnio słuchane", "Twoje miksy", "Najczęściej słuchane", "Odkryte dla Ciebie"],
    evening: ["Kontynuuj", "Twoje miksy", "Odkryte dla Ciebie", "Świeże odkrycia", "Twoi ulubieni wykonawcy"],
    night: ["Kontynuuj", "Twoje miksy", "Ostatnio słuchane", "Twoje playlisty"],
  };

  /** Układ tytułów półek po przestawieniu wg pory dnia (stabilnie); zwraca nową tablicę. */
  function orderSections(titles, per) {
    const rank = (SECTION_PRIORITY[per] || ["Kontynuuj"]);
    const weight = (title) => { const i = rank.indexOf(title); return i < 0 ? rank.length : i; };
    return (Array.isArray(titles) ? titles : []).map((title, index) => ({ title, index }))
      .sort((a, b) => weight(a.title) - weight(b.title) || a.index - b.index).map((x) => x.title);
  }

  // ── „Kontynuuj”: wpisy ──
  const MAX_ENTRIES = 10;
  const MAX_AGE_MS = 60 * 86400000;
  const FINISHED = 0.95;
  const IDS_CAP = 100;

  const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : 0; };
  const str = (v) => (v == null ? "" : String(v));

  /**
   * Wpis z migawki odtwarzacza: { items:[{id,title,artist,album,albumId,duration,type}], index, position (ms) }.
   * Odcinek podcastu → „episode”, cała kolejka z jednego albumu → „album”, reszta → „queue” (jedna, najnowsza).
   */
  function entryFromSnapshot(snap, now) {
    const items = snap && Array.isArray(snap.items) ? snap.items.filter((x) => x && x.id != null) : [];
    const index = snap && Number.isFinite(snap.index) ? snap.index : 0;
    const cur = snap && snap.items ? snap.items[index] : null;
    if (!cur || cur.id == null) return null;
    const position = Math.max(0, num(snap.position) / 1000);
    const duration = Math.max(0, num(cur.duration));
    const base = { at: num(now), position, duration, source: "local" };
    if (cur.type === "podcast") {
      return Object.assign(base, { key: "ep:" + cur.id, kind: "episode", title: str(cur.title), sub: str(cur.album || cur.artist), coverId: str(cur.id), items: [str(cur.id)], index: 0 });
    }
    const albumId = str(cur.albumId);
    if (items.length >= 2 && albumId && items.every((x) => str(x.albumId) === albumId)) {
      return Object.assign(base, { key: "al:" + albumId, kind: "album", title: str(cur.album || "Album"), sub: "Utwór " + (index + 1) + " z " + items.length + ": " + str(cur.title),
        coverId: str(cur.id), items: items.map((x) => str(x.id)).slice(0, IDS_CAP), index: Math.min(index, IDS_CAP - 1) });
    }
    const start = Math.max(0, Math.min(index - 10, items.length - IDS_CAP));
    const ids = items.slice(start, start + IDS_CAP).map((x) => str(x.id));
    return Object.assign(base, {
      key: "q", kind: "queue", title: items.length > 1 ? "Ostatnia kolejka" : str(cur.title),
      sub: str(cur.artist) + (items.length > 1 ? " · " + items.length + " utworów" : ""), coverId: str(cur.id), items: ids, index: Math.max(0, index - start),
    });
  }

  /** Wpis z zakładki Subsonic ({entry:{…utwór…}, position (ms), changed}). */
  function entryFromBookmark(bookmark, isPodcast) {
    const song = bookmark && bookmark.entry;
    if (!song || song.id == null) return null;
    const podcast = typeof isPodcast === "function" ? !!isPodcast(song) : false;
    const at = Date.parse(bookmark.changed || bookmark.created || "") || 0;
    return {
      key: (podcast ? "ep:" : "bm:") + song.id, kind: podcast ? "episode" : "queue", title: str(song.title), sub: str(podcast ? song.album || song.artist : song.artist),
      coverId: str(song.coverArt || song.id), items: [str(song.id)], index: 0, position: Math.max(0, num(bookmark.position) / 1000), duration: Math.max(0, num(song.duration)),
      at, source: "bookmark",
    };
  }

  /** Wpis z kolejki zapisanej na serwerze (getPlayQueue: {current, position (ms), entry:[…], changed, changedBy}). */
  function entryFromPlayQueue(queue) {
    const list = queue && Array.isArray(queue.entry) ? queue.entry.filter((s) => s && s.id != null) : [];
    if (!list.length) return null;
    const found = list.findIndex((s) => str(s.id) === str(queue.current));
    const index = found < 0 ? 0 : found;
    const song = list[index];
    const at = Date.parse(queue.changed || "") || 0;
    const who = str(queue.changedBy);
    return {
      key: "pq", kind: "queue", title: list.length > 1 ? "Kolejka" + (who ? " z: " + who : "") : str(song.title),
      sub: str(song.title) + (song.artist ? " — " + str(song.artist) : ""), coverId: str(song.coverArt || song.id),
      items: list.map((s) => str(s.id)).slice(0, IDS_CAP), index: Math.min(index, IDS_CAP - 1), position: Math.max(0, num(queue.position) / 1000), duration: Math.max(0, num(song.duration)),
      at, source: "queue",
    };
  }

  /** Dodaje albo odświeża wpis (po kluczu), najnowszy na górze, z ograniczoną długością; „queue” jest jedna. */
  function upsert(list, entry, max) {
    const rest = (Array.isArray(list) ? list : []).filter((e) => e && e.key !== (entry && entry.key));
    if (!entry || !entry.key) return rest.slice(0, max || MAX_ENTRIES);
    return [entry].concat(rest).sort((a, b) => num(b.at) - num(a.at)).slice(0, max || MAX_ENTRIES);
  }

  /** Postęp 0–1 (0, gdy czas trwania nieznany). */
  function progress(entry) {
    const d = num(entry && entry.duration);
    return d > 0 ? Math.min(1, Math.max(0, num(entry.position) / d)) : 0;
  }

  /** Czy wpis jest dokończony (odcinek prawie do końca; album/kolejka — ostatni utwór prawie do końca). */
  function finished(entry) {
    if (!entry) return true;
    if (entry.kind === "episode") return progress(entry) >= FINISHED;
    const last = num(entry.index) >= (entry.items || []).length - 1;
    return last && progress(entry) >= FINISHED;
  }

  function prune(list, now) {
    return (Array.isArray(list) ? list : []).filter((e) => e && e.key && Array.isArray(e.items) && e.items.length &&
      num(now) - num(e.at) < MAX_AGE_MS && !finished(e));
  }

  /** Jak długo jeszcze: „zostało 23 min”, „zostało 1 godz. 5 min”, „zostało poniżej minuty”; pusty napis bez czasu trwania. */
  function remainingLabel(entry) {
    const d = num(entry && entry.duration);
    if (d <= 0) return "";
    const left = Math.max(0, Math.round(d - num(entry.position)));
    if (left < 60) return "zostało poniżej minuty";
    const minutes = Math.round(left / 60);
    if (minutes < 60) return "zostało " + minutes + " min";
    const h = Math.floor(minutes / 60), m = minutes % 60;
    return "zostało " + h + " godz." + (m ? " " + m + " min" : "");
  }

  /** Obwód i przesunięcie kreski pierścienia postępu o promieniu `r`. */
  function ringDash(p, r) {
    const circumference = 2 * Math.PI * r;
    const clamped = Math.min(1, Math.max(0, num(p)));
    return { circumference: Number(circumference.toFixed(2)), offset: Number((circumference * (1 - clamped)).toFixed(2)) };
  }

  /**
   * Łączy źródła: przy tym samym kluczu wygrywa wpis lokalny (a przy równym źródle nowszy); potem od najnowszych, a ten sam utwór
   * z dwóch źródeł (np. kolejka z telefonu i lokalna historia) pokazuje się raz — jako nowszy. Maksymalnie `limit` pozycji.
   */
  function merge(local, remote, now, limit) {
    const byKey = new Map();
    prune(local, now).concat(prune(remote, now)).forEach((e) => {
      const old = byKey.get(e.key);
      const better = !old || (old.source !== "local" && e.source === "local") || (old.source === e.source && num(e.at) > num(old.at));
      if (better) byKey.set(e.key, e);
    });
    const seen = new Set();
    return Array.from(byKey.values()).sort((a, b) => num(b.at) - num(a.at)).filter((e) => {
      const id = (e.items || [])[e.index || 0];
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    }).slice(0, limit || 8);
  }

  return {
    PERIODS, MAX_ENTRIES, period, greeting, mixScore, orderMixes, orderSections, entryFromSnapshot, entryFromBookmark, entryFromPlayQueue,
    upsert, progress, finished, prune, remainingLabel, ringDash, merge,
  };
});
