/* Piterfy — tryb Lite dla słabych urządzeń (np. stary iPad mini 4 z Safari 15): czysta logika bez DOM (testy: lite-core.test.cjs).
 *
 *   pf-lite        "1" włączony | "0" wyłączony | brak = decyduje użytkownik (patrz hint)
 *   pf-lite-hint   "1" = jednorazowa podpowiedź o trybie Lite została już pokazana/odrzucona
 *   ?lite=1 / ?lite=0 w adresie (także w części po #) włącza albo wyłącza tryb i zapamiętuje wybór.
 *
 * Wykrywanie nigdy nie włącza trybu samo: tylko podpowiada (jedna dymka z pytaniem), bo tylko użytkownik wie, czy woli płynność, czy efekty. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyLiteCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  const KEY = "pf-lite";
  const HINT_KEY = "pf-lite-hint";
  const ON = ["1", "true", "on", "tak", "yes"];
  const OFF = ["0", "false", "off", "nie", "no"];

  /**
   * Rozpoznaje przeglądarkę z User-Agenta. iPadOS 13+ podaje się za Maca („Macintosh”), więc iPada poznajemy po ekranie dotykowym
   * (`maxTouchPoints` > 1). Wersja WebKita to „Version/x” w Safari, a w innych przeglądarkach na iOS (Chrome, Firefox) wersja systemu —
   * wszystkie używają silnika systemowego, więc obowiązują te same ograniczenia.
   * @returns {{ios:boolean, ipad:boolean, iphone:boolean, safari:boolean, android:boolean, webkit:number|null, webkitMinor:number|null}}
   */
  function parseBrowser(ua, touchPoints) {
    const text = String(ua || "");
    const touches = Number(touchPoints) || 0;
    const iphone = /iPhone|iPod/.test(text);
    const ipadUa = /iPad/.test(text);
    const ipadDesktop = !iphone && !ipadUa && /Macintosh/.test(text) && touches > 1;
    const ipad = ipadUa || ipadDesktop;
    const ios = iphone || ipad;
    const android = /Android/.test(text);
    const alt = /CriOS|FxiOS|EdgiOS|OPiOS|GSA\//.test(text);
    const safari = !alt && /Safari\//.test(text) && !/Chrome\/|Chromium\/|Edg\/|OPR\/|Android/.test(text);
    let webkit = null, webkitMinor = null;
    if (ios) {
      const version = text.match(/Version\/(\d+)(?:\.(\d+))?/);
      const system = text.match(/(?:iPhone |CPU |)OS (\d+)[_.](\d+)/);
      const pick = !alt && version ? version : system;
      if (pick) { webkit = Number(pick[1]); webkitMinor = Number(pick[2] || 0); }
    } else if (safari) {
      const version = text.match(/Version\/(\d+)(?:\.(\d+))?/);
      if (version) { webkit = Number(version[1]); webkitMinor = Number(version[2] || 0); }
    }
    return { ios, ipad, iphone, safari: safari || (ios && !alt), android, webkit, webkitMinor };
  }

  /**
   * Powody, dla których urządzenie wygląda na słabe (puste = nie wygląda).
   * @param env {{ua?:string, touchPoints?:number, deviceMemory?:number, cores?:number}}
   */
  function weakReasons(env) {
    const e = env || {};
    const b = parseBrowser(e.ua, e.touchPoints);
    const out = [];
    if (b.ios && b.webkit != null && b.webkit < 16) out.push("old-webkit");
    if (!b.ios && b.safari && b.webkit != null && b.webkit < 15) out.push("old-webkit");
    if (typeof e.deviceMemory === "number" && e.deviceMemory > 0 && e.deviceMemory <= 2) out.push("low-memory");
    if (typeof e.cores === "number" && e.cores > 0 && e.cores <= 2) out.push("few-cores");
    return out;
  }

  const REASON_TEXT = {
    "old-webkit": "stara przeglądarka",
    "low-memory": "mało pamięci",
    "few-cores": "dwa rdzenie procesora",
  };
  const describeReasons = (reasons) => (reasons || []).map((r) => REASON_TEXT[r]).filter(Boolean).join(", ");

  /** Wartość parametru lite z adresu: true / false / null (brak albo śmieci). Szuka w części „?…” i po „#…?”. */
  function paramFromUrl(search, hash) {
    const read = (query) => {
      const m = String(query || "").match(/(?:^|[?&])lite=([^&#]*)/i);
      if (!m) return null;
      let v = m[1];
      try { v = decodeURIComponent(v); } catch (e) { /* zostaje surowy */ }
      v = v.trim().toLowerCase();
      if (ON.indexOf(v) >= 0) return true;
      if (OFF.indexOf(v) >= 0) return false;
      return null;
    };
    const fromSearch = read(search);
    if (fromSearch !== null) return fromSearch;
    const h = String(hash || "");
    const q = h.indexOf("?");
    return q >= 0 ? read(h.slice(q)) : null;
  }

  /** Adres bez parametru lite (np. "?a=1&lite=1" → "?a=1", "#/start?lite=1" → "#/start"); inne parametry zostają. */
  function stripParam(text) {
    return String(text || "").replace(/([?&])lite=[^&#]*&?/i, "$1").replace(/[?&]$/, "");
  }

  /**
   * Czy tryb Lite ma być włączony i co zapisać.
   * @returns {{on:boolean, persist:("1"|"0"|null), source:("url"|"stored"|"default")}}
   */
  function resolve(search, hash, stored) {
    const fromUrl = paramFromUrl(search, hash);
    if (fromUrl !== null) return { on: fromUrl, persist: fromUrl ? "1" : "0", source: "url" };
    if (stored === "1") return { on: true, persist: null, source: "stored" };
    if (stored === "0") return { on: false, persist: null, source: "stored" };
    return { on: false, persist: null, source: "default" };
  }

  /** Czy pokazać jednorazową podpowiedź „włączyć tryb Lite?”. */
  function shouldSuggest(stored, hintSeen, env) {
    if (stored === "1" || stored === "0") return false;
    if (hintSeen === "1") return false;
    return weakReasons(env).length > 0;
  }

  // ── stan odtwarzania (zgodny z trybami player.mode Navidrome / Piterfy: order, orderLoop, singleLoop, shufflePlay) ──

  /** Następny tryb powtarzania po kliknięciu (jak w Spotify: wyłączone → cała kolejka → jeden utwór → wyłączone). */
  function nextRepeat(mode) {
    if (mode === "orderLoop") return "singleLoop";
    if (mode === "singleLoop") return "order";
    return "orderLoop"; // order, shufflePlay i nieznane
  }

  /** Przełącznik losowania: włącza „shufflePlay”, a z powrotem wraca do „order”. */
  const toggleShuffle = (mode) => (mode === "shufflePlay" ? "order" : "shufflePlay");
  const repeatState = (mode) => ({ on: mode === "orderLoop" || mode === "singleLoop", one: mode === "singleLoop" });
  const shuffleOn = (mode) => mode === "shufflePlay";
  const repeatLabel = (mode) => (mode === "singleLoop" ? "Powtarzanie: jeden utwór" : mode === "orderLoop" ? "Powtarzanie: cała kolejka" : "Powtarzanie: wyłączone");

  /** „m:ss” albo „h:mm:ss”; nieskończone i nieliczbowe wartości dają „0:00”. */
  function formatTime(seconds) {
    const total = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
    const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
    const two = (n) => (n < 10 ? "0" + n : String(n));
    return h ? h + ":" + two(m) + ":" + two(s) : m + ":" + two(s);
  }

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /** Postęp 0–1 (strumienie bez długości dają 0). */
  function progress(current, duration) {
    if (!Number.isFinite(current) || !Number.isFinite(duration) || duration <= 0) return 0;
    return clamp(current / duration, 0, 1);
  }

  /** Położenie palca na suwaku jako ułamek 0–1 (`left`/`width` z getBoundingClientRect). */
  function fractionAt(clientX, left, width) {
    if (!(width > 0)) return 0;
    return clamp((clientX - left) / width, 0, 1);
  }

  /** Adres okładki Subsonic z innym rozmiarem (`size=`); inne adresy zostają bez zmian. Małe okładki to mniej pikseli do zdekodowania. */
  function withCoverSize(url, size) {
    const text = String(url || "");
    if (!/getCoverArt/i.test(text) || !(size > 0)) return text;
    if (/([?&])size=\d+/.test(text)) return text.replace(/([?&])size=\d+/, "$1size=" + Math.round(size));
    return text + (text.indexOf("?") >= 0 ? "&" : "?") + "size=" + Math.round(size);
  }

  /**
   * Ile pozycji zostawić w półce na stronie startowej w trybie Lite (im mniej, tym mniej obrazków i elementów do utrzymania w pamięci).
   * @returns {{shelves:number, perShelf:number}}
   */
  function homeLimits(viewportWidth) {
    const w = Number(viewportWidth) || 0;
    return w >= 900 ? { shelves: 5, perShelf: 8 } : { shelves: 4, perShelf: 6 };
  }

  return {
    KEY, HINT_KEY, parseBrowser, weakReasons, describeReasons, paramFromUrl, stripParam, resolve, shouldSuggest,
    nextRepeat, toggleShuffle, repeatState, shuffleOn, repeatLabel, formatTime, progress, fractionAt, withCoverSize, homeLimits,
  };
});
