/* Piterfy — zgodność ze starymi przeglądarkami (Safari 15 na iPadzie mini 4 i podobnych). Czysta logika, bez DOM (testy: compat-core.test.cjs).
 *
 * Moduły Piterfy używają kilku nowszych funkcji, których Safari 15.0–15.3 albo całe 15.x nie ma, a brak którejkolwiek rzuca
 * wyjątek w środku modułu (np. fetch(..., { signal: AbortSignal.timeout(8000) }) → „AbortSignal.timeout is not a function”):
 *   AbortSignal.timeout   Safari 16.0     Object.hasOwn          Safari 15.4     Array/String/TypedArray.prototype.at   Safari 15.4
 *   crypto.randomUUID     Safari 15.4     Array.prototype.findLast/findLastIndex   Safari 15.4
 * `install(global)` dopisuje tylko to, czego brakuje (nigdy nie podmienia istniejących funkcji), a `missing(global)` mówi, czy w ogóle
 * trzeba go wołać — boot.js ładuje ten plik synchronicznie przed resztą tylko wtedy, gdy czegoś brakuje, więc nowoczesne
 * przeglądarki nie płacą za niego ani jednym żądaniem. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyCompat = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  "use strict";

  function define(target, name, value) {
    try {
      Object.defineProperty(target, name, { value, writable: true, configurable: true, enumerable: false });
    } catch (e) { /* zamrożony prototyp: zostaje bez polifilla */ }
  }

  function at(index) {
    const n = Math.trunc(index) || 0;
    const i = n < 0 ? this.length + n : n;
    return i < 0 || i >= this.length ? undefined : this[i];
  }

  function findLast(predicate, thisArg) {
    for (let i = this.length - 1; i >= 0; i--) if (predicate.call(thisArg, this[i], i, this)) return this[i];
    return undefined;
  }

  function findLastIndex(predicate, thisArg) {
    for (let i = this.length - 1; i >= 0; i--) if (predicate.call(thisArg, this[i], i, this)) return i;
    return -1;
  }

  /** UUID v4 z getRandomValues (albo Math.random, gdy nie ma kryptografii — to identyfikator, nie sekret). */
  function randomUUID(cryptoObject) {
    const bytes = new Uint8Array(16);
    if (cryptoObject && typeof cryptoObject.getRandomValues === "function") cryptoObject.getRandomValues(bytes);
    else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.prototype.map.call(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20);
  }

  /** Lista braków w danym środowisku (puste = nic do roboty). */
  function missing(g) {
    const out = [];
    const proto = (ctor) => (g[ctor] && g[ctor].prototype) || null;
    if (g.AbortSignal && typeof g.AbortSignal.timeout !== "function") out.push("AbortSignal.timeout");
    if (typeof g.Object.hasOwn !== "function") out.push("Object.hasOwn");
    if (proto("Array") && typeof proto("Array").at !== "function") out.push("Array.at");
    if (proto("String") && typeof proto("String").at !== "function") out.push("String.at");
    if (proto("Array") && typeof proto("Array").findLast !== "function") out.push("Array.findLast");
    if (g.crypto && typeof g.crypto.randomUUID !== "function") out.push("crypto.randomUUID");
    return out;
  }

  /** Dopisuje brakujące funkcje do podanego obiektu globalnego; zwraca listę tego, co dopisano. */
  function install(g) {
    const done = [];
    const gap = missing(g);
    const need = (name) => gap.indexOf(name) >= 0;
    if (need("AbortSignal.timeout")) {
      define(g.AbortSignal, "timeout", function timeout(ms) {
        const controller = new g.AbortController();
        const reason = typeof g.DOMException === "function" ? new g.DOMException("The operation timed out.", "TimeoutError") : new Error("timeout");
        setTimeout(() => controller.abort(reason), ms);
        return controller.signal;
      });
      done.push("AbortSignal.timeout");
    }
    if (need("Object.hasOwn")) {
      define(g.Object, "hasOwn", function hasOwn(obj, key) { return Object.prototype.hasOwnProperty.call(Object(obj), key); });
      done.push("Object.hasOwn");
    }
    if (need("Array.at")) {
      define(g.Array.prototype, "at", at);
      ["Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array"]
        .forEach((name) => { if (g[name] && g[name].prototype && typeof g[name].prototype.at !== "function") define(g[name].prototype, "at", at); });
      done.push("Array.at");
    }
    if (need("String.at")) {
      define(g.String.prototype, "at", function (index) {
        const text = String(this);
        const n = Math.trunc(index) || 0;
        const i = n < 0 ? text.length + n : n;
        return i < 0 || i >= text.length ? undefined : text.charAt(i);
      });
      done.push("String.at");
    }
    if (need("Array.findLast")) {
      define(g.Array.prototype, "findLast", findLast);
      define(g.Array.prototype, "findLastIndex", findLastIndex);
      done.push("Array.findLast");
    }
    if (need("crypto.randomUUID")) {
      const c = g.crypto;
      define(c, "randomUUID", function () { return randomUUID(c); });
      done.push("crypto.randomUUID");
    }
    return done;
  }

  return { missing, install, randomUUID, at, findLast, findLastIndex };
});
