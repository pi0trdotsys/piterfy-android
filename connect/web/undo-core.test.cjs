const test = require("node:test");
const assert = require("node:assert/strict");
const u = require("./undo-core.js");

const q = (uuids, current = uuids[0]) => ({ uuids, current });

test("brak zmiany i przestawienie kolejności nie dają komunikatu", () => {
  assert.equal(u.queueDiff(q(["a", "b", "c"]), q(["a", "b", "c"])).kind, "none");
  assert.equal(u.queueDiff(q(["a", "b", "c"]), q(["a", "c", "b"])).kind, "moved");
  assert.equal(u.message({ kind: "moved", count: 0 }), null);
  assert.equal(u.message({ kind: "none", count: 0 }), null);
});

test("usunięcie: podciąg poprzedniej kolejki", () => {
  const d = u.queueDiff(q(["a", "b", "c", "d"]), q(["a", "c", "d"]));
  assert.equal(d.kind, "removed");
  assert.equal(d.count, 1);
  assert.deepEqual(d.removed, ["b"]);
  assert.equal(u.queueDiff(q(["a", "b", "c", "d"]), q(["a", "d"])).count, 2);
});

test("wyczyszczenie kolejki ma sens od dwóch utworów", () => {
  assert.deepEqual(u.queueDiff(q(["a", "b", "c"]), q([], null)), { kind: "cleared", count: 3 });
  assert.equal(u.queueDiff(q(["a"]), q([], null)).kind, "replaced");
});

test("dodanie jako następne i na koniec", () => {
  assert.equal(u.queueDiff(q(["a", "b", "c"], "a"), q(["a", "x", "b", "c"], "a")).kind, "added-next");
  assert.equal(u.queueDiff(q(["a", "b", "c"], "b"), q(["a", "b", "x", "y", "c"], "b")).kind, "added-next");
  const end = u.queueDiff(q(["a", "b", "c"], "a"), q(["a", "b", "c", "x", "y"], "a"));
  assert.equal(end.kind, "added-end");
  assert.equal(end.count, 2);
  assert.equal(u.queueDiff(q(["a", "b", "c"], "a"), q(["a", "x", "b", "y", "c"], "a")).kind, "added");
});

test("dodanie na końcu, gdy gra ostatni utwór, to „na koniec”, nie „następne”", () => {
  assert.equal(u.queueDiff(q(["a", "b"], "b"), q(["a", "b", "x"], "b")).kind, "added-end");
});

test("wymiana kolejki (nowy album) i start z pustej to nie jest ani usunięcie, ani dodanie", () => {
  assert.equal(u.queueDiff(q(["a", "b", "c"]), q(["x", "y", "z"])).kind, "replaced");
  assert.equal(u.queueDiff(q(["a", "b", "c"]), q(["a", "x"])).kind, "replaced");
  assert.equal(u.queueDiff(q([], null), q(["a", "b"])).kind, "replaced");
  assert.equal(u.message({ kind: "replaced", count: 2 }), null);
});

test("odmiana: 1 utwór, 2–4 utwory, 5+ utworów, 12–14 utworów, 22 utwory", () => {
  assert.equal(u.songs(1), "1 utwór");
  assert.equal(u.songs(3), "3 utwory");
  assert.equal(u.songs(5), "5 utworów");
  assert.equal(u.songs(12), "12 utworów");
  assert.equal(u.songs(22), "22 utwory");
});

test("komunikaty: tytuł przy jednym utworze, liczba przy wielu, zawsze z „Cofnij”", () => {
  assert.deepEqual(u.message({ kind: "removed", count: 1 }, "Genesis"), { text: "Usunięto z kolejki: „Genesis”", undo: true });
  assert.equal(u.message({ kind: "removed", count: 3 }, "Genesis").text, "Usunięto z kolejki: 3 utwory");
  assert.equal(u.message({ kind: "cleared", count: 12 }).text, "Wyczyszczono kolejkę (12 utworów)");
  assert.equal(u.message({ kind: "added-next", count: 1 }, "Intro").text, "Zagrane jako następne: „Intro”");
  assert.equal(u.message({ kind: "added-end", count: 5 }).text, "Dodano na koniec kolejki: 5 utworów");
  assert.equal(u.message({ kind: "added", count: 2 }).undo, true);
});

test("odporność na brakujące dane", () => {
  assert.equal(u.queueDiff(null, null).kind, "none");
  assert.equal(u.queueDiff(undefined, q(["a"])).kind, "replaced");
});
