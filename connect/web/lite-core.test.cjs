const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("./lite-core.js");

// Prawdziwe User-Agenty
const IPAD_MINI4_IPADOS15 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Safari/605.1.15";
const IPAD_MOBILE_UA = "Mozilla/5.0 (iPad; CPU OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1";
const IPHONE_IOS17 = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1";
const CHROME_IOS15 = "Mozilla/5.0 (iPad; CPU OS 15_8 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0.6099.119 Mobile/15E148 Safari/604.1";
const MAC_SAFARI17 = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15";
const CHROME_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
const ANDROID_CHROME = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

test("parseBrowser: iPadOS 15 podający się za Maca jest rozpoznany po ekranie dotykowym", () => {
  const b = core.parseBrowser(IPAD_MINI4_IPADOS15, 5);
  assert.equal(b.ipad, true);
  assert.equal(b.ios, true);
  assert.equal(b.webkit, 15);
  assert.equal(b.webkitMinor, 6);
  assert.equal(core.parseBrowser(MAC_SAFARI17, 0).ios, false, "prawdziwy Mac bez dotyku to nie iPad");
  assert.equal(core.parseBrowser(MAC_SAFARI17, 0).webkit, 17);
});

test("parseBrowser: iPad w trybie mobilnym, iPhone i Chrome na iOS", () => {
  assert.deepEqual([core.parseBrowser(IPAD_MOBILE_UA, 5).ipad, core.parseBrowser(IPAD_MOBILE_UA, 5).webkit], [true, 15]);
  const iphone = core.parseBrowser(IPHONE_IOS17, 5);
  assert.deepEqual([iphone.iphone, iphone.ios, iphone.webkit], [true, true, 17]);
  const chromeIos = core.parseBrowser(CHROME_IOS15, 5);
  assert.equal(chromeIos.webkit, 15, "Chrome na iOS używa silnika systemu (15), nie numeru Chrome");
  assert.equal(chromeIos.ios, true);
});

test("parseBrowser: Windows Chrome i Android nie są WebKitem iOS", () => {
  assert.equal(core.parseBrowser(CHROME_DESKTOP, 0).ios, false);
  assert.equal(core.parseBrowser(CHROME_DESKTOP, 0).webkit, null);
  assert.equal(core.parseBrowser(ANDROID_CHROME, 5).android, true);
  assert.equal(core.parseBrowser("", 0).webkit, null);
  assert.equal(core.parseBrowser(undefined, undefined).ios, false);
});

test("weakReasons: iPad mini 4 (Safari 15, 2 rdzenie) wygląda na słaby; nowy iPhone i komputer nie", () => {
  assert.deepEqual(core.weakReasons({ ua: IPAD_MINI4_IPADOS15, touchPoints: 5, cores: 2 }), ["old-webkit", "few-cores"]);
  assert.deepEqual(core.weakReasons({ ua: IPHONE_IOS17, touchPoints: 5, cores: 6 }), []);
  assert.deepEqual(core.weakReasons({ ua: CHROME_DESKTOP, touchPoints: 0, cores: 8, deviceMemory: 8 }), []);
  assert.deepEqual(core.weakReasons({ ua: ANDROID_CHROME, touchPoints: 5, cores: 4, deviceMemory: 1 }), ["low-memory"]);
  assert.deepEqual(core.weakReasons({}), []);
  assert.deepEqual(core.weakReasons(null), []);
});

test("weakReasons: Safari 16 i nowszy na iOS nie jest uznany za starą przeglądarkę", () => {
  const ios16 = "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1";
  assert.deepEqual(core.weakReasons({ ua: ios16, touchPoints: 5, cores: 4 }), []);
});

test("describeReasons opisuje powody po polsku", () => {
  assert.equal(core.describeReasons(["old-webkit", "few-cores"]), "stara przeglądarka, dwa rdzenie procesora");
  assert.equal(core.describeReasons([]), "");
});

test("paramFromUrl czyta ?lite= także z części po #", () => {
  assert.equal(core.paramFromUrl("?lite=1", ""), true);
  assert.equal(core.paramFromUrl("?lite=0", ""), false);
  assert.equal(core.paramFromUrl("?a=1&lite=on", ""), true);
  assert.equal(core.paramFromUrl("?lite=Off", ""), false);
  assert.equal(core.paramFromUrl("", "#/start?lite=1"), true);
  assert.equal(core.paramFromUrl("?lite=cokolwiek", ""), null);
  assert.equal(core.paramFromUrl("", "#/album/xyz"), null);
  assert.equal(core.paramFromUrl("?elite=1", ""), null, "inny parametr o podobnej nazwie");
  assert.equal(core.paramFromUrl(null, null), null);
});

test("resolve: adres wygrywa z zapisem i jest zapamiętywany, brak wyboru zostaje wyłączony", () => {
  assert.deepEqual(core.resolve("?lite=1", "", "0"), { on: true, persist: "1", source: "url" });
  assert.deepEqual(core.resolve("?lite=0", "", "1"), { on: false, persist: "0", source: "url" });
  assert.deepEqual(core.resolve("", "", "1"), { on: true, persist: null, source: "stored" });
  assert.deepEqual(core.resolve("", "", "0"), { on: false, persist: null, source: "stored" });
  assert.deepEqual(core.resolve("", "", null), { on: false, persist: null, source: "default" });
  assert.deepEqual(core.resolve("", "", "dziwne"), { on: false, persist: null, source: "default" });
});

test("shouldSuggest: tylko na słabym urządzeniu, bez wyboru i bez wcześniejszej podpowiedzi", () => {
  const weak = { ua: IPAD_MINI4_IPADOS15, touchPoints: 5, cores: 2 };
  const strong = { ua: CHROME_DESKTOP, touchPoints: 0, cores: 8, deviceMemory: 8 };
  assert.equal(core.shouldSuggest(null, null, weak), true);
  assert.equal(core.shouldSuggest(null, null, strong), false);
  assert.equal(core.shouldSuggest("1", null, weak), false, "już włączony");
  assert.equal(core.shouldSuggest("0", null, weak), false, "świadomie wyłączony");
  assert.equal(core.shouldSuggest(null, "1", weak), false, "podpowiedź już była");
});

test("tryby powtarzania i losowania (zgodne z przyciskiem Piterfy)", () => {
  assert.equal(core.nextRepeat("order"), "orderLoop");
  assert.equal(core.nextRepeat("orderLoop"), "singleLoop");
  assert.equal(core.nextRepeat("singleLoop"), "order");
  assert.equal(core.nextRepeat("shufflePlay"), "orderLoop");
  assert.equal(core.nextRepeat(undefined), "orderLoop");
  assert.equal(core.toggleShuffle("order"), "shufflePlay");
  assert.equal(core.toggleShuffle("shufflePlay"), "order");
  assert.equal(core.toggleShuffle("singleLoop"), "shufflePlay");
  assert.deepEqual(core.repeatState("singleLoop"), { on: true, one: true });
  assert.deepEqual(core.repeatState("orderLoop"), { on: true, one: false });
  assert.deepEqual(core.repeatState("order"), { on: false, one: false });
  assert.equal(core.shuffleOn("shufflePlay"), true);
  assert.equal(core.repeatLabel("singleLoop"), "Powtarzanie: jeden utwór");
});

test("formatTime i progress są odporne na nieskończoność i NaN", () => {
  assert.equal(core.formatTime(0), "0:00");
  assert.equal(core.formatTime(65.9), "1:05");
  assert.equal(core.formatTime(3725), "1:02:05");
  assert.equal(core.formatTime(Infinity), "0:00");
  assert.equal(core.formatTime(NaN), "0:00");
  assert.equal(core.formatTime(-5), "0:00");
  assert.equal(core.progress(30, 120), 0.25);
  assert.equal(core.progress(10, 0), 0);
  assert.equal(core.progress(10, Infinity), 0);
  assert.equal(core.progress(500, 100), 1);
});

test("fractionAt przelicza dotyk na ułamek suwaka w granicach 0–1", () => {
  assert.equal(core.fractionAt(150, 100, 200), 0.25);
  assert.equal(core.fractionAt(50, 100, 200), 0);
  assert.equal(core.fractionAt(999, 100, 200), 1);
  assert.equal(core.fractionAt(150, 100, 0), 0);
});

test("withCoverSize podmienia rozmiar tylko w adresach okładek Subsonic", () => {
  assert.equal(core.withCoverSize("/rest/getCoverArt.view?id=al1&size=600&u=x", 240), "/rest/getCoverArt.view?id=al1&size=240&u=x");
  assert.equal(core.withCoverSize("/rest/getCoverArt.view?id=al1&u=x", 120), "/rest/getCoverArt.view?id=al1&u=x&size=120");
  assert.equal(core.withCoverSize("/rest/getCoverArt?id=al1", 120), "/rest/getCoverArt?id=al1&size=120");
  assert.equal(core.withCoverSize("/img/logo.png?size=600", 120), "/img/logo.png?size=600");
  assert.equal(core.withCoverSize("", 120), "");
  assert.equal(core.withCoverSize("/rest/getCoverArt?id=a", 0), "/rest/getCoverArt?id=a");
});

test("homeLimits: mniej półek na wąskim ekranie", () => {
  assert.deepEqual(core.homeLimits(1024), { shelves: 5, perShelf: 8 });
  assert.deepEqual(core.homeLimits(768), { shelves: 4, perShelf: 6 });
  assert.deepEqual(core.homeLimits(0), { shelves: 4, perShelf: 6 });
});

test("stripParam zdejmuje tylko parametr lite i nie zostawia śmieci w adresie", () => {
  assert.equal(core.stripParam("?lite=1"), "");
  assert.equal(core.stripParam("?lite=1&a=2"), "?a=2");
  assert.equal(core.stripParam("?a=1&lite=1"), "?a=1");
  assert.equal(core.stripParam("?a=1&lite=1&b=2"), "?a=1&b=2");
  assert.equal(core.stripParam("#/start?lite=0"), "#/start");
  assert.equal(core.stripParam("?elite=1"), "?elite=1");
  assert.equal(core.stripParam(""), "");
  assert.equal(core.stripParam(null), "");
});
