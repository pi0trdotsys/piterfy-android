const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./feel-core.js");

test("resolveMotion: bez wyboru ruch jest pełny (Piterfy nie słucha systemowego „ogranicz ruch”), pf-calm go ogranicza", () => {
  assert.equal(core.resolveMotion(null, true, false), "full");
  assert.equal(core.resolveMotion("", true, false), "full");
  assert.equal(core.resolveMotion("śmieci", true, false), "full");
  assert.equal(core.resolveMotion(null, false, true), "reduced");
});

test("resolveMotion: jawny wybór wygrywa, „system” słucha systemu", () => {
  assert.equal(core.resolveMotion("full", true, true), "full");
  assert.equal(core.resolveMotion("reduced", false, false), "reduced");
  assert.equal(core.resolveMotion("system", true, false), "reduced");
  assert.equal(core.resolveMotion("system", false, true), "full");
});

test("hapticsOn: domyślnie włączona, wyłączają ją 0/false/off", () => {
  assert.equal(core.hapticsOn(null), true);
  assert.equal(core.hapticsOn(undefined), true);
  assert.equal(core.hapticsOn("1"), true);
  assert.equal(core.hapticsOn("0"), false);
  assert.equal(core.hapticsOn(" OFF "), false);
  assert.equal(core.hapticsOn(false), false);
});

test("pattern i kindForLabel", () => {
  assert.deepEqual(core.pattern("success"), [10, 45, 16]);
  assert.equal(core.pattern("nieznany"), core.PATTERNS.tap);
  assert.equal(core.kindForLabel("Usuń z kolejki"), "warn");
  assert.equal(core.kindForLabel("Polub utwór"), "success");
  assert.equal(core.kindForLabel("Odtwarzaj"), "tap");
  assert.equal(core.kindForLabel("Start radia"), "tap");        // „star” w „Start” to nie gwiazdka
  assert.equal(core.kindForLabel("Love"), "success");
  assert.equal(core.kindForLabel("Zamknij odtwarzacz"), "tick");
  assert.equal(core.kindForLabel(""), null);
});

test("parseColor czyta hsl, rgb i hex", () => {
  assert.deepEqual(core.parseColor("#fff"), [255, 255, 255]);
  assert.deepEqual(core.parseColor("#1db954"), [29, 185, 84]);
  assert.deepEqual(core.parseColor("rgb(10 20 30)"), [10, 20, 30]);
  assert.deepEqual(core.parseColor("rgb(10, 20, 30)"), [10, 20, 30]);
  assert.deepEqual(core.parseColor("hsl(0 0% 100%)"), [255, 255, 255]);
  assert.deepEqual(core.parseColor("hsl(120, 100%, 25%)"), [0, 128, 0]);
  assert.equal(core.parseColor("nie kolor"), null);
  assert.equal(core.parseColor(null), null);
});

test("contrast: czerń–biel to 21:1, ten sam kolor 1:1", () => {
  assert.ok(Math.abs(core.contrast([0, 0, 0], [255, 255, 255]) - 21) < 1e-9);
  assert.equal(core.contrast([90, 90, 90], [90, 90, 90]), 1);
});

test("onAccent: na jasnej okładce tekst czarny, na ciemnej biały, zawsze co najmniej AA (4,5:1)", () => {
  assert.equal(core.onAccent([255, 255, 255]), "#000000");
  assert.equal(core.onAccent([250, 240, 150]), "#000000");
  assert.equal(core.onAccent([20, 20, 60]), "#ffffff");
  let worst = 99;
  for (let r = 0; r <= 255; r += 51) for (let g = 0; g <= 255; g += 51) for (let b = 0; b <= 255; b += 51) {
    const rgb = [r, g, b], fg = core.onAccent(rgb) === "#000000" ? [0, 0, 0] : [255, 255, 255];
    worst = Math.min(worst, core.contrast(rgb, fg));
  }
  assert.ok(worst >= 4.5, "najgorszy kontrast tekstu na akcencie: " + worst);
});

test("guardAccent rozjaśnia za ciemny akcent na ciemnym tle i zachowuje odcień", () => {
  const dark = [10, 20, 90];                        // granat prawie nie do zobaczenia na #121212
  assert.ok(core.contrast(dark, [18, 18, 18]) < 3);
  const guarded = core.guardAccent(dark);
  assert.ok(core.contrast(guarded, [18, 18, 18]) >= 3);
  const [h0] = core.rgbToHsl(...dark), [h1] = core.rgbToHsl(...guarded);
  assert.ok(Math.abs(h0 - h1) < 6, "odcień po korekcie: " + h1);
  assert.deepEqual(core.guardAccent([29, 185, 84]), [29, 185, 84]);   // dobry akcent zostaje nietknięty
});

test("guardAccent na jasnym tle przyciemnia (jasna okładka na białym panelu)", () => {
  const white = [255, 255, 255], yellow = [255, 240, 120];
  assert.ok(core.contrast(yellow, white) < 3);
  const guarded = core.guardAccent(yellow, white);
  assert.ok(core.contrast(guarded, white) >= 3);
  assert.ok(core.luminance(guarded) < core.luminance(yellow));
});

test("guardAccent dla białej okładki na ciemnym tle nic nie zmienia, a tekst na niej jest czarny", () => {
  assert.deepEqual(core.guardAccent([255, 255, 255]), [255, 255, 255]);
  assert.equal(core.onAccent([255, 255, 255]), "#000000");
});

test("accentVars: zmienne dla akcentu z library.js", () => {
  const vars = core.accentVars("hsl(210 70% 50%)");
  assert.match(vars["--pf-now-accent-safe"], /^rgb\(\d+ \d+ \d+\)$/);
  assert.ok(["#000000", "#ffffff"].includes(vars["--pf-on-accent"]));
  assert.equal(core.accentVars("???"), null);
  const lightCover = core.accentVars("hsl(55 90% 60%)");        // żółty: czytelny tekst jest czarny
  assert.equal(lightCover["--pf-on-accent"], "#000000");
  const navy = core.accentVars("hsl(230 80% 20%)");
  assert.equal(navy["--pf-on-accent"], "#ffffff");
  const safe = core.parseColor(navy["--pf-now-accent-safe"]);
  assert.ok(core.contrast(safe, [18, 18, 18]) >= 3);
});
