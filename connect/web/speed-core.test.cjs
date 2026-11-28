const test = require("node:test");
const assert = require("node:assert/strict");
const s = require("./speed-core.js");

const memory = (initial = {}) => {
  const map = new Map(Object.entries(initial));
  return { getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, String(v)), removeItem: (k) => map.delete(k), map };
};
const pixels = (...rgba) => Uint8ClampedArray.from(rgba.flat());

test("hex conversion round-trips and rejects garbage", () => {
  assert.equal(s.toHex({ r: 255, g: 0, b: 128 }), "#ff0080");
  assert.deepEqual(s.fromHex("#1db954"), { r: 29, g: 185, b: 84 });
  assert.deepEqual(s.fromHex("1db954"), { r: 29, g: 185, b: 84 });
  assert.equal(s.fromHex("zielony"), null);
  assert.equal(s.fromHex(null), null);
  assert.equal(s.toHex({ r: 300, g: -5, b: 12.4 }), "#ff000c");   // przycina do 0–255
});

test("averageColour favours vivid mid-tones over black, white and grey", () => {
  const vivid = [200, 30, 30, 255], black = [0, 0, 0, 255], white = [255, 255, 255, 255];
  const c = s.averageColour(pixels(vivid, vivid, black, black, black, white));
  assert.ok(c.r > 150 && c.g < 80 && c.b < 80, "wyszedł " + JSON.stringify(c));
  assert.equal(s.averageColour(pixels([10, 20, 30, 0], [10, 20, 30, 10])), null);          // przezroczyste
  assert.deepEqual(s.averageColour(pixels([100, 100, 100, 255])), { r: 100, g: 100, b: 100 });   // sam szary
  assert.equal(s.averageColour(new Uint8ClampedArray(0)), null);
  assert.equal(s.averageColour(null), null);
});

test("placeholderColour darkens towards the panel and keeps the hue", () => {
  const out = s.fromHex(s.placeholderColour("#ff0000"));
  assert.ok(out.r > out.g && out.r > out.b && out.r < 255);
  assert.equal(s.placeholderColour("nie-kolor"), "");
});

test("coverId reads the id of getCoverArt urls only", () => {
  assert.equal(s.coverId("/rest/getCoverArt?u=a&id=al-12&size=300"), "al-12");
  assert.equal(s.coverId("http://x/rest/getCoverArt.view?id=mf-1"), "mf-1");
  assert.equal(s.coverId("/rest/getCoverArt?size=300"), null);
  assert.equal(s.coverId("/img/logo.png"), null);
  assert.equal(s.coverId(null), null);
});

test("ColourCache stores, persists, rejects bad colours and evicts the oldest", () => {
  let t = 1000;
  const store = memory();
  const cache = s.ColourCache(store, () => t++);
  assert.equal(cache.set("a", "#112233"), true);
  assert.equal(cache.set("b", "nie"), false);
  assert.equal(cache.get("a"), "#112233");
  assert.equal(cache.get("zzz"), null);
  assert.equal(s.ColourCache(store).get("a"), "#112233");   // odczyt z magazynu po „restarcie”
  for (let i = 0; i < s.COLOUR_MAX + 25; i++) cache.set("id" + i, "#0000" + (i % 256).toString(16).padStart(2, "0"));
  assert.equal(cache.size(), s.COLOUR_MAX);
  assert.equal(cache.get("a"), null);                        // najstarszy wypadł
  assert.equal(cache.recent(2).length, 2);
  assert.equal(cache.get("id" + (s.COLOUR_MAX + 24)) !== null, true);
});

test("ColourCache survives broken storage", () => {
  const broken = { getItem() { throw new Error("x"); }, setItem() { throw new Error("x"); } };
  const cache = s.ColourCache(broken);
  assert.doesNotThrow(() => cache.set("a", "#abcdef"));
  assert.equal(cache.get("a"), "#abcdef");
  assert.equal(s.ColourCache(memory({ "pf-cover-colours": "{nie json" })).size(), 0);
});

const PF = "/rest/getAlbumList2?u=piotr&t=tok&s=salt&v=1.16.1&c=Piterfy&f=json&type=recent&size=16";

test("cacheable: only read-only calls made by the Piterfy layer", () => {
  const key = s.cacheable(PF, "GET");
  assert.equal(key, "piotr|getAlbumList2?size=16&type=recent");
  assert.equal(s.cacheable(PF, undefined), key);
  assert.equal(s.cacheable(PF, "POST"), null);
  assert.equal(s.cacheable(PF.replace("c=Piterfy", "c=NavidromeUI"), "GET"), null);          // zapytania samego Navidrome zostają w spokoju
  assert.equal(s.cacheable(PF.replace("c=Piterfy", "c=PiterfyRadio"), "GET") !== null, true);
  assert.equal(s.cacheable(PF.replace("type=recent", "type=random"), "GET"), null);
  assert.equal(s.cacheable("/rest/getRandomSongs?c=Piterfy&size=100", "GET"), null);
  assert.equal(s.cacheable("/rest/getSimilarSongs2?c=Piterfy&id=1", "GET"), null);
  assert.equal(s.cacheable("/rest/getPlayQueue?c=Piterfy", "GET"), null);
  assert.equal(s.cacheable("/rest/getBookmarks?c=Piterfy", "GET"), null);
  assert.equal(s.cacheable("/rest/star?c=Piterfy&id=1", "GET"), null);
  assert.equal(s.cacheable("/rest/getPlaylists.view?c=Piterfy&u=a", "GET"), "a|getPlaylists?");
  assert.equal(s.cacheable("/api/song?c=Piterfy", "GET"), null);
  assert.equal(s.cacheable("nie url", "GET"), null);
});

test("cacheable: keys ignore tokens and salts but not the account or the arguments", () => {
  const a = s.cacheable(PF, "GET");
  assert.equal(s.cacheable(PF.replace("t=tok", "t=inny").replace("s=salt", "s=sol"), "GET"), a);
  assert.notEqual(s.cacheable(PF.replace("u=piotr", "u=ola"), "GET"), a);
  assert.notEqual(s.cacheable(PF.replace("size=16", "size=8"), "GET"), a);
  const reordered = "/rest/getAlbumList2?size=16&type=recent&c=Piterfy&u=piotr";
  assert.equal(s.cacheable(reordered, "GET"), a);
});

test("invalidates: Subsonic mutations from any client and native API writes", () => {
  assert.equal(s.invalidates("/rest/star?id=1&c=NavidromeUI", "GET"), true);
  assert.equal(s.invalidates("/rest/scrobble?id=1&c=NavidromeUI", "GET"), true);
  assert.equal(s.invalidates("/rest/createPlaylist.view?name=x", "POST"), true);
  assert.equal(s.invalidates("/api/playlist/1", "PUT"), true);
  assert.equal(s.invalidates("/api/song?_start=0", "GET"), false);
  assert.equal(s.invalidates("/rest/getAlbumList2?c=Piterfy", "GET"), false);
  assert.equal(s.invalidates("/piterfy-api/radar", "POST"), false);
});

test("swrPlan: fresh, stale, expired and missing entries", () => {
  const now = 10_000_000;
  assert.equal(s.swrPlan(null, now), "network");
  assert.equal(s.swrPlan({ t: now - 5000 }, now), "cache");
  assert.equal(s.swrPlan({ t: now - s.FRESH_MS }, now), "stale");
  assert.equal(s.swrPlan({ t: now - s.STALE_MS + 1 }, now), "stale");
  assert.equal(s.swrPlan({ t: now - s.STALE_MS }, now), "network");
  assert.equal(s.swrPlan({ t: now + 5000 }, now), "network");    // zegar cofnięty
  assert.equal(s.swrPlan({}, now), "network");
  assert.equal(s.usableOnError({ t: now - 3 * 86400000 }, now), true);
  assert.equal(s.usableOnError({ t: now - 8 * 86400000 }, now), false);
  assert.equal(s.usableOnError(null, now), false);
});

test("SwrStore: put/get, persistence, size limits and clear", () => {
  let t = 1;
  const store = memory();
  const swr = s.SwrStore(store, () => t++);
  assert.equal(swr.put("k1", '{"a":1}'), true);
  assert.deepEqual(swr.get("k1"), { t: 1, b: '{"a":1}' });
  assert.equal(s.SwrStore(store).get("k1").b, '{"a":1}');                    // po „restarcie”
  assert.equal(swr.put("big", "x".repeat(s.MAX_ENTRY + 1)), false);        // za duża pojedyncza odpowiedź
  assert.equal(swr.get("big"), null);
  assert.equal(swr.put("nie-tekst", { a: 1 }), false);
  const chunk = "y".repeat(240000);
  for (let i = 0; i < 10; i++) swr.put("c" + i, chunk);                      // 2,4 MB — musi zmieścić się w ~1,5 MB
  assert.ok(swr.size() <= s.MAX_TOTAL, "rozmiar " + swr.size());
  assert.equal(swr.get("c9") !== null, true);                                // najnowszy zostaje
  assert.equal(swr.get("k1"), null);                                         // najstarszy wypadł
  swr.clear();
  assert.equal(swr.keys().length, 0);
  assert.equal(store.map.has("pf-swr-v1"), false);
});

test("SwrStore survives broken or full storage", () => {
  const full = { getItem: () => null, setItem() { throw new Error("QuotaExceededError"); }, removeItem() {} };
  const swr = s.SwrStore(full);
  assert.doesNotThrow(() => swr.put("a", "{}"));
  assert.equal(s.SwrStore(memory({ "pf-swr-v1": "{{{" })).keys().length, 0);
});
