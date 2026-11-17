#!/usr/bin/env node
/* Odtwarza app/src/test/resources/piterfy_canvas_vectors.json: wzorce sceny „Canvas” z connect/web/canvas-core.js.
 * Test Androida (PiterfyCanvasSceneTest) czyta ten plik, więc port Kotlin musi dawać te same wyniki co strona.
 * Gdy zmienisz canvas-core.js, uruchom ten skrypt z katalogu głównego repozytorium i popraw port Kotlin. */
const fs = require("fs");
const path = require("path");
const core = require("../connect/web/canvas-core.js");

const cases = [
  ["s86", "#cd1916"], ["al-0f3a", "#1db954"], ["Zażółć gęślą jaźń", "#3b6fd6"], ["", null], ["a-very-long-id-0123456789-abcdefghijklmnopqrstuvwxyz", "#808080"],
  ["x1", "bad"], ["x2", "#ffd400"], ["x3", "#101040"], ["x4", "#e0e0e0"], ["x5", "#1db954"], ["x6", "#d6336c"],
];
const times = [0, 3.5, 47.2];
const out = cases.map(([id, accent]) => {
  const sc = core.scene(id, accent);
  return {
    id, accent, hash: sc.hash, style: sc.style, speed: sc.speed, palette: sc.palette, elements: sc.elements,
    frames: times.map((t) => ({ t, ops: core.layout(sc, t) })),
  };
});
fs.writeFileSync(path.join(__dirname, "../app/src/test/resources/piterfy_canvas_vectors.json"), JSON.stringify(out));
console.log("zapisano", out.length, "scen");

// montaż: harmonogram ujęć i metadane klatek (bez pełnych list prymitywów — te sprawdzają wzorce powyżej)
const frameTimes = [0, 3.5, 6.2, 6.8, 7.3, 14.1, 19.9, 47.2, 130, 301.7];
const montages = cases.map(([id, accent]) => {
  const m = core.montage(id, accent);
  const frames = frameTimes.map((t) => {
    const f = core.montageFrame(m, t);
    return { t, shot: f.shot, flash: f.flash, layers: f.layers.map((l) => ({ shot: l.shot, style: l.scene.style, alpha: l.alpha, dx: l.dx, dy: l.dy, clip: l.clip, cam: l.cam })) };
  });
  const shots = m.shots.slice(0, 14).map((sh) => ({ i: sh.i, start: sh.start, len: sh.len, style: sh.style, shift: sh.shift, kind: sh.kind, dir: sh.dir, cx: sh.cx, cy: sh.cy,
    cam: sh.cam, sceneHash: sh.scene.hash, sceneSpeed: sh.scene.speed, palette: sh.scene.palette }));
  return { id, accent, shots, frames };
});
fs.writeFileSync(path.join(__dirname, "../app/src/test/resources/piterfy_canvas_montage_vectors.json"), JSON.stringify(montages));
console.log("zapisano", montages.length, "montaży");
