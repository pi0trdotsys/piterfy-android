const test = require("node:test");
const assert = require("node:assert/strict");
const a = require("./artist-core.js");

const NOW = Date.UTC(2026, 10, 28);
const DAY = 86400000;
const iso = (daysAgo) => new Date(NOW - daysAgo * DAY).toISOString();

test("normName strips accents, case and punctuation", () => {
  assert.equal(a.normName("  Łona & Webber! "), "lona webber");
  assert.equal(a.normName("Beyoncé"), "beyonce");
  assert.equal(a.normName(null), "");
});

test("sameArtist matches equal names and collaborations on a word boundary only", () => {
  assert.equal(a.sameArtist("Taco Hemingway", "taco hemingway"), true);
  assert.equal(a.sameArtist("Tyler, The Creator", "Tyler The Creator"), true);
  assert.equal(a.sameArtist("Daft Punk & Pharrell", "Daft Punk"), true);
  assert.equal(a.sameArtist("Daft Punk", "Daft Punk & Pharrell"), true);
  assert.equal(a.sameArtist("Queen", "Queens of the Stone Age"), false);
  assert.equal(a.sameArtist("Bad", "Bad Bunny"), true);       // znany kompromis: początek nazwy na granicy słowa
  assert.equal(a.sameArtist("", "X"), false);
});

test("rankPopular sorts by plays, dedupes by id and title, skips podcasts and keeps the input order on ties", () => {
  const songs = [
    { id: "1", title: "Intro", playCount: 2 },
    { id: "2", title: "Hit", playCount: 50 },
    { id: "3", title: "HIT", playCount: 80 },          // ten sam tytuł (inne wydanie) — zostaje częściej słuchane
    { id: "2", title: "Hit", playCount: 99 },          // powtórzone id
    { id: "4", title: "Cichy", playCount: 0 },
    { id: "5", title: "Odc. 9", playCount: 500, genre: "Podcast" },
    { id: "6", title: "Drugi z zerem", playCount: 0 },
    null,
  ];
  const got = a.rankPopular(songs, 4, (s) => s.genre === "Podcast");
  assert.deepEqual(got.map((s) => s.id), ["3", "1", "4", "6"]);
  assert.deepEqual(a.rankPopular(songs, 1).map((s) => s.id), ["5"]);
  assert.deepEqual(a.rankPopular(null, 5), []);
});

test("fallbackAlbums prefers played albums, then newer ones", () => {
  const albums = [{ id: "a", year: 2010, playCount: 0 }, { id: "b", year: 2022, playCount: 0 }, { id: "c", year: 2001, playCount: 9 }, { id: "d", year: 2019, playCount: 3 }, null];
  assert.deepEqual(a.fallbackAlbums(albums, 3).map((x) => x.id), ["c", "d", "b"]);
  assert.deepEqual(a.fallbackAlbums(undefined, 3), []);
});

test("timeline groups by year (newest first) and flags fresh library albums", () => {
  const albums = [
    { id: "1", name: "Stary", year: 2016, created: iso(900), songCount: 12, coverArt: "al-1" },
    { id: "2", name: "Środek", year: 2020, created: iso(300), songCount: 10 },
    { id: "3", name: "Świeży", year: 2026, created: iso(10), songCount: 9 },
    { id: "4", name: "Bonus", year: 2020, created: iso(200) },
  ];
  const groups = a.timeline(albums, [], "X", NOW);
  assert.deepEqual(groups.map((g) => g.year), [2026, 2020, 2016]);
  assert.equal(groups[0].items[0].badge, "Nowość");
  assert.equal(groups[1].items[0].title, "Bonus");        // w obrębie roku nowsze dodanie pierwsze
  assert.equal(groups[1].items[0].badge, "");
  assert.equal(groups[2].items[0].sub, "2016 · 12 utw.");
  assert.equal(groups[1].items[1].coverId, "al-2");       // domyślnie okładka albumu
});

test("timeline adds radar releases of the same artist, skips others and albums already owned", () => {
  const albums = [{ id: "1", name: "Nowy Album", year: 2026, created: iso(3) }];
  const radar = [
    { id: "r1", artist: "Taco Hemingway", title: "Wielki Zły", released: "2026-11-20", cover: "https://cdn/x.jpg", status: "new", type: "album", tracks: 12 },
    { id: "r2", artist: "Quebonafide", title: "Inny", released: "2026-11-21" },
    { id: "r3", artist: "taco hemingway", title: "nowy album", released: "2026-11-10" },       // już w bibliotece
    { id: "r4", artist: "Taco Hemingway", title: "Bez okładki", released: "2025-02-01", cover: "http://insecure/x.jpg" },
    null,
  ];
  const groups = a.timeline(albums, radar, "Taco Hemingway", NOW);
  const all = groups.flatMap((g) => g.items);
  assert.deepEqual(all.map((i) => i.id).sort(), ["1", "r1", "r4"]);
  const fresh = all.find((i) => i.id === "r1");
  assert.equal(fresh.kind, "new");
  assert.equal(fresh.badge, "Nowość");
  assert.equal(fresh.cover, "https://cdn/x.jpg");
  assert.equal(all.find((i) => i.id === "r4").cover, "");                  // tylko https
  assert.equal(groups[0].year, 2026);
  assert.deepEqual(groups[0].items.map((i) => i.id), ["1", "r1"]);         // w roku nowsza data pierwsza: album dodany 25.11 przed wydaniem z 20.11
});

test("worthShowing needs at least two entries", () => {
  assert.equal(a.worthShowing([{ year: 2020, items: [{}] }]), false);
  assert.equal(a.worthShowing([{ year: 2020, items: [{}, {}] }]), true);
  assert.equal(a.worthShowing([{ year: 2020, items: [{}] }, { year: 2019, items: [{}] }]), true);
  assert.equal(a.worthShowing(null), false);
});

test("scroll maths: parallax is capped, collapse stays within 0-1, the bar appears after the hero scrolls away", () => {
  assert.equal(a.parallax(0), 0);
  assert.equal(a.parallax(100), 35);
  assert.equal(a.parallax(10000), 180);
  assert.equal(a.parallax(-50), 0);
  assert.equal(a.collapse(0, 400), 0);
  assert.equal(a.collapse(160, 400), 0.5);
  assert.equal(a.collapse(9999, 400), 1);
  assert.equal(a.collapse(100, 0), 0);
  assert.equal(a.showBar(300, 64), false);
  assert.equal(a.showBar(80, 64), true);
  assert.equal(a.showBar(-200, 64), true);
});

test("shade darkens a colour and rejects bad input", () => {
  assert.equal(a.shade("#ff8000", 0.5), "#804000");
  assert.equal(a.shade("ff8000", 0), "#ff8000");
  assert.equal(a.shade("#ff8000", 1), "#000000");
  assert.equal(a.shade("pomarańczowy", 0.5), "");
});
