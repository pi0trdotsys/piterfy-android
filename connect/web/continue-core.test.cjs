const test = require("node:test");
const assert = require("node:assert/strict");
const c = require("./continue-core.js");

const NOW = Date.UTC(2026, 10, 28, 12, 0);
const DAY = 86400000;
const song = (id, extra = {}) => Object.assign({ id: String(id), title: "T" + id, artist: "A", album: "Album", albumId: "al1", duration: 200, type: "music" }, extra);

test("period boundaries and wrap-around", () => {
  assert.deepEqual([4, 5, 11, 12, 17, 18, 22, 23, 0].map(c.period), ["night", "morning", "morning", "afternoon", "afternoon", "evening", "evening", "night", "night"]);
  assert.equal(c.period(29), "morning");   // 29 % 24 = 5
  assert.equal(c.period(-1), "night");
  assert.equal(c.period(NaN), "afternoon");
});

test("greeting follows the clock and keeps the old wording for the day", () => {
  assert.equal(c.greeting(8).text, "Dzień dobry");
  assert.equal(c.greeting(14).text, "Dzień dobry");
  assert.equal(c.greeting(20).text, "Dobry wieczór");
  assert.equal(c.greeting(2).text, "Dobrej nocy");
  assert.match(c.greeting(20).sub, /radio/);
  assert.equal(c.greeting(8).period, "morning");
});

test("mixes: calm first in the morning, dark first in the evening, stable on ties", () => {
  const names = ["Dark Mix", "Happy Mix", "Chill Mix", "Rock Mix", "Bass Boost", "Phonk Mix", "Electronic Mix"];
  assert.deepEqual(c.orderMixes(names, "morning").slice(0, 3), ["Chill Mix", "Happy Mix", "Rock Mix"]);
  assert.deepEqual(c.orderMixes(names, "morning").slice(-3), ["Bass Boost", "Dark Mix", "Phonk Mix"]);
  assert.equal(c.orderMixes(names, "evening")[0], "Dark Mix");
  assert.deepEqual(c.orderMixes(names, "evening").slice(0, 4), ["Dark Mix", "Bass Boost", "Phonk Mix", "Electronic Mix"]);
  assert.equal(c.orderMixes(names, "night")[0], "Chill Mix");
  assert.deepEqual(c.orderMixes(["X", "Y", "Z"], "evening"), ["X", "Y", "Z"]);   // brak wag = bez zmian
  assert.deepEqual(c.orderMixes(null, "evening"), []);
  assert.equal(c.mixScore("Dark Mix", "unknown"), 0);
});

test("sections: Kontynuuj first, listed ones by priority, the rest in original order", () => {
  const titles = ["Twoje miksy", "Listy przebojów", "Odkryte dla Ciebie", "Ostatnio słuchane", "Kontynuuj", "Nowe w bibliotece"];
  assert.deepEqual(c.orderSections(titles, "afternoon"), ["Kontynuuj", "Ostatnio słuchane", "Twoje miksy", "Odkryte dla Ciebie", "Listy przebojów", "Nowe w bibliotece"]);
  assert.deepEqual(c.orderSections(titles, "evening").slice(0, 3), ["Kontynuuj", "Twoje miksy", "Odkryte dla Ciebie"]);
  assert.deepEqual(c.orderSections(titles, "bogus").slice(0, 1), ["Kontynuuj"]);
  assert.deepEqual(c.orderSections([], "morning"), []);
});

test("snapshot of a podcast episode becomes an episode entry with a single id", () => {
  const e = c.entryFromSnapshot({ items: [song(1), song(2, { type: "podcast", album: "Zero", albumId: "p1", duration: 3600 })], index: 1, position: 600000 }, NOW);
  assert.equal(e.kind, "episode");
  assert.equal(e.key, "ep:2");
  assert.deepEqual(e.items, ["2"]);
  assert.equal(e.sub, "Zero");
  assert.equal(e.position, 600);
  assert.equal(Math.round(c.progress(e) * 100), 17);
});

test("a queue from a single album becomes an album entry, anything else the one queue entry", () => {
  const album = c.entryFromSnapshot({ items: [song(1), song(2), song(3)], index: 1, position: 30000 }, NOW);
  assert.equal(album.kind, "album");
  assert.equal(album.key, "al:al1");
  assert.equal(album.sub, "Utwór 2 z 3: T2");
  assert.deepEqual(album.items, ["1", "2", "3"]);
  const mixed = c.entryFromSnapshot({ items: [song(1), song(2, { albumId: "al2" })], index: 1, position: 1000 }, NOW);
  assert.equal(mixed.kind, "queue");
  assert.equal(mixed.key, "q");
  assert.equal(mixed.title, "Ostatnia kolejka");
  const single = c.entryFromSnapshot({ items: [song(9)], index: 0, position: 1000 }, NOW);
  assert.equal(single.kind, "queue");
  assert.equal(single.title, "T9");
  assert.equal(c.entryFromSnapshot({ items: [], index: 0, position: 0 }, NOW), null);
  assert.equal(c.entryFromSnapshot(null, NOW), null);
});

test("long queues keep a window of 100 ids around the current track", () => {
  const items = Array.from({ length: 300 }, (_, i) => song(i, { albumId: "al" + i }));
  const e = c.entryFromSnapshot({ items, index: 150, position: 0 }, NOW);
  assert.equal(e.items.length, 100);
  assert.equal(e.items[e.index], "150");
});

test("upsert replaces by key, sorts newest first and caps the list", () => {
  let list = [];
  for (let i = 0; i < 14; i++) list = c.upsert(list, { key: "k" + i, at: i, items: ["x"] });
  assert.equal(list.length, 10);
  assert.equal(list[0].key, "k13");
  list = c.upsert(list, { key: "k5", at: 99, items: ["y"] });
  assert.equal(list[0].key, "k5");
  assert.equal(list.filter((e) => e.key === "k5").length, 1);
  assert.equal(c.upsert(list, null).length, 10);   // bez wpisu lista zostaje (przycięta)
});

test("prune drops finished, old and empty entries", () => {
  const fresh = { key: "a", kind: "episode", items: ["1"], index: 0, position: 100, duration: 1000, at: NOW };
  const done = Object.assign({}, fresh, { key: "b", position: 990 });
  const old = Object.assign({}, fresh, { key: "c", at: NOW - 61 * DAY });
  const empty = Object.assign({}, fresh, { key: "d", items: [] });
  const albumDone = { key: "e", kind: "album", items: ["1", "2"], index: 1, position: 199, duration: 200, at: NOW };
  const albumMid = { key: "f", kind: "album", items: ["1", "2"], index: 0, position: 199, duration: 200, at: NOW };   // dalej jest drugi utwór
  assert.deepEqual(c.prune([fresh, done, old, empty, albumDone, albumMid, null], NOW).map((e) => e.key), ["a", "f"]);
});

test("progress, remaining label and ring geometry", () => {
  assert.equal(c.progress({ position: 50, duration: 200 }), 0.25);
  assert.equal(c.progress({ position: 500, duration: 200 }), 1);
  assert.equal(c.progress({ position: 5, duration: 0 }), 0);
  assert.equal(c.remainingLabel({ position: 0, duration: 1380 }), "zostało 23 min");
  assert.equal(c.remainingLabel({ position: 0, duration: 3900 }), "zostało 1 godz. 5 min");
  assert.equal(c.remainingLabel({ position: 0, duration: 3600 }), "zostało 1 godz.");
  assert.equal(c.remainingLabel({ position: 195, duration: 200 }), "zostało poniżej minuty");
  assert.equal(c.remainingLabel({ position: 5, duration: 0 }), "");
  const ring = c.ringDash(0.25, 10);
  assert.equal(ring.circumference, 62.83);
  assert.equal(ring.offset, 47.12);
  assert.equal(c.ringDash(2, 10).offset, 0);
  assert.equal(c.ringDash(-1, 10).offset, 62.83);
});

test("bookmark and server play queue entries", () => {
  const b = c.entryFromBookmark({ entry: { id: "7", title: "Odc. 7", album: "Show", duration: 3000, coverArt: "al-p" }, position: 900000, changed: "2026-11-27T10:00:00Z" }, (s) => s.album === "Show");
  assert.equal(b.kind, "episode");
  assert.equal(b.position, 900);
  assert.equal(b.source, "bookmark");
  assert.equal(b.at, Date.parse("2026-11-27T10:00:00Z"));
  assert.equal(c.entryFromBookmark({}, null), null);
  const q = c.entryFromPlayQueue({ current: "2", position: 61000, changed: "2026-11-28T08:00:00Z", changedBy: "Tempus", entry: [song(1), song(2), song(3)] });
  assert.equal(q.key, "pq");
  assert.equal(q.index, 1);
  assert.equal(q.position, 61);
  assert.equal(q.title, "Kolejka z: Tempus");
  assert.equal(c.entryFromPlayQueue({ entry: [] }), null);
  assert.equal(c.entryFromPlayQueue(null), null);
  assert.equal(c.entryFromPlayQueue({ current: "zzz", entry: [song(1)] }).index, 0);
});

test("merge: local wins the same key, the same track shows once as the newer entry, the result is capped", () => {
  const local = [{ key: "al:al1", kind: "album", items: ["1", "2"], index: 0, position: 10, duration: 200, at: NOW - 1000, source: "local" }];
  const remote = [
    { key: "al:al1", kind: "album", items: ["1", "2"], index: 0, position: 99, duration: 200, at: NOW, source: "bookmark" },   // ten sam klucz: wygrywa lokalny
    { key: "pq", kind: "queue", items: ["9", "1"], index: 1, position: 5, duration: 200, at: NOW - 500, source: "queue" },     // gra „1”, nowsza niż lokalny album → zastępuje go
    { key: "bm:5", kind: "queue", items: ["5"], index: 0, position: 5, duration: 200, at: NOW - 5000, source: "bookmark" },
  ];
  const merged = c.merge(local, remote, NOW, 8);
  assert.deepEqual(merged.map((e) => e.key), ["pq", "bm:5"]);
  assert.equal(c.merge(local, [], NOW, 8)[0].key, "al:al1");
  assert.equal(c.merge(local, [], NOW, 8)[0].position, 10);
  const many = Array.from({ length: 20 }, (_, i) => ({ key: "k" + i, kind: "queue", items: ["i" + i], index: 0, position: 1, duration: 100, at: NOW - i, source: "local" }));
  assert.equal(c.merge(many, [], NOW, 5).length, 5);
});
