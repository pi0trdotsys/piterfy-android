const test = require("node:test");
const assert = require("node:assert/strict");
const g = require("./gesture-core.js");

test("axisLock: poniżej progu null, potem oś, która wyraźnie przeważa", () => {
  assert.equal(g.axisLock(3, 2), null);
  assert.equal(g.axisLock(30, 4), "x");
  assert.equal(g.axisLock(-30, 10), "x");
  assert.equal(g.axisLock(5, 40), "y");
  assert.equal(g.axisLock(30, 28), null);          // po skosie nie zgadujemy
});

test("releaseHorizontal: daleko albo szybko zatwierdza, krótko i powoli wraca", () => {
  const base = { width: 300, canNext: true, canPrev: true };
  assert.equal(g.releaseHorizontal({ ...base, dx: -100, dt: 600 }), "next");
  assert.equal(g.releaseHorizontal({ ...base, dx: 100, dt: 600 }), "prev");
  assert.equal(g.releaseHorizontal({ ...base, dx: -40, dt: 600 }), "cancel");           // za krótko i za wolno
  assert.equal(g.releaseHorizontal({ ...base, dx: -40, dt: 60 }), "next");              // machnięcie
  assert.equal(g.releaseHorizontal({ ...base, dx: -10, dt: 5 }), "cancel");             // szybkie, ale mikroskopijne
});

test("releaseHorizontal: na końcu kolejki gest nie zatwierdza", () => {
  assert.equal(g.releaseHorizontal({ dx: -200, dt: 200, width: 300, canNext: false, canPrev: true }), "cancel");
  assert.equal(g.releaseHorizontal({ dx: 200, dt: 200, width: 300, canNext: true, canPrev: false }), "cancel");
  assert.equal(g.releaseHorizontal({ dx: 200, dt: 200, width: 300, canNext: false, canPrev: true }), "prev");
});

test("releaseVertical: w dół minimalizuje, w górę (mini-pasek) otwiera, krzywe i krótkie wracają", () => {
  assert.equal(g.releaseVertical({ dx: 5, dy: 200, dt: 500, height: 700 }), "minimise");
  assert.equal(g.releaseVertical({ dx: 5, dy: 60, dt: 500, height: 700 }), "cancel");
  assert.equal(g.releaseVertical({ dx: 5, dy: 60, dt: 80, height: 700 }), "minimise");   // machnięcie
  assert.equal(g.releaseVertical({ dx: 5, dy: -150, dt: 300, height: 700 }), "cancel");  // w górę w pełnym odtwarzaczu nic nie robi
  assert.equal(g.releaseVertical({ dx: 5, dy: -150, dt: 300, height: 700, direction: "up" }), "open");
  assert.equal(g.releaseVertical({ dx: 5, dy: -30, dt: 300, height: 700, direction: "up" }), "cancel");
  assert.equal(g.releaseVertical({ dx: 190, dy: 200, dt: 500, height: 700 }), "cancel"); // zbyt ukośne
});

test("rubber: swobodnie 1:1, na końcu kolejki gumowy opór z górną granicą", () => {
  assert.equal(g.rubber(-80, { canNext: true }), -80);
  assert.equal(g.rubber(80, { canPrev: true }), 80);
  const a = g.rubber(-40, { canNext: false }), b = g.rubber(-400, { canNext: false });
  assert.ok(a < 0 && a > -40, "opór już przy małym przesunięciu: " + a);
  assert.ok(b < a, "im dalej tym więcej…");
  assert.ok(b > -90, "…ale z granicą: " + b);
  assert.ok(g.rubber(60, { canPrev: false }) > 0);
});

test("tapZone dzieli okładkę na lewo / środek / prawo", () => {
  assert.equal(g.tapZone(10, 300), "left");
  assert.equal(g.tapZone(150, 300), "center");
  assert.equal(g.tapZone(290, 300), "right");
});

test("doubleTapSeek: ta sama boczna strefa, krótki odstęp, blisko siebie", () => {
  const a = { x: 40, y: 100, at: 1000, zone: "left" };
  assert.equal(g.doubleTapSeek(a, { x: 50, y: 104, at: 1200, zone: "left" }), -10);
  assert.equal(g.doubleTapSeek({ x: 245, y: 100, at: 1000, zone: "right" }, { x: 250, y: 100, at: 1100, zone: "right" }), 10);
  assert.equal(g.doubleTapSeek(a, { x: 50, y: 104, at: 1400, zone: "left" }), 0);       // za długo
  assert.equal(g.doubleTapSeek(a, { x: 250, y: 100, at: 1100, zone: "right" }), 0);     // inna strefa
  assert.equal(g.doubleTapSeek({ ...a, zone: "center" }, { ...a, at: 1100, zone: "center" }), 0);
  assert.equal(g.doubleTapSeek(a, { x: 200, y: 100, at: 1100, zone: "left" }), 0);      // za daleko
  assert.equal(g.doubleTapSeek(null, a), 0);
});

test("seekTarget trzyma pozycję w granicach utworu", () => {
  assert.equal(g.seekTarget(100, -10, 200), 90);
  assert.equal(g.seekTarget(5, -10, 200), 0);
  assert.equal(g.seekTarget(195, 10, 200), 199.75);
  assert.equal(g.seekTarget(50, 10, NaN), 60);
});

test("settleMs mieści się w 140–320 ms", () => {
  assert.equal(g.settleMs(10, 5), 140);
  assert.equal(g.settleMs(500, 0.2), 320);
  assert.ok(g.settleMs(120, 0.6) >= 140 && g.settleMs(120, 0.6) <= 320);
});
