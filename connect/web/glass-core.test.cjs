const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./glass-core.js");

test("normalize accepts anything and clamps the level", () => {
  assert.deepEqual(core.normalize(null), { on: false, level: 60 });
  assert.deepEqual(core.normalize({ on: true }), { on: true, level: 60 });
  assert.deepEqual(core.normalize({ on: 1, level: 140 }), { on: true, level: 100 });
  assert.deepEqual(core.normalize({ on: true, level: -5 }), { on: true, level: 0 });
  assert.deepEqual(core.normalize({ on: true, level: "35.4" }), { on: true, level: 35 });
  assert.deepEqual(core.normalize({ on: true, level: "abc" }), { on: true, level: 60 });
  assert.deepEqual(core.normalize({ on: true, level: null }), { on: true, level: 60 });
  assert.deepEqual(core.normalize({ on: "", level: 20 }), { on: false, level: 20 });
});

test("parse survives broken stored values", () => {
  assert.deepEqual(core.parse('{"on":true,"level":80}'), { on: true, level: 80 });
  assert.deepEqual(core.parse("{nie json"), { on: false, level: 60 });
  assert.deepEqual(core.parse(null), { on: false, level: 60 });
  assert.deepEqual(core.parse(""), { on: false, level: 60 });
});

test("vars: blur, sheen and edge grow with the level while the fill shrinks", () => {
  const num = (v) => parseFloat(v);
  let last = null;
  for (let level = 0; level <= 100; level += 10) {
    const v = core.vars(level);
    if (last) {
      assert.ok(num(v["--pf-glass-blur"]) > num(last["--pf-glass-blur"]));
      assert.ok(num(v["--pf-glass-blur-soft"]) > num(last["--pf-glass-blur-soft"]));
      assert.ok(num(v["--pf-glass-sat"]) > num(last["--pf-glass-sat"]));
      assert.ok(num(v["--pf-glass-edge"]) > num(last["--pf-glass-edge"]));
      assert.ok(num(v["--pf-glass-sheen"]) > num(last["--pf-glass-sheen"]));
      assert.ok(num(v["--pf-glass-glow"]) > num(last["--pf-glass-glow"]));
      assert.ok(num(v["--pf-glass-fill"]) < num(last["--pf-glass-fill"]));
      assert.ok(num(v["--pf-glass-fill-strong"]) < num(last["--pf-glass-fill-strong"]));
    }
    last = v;
  }
  const mid = core.vars(60);
  assert.equal(mid["--pf-glass-blur"], "20.4px");
  assert.equal(mid["--pf-glass-fill"], "37%");
  assert.match(mid["--pf-glass-sat"], /^1\.\d+$/);
  const low = core.vars(0), high = core.vars(100);
  assert.ok(num(low["--pf-glass-fill"]) <= 62 && num(high["--pf-glass-fill"]) >= 18);   // nigdy w pełni przezroczyste
  assert.deepEqual(core.vars(-50), core.vars(0));
  assert.deepEqual(core.vars(500), core.vars(100));
});

test("apply sets the attribute and variables and removes them again", () => {
  const attrs = new Map(), props = new Map();
  const el = {
    setAttribute: (k, v) => attrs.set(k, v), removeAttribute: (k) => attrs.delete(k),
    style: { setProperty: (k, v) => props.set(k, v), removeProperty: (k) => props.delete(k) },
  };
  const got = core.apply(el, { on: true, level: 80 });
  assert.deepEqual(got, { on: true, level: 80 });
  assert.equal(attrs.get("data-pf-glass"), "1");
  assert.equal(props.get("--pf-glass-blur"), core.vars(80)["--pf-glass-blur"]);
  assert.equal(props.size, Object.keys(core.vars(80)).length);
  core.apply(el, { on: false, level: 80 });
  assert.equal(attrs.has("data-pf-glass"), false);
  assert.equal(props.size, 0);
});
