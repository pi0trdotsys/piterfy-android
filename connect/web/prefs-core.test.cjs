const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./prefs-core.js");

const store = (initial = {}) => {
  const map = new Map(Object.entries(initial));
  return { getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, String(v)), map };
};

test("defaults when nothing is stored", () => {
  assert.deepEqual(core.read(store()), { haptics: true, motion: "system", oled: false, density: "comfortable", textsize: 100 });
  assert.deepEqual(core.normalize(null), core.DEFAULTS);
  assert.deepEqual(core.read(null), core.DEFAULTS);
});

test("normalize accepts strings from localStorage and rejects garbage", () => {
  assert.deepEqual(core.normalize({ haptics: "0", motion: "reduced", oled: "1", density: "compact", textsize: "115" }),
    { haptics: false, motion: "reduced", oled: true, density: "compact", textsize: 115 });
  assert.deepEqual(core.normalize({ haptics: "nie wiem", motion: "szybko", oled: "tak", density: "ciasno", textsize: "abc" }),
    { haptics: false, motion: "system", oled: false, density: "comfortable", textsize: 100 });
  assert.equal(core.normalize({ haptics: "" }).haptics, true);   // pusty napis = domyślne
});

test("text size is clamped to 85-130 and snapped to steps of 5", () => {
  assert.equal(core.normalizeTextSize(10), 85);
  assert.equal(core.normalizeTextSize(500), 130);
  assert.equal(core.normalizeTextSize("107"), 105);
  assert.equal(core.normalizeTextSize("108"), 110);
  assert.equal(core.normalizeTextSize(null), 100);
  assert.equal(core.normalizeTextSize(NaN), 100);
  assert.equal(core.textScale(130), 1.3);
  assert.equal(core.textScale(85), 0.85);
});

test("write stores only the keys that changed, as strings, and returns the new prefs", () => {
  const s = store();
  const next = core.write(s, { oled: true, textsize: 120 });
  assert.equal(s.map.get("pf-oled"), "1");
  assert.equal(s.map.get("pf-textsize"), "120");
  assert.equal(s.map.has("pf-motion"), false);   // nie ruszone → bez zapisu (ważne dla starego pf-calm)
  assert.equal(next.oled, true);
  assert.equal(next.textsize, 120);
  core.write(s, { motion: "reduced", haptics: false, density: "compact" });
  assert.deepEqual([s.map.get("pf-motion"), s.map.get("pf-haptics"), s.map.get("pf-density")], ["reduced", "0", "compact"]);
  assert.deepEqual(core.read(s), { haptics: false, motion: "reduced", oled: true, density: "compact", textsize: 120 });
});

test("write survives a storage that throws", () => {
  const broken = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } };
  assert.deepEqual(core.read(broken), core.DEFAULTS);
  assert.doesNotThrow(() => core.write(broken, { oled: true }));
});

test("early apply only when a visual preference differs from the default", () => {
  assert.equal(core.needsEarlyApply(store()), false);
  assert.equal(core.needsEarlyApply(store({ "pf-haptics": "0", "pf-motion": "reduced" })), false);
  assert.equal(core.needsEarlyApply(store({ "pf-oled": "1" })), true);
  assert.equal(core.needsEarlyApply(store({ "pf-density": "compact" })), true);
  assert.equal(core.needsEarlyApply(store({ "pf-textsize": "90" })), true);
  assert.equal(core.needsEarlyApply(store({ "pf-textsize": "100" })), false);
});

test("calmFlag maps the motion choice onto the legacy pf-calm switch", () => {
  assert.equal(core.calmFlag("reduced", false), "1");
  assert.equal(core.calmFlag("full", true), "0");     // „pełny” ignoruje ustawienie systemu
  assert.equal(core.calmFlag("system", true), "1");
  assert.equal(core.calmFlag("system", false), "0");
  assert.equal(core.isCalm("system", true), true);
  assert.equal(core.isCalm("full", true), false);
});

test("apply sets and removes attributes and the text scale variable", () => {
  const attrs = new Map(), props = new Map();
  const el = {
    setAttribute: (k, v) => attrs.set(k, v), removeAttribute: (k) => attrs.delete(k),
    style: { setProperty: (k, v) => props.set(k, v), removeProperty: (k) => props.delete(k) },
  };
  core.apply(el, { oled: true, density: "compact", textsize: 115, motion: "reduced" });
  assert.equal(attrs.get("data-pf-oled"), "1");
  assert.equal(attrs.get("data-pf-density"), "compact");
  assert.equal(attrs.get("data-pf-text"), "115");
  assert.equal(attrs.get("data-pf-motion"), "reduced");
  assert.equal(props.get("--pf-text-scale"), "1.15");
  core.apply(el, core.DEFAULTS);
  assert.equal(attrs.size, 0);
  assert.equal(props.size, 0);
});
