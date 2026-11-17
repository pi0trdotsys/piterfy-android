const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./canvas-core.js");

test("hash and rng are stable", () => {
  assert.equal(core.hash32(""), 0x811c9dc5);
  assert.equal(core.hash32("a"), 0xe40c292c);
  assert.equal(core.hash32("s86"), core.hash32("s86"));
  const a = core.rng(1), b = core.rng(1);
  for (let i = 0; i < 5; i++) { const v = a(); assert.equal(v, b()); assert.ok(v >= 0 && v < 1); }
  assert.notEqual(core.rng(1)(), core.rng(2)());
});

test("palette is derived from the accent and falls back to Piterfy green", () => {
  const p = core.palette("#cd1916");
  assert.match(p.base, /^#[0-9a-f]{6}$/);
  assert.deepEqual(core.palette("zle"), core.palette(core.FALLBACK_ACCENT));
  assert.deepEqual(core.palette(null), core.palette("#1db954"));
  const lum = (h) => 0.299 * parseInt(h.slice(1, 3), 16) + 0.587 * parseInt(h.slice(3, 5), 16) + 0.114 * parseInt(h.slice(5, 7), 16);
  assert.ok(lum(p.base) < 40);                  // tło zawsze ciemne
  assert.ok(lum(core.palette("#101040").a) > 50); // ciemny akcent zostaje czytelny
  assert.ok(lum(core.palette("#e0e0e0").a) < 200);
});

test("scene is deterministic per song and every style occurs", () => {
  assert.deepEqual(core.scene("s86", "#1db954"), core.scene("s86", "#1db954"));
  assert.notEqual(core.scene("s86").hash, core.scene("s87").hash);
  const seen = new Set();
  for (let i = 0; i < 300; i++) seen.add(core.scene("song-" + i).style);
  assert.deepEqual([...seen].sort(), [...core.STYLES].sort());
  const sc = core.scene("x");
  assert.ok(sc.speed >= 0.7 && sc.speed <= 1.3);
});

test("layout returns bounded primitives for every style over time", () => {
  const styles = new Map();
  for (let i = 0; i < 400 && styles.size < core.STYLES.length; i++) { const sc = core.scene("id" + i, "#3b6fd6"); if (!styles.has(sc.style)) styles.set(sc.style, sc); }
  assert.equal(styles.size, core.STYLES.length);
  for (const [style, sc] of styles) {
    for (const t of [0, 1.7, 33, 600, 36000]) {
      const ops = core.layout(sc, t);
      assert.ok(ops.length > 0, style);
      for (const op of ops) {
        assert.match(op.c, /^#[0-9a-f]{6}$/);
        assert.ok(op.a >= 0 && op.a <= 1, style + " alpha " + op.a);
        if (op.k === "wave") { assert.equal(op.pts.length, 80); assert.ok(op.pts.every(Number.isFinite)); }
        else { assert.ok(Number.isFinite(op.x) && Number.isFinite(op.y) && op.r > 0, style + " " + JSON.stringify(op)); }
      }
    }
    assert.deepEqual(core.layout(sc, 5), core.layout(sc, 5)); // bez ukrytego stanu
  }
});

test("scene moves: layout differs between two moments", () => {
  const sc = core.scene("s86", "#1db954");
  assert.notDeepEqual(core.layout(sc, 0), core.layout(sc, 10));
});
