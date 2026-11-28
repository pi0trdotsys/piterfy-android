const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./stats-core.js");

const nb = (s) => s.replace(/ /g, " ");

test("number formatting is stable and Polish", () => {
  assert.equal(nb(core.fmtNum(1234567)), "1 234 567");
  assert.equal(core.fmtNum(999), "999");
  assert.equal(core.fmtNum(null), "0");
  assert.equal(core.fmtDec(1.5, 1), "1,5");
  assert.equal(core.fmtDec(2, 2), "2");
  assert.equal(nb(core.fmtBytes(0)), "0 B");
  assert.equal(nb(core.fmtBytes(1536)), "1,5 KB");
  assert.equal(nb(core.fmtBytes(5 * 1024 ** 3)), "5 GB");
  assert.equal(nb(core.fmtBytes(123.4 * 1024 ** 3)), "123 GB");
  assert.equal(nb(core.fmtBytes(12.34 * 1024 ** 3)), "12,3 GB");
});

test("durations and listening time", () => {
  assert.equal(nb(core.fmtDuration(90061)), "1 d 1 h");
  assert.equal(nb(core.fmtDuration(3725)), "1 h 2 min");
  assert.equal(nb(core.fmtDuration(59)), "0 min");
  assert.equal(nb(core.fmtListening(95)), "95 min");
  assert.equal(nb(core.fmtListening(600)), "10 godz.");
  assert.equal(nb(core.fmtListening(24700)), "412 godz.");
});

test("percent, delta and dates", () => {
  assert.equal(core.fmtPercent(1, 4), "25%");
  assert.equal(core.fmtPercent(1, 0), "—");
  assert.equal(core.deltaText(18), "▲ 18%");
  assert.equal(core.deltaText(-7), "▼ 7%");
  assert.equal(core.deltaText(0), "bez zmian");
  assert.equal(core.deltaText(null), "");
  assert.equal(nb(core.fmtDay("2026-11-05")), "5 lis 2026");
  assert.equal(core.fmtDay("zepsute"), "—");
  assert.equal(core.fmtShortDay("2026-11-16"), "16.11");
  assert.equal(core.fmtStamp("nie czas"), "—");
  assert.match(nb(core.fmtStamp("2026-11-16T12:05:00.123456789Z".slice(0, 19) + "Z")), /\d+ \p{L}+ 2026, \d{2}:\d{2}/u);
});

test("bars scale to the maximum and keep zero at zero", () => {
  assert.equal(core.barPercent(0, 10), 0);
  assert.equal(core.barPercent(1, 1000), 2);       // widoczny ślad
  assert.equal(core.barPercent(10, 10), 100);
  assert.equal(core.barPercent(5, 0), 0);
  const rows = core.bars([["flac", 40], ["mp3", 10], ["opus", 0]]);
  assert.deepEqual(rows.map((r) => r.pct), [100, 25, 0]);
  assert.equal(rows[0].label, "flac");
});

test("quality breakdown merges verdicts into four honest segments", () => {
  const out = core.qualityBreakdown({ ok: 70, suspect: 5, upsampled: 5, fake: 8, transcoded: 2, pending: 8, unknown: 2 });
  assert.equal(out.total, 100);
  assert.deepEqual(out.segments.map((s) => [s.key, s.n]), [["ok", 70], ["suspect", 10], ["fake", 10], ["pending", 10]]);
  assert.equal(out.segments[0].pct, 70);
  assert.equal(core.qualityBreakdown({}).total, 0);
  assert.equal(core.qualityBreakdown(null).segments[0].pct, 0);
});

test("growth series labels weeks by their Monday", () => {
  const rows = core.growthSeries([["2026-11-02", 4, 100], ["2026-11-09", 8, 200], ["2026-11-16", 0, 0]]);
  assert.deepEqual(rows.map((r) => r.label), ["2.11", "9.11", "16.11"]);
  assert.deepEqual(rows.map((r) => r.pct), [50, 100, 0]);
});

test("disk breakdown never exceeds the disk and survives missing data", () => {
  assert.equal(core.diskBreakdown(null, 1, 1), null);
  assert.equal(core.diskBreakdown({ total: 0 }, 1, 1), null);
  const d = core.diskBreakdown({ total: 1000, used: 600, free: 400 }, 500, 300);
  assert.equal(d.usedPct, 60);
  const bytes = Object.fromEntries(d.parts.map((p) => [p.key, p.bytes]));
  assert.deepEqual(bytes, { library: 500, trash: 100, other: 0, free: 400 });   // kosz przycięty do reszty zajętego
  assert.ok(Math.abs(d.parts.reduce((s, p) => s + p.pct, 0) - 100) < 1e-9);
});

test("outcome rows skip empty outcomes and keep a stable order", () => {
  const rows = core.outcomeRows({ failed: 2, done: 9, trashed: 0 });
  assert.deepEqual(rows.map((r) => [r.key, r.n]), [["done", 9], ["failed", 2]]);
  assert.deepEqual(core.outcomeRows(null), []);
});

const period = {
  key: "2026-11", label: "Listopad 2026", partial: true, minutes: 24700, plays: 1200, tracks: 400, artists: 90,
  active_days: 15, longest_streak: 9, new_artists: 12, busiest_day: { day: "2026-11-05", plays: 77 },
  top_artists: [{ name: "A", plays: 90 }, { name: "B", plays: 80 }, { name: "C", plays: 70 }, { name: "D", plays: 60 }],
  top_tracks: [{ title: "Hit", artist: "A", plays: 30 }],
  listener: { id: "night", name: "Nocny marek", blurb: "Po zmroku.", why: ["61% po 22:00", "x", "y"] },
  compare: { plays: 18, minutes: 5 },
};

test("wrapped card model is ready-made text", () => {
  const m = core.cardModel(period);
  assert.equal(m.title, "Listopad 2026 · do dziś");
  assert.equal(nb(m.big), "412 godz.");
  assert.equal(nb(m.bigCaption), "słuchania · 1 200 odsłuchów");
  assert.equal(m.artists.length, 3);
  assert.equal(m.track.title, "Hit");
  assert.equal(m.listener.why.length, 2);
  assert.equal(m.delta.text, "▲ 18% odsłuchów");
  assert.equal(m.delta.up, true);
  assert.equal(m.facts.length, 4);
  assert.match(nb(m.facts[1]), /15 dni z muzyką, seria 9 dni/);
  assert.equal(m.fileName, "piterfy-wrapped-2026-11.png");
});

test("wrapped card model copes with sparse periods", () => {
  const m = core.cardModel({ key: "2026", label: "Rok 2026", minutes: 30, plays: 10, tracks: 3, artists: 2, active_days: 1,
    longest_streak: 1, top_artists: [], top_tracks: [], listener: null, compare: null, busiest_day: null });
  assert.equal(m.listener, null);
  assert.equal(m.delta, null);
  assert.equal(m.track, null);
  assert.equal(m.title, "Rok 2026");
  assert.equal(m.facts.length, 2);
  assert.equal(core.cardModel(null), null);
  assert.equal(core.cardModel({ ...period, compare: { plays: 0 } }).delta.flat, true);
});

test("wrapText wraps by measured width and ellipsizes the last line", () => {
  const measure = (s) => s.length * 10;
  assert.deepEqual(core.wrapText("ala ma kota", 1000, measure, 3), ["ala ma kota"]);
  assert.deepEqual(core.wrapText("ala ma kota i psa", 80, measure, 5), ["ala ma", "kota i", "psa"]);
  const cut = core.wrapText("bardzo długi tytuł który się nie mieści w dwóch liniach", 100, measure, 2);
  assert.equal(cut.length, 2);
  assert.ok(cut[1].endsWith("…"));
  assert.ok(measure(cut[1]) <= 100);
  assert.deepEqual(core.wrapText("", 100, measure, 2), []);
  assert.deepEqual(core.wrapText("superdługiejednosłowo", 50, measure, 2), ["superdługiejednosłowo"]);
});

test("story slides skip empty ones and keep the finale last", () => {
  const full = { top_artists: [{}], top_tracks: [{}], top_genres: [["Rock", 3]], listener: { name: "x" }, compare: { plays: 5 } };
  assert.deepEqual(core.storySlides(full).map((s) => s.id),
    ["intro", "time", "artist", "tracks", "genres", "listener", "compare", "finale"]);
  assert.deepEqual(core.storySlides({ top_artists: [], top_tracks: [], top_genres: [], listener: null, compare: null }).map((s) => s.id),
    ["intro", "time", "finale"]);
  assert.deepEqual(core.storySlides({ compare: { plays: null, minutes: null, artists: null } }).map((s) => s.id), ["intro", "time", "finale"]);
  assert.equal(core.storySlides(null).length, 0);
  const slides = core.storySlides(full);
  assert.equal(slides.find((s) => s.id === "tracks").ms, 12000);
  assert.equal(slides[slides.length - 1].ms, 0);
});

test("preview window and fades", () => {
  assert.deepEqual(core.previewWindow(200), { start: 70, length: 25 });
  assert.deepEqual(core.previewWindow(40), { start: 0, length: 25 });
  assert.deepEqual(core.previewWindow(10), { start: 0, length: 10 });
  assert.deepEqual(core.previewWindow(61), { start: 21, length: 25 });
  assert.deepEqual(core.previewWindow(0), { start: 0, length: 25 });
  assert.equal(core.previewGain(0, 25), 0);
  assert.equal(core.previewGain(0.4, 25), 0.5);
  assert.equal(core.previewGain(10, 25), 1);
  assert.ok(Math.abs(core.previewGain(24.7, 25) - 0.5) < 1e-9);
  assert.equal(core.previewGain(25, 25), 0);
});

test("dominant colour favours saturated pixels and ignores grey", () => {
  const px = (r, g, b, a = 255) => [r, g, b, a];
  assert.equal(core.dominantColor([].concat(px(200, 20, 20), px(210, 30, 25), px(90, 90, 90), px(240, 240, 240))), "#cd1916");
  assert.equal(core.dominantColor([].concat(px(90, 90, 90), px(10, 10, 10))), null);
  assert.equal(core.dominantColor([].concat(px(200, 20, 20, 0))), null);
  assert.equal(core.dominantColor([]), null);
});

test("readable accent lightens dark colours only", () => {
  assert.equal(core.readableAccent("#ff8800", "#fff"), "#ff8800");
  assert.equal(core.readableAccent("nope", "#abc"), "#abc");
  const lifted = core.readableAccent("#101040", "#fff");
  const lum = (h) => 0.299 * parseInt(h.slice(1, 3), 16) + 0.587 * parseInt(h.slice(3, 5), 16) + 0.114 * parseInt(h.slice(5, 7), 16);
  assert.ok(lum(lifted) >= 115 || lifted === "#ffffff");
  assert.ok(lum(lifted) > lum("#101040"));
});

test("soundtrack plan gives every slide a track of its own subject and merges neighbours", () => {
  const period = {
    plays: 100, top_genres: [["rock", 10]], listener: { name: "x", blurb: "y" }, compare: { plays: 3 },
    top_artists: [{ name: "Beta" }],
    top_tracks: [{ id: "a", title: "A", artist: "Alfa", seconds: 300 }, { id: "", title: "no id", seconds: 200 },
      { id: "b", title: "B", artist: "beta", seconds: 50 }, { id: "c", artist: "Gamma", seconds: 70 }, { id: "d", seconds: 10 }],
  };
  const p = core.soundtrackPlan(period);
  assert.deepEqual(p.segments.map((x) => x.id), ["a", "b", "a", "c", "d", "c", "a"]);
  assert.deepEqual(p.bySlide, { intro: 0, time: 0, artist: 1, tracks: 2, genres: 3, listener: 4, compare: 5, finale: 6 });
  assert.deepEqual(p.segments[0], { id: "a", title: "A", artist: "Alfa", start: 105, length: 45 });
  assert.deepEqual([p.segments[1].start, p.segments[1].length], [0, 45]);  // krótki utwór od początku
  assert.deepEqual([p.segments[4].start, p.segments[4].length], [0, 10]);  // utwór krótszy niż segment
  // top wykonawca poza top 5 utworów: slajd wykonawcy gra hit, więc intro…lista to jeden segment
  const noMatch = core.soundtrackPlan({ top_artists: [{ name: "Nikt" }], listener: { name: "x", blurb: "y" }, top_tracks: [{ id: "a", seconds: 200 }, { id: "b", seconds: 200 }] });
  assert.deepEqual(noMatch.segments.map((x) => x.id), ["a", "b", "a"]);
  assert.deepEqual([noMatch.bySlide.intro, noMatch.bySlide.artist, noMatch.bySlide.tracks, noMatch.bySlide.listener, noMatch.bySlide.finale], [0, 0, 0, 1, 2]);
  // jeden utwór: wszystko gra jeden segment
  assert.equal(core.soundtrackPlan({ top_tracks: [{ id: "a", seconds: 200 }] }).segments.length, 1);
  assert.deepEqual(core.soundtrackPlan(null), { segments: [], bySlide: {} });
  assert.deepEqual(core.soundtrackPlan({ top_tracks: [{ title: "bez id" }] }).segments, []);
  assert.equal(core.previewWindow(300, 45).length, 45);
  assert.equal(core.previewWindow(300).length, 25);
});

test("health model sorts alerts, tolerates bad input and counts in Polish", () => {
  assert.equal(core.healthModel(null), null);
  assert.equal(core.healthModel("x"), null);
  const empty = core.healthModel({ status: "ok", alerts: [], items: [] });
  assert.equal(empty.status, "ok");
  assert.equal(empty.level.cls, "ok");
  assert.equal(nb(empty.summary), "Nic nie wymaga uwagi.");
  const m = core.healthModel({
    status: "crit",
    alerts: [
      { level: "warn", code: "a", title: "Ostrzeżenie A", detail: "x" },
      { level: "crit", code: "b", title: "Awaria B" },
      { level: "weird", title: "Dziwny" },
      { title: "" },
      null,
    ],
    items: [{ label: "Dysk", value: "ok", level: "ok" }, { label: "Bez wartości" }, { value: "bez etykiety" }],
  });
  assert.deepEqual(m.alerts.map((a) => a.title), ["Awaria B", "Ostrzeżenie A", "Dziwny"]);
  assert.equal(m.alerts[0].detail, "");
  assert.equal(m.level.cls, "crit");
  assert.equal(nb(m.summary), "1 awaria, 1 ostrzeżenie");
  assert.deepEqual(m.items.map((x) => [x.label, x.value, x.level]), [["Dysk", "ok", "ok"], ["Bez wartości", "—", "ok"]]);
  assert.equal(core.healthModel({ alerts: [{ level: "warn", title: "t" }] }).status, "warn");
  assert.equal(nb(core.healthModel({ status: "crit", alerts: Array.from({ length: 5 }, () => ({ level: "crit", title: "t" })) }).summary), "5 awarii");
  assert.equal(core.healthLevel("nonsense").cls, "ok");
});

test("relative time in Polish", () => {
  const now = Date.parse("2026-11-16T12:00:00Z");
  const ago = (iso) => nb(core.fmtAgo(iso, now));
  assert.equal(ago("2026-11-16T11:59:30Z"), "przed chwilą");
  assert.equal(ago("2026-11-16T13:00:00Z"), "przed chwilą");
  assert.equal(ago("2026-11-16T11:59:00Z"), "1 minutę temu");
  assert.equal(ago("2026-11-16T11:55:00Z"), "5 minut temu");
  assert.equal(ago("2026-11-16T11:38:00Z"), "22 minuty temu");
  assert.equal(ago("2026-11-16T11:00:00Z"), "1 godzinę temu");
  assert.equal(ago("2026-11-16T08:00:00Z"), "4 godziny temu");
  assert.equal(ago("2026-11-15T12:00:00Z"), "1 dzień temu");
  assert.equal(ago("2026-11-09T12:00:00Z"), "7 dni temu");
  assert.equal(core.fmtAgo("nope", now), "—");
});
