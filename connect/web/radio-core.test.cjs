const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./radio-core.js");

const song = (id, artist, extra) => ({ id: String(id), artist, title: "t" + id, ...extra });
const seeded = (seed) => { let a = seed >>> 0; return () => { a = (a * 1664525 + 1013904223) >>> 0; return a / 4294967296; }; };

test("clean drops empties, podcasts, 1-star, seed, memory and duplicates", () => {
  const list = [song(1, "A"), null, { title: "bez id" }, song(2, "B", { userRating: 1 }), song(3, "C", { path: "Podcasty/Audycja/x.mp3" }),
    song(4, "D", { type: "podcast" }), song(1, "A"), song(5, "E"), song(6, "F"), song(7, "G", { genre: "Podcast" })];
  const got = core.clean(list, { exclude: ["5"], memory: ["6"] });
  assert.deepEqual(got.map((s) => s.id), ["1"]);
  assert.deepEqual(core.clean(list, {}).map((s) => s.id), ["1", "5", "6"]);
});

test("artistKey normalises accents, case and featuring", () => {
  assert.equal(core.artistKey({ artist: "Beyoncé" }), "beyonce");
  assert.equal(core.artistKey({ artist: "Daft Punk feat. Pharrell" }), "daft punk");
  assert.equal(core.artistKey({ albumArtist: "Pro8l3m", artist: "Pro8l3m x Ktoś" }), "pro8l3m");
  assert.equal(core.artistKey(null), "");
});

test("order never repeats an artist back to back while someone else is near", () => {
  const list = [];
  for (let i = 0; i < 20; i++) list.push(song(i, i % 2 ? "Alfa" : i % 4 === 0 ? "Beta" : "Gamma"));
  for (let seed = 1; seed <= 25; seed++) {
    const out = core.order(list, seeded(seed), "alfa");
    assert.equal(out.length, 20);
    assert.equal(new Set(out.map((s) => s.id)).size, 20);
    assert.notEqual(core.artistKey(out[0]), "alfa");
    for (let i = 1; i < out.length; i++) {
      const rest = out.slice(i, i + 6).some((s) => core.artistKey(s) !== core.artistKey(out[i - 1]));
      if (rest) assert.notEqual(core.artistKey(out[i]), core.artistKey(out[i - 1]), "seed " + seed + " pos " + i);
    }
  }
});

test("order keeps the similarity order roughly and varies between runs", () => {
  const list = Array.from({ length: 30 }, (_, i) => song(i, "A" + i));
  const a = core.order(list, seeded(1), ""), b = core.order(list, seeded(2), "");
  assert.notDeepEqual(a.map((s) => s.id), b.map((s) => s.id));
  const avgFirstTen = a.slice(0, 10).reduce((n, s) => n + Number(s.id), 0) / 10;
  assert.ok(avgFirstTen < 15, "najbardziej podobne zostają blisko początku (" + avgFirstTen + ")");
  assert.equal(core.order([], seeded(1), "").length, 0);
});

test("build puts the seed first, once, and skips podcasts as seed", () => {
  const seed = song(1, "Alfa");
  const similar = [song(1, "Alfa"), song(2, "Beta"), song(3, "Gamma"), song(4, "Delta")];
  const out = core.build(seed, similar, { rng: seeded(3) });
  assert.equal(out[0].id, "1");
  assert.deepEqual(out.map((s) => s.id).sort(), ["1", "2", "3", "4"]);
  const noSeed = core.build(null, similar, { rng: seeded(3) });
  assert.equal(noSeed.length, 4);
  const podSeed = core.build(song(9, "X", { type: "podcast" }), similar, { rng: seeded(3) });
  assert.ok(!podSeed.some((s) => s.id === "9"));
});

test("needMore triggers when at most three tracks remain after the current one", () => {
  assert.equal(core.needMore(10, 5), false);       // zostaje 4
  assert.equal(core.needMore(10, 6), true);        // zostają 3
  assert.equal(core.needMore(10, 9), true);
  assert.equal(core.needMore(0, 0), false);
  assert.equal(core.needMore(5, -1), false);
  assert.equal(core.needMore(10, 4, 5), true);
});

test("remember keeps the newest ids first, without duplicates, capped", () => {
  assert.deepEqual(core.remember(["a", "b", "c"], ["c", "d"]), ["c", "d", "a", "b"]);
  const many = core.remember(Array.from({ length: 150 }, (_, i) => "m" + i), ["x"]);
  assert.equal(many.length, core.MEMORY_SIZE);
  assert.equal(many[0], "x");
  assert.deepEqual(core.remember(null, null), []);
});

test("shouldStop only after the grace period and only for foreign tracks", () => {
  const known = new Set(["1", "2"]);
  assert.equal(core.shouldStop("2", known, 10000), false);
  assert.equal(core.shouldStop("9", known, 1000), false);    // karencja
  assert.equal(core.shouldStop("9", known, 10000), true);
  assert.equal(core.shouldStop(null, known, 10000), false);
});

test("seedsForTopUp and interleave", () => {
  assert.deepEqual(core.seedsForTopUp(["a", "b", "c", "d"], 3), ["d", "c", "b"]);
  assert.deepEqual(core.seedsForTopUp(["a", "a", "b"], 2), ["b", "a"]);
  assert.deepEqual(core.seedsForTopUp([], 0), []);
  assert.deepEqual(core.interleave([[1, 2, 3], ["a"], ["x", "y"]]), [1, "a", "x", 2, "y", 3]);
});
