/* Piterfy — gesty dotykowe w „Teraz gra” i na mini-pasku telefonu (decyzje: gesture-core.js, testy: gesture-core.test.cjs):
 *  • pełny odtwarzacz: przeciągnięcie okładki w bok zmienia utwór (okładka jedzie pod palcem, na końcu kolejki stawia gumowy
 *    opór, po zatwierdzeniu wylatuje, a nowa wjeżdża z przeciwnej strony); przeciągnięcie w dół minimalizuje odtwarzacz;
 *    podwójne tapnięcie lewej/prawej części okładki przewija o 10 s do tyłu/do przodu,
 *  • mini-pasek (.pf-mobile-bar): przesunięcie w lewo/prawo = następny/poprzedni, w górę = otwórz odtwarzacz.
 * Nie dotykamy zwykłych kliknięć: gest startuje dopiero po przekroczeniu progu, suwaków, przycisków, tekstów i okien nie
 * obsługujemy. Zmiana utworu klika natywny przycisk (albo woła PiterfyPlayback przy odtwarzaniu na innym urządzeniu), więc
 * logika pomijania i kolejki działa jak przy zwykłym kliknięciu. */
(() => {
  'use strict';
  if (window.PiterfyGestures) return;
  const g = window.PiterfyGestureCore;
  if (!g || !('ontouchstart' in window)) { window.PiterfyGestures = { active: false }; return; }
  const feel = () => window.PiterfyFeel;
  const reduced = () => !!(feel() && feel().motion() === 'reduced');
  const FULL = '.react-jinke-music-player-mobile';
  const COVER = '.react-jinke-music-player-mobile-cover, .react-jinke-music-player-mobile img.cover';
  const IGNORE = '.rc-slider, input, select, textarea, a, button, [role="button"], [class*="lyric" i], .pf-lyrics, .audio-lists-panel, dialog, .pf-undo';
  const ease = { out: 'cubic-bezier(0.2, 0.7, 0.2, 1)', spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)' };
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  let s = null, lastTap = null, suppressClickUntil = 0;

  function queueLength() {
    try { return window.PiterfyLibrary.store().getState().player.queue.length; } catch (_) { return 2; }   // nie wiemy → nie blokuj
  }
  const remote = () => { const p = window.PiterfyPlayback; try { return !!(p && p.current() && p.current().remote); } catch (_) { return false; } };

  function skip(direction) {
    const next = direction === 'next', playback = window.PiterfyPlayback;
    if (remote() && playback) { playback.control(next ? 'next' : 'previous'); return; }
    const scopes = next ? [FULL + ' .next-audio', '.pf-mobile-bar .pf-mobile-next', '.music-player-panel .next-audio']
      : [FULL + ' .prev-audio', '.music-player-panel .prev-audio'];
    const native = scopes.map((q) => document.querySelector(q)).find(Boolean);
    if (native) native.click(); else if (playback) playback.control(next ? 'next' : 'previous');
  }

  function seekBy(delta) {
    const playback = window.PiterfyPlayback, element = audio();
    let now = element ? element.currentTime : 0, duration = element ? element.duration : NaN;
    try { const c = playback && playback.current(); if (c && c.remote) { now = c.position / 1000; duration = Number(c.duration) || NaN; } } catch (_) { /* lokalnie */ }
    const target = g.seekTarget(now, delta, duration);
    if (playback) playback.seek(target * 1000); else if (element) element.currentTime = target;
  }

  // Animacja z pewnym „końcem”: gdy karta jest w tle albo animację przerwano, promise i tak się rozstrzyga po czasie jej trwania.
  function animate(node, frames, ms, easing) {
    if (!node || !node.animate || reduced()) return { finished: Promise.resolve() };
    const run = node.animate(frames, { duration: ms, easing, fill: 'none' });
    const fallback = new Promise((resolve) => setTimeout(resolve, ms + 120));
    return { finished: Promise.race([run.finished.catch(() => {}), fallback]) };
  }
  const clearInline = (node, ...props) => { if (node) for (const p of props) node.style.removeProperty(p); };

  // ── dotyk ───────────────────────────────────────────────────────────────────────────────────────────────
  // touchmove (nieliniowy, nie-pasywny) podpinamy tylko na czas gestu — stały nasłuch na dokumencie spowalniałby przewijanie.
  function drop() { if (s && s.el) s.el.removeEventListener('touchmove', onMove); s = null; }

  document.addEventListener('touchstart', (event) => {
    drop();
    if (event.touches.length !== 1) return;
    const t = event.touches[0], el = event.target && event.target.closest ? event.target : null;
    if (!el) return;
    const full = el.closest(FULL), bar = el.closest('.pf-mobile-bar .pf-mobile-track');
    const base = { el, x0: t.clientX, y0: t.clientY, t0: performance.now(), dx: 0, dy: 0, axis: null, moved: false };
    if (full && !el.closest(IGNORE)) {
      const cover = el.closest(COVER);
      s = { ...base, mode: 'full', node: full, cover, rect: (cover || full).getBoundingClientRect() };
    } else if (bar) {
      s = { ...base, mode: 'bar', node: bar, rect: bar.getBoundingClientRect() };
    }
    if (s) el.addEventListener('touchmove', onMove, { passive: false });
  }, { passive: true });

  function onMove(event) {
    if (!s || event.touches.length !== 1) return;
    const t = event.touches[0];
    s.dx = t.clientX - s.x0; s.dy = t.clientY - s.y0;
    if (!s.axis) {
      s.axis = g.axisLock(s.dx, s.dy);
      if (!s.axis) return;
      const horizontalOk = s.mode === 'bar' || !!s.cover;
      const verticalOk = s.mode === 'full' ? s.dy > 0 : true;
      if ((s.axis === 'x' && !horizontalOk) || (s.axis === 'y' && !verticalOk)) { drop(); return; }
      s.moved = true;
      const target = dragTarget();
      if (target) target.classList.add('pf-dragging');
    }
    if (event.cancelable) event.preventDefault();
    if (s.axis === 'x') {
      const count = queueLength();
      const x = g.rubber(s.dx, { canPrev: count > 1, canNext: count > 1 });
      const target = dragTarget();
      if (target) { target.style.translate = x + 'px 0'; if (s.mode === 'full') target.style.rotate = x / 28 + 'deg'; }
    } else if (s.mode === 'full') {
      const y = Math.max(0, s.dy), h = window.innerHeight || 700;
      s.node.classList.add('pf-minimising');
      s.node.style.translate = '0 ' + y + 'px';
      s.node.style.opacity = String(Math.max(0.35, 1 - y / h * 0.7));
    } else {
      s.node.style.translate = '0 ' + Math.min(0, s.dy) * 0.5 + 'px';
    }
  }

  const dragTarget = () => (s ? (s.mode === 'full' ? s.cover : s.node) : null);

  document.addEventListener('touchcancel', () => { if (s && s.moved) settleBack(); drop(); }, { passive: true });

  document.addEventListener('touchend', (event) => {
    const gesture = s;
    if (!gesture) return;
    gesture.el.removeEventListener('touchmove', onMove);
    const dt = performance.now() - gesture.t0;
    if (!gesture.moved) { s = null; onTap(event, gesture, dt); return; }
    suppressClickUntil = Date.now() + 450;                 // po przeciągnięciu przeglądarka potrafi dołożyć „klik”
    if (event.cancelable) event.preventDefault();
    finish(gesture, dt);                                   // s zostaje ustawione na czas finish (dragTarget / settleBack)
    s = null;
  }, { passive: false });

  document.addEventListener('click', (event) => {
    // tłumimy tylko „prawdziwe” kliknięcia, które przeglądarka dokłada po przeciągnięciu; nasze własne (skip(), open) mają isTrusted=false
    if (event.isTrusted && Date.now() < suppressClickUntil && event.target.closest && event.target.closest('.pf-mobile-bar .pf-mobile-track, ' + FULL)) {
      event.stopImmediatePropagation(); event.preventDefault();
    }
  }, true);

  // ── dokończenie gestu ───────────────────────────────────────────────────────────────────────────────────
  function settleBack() {
    const g0 = s;
    if (!g0) return;
    const target = g0.mode === 'full' && g0.axis === 'y' ? g0.node : dragTarget();
    if (!target) return;
    const from = target.style.translate || '0 0', rotate = target.style.rotate || '0deg', opacity = target.style.opacity || '1';
    clearInline(target, 'translate', 'rotate', 'opacity');
    target.classList.remove('pf-dragging', 'pf-minimising');
    animate(target, [{ translate: from, rotate, opacity }, { translate: '0 0', rotate: '0deg', opacity: 1 }], 320, ease.spring);
  }

  function finish(gesture, dt) {
    const w = gesture.rect.width || window.innerWidth, h = gesture.rect.height || window.innerHeight;
    if (gesture.axis === 'x') {
      const count = queueLength();
      const decision = g.releaseHorizontal({ dx: gesture.dx, dt, width: w, canNext: count > 1, canPrev: count > 1 });
      if (decision === 'cancel') { s = gesture; settleBack(); s = null; if (feel() && gesture.dx) feel().tick(); return; }
      const sign = decision === 'next' ? -1 : 1, node = dragTarget(), speed = Math.abs(gesture.dx) / Math.max(1, dt);
      const from = g.rubber(gesture.dx, { canPrev: true, canNext: true });
      const out = animate(node, [{ translate: from + 'px 0', opacity: 1 }, { translate: sign * w * 0.7 + 'px 0', opacity: 0 }], g.settleMs(Math.abs(sign * w * 0.7 - from), speed), ease.out);
      const go = () => {
        clearInline(node, 'translate', 'rotate', 'opacity');
        if (node) node.classList.remove('pf-dragging');
        skip(decision);
        if (feel()) feel().tap();
        setTimeout(() => {      // okładka po zmianie utworu (React mógł podmienić element) wjeżdża z przeciwnej strony
          const fresh = gesture.mode === 'full' ? gesture.node.querySelector(COVER) || node : node;
          animate(fresh, [{ translate: -sign * w * 0.45 + 'px 0', opacity: 0 }, { translate: '0 0', opacity: 1 }], 300, ease.spring);
        }, 90);
      };
      if (out.finished) out.finished.then(go, go); else go();
      return;
    }
    if (gesture.mode === 'full') {
      const decision = g.releaseVertical({ dx: gesture.dx, dy: gesture.dy, dt, height: h, direction: 'down' });
      const node = gesture.node;
      if (decision === 'minimise') {
        const close = document.querySelector(FULL + '-header-right') || node.querySelector('[class*="header-right"]');
        const run = () => {
          if (close) close.click();
          if (feel()) feel().tap();
          setTimeout(() => { clearInline(node, 'translate', 'opacity'); node.classList.remove('pf-minimising', 'pf-dragging'); }, 350);
        };
        const out = animate(node, [{ translate: '0 ' + Math.max(0, gesture.dy) + 'px', opacity: node.style.opacity || 1 }, { translate: '0 ' + h + 'px', opacity: 0 }], 220, ease.out);
        if (out.finished) out.finished.then(run, run); else run();
      } else { s = gesture; settleBack(); s = null; }
      return;
    }
    // mini-pasek: w górę = otwórz
    const decision = g.releaseVertical({ dx: gesture.dx, dy: gesture.dy, dt, height: 120, direction: 'up' });
    clearInline(gesture.node, 'translate');
    gesture.node.classList.remove('pf-dragging');
    if (decision === 'open') { gesture.node.click(); if (feel()) feel().tap(); }
  }

  // ── pojedyncze dotknięcia: podwójne tapnięcie okładki = przewinięcie ────────────────────────────────────
  function onTap(event, gesture, dt) {
    if (gesture.mode !== 'full' || !gesture.cover || dt > 260) { lastTap = null; return; }
    const touch = event.changedTouches && event.changedTouches[0];
    if (!touch) return;
    const rect = gesture.cover.getBoundingClientRect();
    const tap = { x: touch.clientX, y: touch.clientY, at: Date.now(), zone: g.tapZone(touch.clientX - rect.left, rect.width) };
    const delta = g.doubleTapSeek(lastTap, tap);
    if (!delta) { lastTap = tap; return; }
    lastTap = null;
    if (event.cancelable) event.preventDefault();
    suppressClickUntil = Date.now() + 450;
    seekBy(delta);
    if (feel()) feel().tick();
    const hint = document.createElement('div');
    hint.className = 'pf-seek-hint';
    hint.style.left = tap.x + 'px'; hint.style.top = tap.y + 'px';
    hint.textContent = (delta < 0 ? '−' : '+') + Math.abs(delta) + ' s';
    document.body.append(hint);
    setTimeout(() => hint.remove(), 650);
  }

  window.PiterfyGestures = { active: true, core: g };
})();
