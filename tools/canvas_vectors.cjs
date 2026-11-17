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
