/* Piterfy — fala dźwięku zamiast kreski postępu w „Teraz gra” (pasek na komputerze, pełny ekran na telefonie) i w mini-odtwarzaczu.
 * Kształt fali pochodzi z serwera: GET /piterfy-api/peaks/{id} → 200 {v:1, n, peaks:[0..255], duration} | 202 {pending:true}
 * (jeszcze liczy: ponawiamy z rosnącym odstępem) | cokolwiek innego (zostaje zwykły pasek). Ocena odpowiedzi i geometria: wave-core.js.
 *
 * Fala jest nakładką: pod nią nadal działa natywny suwak (rc-slider / input range), więc kliknięcie i przeciąganie przewijają
 * tak jak dotąd. Zagrana część ma kolor akcentu z okładki (bezpieczny kontrast), a przy przeciąganiu pojawia się dymek z czasem
 * i fala robi się wyższa. Gdy utwór gra, a ruch jest dozwolony, fala lekko „oddycha”. window.PiterfyWave.mount(host, opcje)
 * pozwala podpiąć ją też gdzie indziej (miniplayer.js). */
(() => {
  'use strict';
  if (window.PiterfyWave) return;
  const core = window.PiterfyWaveCore;
  if (!core) return;
  const root = document.documentElement;
  const SELECTOR = '.music-player-panel .progress-bar-content .rc-slider, .music-player-panel .progress-bar .rc-slider, .react-jinke-music-player-mobile-progress .rc-slider';
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  const feel = () => window.PiterfyFeel;
  const motionReduced = () => !!(feel() && feel().motion() === 'reduced');

  const cache = new Map();      // id → { state: 'ready', peaks, duration } | { state: 'none', until }
  const jobs = new Map();       // id → { attempt, timer }
  const mounts = new Set();
  const mounted = new WeakSet();
  let currentId = '', scheduled = 0, kind = '', lastFrame = 0, colorAt = 0, colors = { accent: '#1db954', on: '#000000' };

  const token = () => { try { return localStorage.getItem('token') || ''; } catch (_) { return ''; } };
  const remember = (id, entry) => { cache.set(id, entry); while (cache.size > 40) cache.delete(cache.keys().next().value); };

  async function load(id) {
    const known = cache.get(id);
    if ((known && known.state === 'ready') || (known && known.until > Date.now()) || jobs.has(id)) return;
    const job = { attempt: 0, timer: 0 };
    jobs.set(id, job);
    const run = async () => {
      let result = { state: 'none' }, failed = false;
      try {
        const response = await fetch('/piterfy-api/peaks/' + encodeURIComponent(id), {
          headers: { 'X-ND-Authorization': 'Bearer ' + token() }, cache: 'no-store', signal: AbortSignal.timeout(8000),
        });
        const body = await response.json().catch(() => null);
        result = core.classify(response.status, body);
      } catch (_) { failed = true; }
      if (result.state === 'pending') {
        const delay = core.retryDelay(job.attempt++);
        if (delay != null) { job.timer = setTimeout(run, delay); return; }
        result = { state: 'none' };
      }
      jobs.delete(id);
      if (result.state === 'ready') remember(id, result);
      else remember(id, { state: 'none', until: Date.now() + (failed ? 30000 : 5 * 60000) });
      schedule(0);
    };
    run();
  }

  // ── kolory (akcent z okładki przechodzi płynnie, więc czytamy go na bieżąco, ale nie częściej niż co 200 ms) ──
  function readColors(now) {
    if (now - colorAt < 200) return colors;
    colorAt = now;
    const style = getComputedStyle(root);
    const accent = style.getPropertyValue('--pf-now-accent-safe').trim() || style.getPropertyValue('--pf-now-accent').trim();
    colors = { accent: accent || '#1db954', on: style.getPropertyValue('--pf-on-accent').trim() || '#000000', light: root.hasAttribute('data-pf-light') };
    return colors;
  }

  // ── montowanie ──────────────────────────────────────────────────────────────────────────────────────────
  function mount(host, options = {}) {
    if (!host || mounted.has(host)) return null;
    mounted.add(host);
    const doc = host.ownerDocument;
    if (doc !== document && !doc.getElementById('pf-motion-css')) {       // okno Picture-in-Picture ma własny dokument
      const link = doc.createElement('link');
      link.id = 'pf-motion-css'; link.rel = 'stylesheet'; link.href = new URL('/piterfy-ui/motion.css', location.origin).href;
      doc.head.append(link);
    }
    const canvas = doc.createElement('canvas'), bubble = doc.createElement('div');
    canvas.className = 'pf-wave'; canvas.setAttribute('aria-hidden', 'true');
    bubble.className = 'pf-wave-bubble'; bubble.setAttribute('aria-hidden', 'true');
    host.classList.add('pf-wave-host');
    host.append(canvas, bubble);
    const m = { host, canvas, bubble, doc, options, scrub: null, tickBucket: -1, key: '', bars: null };
    const place = (event) => {
      const rect = host.getBoundingClientRect();
      m.scrub = { x: Math.max(0, Math.min(rect.width, event.clientX - rect.left)) };
      host.classList.add('pf-scrub');
      const count = core.layout(rect.width).count || 1, bucket = Math.floor((m.scrub.x / Math.max(1, rect.width)) * count / 6);
      if (bucket !== m.tickBucket) { if (m.tickBucket >= 0 && feel()) feel().tick(); m.tickBucket = bucket; }
      schedule(0);
    };
    const end = () => { if (!m.scrub) return; m.scrub = null; m.tickBucket = -1; host.classList.remove('pf-scrub'); schedule(0); };
    host.addEventListener('pointerdown', (event) => { if (event.button === 0 || event.pointerType !== 'mouse') place(event); }, true);
    doc.addEventListener('pointermove', (event) => { if (m.scrub) place(event); }, true);
    doc.addEventListener('pointerup', end, true);
    doc.addEventListener('pointercancel', end, true);
    mounts.add(m);
    schedule(0);
    return m;
  }

  // ── rysowanie ───────────────────────────────────────────────────────────────────────────────────────────
  function progressOf(m) {
    if (m.options.range) { const max = Number(m.options.range.max) || 0; return max > 0 ? Number(m.options.range.value) / max : 0; }
    const element = audio();
    return element && Number.isFinite(element.duration) && element.duration > 0 ? element.currentTime / element.duration : 0;
  }
  function durationOf(m, entry) {
    if (m.options.range && Number(m.options.range.max) > 1) return Number(m.options.range.max);
    const element = audio();
    return element && Number.isFinite(element.duration) && element.duration > 0 ? element.duration : entry.duration || 0;
  }
  const rounded = (ctx, x, y, w, h, r) => { if (ctx.roundRect) { ctx.beginPath(); ctx.roundRect(x, y, w, h, r); ctx.fill(); } else ctx.fillRect(x, y, w, h); };

  function draw(m, now, breathing) {
    const entry = cache.get(currentId);
    const ready = entry && entry.state === 'ready';
    const want = ready ? 'ready' : '';
    if ((m.host.dataset.pfWave || '') !== want) { if (want) m.host.dataset.pfWave = want; else delete m.host.dataset.pfWave; }
    if (!ready) return;
    const width = m.host.clientWidth, height = m.canvas.clientHeight || 36;
    if (!width) return;
    const dpr = Math.min(3, (m.doc.defaultView && m.doc.defaultView.devicePixelRatio) || 1);
    const pw = Math.round(width * dpr), ph = Math.round(height * dpr);
    if (m.canvas.width !== pw || m.canvas.height !== ph) { m.canvas.width = pw; m.canvas.height = ph; m.key = ''; }
    const layout = core.layout(width);
    if (!layout.count) return;
    const key = currentId + '|' + layout.count;
    if (m.key !== key) { m.key = key; m.bars = core.resample(entry.peaks, layout.count); }
    const scrubbing = !!m.scrub, colorsNow = readColors(now);
    const progress = scrubbing ? m.scrub.x / width : progressOf(m);
    const ctx = m.canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);
    const unit = layout.bar + layout.gap, offset = (width - (layout.count * unit - layout.gap)) / 2, boost = scrubbing ? 1.18 : 1;
    for (let i = 0; i < layout.count; i += 1) {
      const h = Math.max(2, core.height(m.bars[i], i, layout.count, now, breathing) * height * 0.92 * boost);
      const isPlayed = core.played(i, layout.count, progress);
      ctx.fillStyle = isPlayed ? colorsNow.accent : colorsNow.light ? 'rgba(0,0,0,0.26)' : scrubbing ? 'rgba(255,255,255,0.42)' : 'rgba(255,255,255,0.3)';
      rounded(ctx, offset + i * unit, (height - h) / 2, layout.bar, h, layout.bar / 2);
    }
    if (scrubbing) {
      m.bubble.textContent = core.format(core.scrubTime(m.scrub.x, width, durationOf(m, entry)));
      m.bubble.style.left = Math.max(26, Math.min(width - 26, m.scrub.x)) + 'px';
      m.bubble.style.background = colorsNow.accent; m.bubble.style.color = colorsNow.on;
    }
  }

  // ── pętla ───────────────────────────────────────────────────────────────────────────────────────────────
  function scan() {
    for (const host of document.querySelectorAll(SELECTOR)) mount(host);
    for (const m of [...mounts]) if (!m.host.isConnected) { mounts.delete(m); mounted.delete(m.host); }
    const element = audio();
    const id = element ? core.songIdFromSrc(element.currentSrc || element.src || '') : '';
    if (id !== currentId) { currentId = id; for (const m of mounts) m.key = ''; }
    if (id && core.wanted(element.duration)) load(id);
  }

  function frame() {
    scheduled = 0; kind = '';
    if (document.visibilityState === 'hidden') return;
    const now = performance.now(), element = audio();
    const playing = !!element && !element.paused && !element.ended;
    let breathing = false;
    for (const m of mounts) {
      const live = cache.get(currentId);
      const scrubbing = !!m.scrub;
      const breathe = playing && !scrubbing && !motionReduced() && !!live && live.state === 'ready';
      breathing = breathing || breathe;
      if (breathe && now - lastFrame < 32) continue;            // ~30 kl./s wystarczy do oddychania
      draw(m, now, breathe);
    }
    if (breathing && now - lastFrame >= 32) lastFrame = now;
    if (mounts.size) schedule(breathing ? 0 : 220);
  }
  function schedule(delay) {
    if (scheduled && (delay !== 0 || kind === 'raf')) return;
    if (scheduled) clearTimeout(scheduled);                    // czekał zwykły licznik, a potrzebna jest natychmiastowa klatka
    if (delay === 0) { kind = 'raf'; scheduled = requestAnimationFrame(frame); } else { kind = 'timer'; scheduled = setTimeout(frame, delay); }
  }

  document.addEventListener('visibilitychange', () => schedule(0));
  window.addEventListener('pf-feel-applied', () => schedule(0));
  setInterval(scan, 700);
  scan();
  window.PiterfyWave = { mount, core, peaks: (id) => { const e = cache.get(id); return e && e.state === 'ready' ? e : null; } };
})();
