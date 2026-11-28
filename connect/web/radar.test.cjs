const test = require("node:test");
const assert = require("node:assert/strict");
const radar = require("./radar.js");

const item = (id, extra) => ({ id: String(id), artist: "Taco Hemingway", title: "Nowy album", type: "album", released: "2026-11-20", cover: "https://cdn.example/c.jpg", tracks: 12, status: "new", ...extra });

test("typeLabel names the release kinds", () => {
  assert.equal(radar.typeLabel("album"), "Album");
  assert.equal(radar.typeLabel("EP"), "EP");
  assert.equal(radar.typeLabel("single"), "Singiel");
  assert.equal(radar.typeLabel("coś"), "Wydanie");
  assert.equal(radar.typeLabel(null), "Wydanie");
});

test("dateLabel counts days, goes absolute after two weeks and survives garbage", () => {
  const now = new Date(2026, 10, 28, 15, 0);
  assert.equal(radar.dateLabel("2026-11-28", now), "Dziś");
  assert.equal(radar.dateLabel("2026-11-29", now), "Dziś");        // wydanie „na jutro” nie jest z przyszłości dla czytelnika
  assert.equal(radar.dateLabel("2026-11-27", now), "Wczoraj");
  assert.equal(radar.dateLabel("2026-11-20", now), "8 dni temu");
  assert.equal(radar.dateLabel("2026-11-05", now), "05.11");
  assert.equal(radar.dateLabel("", now), "");
  assert.equal(radar.dateLabel("nie data", now), "");
  assert.equal(radar.dateLabel(undefined, now), "");
});

test("actionState follows the request lifecycle and the server switch", () => {
  assert.deepEqual(radar.actionState(item(1), true), { kind: "new", disabled: false, label: "Dociągnij" });
  assert.deepEqual(radar.actionState(item(1, { status: "queued" }), true), { kind: "busy", disabled: true, label: "W kolejce…" });
  assert.equal(radar.actionState(item(1, { status: "queued", have: 3, total: 12 }), true).label, "Pobieram 3/12");
  assert.equal(radar.actionState(item(1, { status: "done", have: 11, total: 12 }), true).label, "Gotowe 11/12");
  assert.equal(radar.actionState(item(1, { status: "done" }), true).label, "Gotowe");
  assert.deepEqual(radar.actionState(item(1, { status: "failed" }), true), { kind: "failed", disabled: false, label: "Spróbuj ponownie" });
  assert.equal(radar.actionState(item(1), false).kind, "off");
  assert.equal(radar.actionState(item(1, { status: "disabled" }), false).disabled, true);
  assert.equal(radar.actionState(item(1, { status: "disabled" }), true).kind, "new");   // serwer włączył dociąganie po tym zapisie
});

test("sortItems puts the newest first and drops broken rows", () => {
  const got = radar.sortItems([item(1, { released: "2026-11-01" }), null, { id: "x" }, item(2, { released: "2026-11-20", artist: "B" }),
    item(3, { released: "2026-11-20", artist: "A" }), item(4, { released: "" })]);
  assert.deepEqual(got.map((i) => i.id), ["3", "2", "1", "4"]);
  assert.deepEqual(radar.sortItems(null), []);
});

test("shouldPoll only while something is queued", () => {
  assert.equal(radar.shouldPoll([item(1), item(2, { status: "queued" })]), true);
  assert.equal(radar.shouldPoll([item(1), item(2, { status: "done" })]), false);
  assert.equal(radar.shouldPoll(undefined), false);
});

test("html escapes everything and only trusts https covers", () => {
  const evil = item(1, { title: '<img src=x onerror="alert(1)">', artist: "A & B", cover: "javascript:alert(1)", note: "<b>uwaga</b>", status: "failed" });
  const html = radar.cardHtml(evil, true, new Date(2026, 10, 28));
  assert.ok(!html.includes("<img src=x"));
  assert.ok(!html.includes("javascript:"));
  assert.ok(!html.includes("<b>uwaga"));
  assert.match(html, /A &amp; B/);
  assert.match(html, /Spróbuj ponownie/);
  assert.match(html, /data-radar-id="1"/);
  const ok = radar.cardHtml(item(2), true, new Date(2026, 10, 28));
  assert.match(ok, /<img src="https:\/\/cdn\.example\/c\.jpg"/);
  assert.match(ok, /Album · 8 dni temu · 12 utw\./);
  assert.match(ok, /data-kind="new"/);
  assert.ok(!/ disabled/.test(ok));
});

test("note is hidden while queued and the shelf is empty without releases", () => {
  const queued = radar.cardHtml(item(1, { status: "queued", note: "Szukam w Soulseek…" }), true, new Date());
  assert.ok(!queued.includes("pf-radar-note"));
  assert.equal(radar.sectionHtml([], true, new Date()), "");
  assert.equal(radar.sectionHtml(undefined, true, new Date()), "");
  const html = radar.sectionHtml([item(1), item(2, { released: "2026-11-25" })], true, new Date(2026, 10, 28));
  assert.match(html, /Nowości od Twoich artystów/);
  assert.equal((html.match(/pf-radar-card/g) || []).length, 2);
  assert.ok(html.indexOf('data-radar-id="2"') < html.indexOf('data-radar-id="1"'));   // nowsze pierwsze
});
