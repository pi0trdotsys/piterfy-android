const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const core = require("./compat-core.js");

/** Świeże środowisko JS (własne wbudowane obiekty) z usuniętymi funkcjami, których brakuje w Safari 15. */
function oldBrowser({ keep = [] } = {}) {
  const g = vm.createContext({ setTimeout, clearTimeout });
  vm.runInContext(`
    delete Object.hasOwn; delete Array.prototype.at; delete String.prototype.at;
    delete Array.prototype.findLast; delete Array.prototype.findLastIndex;
    delete Object.getPrototypeOf(Uint8Array.prototype).at;
    globalThis.AbortSignal = class AbortSignal {};
    globalThis.crypto = { getRandomValues(a) { for (let i = 0; i < a.length; i++) a[i] = (i * 37 + 11) & 255; return a; } };
  `, g);
  // w przeglądarce wbudowane konstruktory są właściwościami obiektu globalnego (window.Object itd.); w vm trzeba je dopisać
  ["Object", "Array", "String", "Uint8Array", "Int8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array"]
    .forEach((name) => { g[name] = vm.runInContext(name, g); });
  g.AbortController = class { constructor() { this.signal = { aborted: false, reason: undefined }; } abort(reason) { this.signal.aborted = true; this.signal.reason = reason; } };
  keep.forEach((name) => vm.runInContext(name, g));
  return g;
}

test("missing wskazuje wszystko, czego brakuje w starym Safari, i nic w nowoczesnej przeglądarce", () => {
  assert.deepEqual(core.missing(oldBrowser()).sort(), [
    "AbortSignal.timeout", "Array.at", "Array.findLast", "Object.hasOwn", "String.at", "crypto.randomUUID"].sort());
  assert.deepEqual(core.missing(globalThis), []);
});

test("install dopisuje brakujące funkcje i potem missing jest puste", () => {
  const g = oldBrowser();
  const done = core.install(g);
  assert.ok(done.includes("AbortSignal.timeout") && done.includes("Object.hasOwn") && done.includes("crypto.randomUUID"));
  assert.deepEqual(core.missing(g), []);
  assert.deepEqual(core.install(g), [], "drugie wywołanie nic nie zmienia");
});

test("Array.at, String.at i tablice typowane zachowują się jak w specyfikacji", () => {
  const g = oldBrowser();
  core.install(g);
  assert.equal(vm.runInContext("[1,2,3].at(-1)", g), 3);
  assert.equal(vm.runInContext("[1,2,3].at(0)", g), 1);
  assert.equal(vm.runInContext("[1,2,3].at(3)", g), undefined);
  assert.equal(vm.runInContext("[1,2,3].at(-4)", g), undefined);
  assert.equal(vm.runInContext("'abc'.at(-1)", g), "c");
  assert.equal(vm.runInContext("'abc'.at(5)", g), undefined);
  assert.equal(vm.runInContext("new Uint8Array([4,5,6]).at(-2)", g), 5);
});

test("findLast i findLastIndex szukają od końca", () => {
  const g = oldBrowser();
  core.install(g);
  assert.equal(vm.runInContext("[1,2,3,4].findLast((x) => x % 2 === 1)", g), 3);
  assert.equal(vm.runInContext("[1,2,3,4].findLastIndex((x) => x % 2 === 1)", g), 2);
  assert.equal(vm.runInContext("[1,2].findLast((x) => x > 5)", g), undefined);
  assert.equal(vm.runInContext("[1,2].findLastIndex((x) => x > 5)", g), -1);
});

test("Object.hasOwn sprawdza tylko własne właściwości", () => {
  const g = oldBrowser();
  core.install(g);
  assert.equal(vm.runInContext("Object.hasOwn({a: 1}, 'a')", g), true);
  assert.equal(vm.runInContext("Object.hasOwn({a: 1}, 'toString')", g), false);
  assert.equal(vm.runInContext("Object.hasOwn([], 'length')", g), true);
});

test("AbortSignal.timeout zwraca sygnał, który po czasie się przerywa z TimeoutError", async () => {
  const g = oldBrowser();
  g.DOMException = DOMException;
  core.install(g);
  const signal = g.AbortSignal.timeout(15);
  assert.equal(signal.aborted, false);
  await new Promise((r) => setTimeout(r, 40));
  assert.equal(signal.aborted, true);
  assert.equal(signal.reason.name, "TimeoutError");
});

test("randomUUID daje poprawny UUID v4 i różne wartości", () => {
  const bytes = new Uint8Array(16);
  const id = core.randomUUID({ getRandomValues(a) { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; } });
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(id, core.randomUUID(null)); // bez kryptografii też działa
  assert.match(core.randomUUID(null), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(bytes.length, 16);
});

test("install nigdy nie podmienia funkcji, które już istnieją", () => {
  const g = oldBrowser();
  const mine = () => "moje";
  vm.runInContext("Object.hasOwn = null", g);
  g.Object.hasOwn = mine;
  core.install(g);
  assert.equal(g.Object.hasOwn, mine);
});
