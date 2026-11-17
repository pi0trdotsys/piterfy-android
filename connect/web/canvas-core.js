/* Piterfy — „Canvas”: generowana animacja do ekranu „Teraz gra” (czysta logika, bez DOM; testy: canvas-core.test.cjs).
 * Każdy utwór dostaje własną, zawsze taką samą scenę (styl, prędkość, paleta z koloru okładki) wybraną z jego ID.
 * layout(scena, t) zwraca listę prostych prymitywów (plama, pierścień, kropka, fala) we współrzędnych 0–1, więc strona
 * (canvas.js) i aplikacja (PiterfyCanvasScene.kt) rysują dokładnie to samo. Port Kotlin jest sprawdzany tym samym
 * zestawem wzorców (app/src/test/resources/piterfy_canvas_vectors.json, generuje go tools/canvas_vectors.cjs). */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyCanvasCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const STYLES = ["aurora", "waves", "fireflies", "pulses", "mesh", "vinyl"];
  const FPS = 30;
  const FALLBACK_ACCENT = "#1db954";
  const TAU = Math.PI * 2;

  /** FNV-1a 32 bit po jednostkach UTF-16 (to samo robi port Kotlin). */
  function hash32(text) {
    const s = String(text == null ? "" : text);
    let h = 0x811c9dc5;
    for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193) >>> 0; }
    return h >>> 0;
  }

  /** mulberry32: deterministyczny generator 0 ≤ x < 1. */
  function rng(seed) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
  const frac = (v) => v - Math.floor(v);

  function parseHex(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || ""));
    if (!m) return null;
    return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16) / 255);
  }

  function rgbToHsl(r, g, b) {
    const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
    if (max === min) return [0, 0, l];
    const d = max - min, s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    let h;
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    return [h / 6, s, l];
  }

  function hslToHex(h, s, l) {
    h = frac(h);
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
    const channel = (t) => {
      t = frac(t);
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };
    return "#" + [channel(h + 1 / 3), channel(h), channel(h - 1 / 3)]
      .map((v) => Math.round(clamp(v, 0, 1) * 255).toString(16).padStart(2, "0")).join("");
  }

  /** Paleta sceny z koloru okładki (np. dominantColor): ciemne tło i trzy kolory akcentów. Bez koloru — zieleń Piterfy. */
  function palette(accentHex, shift) {
    const rgb = parseHex(accentHex) || parseHex(FALLBACK_ACCENT);
    const [h0, s, l] = rgbToHsl(rgb[0], rgb[1], rgb[2]);
    const h = h0 + (shift || 0);
    const sat = clamp(s, 0.45, 0.9), light = clamp(l, 0.45, 0.62);
    return {
      base: hslToHex(h, clamp(sat * 0.6, 0.25, 0.6), 0.07),
      a: hslToHex(h, sat, light),
      b: hslToHex(h + 0.11, 0.7, 0.55),
      c: hslToHex(h - 0.085, 0.65, 0.62),
    };
  }

  /** Scena z podanego stylu i ziarna (elementy, tempo) w podanej palecie. */
  function buildScene(style, hash, pal) {
    const next = rng((hash ^ 0x9e3779b9) >>> 0);
    const speed = 0.7 + next() * 0.6;
    const count = { aurora: 5, waves: 4, fireflies: 22, pulses: 5, mesh: 6, vinyl: 8 }[style];
    const elements = [];
    for (let i = 0; i < count; i++) {
      elements.push({ x: 0.1 + next() * 0.8, y: 0.1 + next() * 0.8, r: next(), o: next(), p: next() * TAU, s: next() });
    }
    return { style, hash, speed, elements, palette: pal };
  }

  /** Scena utworu: styl, tempo i parametry elementów (wszystko z ID — ten sam utwór wygląda zawsze tak samo). */
  function scene(songId, accentHex) {
    const hash = hash32(songId);
    return buildScene(STYLES[hash % STYLES.length], hash, palette(accentHex));
  }

  /**
   * Prymitywy do narysowania w chwili t (sekundy od startu). Współrzędne 0–1 (x względem szerokości, y wysokości),
   * promienie i grubości względem krótszego boku. Rodzaje: blob (promienista plama), dot (kropka), ring (pierścień),
   * wave (łamana: pts = [x, y, …]). Kolejność = kolejność rysowania.
   */
  function layout(sc, t) {
    const out = [];
    const pal = sc.palette, tone = [pal.a, pal.b, pal.c];
    const k = t * sc.speed;
    const blob = (x, y, r, c, a) => out.push({ k: "blob", x, y, r, c, a });
    sc.elements.forEach((e, i) => {
      const c = tone[i % 3];
      switch (sc.style) {
        case "aurora":
          blob(e.x + (0.08 + e.o * 0.14) * Math.cos(e.p + k * (0.05 + e.s * 0.07)),
            e.y + (0.08 + e.o * 0.14) * Math.sin(e.p * 1.3 + k * (0.04 + e.s * 0.06)), 0.5 + e.r * 0.35, c, 0.35 + e.o * 0.2);
          break;
        case "mesh":
          blob(e.x + 0.18 * Math.sin(e.p + k * (0.06 + e.s * 0.05)),
            e.y + 0.14 * Math.sin(2 * (e.p + k * (0.06 + e.s * 0.05))), 0.7 + e.r * 0.4, c, 0.4 + e.o * 0.15);
          break;
        case "waves": {
          const by = 0.3 + i * 0.14, amp = 0.04 + e.o * 0.05, freq = 1.2 + e.r * 1.2, speedI = 0.25 + e.s * 0.35, pts = [];
          for (let n = 0; n < 40; n++) {
            const x = n / 39;
            pts.push(x, by + amp * Math.sin(TAU * freq * x + e.p + k * speedI * TAU * 0.35));
          }
          if (i === 0) { blob(0.3, 0.35, 0.7, pal.a, 0.3); blob(0.75, 0.7, 0.6, pal.b, 0.25); }
          out.push({ k: "wave", w: 0.006, c, a: 0.55, pts });
          break;
        }
        case "fireflies": {
          const v = 0.02 + e.s * 0.03, y = 1.1 - frac(e.r + k * v) * 1.2;
          if (i === 0) { blob(0.25, 0.3, 0.6, pal.a, 0.28); blob(0.8, 0.75, 0.55, pal.c, 0.22); }
          out.push({ k: "dot", x: e.x + 0.05 * Math.sin(e.p + k * (0.2 + e.o * 0.3)), y, r: 0.006 + e.o * 0.014, c, a: 0.25 + 0.55 * Math.abs(Math.sin(e.p + k * (0.4 + e.s))) });
          break;
        }
        case "pulses": {
          const p = frac(k * 0.07 + i / 5);
          if (i === 0) blob(0.5, 0.45, 0.45, pal.a, 0.4);
          out.push({ k: "ring", x: 0.5, y: 0.45, r: 0.05 + p * 0.95, w: 0.006, c, a: (1 - p) * 0.55 });
          break;
        }
        case "vinyl": {
          if (i === 0) {
            blob(0.5, 0.45, 0.3, pal.a, 0.35);
            blob(0.5 + 0.28 * Math.cos(k * 0.12), 0.45 + 0.28 * Math.sin(k * 0.12), 0.4, pal.b, 0.3);
          }
          out.push({ k: "ring", x: 0.5, y: 0.45, r: 0.1 + i * 0.065, w: 0.004, c, a: 0.12 + (i % 3) * 0.07 });
          break;
        }
        default: break;
      }
    });
    return out;
  }


  // ── montaż: utwór to ciąg ujęć (inny styl, kamera i przejście), jak w teledysku ──
  const SHOT_MIN = 6, SHOT_SPAN = 5, TRANS = 0.8;
  const KINDS = ["fade", "iris", "slide", "zoom", "flash"];
  const smooth = (u) => { u = clamp(u, 0, 1); return u * u * (3 - 2 * u); };

  /** Ujęcia powstają leniwie, zawsze w tej samej kolejności losowań, więc ten sam utwór ma ten sam montaż. */
  function montage(songId, accentHex) {
    return { songId: String(songId == null ? "" : songId), accent: accentHex, hash: hash32(songId), next: null, shots: [], styleIdx: 0 };
  }

  function addShot(m) {
    if (!m.next) m.next = rng((m.hash ^ 0x85ebca6b) >>> 0);
    const r = m.next, i = m.shots.length;
    const len = SHOT_MIN + r() * SHOT_SPAN;
    const step = Math.floor(r() * 5);
    const shift = (Math.floor(r() * 5) - 2) * 0.035;
    const kindIdx = Math.floor(r() * KINDS.length);
    const dir = r() < 0.5 ? -1 : 1;
    const cx = 0.25 + r() * 0.5, cy = 0.25 + r() * 0.5;
    const zoomIn = r() < 0.5;
    const sa = 1.1 + r() * 0.08, sb = sa + 0.06 + r() * 0.08;
    const px0 = (r() - 0.5) * 0.08, py0 = (r() - 0.5) * 0.08, px1 = (r() - 0.5) * 0.08, py1 = (r() - 0.5) * 0.08;
    const r0 = (r() - 0.5) * 0.08, r1 = (r() - 0.5) * 0.08;
    const styleIdx = i === 0 ? m.hash % STYLES.length : (m.styleIdx + 1 + step) % STYLES.length;
    m.styleIdx = styleIdx;
    const start = i === 0 ? 0 : m.shots[i - 1].start + m.shots[i - 1].len;
    const pal = palette(m.accent, i === 0 ? 0 : shift);
    m.shots.push({
      i, start, len, style: STYLES[styleIdx], shift: i === 0 ? 0 : shift, kind: i === 0 ? "none" : KINDS[kindIdx], dir, cx, cy,
      cam: { s0: zoomIn ? sa : sb, s1: zoomIn ? sb : sa, px0, py0, px1, py1, r0, r1 },
      scene: buildScene(STYLES[styleIdx], i === 0 ? m.hash : hash32(m.songId + "#" + i), pal),
    });
  }

  /** Kamera ujęcia po tl sekundach od jego początku: skala, przesunięcie (ułamek szerokości/wysokości) i obrót (rad). */
  function camera(shot, tl) {
    const u = smooth(tl / shot.len), c = shot.cam;
    return { s: c.s0 + (c.s1 - c.s0) * u, tx: c.px0 + (c.px1 - c.px0) * u, ty: c.py0 + (c.py1 - c.py0) * u, rot: c.r0 + (c.r1 - c.r0) * u };
  }

  /**
   * Co narysować w chwili t: warstwy od spodu (każda: scena, kamera, alpha, przesunięcie dx/dy w ułamkach ekranu, opcjonalne
   * przycięcie kołem clip {x, y, r} gdzie r jest ułamkiem dłuższego boku) i błysk flash (alpha białej nakładki).
   */
  function montageFrame(m, t) {
    t = Math.max(0, Number(t) || 0);
    if (!m.shots.length) addShot(m);
    for (let guard = 0; guard < 5000; guard++) {
      const last = m.shots[m.shots.length - 1];
      if (last.start + last.len > t) break;
      addShot(m);
    }
    let i = m.shots.length - 1;
    while (i > 0 && m.shots[i].start > t) i--;
    const shot = m.shots[i], tl = t - shot.start;
    const layer = (sh, cam) => ({ shot: sh.i, scene: sh.scene, cam, alpha: 1, dx: 0, dy: 0, clip: null });
    const current = layer(shot, camera(shot, tl));
    let flash = 0;
    if (i === 0 || tl >= TRANS) return { shot: i, layers: [current], flash };
    const prevShot = m.shots[i - 1];
    const before = layer(prevShot, camera(prevShot, prevShot.len + tl));
    const p = smooth(tl / TRANS);
    switch (shot.kind) {
      case "iris": current.clip = { x: shot.cx, y: shot.cy, r: p * 1.2 }; break;
      case "slide": current.dx = shot.dir * (1 - p); before.dx = -shot.dir * p * 0.3; break;
      case "zoom": current.cam = Object.assign({}, current.cam, { s: current.cam.s * (1 + 0.5 * (1 - p)) }); current.alpha = p; break;
      case "flash": current.alpha = p < 0.35 ? 0 : 1; flash = Math.max(0, 1 - Math.abs(p - 0.35) / 0.35) * 0.85; break;
      default: current.alpha = p; break; // fade
    }
    return { shot: i, layers: [before, current], flash };
  }

  return { STYLES, KINDS, FPS, SHOT_MIN, SHOT_SPAN, TRANS, FALLBACK_ACCENT, hash32, rng, palette, scene, layout, montage, camera, montageFrame };
});
