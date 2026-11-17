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

test("montage is deterministic, starts with the base scene and never repeats a style back to back", () => {
  const a = core.montage("s86", "#1db954"), b = core.montage("s86", "#1db954");
  core.montageFrame(a, 200); core.montageFrame(b, 200);
  assert.deepEqual(a.shots, b.shots);
  assert.equal(a.shots[0].style, core.scene("s86").style);
  assert.equal(a.shots[0].kind, "none");
  assert.equal(a.shots[0].start, 0);
  for (let i = 1; i < a.shots.length; i++) {
    assert.notEqual(a.shots[i].style, a.shots[i - 1].style);
    assert.ok(Math.abs(a.shots[i].start - (a.shots[i - 1].start + a.shots[i - 1].len)) < 1e-9);
    assert.ok(a.shots[i].len >= core.SHOT_MIN && a.shots[i].len <= core.SHOT_MIN + core.SHOT_SPAN);
    assert.ok(core.KINDS.includes(a.shots[i].kind));
  }
  assert.ok(a.shots.length >= 200 / (core.SHOT_MIN + core.SHOT_SPAN));
});

test("montage frames: one layer inside a shot, two during a transition, camera always covers the frame", () => {
  const m = core.montage("al-0f3a", "#3b6fd6");
  assert.equal(core.montageFrame(m, 1).layers.length, 1);
  core.montageFrame(m, 30);
  const second = m.shots[1].start;
  const kinds = new Set();
  for (let id = 0; id < 60 && kinds.size < core.KINDS.length; id++) {
    const mm = core.montage("k" + id, null); core.montageFrame(mm, 30);
    const sh = mm.shots[1];
    const mid = core.montageFrame(mm, sh.start + core.TRANS * 0.5);
    assert.equal(mid.layers.length, 2);
    const cur = mid.layers[1];
    if (sh.kind === "iris") assert.ok(cur.clip && cur.clip.r > 0 && cur.clip.r < 1.2);
    if (sh.kind === "slide") assert.ok(Math.abs(cur.dx) > 0 && Math.abs(cur.dx) < 1 && mid.layers[0].dx * cur.dx < 0);
    if (sh.kind === "zoom") assert.ok(cur.alpha > 0 && cur.alpha < 1);
    if (sh.kind === "flash") assert.ok(mid.flash >= 0 && mid.flash <= 0.85);
    kinds.add(sh.kind);
    assert.equal(core.montageFrame(mm, sh.start + core.TRANS + 0.01).layers.length, 1);
  }
  assert.equal(kinds.size, core.KINDS.length);
  for (let t = 0; t < 120; t += 0.7) {
    for (const l of core.montageFrame(m, t).layers) {
      assert.ok(l.cam.s >= 1.1 && l.cam.s <= 1.4 * 1.5 + 1e-9, "scale " + l.cam.s);
      assert.ok(Math.abs(l.cam.rot) <= 0.04 + 1e-9 && Math.abs(l.cam.tx) <= 0.04 + 1e-9);
    }
  }
  assert.ok(second > 5);
});

test("montage palette shifts hue between shots but keeps a dark base", () => {
  const m = core.montage("s86", "#cd1916"); core.montageFrame(m, 90);
  const pals = new Set(m.shots.map((s) => s.scene.palette.a));
  assert.ok(pals.size > 1);
  for (const s of m.shots) assert.match(s.scene.palette.base, /^#[0-9a-f]{6}$/);
});
