const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const lite = require("./lite-core.js");

const BOOT = fs.readFileSync(path.join(__dirname, "boot.js"), "utf8");

/** Uruchamia prawdziwy boot.js w atrapie przeglądarki i zwraca to, co zrobił z <html>, localStorage i document.write. */
function runBoot({ search = "", hash = "", store = {}, oldBrowser = false, throwing = false } = {}) {
  const attrs = new Map();
  const written = [];
  const local = new Map(Object.entries(store));
  const timers = [];
  const urls = [];
  const element = () => ({
    setAttribute() {}, appendChild() {}, classList: { add() {} }, style: {}, parentNode: null, innerHTML: "", textContent: "", id: "", className: "",
  });
  const root = {
    setAttribute: (k, v) => attrs.set(k, v), hasAttribute: (k) => attrs.has(k), removeAttribute: (k) => attrs.delete(k), appendChild() {},
  };
  const sandbox = {
    document: {
      documentElement: root, currentScript: { src: "https://x.test/piterfy-ui/boot.js?v=123-boot-1" }, head: { appendChild() {} },
      write: (html) => written.push(html), createElement: element,
    },
    location: { search, hash, pathname: "/app/" },
    history: { state: null, replaceState: (state, title, url) => urls.push(url) },
    localStorage: throwing
      ? { getItem() { throw new Error("zablokowane"); }, setItem() { throw new Error("zablokowane"); } }
      : { getItem: (k) => (local.has(k) ? local.get(k) : null), setItem: (k, v) => local.set(k, String(v)) },
    sessionStorage: { getItem: () => null, setItem() {} },
    performance: { now: () => 0 },
    matchMedia: () => ({ matches: false }),
    setTimeout: (fn, ms) => { timers.push([fn, ms]); return timers.length; },
    Date,
  };
  sandbox.window = sandbox;
  const context = vm.createContext(sandbox);
  if (oldBrowser) {
    sandbox.AbortSignal = class AbortSignal {};
    sandbox.crypto = {};
    vm.runInContext("delete Object.hasOwn; delete Array.prototype.at; delete Array.prototype.findLast;", context);
  } else {
    sandbox.AbortSignal = { timeout() {} };
    sandbox.crypto = { randomUUID() {} };
  }
  vm.runInContext(BOOT, context, { filename: "boot.js" });
  return { attrs, written: written.join("\n"), store: local, timers, urls };
}

test("boot.js: ?lite=1 włącza tryb Lite, zapamiętuje go i ładuje lite.css", () => {
  const r = runBoot({ search: "?lite=1" });
  assert.equal(r.attrs.get("data-pf-lite"), "1");
  assert.equal(r.attrs.get("data-pf-motion"), "reduced");
  assert.equal(r.store.get("pf-lite"), "1");
  assert.match(r.written, /id="pf-lite-css" href="\/piterfy-ui\/lite\.css\?v=123-boot-1"/);
});

test("boot.js: ?lite=0 wyłącza zapisany wcześniej tryb Lite", () => {
  const r = runBoot({ search: "?lite=0", store: { "pf-lite": "1" } });
  assert.equal(r.attrs.has("data-pf-lite"), false);
  assert.equal(r.store.get("pf-lite"), "0");
  assert.doesNotMatch(r.written, /lite\.css/);
});

test("boot.js: zapisany wybór działa bez parametru, a brak wyboru zostaje pełną wersją (tylko podpowiedź, nigdy automat)", () => {
  assert.equal(runBoot({ store: { "pf-lite": "1" } }).attrs.get("data-pf-lite"), "1");
  assert.equal(runBoot({ store: { "pf-lite": "0" } }).attrs.has("data-pf-lite"), false);
  const auto = runBoot({});
  assert.equal(auto.attrs.has("data-pf-lite"), false);
  assert.equal(auto.store.has("pf-lite"), false);
});

test("boot.js i PiterfyLiteCore.resolve dają ten sam wynik dla tych samych danych", () => {
  const cases = [
    ["?lite=1", "", null], ["?lite=0", "", "1"], ["", "#/start?lite=1", null], ["", "#/start?lite=0", "1"], ["", "", "1"], ["", "", "0"], ["", "", null],
    ["?lite=Tak", "", null], ["?lite=Off", "", "1"], ["?lite=banan", "", "1"], ["?lite=banan", "", null], ["?x=1&lite=true", "", null],
    ["?lite=%31", "", null], ["", "#/album/5", "1"], ["?lite=0", "#/a?lite=1", null],
  ];
  cases.forEach(([search, hash, stored]) => {
    const expected = lite.resolve(search, hash, stored);
    const got = runBoot({ search, hash, store: stored == null ? {} : { "pf-lite": stored } });
    assert.equal(got.attrs.get("data-pf-lite") === "1", expected.on, JSON.stringify([search, hash, stored]));
    if (expected.persist !== null) assert.equal(got.store.get("pf-lite"), expected.persist, "zapis " + JSON.stringify([search, hash, stored]));
  });
});

test("boot.js w trybie Lite nie ładuje szkła ani speed.js, a zasłona jest prosta (bez intro)", () => {
  const r = runBoot({ search: "?lite=1", store: { "pf-glass": '{"on":true,"level":60}' } });
  assert.doesNotMatch(r.written, /glass/);
  assert.doesNotMatch(r.written, /speed/);
  const full = runBoot({ store: { "pf-glass": '{"on":true,"level":60}' } });
  assert.match(full.written, /glass\.css/);
  assert.match(full.written, /speed\.js/);
  // pierwsze timery: MAX_MS (awaryjne zdjęcie zasłony) zostaje, a intro nie ma minimalnego czasu
  assert.ok(r.timers.some(([, ms]) => ms === 4000));
});

test("boot.js: stary Safari (brak AbortSignal.timeout i spółki) dostaje compat-core.js przed resztą", () => {
  const old = runBoot({ oldBrowser: true });
  assert.match(old.written, /compat-core\.js\?v=123-boot-1/);
  assert.match(old.written, /PiterfyCompat\.install\(window\)/);
  assert.ok(old.written.indexOf("compat-core.js") < (old.written.indexOf("speed-core.js") === -1 ? Infinity : old.written.indexOf("speed-core.js")));
  assert.doesNotMatch(runBoot({}).written, /compat-core/);
});

test("boot.js przeżywa zablokowany localStorage (tryb prywatny): pełna wersja, bez wyjątku", () => {
  const r = runBoot({ throwing: true, search: "?lite=1" });
  assert.equal(r.attrs.get("data-pf-lite"), "1", "parametr działa także bez możliwości zapisu");
  assert.doesNotThrow(() => runBoot({ throwing: true }));
  assert.equal(runBoot({ throwing: true }).attrs.has("data-pf-lite"), false);
});

test("boot.js zdejmuje parametr lite z adresu po zapisaniu wyboru (wyłączenie w „Wygląd” nie wraca po odświeżeniu)", () => {
  assert.deepEqual(runBoot({ search: "?lite=1" }).urls, ["/app/"]);
  assert.deepEqual(runBoot({ search: "?a=1&lite=0&b=2", hash: "#/start" }).urls, ["/app/?a=1&b=2#/start"]);
  assert.deepEqual(runBoot({ hash: "#/start?lite=1" }).urls, ["/app/#/start"]);
  assert.deepEqual(runBoot({ search: "?lite=1", hash: "#/start?lite=1" }).urls, ["/app/#/start"]);
  assert.deepEqual(runBoot({ search: "?x=1" }).urls, [], "bez parametru adres się nie zmienia");
  assert.deepEqual(runBoot({ store: { "pf-lite": "1" } }).urls, []);
  // ta sama reguła co w PiterfyLiteCore.stripParam
  [["?lite=1", ""], ["?a=1&lite=0&b=2", ""], ["", "#/start?lite=1"]].forEach(([search, hash]) => {
    const got = runBoot({ search, hash }).urls[0];
    assert.equal(got, "/app/" + lite.stripParam(search) + lite.stripParam(hash));
  });
});
