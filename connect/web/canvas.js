/* Piterfy — „Teraz gra” z animowanym Canvasem. Przycisk przy przycisku tekstów otwiera pełnoekranową nakładkę:
 * generowana animacja w tle jak w teledysku (montaż ujęć z ID utworu i koloru okładki: canvas-core.js — cięcia, przejścia, kamera), okładka, tytuł, pasek postępu
 * i przyciski. Utwór czytamy z window.PiterfyPlayback (obsługuje też odtwarzanie na innym urządzeniu), a gdy go nie ma —
 * z adresu elementu audio. Animacja idzie tylko, gdy nakładka jest otwarta, karta widoczna i muzyka gra;
 * po wyłączeniu animacji przyciskiem w nakładce rysujemy jedną nieruchomą klatkę (systemowe „ogranicz ruch” ignorujemy). */
(() => {
  'use strict';
  if (window.piterfyCanvasLoaded) return;
  const core = window.PiterfyCanvasCore;
  if (!core) return;
  window.piterfyCanvasLoaded = true;

  const UI = '/piterfy-ui/';
  const VERSION = ((document.currentScript && document.currentScript.src.match(/[?&]v=([^&]+)/)) || [])[1];
  const SCALE = 0.55;          // rysujemy w niższej rozdzielczości — gradienty i tak są miękkie
  const FADE_MS = 700;         // płynne przejście między scenami przy zmianie utworu
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  const stored = (key, fallback) => { try { const v = localStorage.getItem(key); return v == null ? fallback : v; } catch (e) { return fallback; } };

  const ICON = {
    close: '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5h4v14H7zM13 5h4v14h-4z" fill="currentColor"/></svg>',
    prev: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h2v14H6zM20 5v14L9 12z" fill="currentColor"/></svg>',
    next: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M16 5h2v14h-2zM4 5l11 7L4 19z" fill="currentColor"/></svg>',
    motion: '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 12c3-6 6-6 9 0s6 6 9 0"/></svg>',
    still: '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 12h18"/></svg>',
  };

  let dialog, canvas, ctx, button;
  const el = {};
  let key = '', song = null, scenes = { cur: null, prev: null, prevT: 0, since: 0 }, accentFor = {};
  let animate = stored('pf-canvas', '1') !== '0', t = 0, last = 0, raf = 0, isOpen = false, timer = 0;

  function current() {
    const bridged = window.PiterfyPlayback && window.PiterfyPlayback.current();
    if (bridged) return bridged;
    const element = audio();
    const src = element && element.getAttribute('src');
    if (!src) return null;
    const url = new URL(src, location.href);
    const id = url.searchParams.get('id') || url.searchParams.get('mediaId');
    return id ? { id, cover: id, title: '', artist: '', position: element.currentTime * 1000, playing: !element.paused, duration: element.duration || 0 } : null;
  }

  function coverUrl(id, size) {
    const q = new URLSearchParams({
      u: localStorage.getItem('username') || '', t: localStorage.getItem('subsonic-token') || '', s: localStorage.getItem('subsonic-salt') || '',
      v: '1.16.1', c: 'PiterfyCanvas', id, size,
    });
    return '/rest/getCoverArt?' + q;
  }

  const clock = (ms) => { const s = Math.max(0, Math.floor(ms / 1000)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };

  // ── scena ──
  function setScene(id, accent) {
    scenes = { cur: core.montage(id, accent), prev: scenes.cur, prevT: t, since: performance.now() };
  }

  /** Kolor dominujący okładki (stats-core.js, gdy jest załadowany) — z miniatury 16×16 tej samej origin. */
  function accentOf(id, img) {
    if (accentFor[id]) return Promise.resolve(accentFor[id]);
    return new Promise((resolve) => {
      const probe = new Image();
      probe.onload = () => {
        try {
          const c = document.createElement('canvas'); c.width = c.height = 16;
          const x = c.getContext('2d'); x.drawImage(probe, 0, 0, 16, 16);
          const hex = window.PiterfyStatsCore && window.PiterfyStatsCore.dominantColor(x.getImageData(0, 0, 16, 16).data);
          resolve(accentFor[id] = hex || core.FALLBACK_ACCENT);
        } catch (e) { resolve(core.FALLBACK_ACCENT); }
      };
      probe.onerror = () => resolve(core.FALLBACK_ACCENT);
      probe.src = img;
    });
  }

  // ── rysowanie ──
  function rgba(hex, a) {
    return 'rgba(' + parseInt(hex.slice(1, 3), 16) + ',' + parseInt(hex.slice(3, 5), 16) + ',' + parseInt(hex.slice(5, 7), 16) + ',' + a + ')';
  }

  function paintOps(sc, time, w, h, m) {
    for (const op of core.layout(sc, time)) {
      if (op.k === 'blob' || op.k === 'dot') {
        const cx = op.x * w, cy = op.y * h, r = op.r * m;
        const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r);
        g.addColorStop(0, rgba(op.c, op.a));
        g.addColorStop(1, rgba(op.c, 0));
        ctx.fillStyle = g;
        ctx.fillRect(cx - r, cy - r, r * 2, r * 2);
      } else if (op.k === 'ring') {
        ctx.strokeStyle = rgba(op.c, op.a);
        ctx.lineWidth = Math.max(1, op.w * m);
        ctx.beginPath(); ctx.arc(op.x * w, op.y * h, op.r * m, 0, Math.PI * 2); ctx.stroke();
      } else if (op.k === 'wave') {
        ctx.strokeStyle = rgba(op.c, op.a);
        ctx.lineWidth = Math.max(1, op.w * m);
        ctx.lineJoin = ctx.lineCap = 'round';
        ctx.beginPath();
        for (let i = 0; i < op.pts.length; i += 2) {
          const px = op.pts[i] * w, py = op.pts[i + 1] * h;
          if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        }
        ctx.stroke();
      }
    }
  }

  /** Jedna warstwa montażu: przycięcie kołem (iris), przesunięcie (slide), kamera (zoom, ruch, obrót), tło i prymitywy sceny. */
  function paintLayer(layer, time, alpha) {
    const w = canvas.width, h = canvas.height, m = Math.min(w, h);
    ctx.save();
    if (layer.clip) {
      ctx.beginPath(); ctx.arc(layer.clip.x * w, layer.clip.y * h, layer.clip.r * Math.max(w, h), 0, Math.PI * 2); ctx.clip();
    }
    ctx.translate(layer.dx * w, layer.dy * h);
    const c = layer.cam;
    ctx.translate(w / 2 + c.tx * w, h / 2 + c.ty * h); ctx.rotate(c.rot); ctx.scale(c.s, c.s); ctx.translate(-w / 2, -h / 2);
    ctx.globalAlpha = alpha * layer.alpha;
    ctx.fillStyle = layer.scene.palette.base;
    ctx.fillRect(-w, -h, w * 3, h * 3);
    paintOps(layer.scene, time, w, h, m);
    ctx.restore();
  }

  function paintMontage(mt, time, alpha) {
    const f = core.montageFrame(mt, time);
    for (const layer of f.layers) paintLayer(layer, time, alpha);
    if (f.flash > 0) {
      ctx.globalAlpha = alpha * f.flash; ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.globalAlpha = 1;
    }
  }

  function paint() {
    if (!scenes.cur) { ctx.fillStyle = '#000'; ctx.fillRect(0, 0, canvas.width, canvas.height); return; }
    const p = scenes.prev ? Math.min(1, (performance.now() - scenes.since) / FADE_MS) : 1;
    if (p < 1) paintMontage(scenes.prev, scenes.prevT, 1);
    paintMontage(scenes.cur, t, p);
    if (p >= 1) scenes.prev = null;
  }

  function resize() {
    if (!canvas) return;
    const w = Math.max(2, Math.round(innerWidth * SCALE)), h = Math.max(2, Math.round(innerHeight * SCALE)); // dialog zajmuje cały ekran
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; paint(); }
  }

  const playing = () => !!(song && song.playing);
  const wantsMotion = () => animate; // celowo bez systemowego „ogranicz ruch” (np. wyłączone animacje w Windowsie): wyłączasz przyciskiem w rogu

  function frame(now) {
    raf = 0;
    if (!isOpen || document.hidden) return;
    const dt = last ? Math.min(0.25, (now - last) / 1000) : 0;
    if (now - last >= 1000 / core.FPS - 3) {
      if (playing() && wantsMotion()) t += dt;
      last = now;
      paint();
    }
    // bez ruchu (pauza, „ogranicz ruch”, wyłączona animacja) nie ma po co rysować co klatkę
    if (playing() && wantsMotion() || scenes.prev) raf = requestAnimationFrame(frame);
  }
  function kick() { if (isOpen && !raf) { last = 0; raf = requestAnimationFrame(frame); } }

  // ── dane i widok ──
  function refresh() {
    song = current();
    if (!isOpen) return;
    if (!song) { el.title.textContent = 'Nic nie gra'; el.artist.textContent = ''; el.art.removeAttribute('src'); return; }
    if (song.id !== key) {
      key = song.id;
      el.title.textContent = song.title || '';
      el.artist.textContent = song.artist || '';
      const img = coverUrl(song.cover || song.id, 600);
      el.art.src = img; el.art.alt = song.title ? 'Okładka: ' + song.title : '';
      const id = song.id;
      t = (song.position || 0) / 1000;
      accentOf(id, coverUrl(song.cover || song.id, 64)).then((hex) => { if (key === id) { setScene(id, hex); kick(); } });
    }
    const dur = (song.duration || 0) * 1000, pos = song.position || 0;
    if (Math.abs(t - pos / 1000) > 1.5) { t = pos / 1000; kick(); } // przewinięcie: montaż wraca do właściwego miejsca w utworze
    el.bar.style.setProperty('--pf-cv-progress', dur ? Math.min(1, pos / dur) : 0);
    el.bar.setAttribute('aria-valuenow', String(Math.round(dur ? Math.min(100, pos / dur * 100) : 0)));
    el.pos.textContent = clock(pos); el.dur.textContent = dur ? clock(dur) : '';
    el.play.innerHTML = song.playing ? ICON.pause : ICON.play;
    el.play.setAttribute('aria-label', song.playing ? 'Wstrzymaj' : 'Odtwórz');
    kick();
  }

  function build() {
    dialog = document.createElement('dialog');
    dialog.id = 'pf-canvas-dialog';
    dialog.setAttribute('aria-label', 'Teraz gra');
    dialog.innerHTML =
      '<canvas class="pf-cv-bg" aria-hidden="true"></canvas><div class="pf-cv-scrim" aria-hidden="true"></div>' +
      '<div class="pf-cv-top"><button type="button" class="pf-cv-btn" data-cv="motion"></button>' +
      '<button type="button" class="pf-cv-btn" data-cv="close" aria-label="Zamknij" title="Zamknij (Esc)">' + ICON.close + '</button></div>' +
      '<div class="pf-cv-main"><img class="pf-cv-art" alt="" decoding="async">' +
      '<div class="pf-cv-meta"><h2 class="pf-cv-title"></h2><p class="pf-cv-artist"></p></div>' +
      '<div class="pf-cv-bar" role="slider" tabindex="0" aria-label="Postęp utworu" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i></i></div>' +
      '<div class="pf-cv-times"><span class="pf-cv-pos">0:00</span><span class="pf-cv-dur"></span></div>' +
      '<div class="pf-cv-controls"><button type="button" class="pf-cv-btn" data-cv="previous" aria-label="Poprzedni">' + ICON.prev + '</button>' +
      '<button type="button" class="pf-cv-btn pf-cv-play" data-cv="toggle"></button>' +
      '<button type="button" class="pf-cv-btn" data-cv="next" aria-label="Następny">' + ICON.next + '</button></div></div>';
    document.body.appendChild(dialog);
    canvas = dialog.querySelector('.pf-cv-bg');
    ctx = canvas.getContext('2d');
    Object.assign(el, {
      art: dialog.querySelector('.pf-cv-art'), title: dialog.querySelector('.pf-cv-title'), artist: dialog.querySelector('.pf-cv-artist'),
      bar: dialog.querySelector('.pf-cv-bar'), pos: dialog.querySelector('.pf-cv-pos'), dur: dialog.querySelector('.pf-cv-dur'),
      play: dialog.querySelector('[data-cv="toggle"]'), motion: dialog.querySelector('[data-cv="motion"]'),
    });
    paintMotion();
    dialog.addEventListener('click', (e) => {
      const b = e.target.closest('[data-cv]');
      if (!b) return;
      const act = b.dataset.cv, pb = window.PiterfyPlayback;
      if (act === 'close') dialog.close();
      else if (act === 'motion') { animate = !animate; try { localStorage.setItem('pf-canvas', animate ? '1' : '0'); } catch (e2) { /* bez zapamiętywania */ } paintMotion(); kick(); }
      else if (pb && (act === 'previous' || act === 'next')) { pb.control(act); setTimeout(refresh, 250); }
      else if (pb && act === 'toggle') { pb.control(song && song.playing ? 'pause' : 'play'); setTimeout(refresh, 150); }
    });
    el.bar.addEventListener('click', (e) => {
      const dur = (song && song.duration || 0) * 1000;
      if (!dur || !window.PiterfyPlayback) return;
      const r = el.bar.getBoundingClientRect();
      window.PiterfyPlayback.seek(Math.round(dur * Math.min(1, Math.max(0, (e.clientX - r.left) / r.width))));
      setTimeout(refresh, 150);
    });
    el.bar.addEventListener('keydown', (e) => {
      if (!window.PiterfyPlayback || !song || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return;
      e.preventDefault();
      window.PiterfyPlayback.seek(Math.max(0, (song.position || 0) + (e.key === 'ArrowRight' ? 5000 : -5000)));
      setTimeout(refresh, 150);
    });
    dialog.addEventListener('close', () => { isOpen = false; clearInterval(timer); cancelAnimationFrame(raf); raf = 0; });
    addEventListener('resize', resize);
  }

  function paintMotion() {
    el.motion.innerHTML = animate ? ICON.motion : ICON.still;
    el.motion.setAttribute('aria-pressed', animate ? 'true' : 'false');
    el.motion.setAttribute('aria-label', animate ? 'Wyłącz animację tła' : 'Włącz animację tła');
    el.motion.title = animate ? 'Wyłącz animację' : 'Włącz animację';
  }

  function open() {
    if (!dialog) build();
    if (dialog.open) return;
    isOpen = true; key = '';
    dialog.showModal();
    resize();
    refresh();
    timer = setInterval(refresh, 400);
  }

  function mountButton() {
    const css = document.createElement('link');
    css.rel = 'stylesheet'; css.href = UI + 'canvas.css' + (VERSION ? '?v=' + VERSION : '');
    document.head.appendChild(css);
    button = document.createElement('button');
    button.type = 'button'; button.id = 'pf-canvas-button'; button.textContent = '◉ Teraz gra'; button.hidden = true;
    button.addEventListener('click', open);
    document.body.appendChild(button);
    setInterval(() => { button.hidden = !current(); }, 1500);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) kick(); });
  }

  if (document.body) mountButton(); else document.addEventListener('DOMContentLoaded', mountButton);
})();
