const test = require("node:test");
const assert = require("node:assert/strict");
const w = require("./wave-core.js");

const peaks = (n, f = (i) => i % 256) => Array.from({ length: n }, (_, i) => f(i));

test("classify: 200 z poprawnymi szczytami → ready (0–1), 202 i pending → pending, reszta → none", () => {
  const ok = w.classify(200, { v: 1, n: 120, peaks: peaks(120), duration: 215.5 });
  assert.equal(ok.state, "ready");
  assert.equal(ok.peaks.length, 120);
  assert.ok(ok.peaks.every((p) => p >= 0 && p <= 1));
  assert.equal(ok.duration, 215.5);
  assert.deepEqual(w.classify(202, { pending: true }), { state: "pending" });
  assert.deepEqual(w.classify(200, { pending: true }), { state: "pending" });
  assert.deepEqual(w.classify(404, null), { state: "none" });
  assert.deepEqual(w.classify(500, {}), { state: "none" });
  assert.deepEqual(w.classify(200, { v: 2, peaks: peaks(120) }), { state: "none" });
  assert.deepEqual(w.classify(200, { v: 1, peaks: peaks(3) }), { state: "none" });
  assert.deepEqual(w.classify(200, "tekst"), { state: "none" });
});

test("classify: śmieci w szczytach nie psują fali, a zły czas trwania daje 0", () => {
  const r = w.classify(200, { v: 1, peaks: [...peaks(40), "x", null, 999, -5], duration: "abc" });
  assert.equal(r.state, "ready");
  assert.ok(r.peaks.every((p) => p >= 0 && p <= 1));
  assert.equal(r.duration, 0);
});

test("retryDelay rośnie i kończy się nullem", () => {
  assert.equal(w.retryDelay(0), 2000);
  assert.ok(w.retryDelay(3) > w.retryDelay(1));
  assert.equal(w.retryDelay(6), null);
  assert.equal(w.retryDelay(-1), null);
});

test("layout: słupki dopasowane do szerokości, wąski pasek ma cieńsze", () => {
  const wide = w.layout(600);
  assert.equal(wide.bar, 3);
  assert.ok(wide.count * (wide.bar + wide.gap) - wide.gap <= 600);
  assert.ok(wide.count >= 100);
  const narrow = w.layout(120);
  assert.ok(narrow.bar < wide.bar);
  assert.equal(w.layout(20).count, 0);
  assert.equal(w.layout(NaN).count, 0);
});

test("resample zachowuje szczyty (maksimum z przedziału) i długość", () => {
  const src = [0, 0, 1, 0, 0, 0, 0.5, 0];
  assert.deepEqual(w.resample(src, 4), [0, 1, 0, 0.5]);
  assert.equal(w.resample(src, 16).length, 16);
  assert.deepEqual(w.resample([], 5), []);
  assert.deepEqual(w.resample(src, 0), []);
  assert.equal(Math.max(...w.resample(peaks(240).map((v) => v / 255), 60)), 239 / 255);
});

test("height: minimalna wysokość dla ciszy, oddychanie tylko gdy włączone i w granicach", () => {
  assert.equal(w.height(0, 0, 10, 0, false), 0.08);
  assert.equal(w.height(0.5, 3, 10, 1234, false), 0.5);
  for (let t = 0; t < 5000; t += 137) for (let i = 0; i < 20; i += 1) {
    const h = w.height(0.7, i, 20, t, true);
    assert.ok(h >= 0.06 && h <= 1 && Math.abs(h - 0.7) < 0.7 * 0.12);
  }
});

test("played i scrubTime", () => {
  assert.equal(w.played(0, 10, 0.2), true);
  assert.equal(w.played(2, 10, 0.2), false);   // środek słupka 2 to 25%
  assert.equal(w.played(9, 10, 1), true);
  assert.equal(w.played(0, 0, 1), false);
  assert.equal(w.scrubTime(150, 300, 200), 100);
  assert.equal(w.scrubTime(-20, 300, 200), 0);
  assert.equal(w.scrubTime(900, 300, 200), 200);
  assert.equal(w.scrubTime(10, 300, NaN), 0);
});

test("format czasu", () => {
  assert.equal(w.format(0), "0:00");
  assert.equal(w.format(75.9), "1:15");
  assert.equal(w.format(3725), "1:02:05");
  assert.equal(w.format(NaN), "0:00");
});

test("songIdFromSrc rozpoznaje strumienie Navidrome i ignoruje resztę", () => {
  assert.equal(w.songIdFromSrc("https://muzyka.example/rest/stream?u=a&id=abc123&c=Piterfy"), "abc123");
  assert.equal(w.songIdFromSrc("/rest/stream.view?id=x9"), "x9");
  assert.equal(w.songIdFromSrc("/rest/getTranscodeStream?mediaId=m1&mediaType=song"), "m1");
  assert.equal(w.songIdFromSrc("https://radio.example/live.mp3"), "");
  assert.equal(w.songIdFromSrc("blob:xyz"), "");
  assert.equal(w.songIdFromSrc(""), "");
});

test("wanted: bez nieskończonych strumieli i bardzo krótkich próbek", () => {
  assert.equal(w.wanted(200), true);
  assert.equal(w.wanted(Infinity), false);
  assert.equal(w.wanted(NaN), false);
  assert.equal(w.wanted(2), false);
  assert.equal(w.wanted(7 * 3600), false);
});
