(() => {
  'use strict';
  if (window.piterfyLyricsLoaded) return;
  window.piterfyLyricsLoaded = true;
  const timing = window.PiterfyLyricsTiming;
  if (!timing) return;
  let dialog, title, status, content, followButton, correctionButton, retryButton, translateButton, shareButton;
  let picked = -1, info = { title: '', artist: '' }, trans = null, transNote = '', transRequest = 0, transAbort;
  let key = '', track = null, data = null, abort, generation = 0, active = -2, follow = true, correction = 0, timer;
  const reduced = (() => { try { return localStorage.getItem("pf-calm") === "1"; } catch (e) { return false; } })();
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  const account = () => localStorage.getItem('username') || '';
  function current() {
    const bridged = window.PiterfyPlayback?.current();
    if (bridged) return bridged;
    const element = audio();
    if (!element?.getAttribute('src')) return null;
    const url = new URL(element.getAttribute('src'), location.href);
    const id = url.searchParams.get('id') || url.searchParams.get('mediaId');
    return id ? { id, position: element.currentTime * 1000, remote: false } : null;
  }
  function seek(ms) {
    if (window.PiterfyPlayback) window.PiterfyPlayback.seek(ms);
    else if (audio()) audio().currentTime = ms / 1000;
  }
  function node(tag, value, parent, cls) {
    const el = document.createElement(tag); if (value) el.textContent = value;
    if (cls) el.className = cls; parent?.appendChild(el); return el;
  }
  function button(label, parent, click) {
    const el = node('button', label, parent); el.type = 'button'; el.addEventListener('click', click); return el;
  }
  function controls() {
    followButton.hidden = correctionButton.hidden = !data?.synced;
    followButton.textContent = follow ? 'Śledzenie włączone' : 'Wróć do tekstu';
    correctionButton.textContent = 'Synchronizacja ' + (correction / 1000).toLocaleString('pl', {signDisplay:'always', minimumFractionDigits:1}) + ' s';
  }
  // ── styl Apple Music: aktywna linia duża, dalsze mniejsze, przygaszone i rozmyte; karaoke wypełnia aktywną linię ──
  let anchorPos = 0, anchorAt = 0, playing = false, karaokeFrame = 0, spring = { raf: 0, pos: 0, vel: 0, target: 0 };
  function remember(now) {  // pozycja z ostatniego odczytu; między odczytami (co 200 ms) dopisujemy upływ czasu, żeby wypełnienie płynęło
    const c = current();
    anchorPos = c?.position || 0; anchorAt = now;
    const element = audio();
    playing = c?.playing ?? !!(element && !element.paused && !element.ended);
  }
  function livePosition(now) { return anchorPos + (playing ? now - anchorAt : 0); }
  function styleLines(activeIndex) {
    [...content.children].forEach((line, i) => {
      const d = activeIndex < 0 ? Math.min(i + 1, 5) : i - activeIndex;
      const st = timing.style(d);
      line.style.setProperty('--ly-scale', st.scale);
      line.style.setProperty('--ly-alpha', st.alpha);
      line.style.setProperty('--ly-blur', (reduced ? 0 : st.blur) + 'px');
      line.style.setProperty('--pf-lyric-progress', i < activeIndex || (reduced && i === activeIndex) ? 1 : 0);
    });
  }
  function karaoke(now) {
    karaokeFrame = 0;
    if (!dialog.open || !data?.synced || active < 0 || reduced) return;
    const line = content.children[active];
    if (line) line.style.setProperty('--pf-lyric-progress', timing.progress(data.lines, active, livePosition(now), data.offset + correction).toFixed(3));
    if (playing) karaokeFrame = requestAnimationFrame(karaoke);
  }
  function springTo(top) {
    if (reduced) { content.scrollTop = top; return; }
    spring.target = top;
    if (spring.raf) return;
    spring.pos = content.scrollTop; spring.vel = 0;
    let last = performance.now();
    const step = now => {
      const dt = Math.min(0.032, (now - last) / 1000); last = now;
      const k = 170, c = 2 * Math.sqrt(k) * 0.82;  // sprężyna lekko niedotłumiona: delikatne „dobicie” do celu
      spring.vel += (-k * (spring.pos - spring.target) - c * spring.vel) * dt;
      spring.pos += spring.vel * dt;
      content.scrollTop = spring.pos;
      if (Math.abs(spring.vel) < 0.5 && Math.abs(spring.pos - spring.target) < 0.5) { content.scrollTop = spring.target; spring.raf = 0; return; }
      spring.raf = requestAnimationFrame(step);
    };
    spring.raf = requestAnimationFrame(step);
  }
  function stopSpring() { if (spring.raf) cancelAnimationFrame(spring.raf); spring.raf = 0; }
  function paint(force = false) {
    if (!data?.synced) return;
    remember(performance.now());
    const next = timing.active(data.lines, anchorPos, data.offset + correction);
    if (!force && next === active) { if (!karaokeFrame && playing) karaokeFrame = requestAnimationFrame(karaoke); return; }
    active = next;
    styleLines(active);
    [...content.children].forEach((line, i) => {
      line.classList.toggle('active', i === active); line.classList.toggle('past', i < active);
      if (i === active) line.setAttribute('aria-current', 'true'); else line.removeAttribute('aria-current');
    });
    if (follow && active >= 0) {
      const line = content.children[active];
      if (line) springTo(line.getBoundingClientRect().top - content.getBoundingClientRect().top + content.scrollTop + line.offsetHeight / 2 - content.clientHeight / 2);
    }
    cancelAnimationFrame(karaokeFrame); karaokeFrame = requestAnimationFrame(karaoke);
  }
  // ── tłumaczenie obok oryginału (POST /piterfy-api/translate; serwer cache'uje wynik) ──
  const translateOn = () => { try { return localStorage.getItem('pf-lyrics-translate') === '1'; } catch (_) { return false; } };
  function showNote(text) { transNote = text; status.textContent = (data?.status || '') + (text ? ' · ' + text : ''); }
  function applyTranslation() {
    [...content.children].forEach((line, i) => {
      line.querySelector('.pf-lyric-tr')?.remove();
      const value = translateOn() && trans && trans[i];
      if (value) node('span', value, line, 'pf-lyric-tr');
    });
  }
  function translationControls() {
    translateButton.hidden = !data;
    translateButton.setAttribute('aria-pressed', translateOn() ? 'true' : 'false');
    translateButton.textContent = translateButton.classList.contains('busy') ? 'Tłumaczę…' : 'Tłumaczenie';
  }
  async function ensureTranslation() {
    translationControls();
    if (!translateOn() || !data) { applyTranslation(); if (transNote) showNote(''); return; }
    if (trans) { applyTranslation(); return; }
    transAbort?.abort(); transAbort = new AbortController();
    const controller = transAbort, mine = ++transRequest, mineKey = key;
    const timeout = setTimeout(() => controller.abort(), 45000);
    translateButton.classList.add('busy'); translationControls();
    try {
      const response = await fetch('/piterfy-api/translate', { method: 'POST', signal: controller.signal, headers: {
        'Content-Type': 'application/json', 'X-ND-Authorization': 'Bearer ' + localStorage.getItem('token') },
        body: JSON.stringify({ song: track?.id || '', target: 'pl', lines: data.lines.map(l => (l.value || '').trim()) }) });
      if (mine !== transRequest || mineKey !== key) return;
      if (!response.ok) { const err = Error('http'); err.status = response.status; throw err; }
      const result = await response.json();
      if (mine !== transRequest || mineKey !== key) return;
      if (result.skipped) { translateButton.disabled = true; translateButton.title = 'Tekst jest już po polsku'; showNote('Tekst jest już po polsku'); return; }
      if (!Array.isArray(result.lines) || result.lines.length !== data.lines.length) throw Error('format');
      trans = result.lines; showNote(''); applyTranslation();
    } catch (e) {
      if (mine !== transRequest || mineKey !== key || e.name === 'AbortError' && !dialog.open) return;
      translateButton.disabled = true;
      const message = e.status === 503 ? 'Tłumaczenia nie są skonfigurowane na serwerze' : 'Tłumaczenie chwilowo niedostępne';
      translateButton.title = message; showNote(message);
    } finally { clearTimeout(timeout); translateButton.classList.remove('busy'); translationControls(); }
  }

  // ── karta z linijką do udostępnienia (canvas 1080 × 1350: rozmyta okładka, duży cytat, tytuł i wykonawca) ──
  function coverUrl(id, size) {
    const q = new URLSearchParams({ u: localStorage.getItem('username') || '', t: localStorage.getItem('subsonic-token') || '',
      s: localStorage.getItem('subsonic-salt') || '', v: '1.16.1', c: 'PiterfyLyrics', id, size });
    return '/rest/getCoverArt?' + q;
  }
  const loadImage = src => new Promise(resolve => { const image = new Image(); image.onload = () => resolve(image); image.onerror = () => resolve(null); image.src = src; });
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  const FONT = 'Figtree, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  async function renderCard({ text, translation, title, artist, coverId }) {
    if (document.fonts?.ready) await document.fonts.ready;
    const W = 1080, H = 1350, P = 96;
    const canvas = document.createElement('canvas'); canvas.width = W; canvas.height = H;
    const ctx = canvas.getContext('2d');
    const image = coverId ? await loadImage(coverUrl(coverId, 600)) : null;
    ctx.fillStyle = '#101a15'; ctx.fillRect(0, 0, W, H);
    if (image) {  // rozmycie przez mały kanwas rozciągnięty z powrotem (tanio i wszędzie działa)
      const tiny = document.createElement('canvas'); tiny.width = 18; tiny.height = 22;
      const t = tiny.getContext('2d'); const side = Math.min(image.width, image.height);
      t.drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, 18, 22);
      ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(tiny, 0, 0, W, H);
    }
    const shade = ctx.createLinearGradient(0, 0, 0, H);
    shade.addColorStop(0, 'rgba(0,0,0,.30)'); shade.addColorStop(0.55, 'rgba(0,0,0,.50)'); shade.addColorStop(1, 'rgba(0,0,0,.78)');
    ctx.fillStyle = shade; ctx.fillRect(0, 0, W, H);
    ctx.fillStyle = 'rgba(255,255,255,.22)'; ctx.font = '800 260px ' + FONT; ctx.textBaseline = 'alphabetic';
    ctx.fillText('\u201C', P - 12, 330);
    const width = W - P * 2, area = { top: 330, bottom: H - 330 };
    const hasTr = !!translation;
    const quote = timing.fitCard(text, (size, t) => { ctx.font = '800 ' + size + 'px ' + FONT; return ctx.measureText(t).width; },
      { width, height: (area.bottom - area.top) * (hasTr ? 0.64 : 1), max: 104, min: 44 });
    let y = area.top;
    ctx.fillStyle = '#fff'; ctx.font = '800 ' + quote.size + 'px ' + FONT; ctx.shadowColor = 'rgba(0,0,0,.35)'; ctx.shadowBlur = 24;
    quote.lines.forEach((line, i) => ctx.fillText(line, P, y + quote.size * 1.05 + i * quote.size * 1.22));
    y += quote.height + 36; ctx.shadowBlur = 0;
    if (hasTr) {
      const tr = timing.fitCard(translation, (size, t) => { ctx.font = '600 ' + size + 'px ' + FONT; return ctx.measureText(t).width; },
        { width, height: Math.max(120, area.bottom - y), max: Math.round(quote.size * 0.55), min: 32, lineHeight: 1.3 });
      ctx.fillStyle = 'rgba(255,255,255,.78)'; ctx.font = '600 ' + tr.size + 'px ' + FONT;
      tr.lines.forEach((line, i) => ctx.fillText(line, P, y + tr.size * 1.0 + i * tr.size * 1.3));
    }
    // stopka: okładka, tytuł, wykonawca i znak Piterfy
    const base = H - P - 150;
    if (image) {
      ctx.save(); roundRect(ctx, P, base, 150, 150, 26); ctx.clip();
      const side = Math.min(image.width, image.height);
      ctx.drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, P, base, 150, 150); ctx.restore();
    }
    const tx = image ? P + 150 + 28 : P, tw = W - P - tx - 160;
    const fitOne = (value, px, weight) => {
      ctx.font = weight + ' ' + px + 'px ' + FONT; let out = value;
      while (out.length > 1 && ctx.measureText(out).width > tw) out = out.slice(0, -1);
      return out === value ? out : out.trimEnd() + '\u2026';
    };
    ctx.fillStyle = '#fff'; ctx.font = '800 42px ' + FONT; ctx.fillText(fitOne(title || '', 42, 800), tx, base + 62);
    ctx.fillStyle = 'rgba(255,255,255,.72)'; ctx.font = '600 32px ' + FONT; ctx.fillText(fitOne(artist || '', 32, 600), tx, base + 112);
    ctx.fillStyle = 'rgba(255,255,255,.7)'; ctx.font = '700 30px ' + FONT; ctx.textAlign = 'right';
    ctx.fillText('piterfy', W - P, base + 140); ctx.textAlign = 'left';
    return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
  }
  function selectedLine() {
    if (!data) return -1;
    if (data.synced && active >= 0) return active;
    return picked;
  }
  async function shareLine() {
    const i = selectedLine(), text = i >= 0 ? (data.lines[i]?.value || '').trim() : '';
    if (!text) { showNote(data?.synced ? 'Włącz utwór, aby wybrać linijkę' : 'Kliknij linijkę, którą chcesz udostępnić'); return; }
    shareButton.classList.add('busy');
    try {
      const blob = await renderCard({ text, translation: translateOn() && trans ? trans[i] : '', title: info.title, artist: info.artist, coverId: track?.cover || track?.id });
      if (!blob) throw Error('canvas');
      const file = new File([blob], 'piterfy-linijka.png', { type: 'image/png' });
      if (navigator.canShare && navigator.canShare({ files: [file] })) { await navigator.share({ files: [file], title: info.title || 'Piterfy' }).catch(() => {}); }
      else {
        const url = URL.createObjectURL(blob), a = document.createElement('a');
        a.href = url; a.download = file.name; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 4000);
      }
      showNote('');
    } catch (e) { showNote('Nie udało się przygotować karty'); }
    finally { shareButton.classList.remove('busy'); }
  }

  function render(result) {
    data = result; active = -2; picked = -1; content.replaceChildren();
    status.textContent = result.status;
    result.lines.forEach(line => {
      const el = node(result.synced ? 'button' : 'p', '', content, 'pf-lyric-line');
      node('span', line.value.trim() || '♪', el, 'pf-lyric-text');
      if (result.synced) {
        el.type = 'button';
        el.addEventListener('click', () => { seek(Math.max(0, line.start - result.offset - correction)); follow = true; controls(); paint(true); });
      } else {  // tekst bez synchronizacji: kliknięcie wybiera linijkę do karty
        el.tabIndex = 0;
        const pick = () => { picked = [...content.children].indexOf(el); [...content.children].forEach(c => c.classList.toggle('picked', c === el)); };
        el.addEventListener('click', pick);
        el.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } });
      }
    });
    controls(); if (result.synced) styleLines(-1); paint(true); ensureTranslation();
  }
  async function json(url, signal, local = false) {
    const response = await fetch(url, { signal, credentials: local ? 'same-origin' : 'omit',
      headers: local ? {'X-ND-Authorization':'Bearer ' + localStorage.getItem('token')} : {} });
    if (response.status === 404 && !local) return null;
    if (!response.ok) throw Error('Nie udało się pobrać tekstu.');
    const text = await response.text();
    if (text.length > 500000) throw Error('Tekst jest zbyt duży.');
    return JSON.parse(text);
  }
  async function load(song, refresh = false) {
    abort?.abort(); abort = new AbortController();
    const controller = abort;
    const request = ++generation, user = account(), cacheKey = 'pf-lyrics:' + user + ':' + song.id;
    const timeout = setTimeout(() => controller.abort(), 18000);
    data = null; active = -2; picked = -1; follow = true; content.replaceChildren(); controls(); retryButton.hidden = true;
    trans = null; transNote = ''; transRequest++; transAbort?.abort(); translateButton.disabled = false; translateButton.title = ''; translateButton.classList.remove('busy'); translationControls();
    status.textContent = 'Szukam tekstu…'; title.textContent = song.title || 'Tekst utworu';
    correction = Number(localStorage.getItem(cacheKey + ':offset')) || 0;
    try {
      let cached;
      try { cached = !refresh && JSON.parse(localStorage.getItem(cacheKey) || 'null'); } catch (_) { /* Ignore damaged cache entries. */ }
      if (cached?.lines && Date.now() - cached.at < 7 * 86400000) { render(cached); return; }
      const record = await json('/api/song/' + encodeURIComponent(song.id), abort.signal, true);
      if (request !== generation || user !== account()) return;
      title.textContent = [record.title, record.artist].filter(Boolean).join(' · ');
      info = { title: record.title || info.title, artist: record.artist || info.artist };
      if (/podcasts?/i.test(record.genre || '') || /(^|\/)Podcasty\//.test(record.path || '')) {
        status.textContent = 'To odcinek podcastu. Teksty są dostępne dla muzyki.'; return;
      }
      let embedded;
      try { embedded = typeof record.lyrics === 'string' ? JSON.parse(record.lyrics) : record.lyrics; } catch (_) { embedded = null; }
      let result = timing.structured(embedded);
      if (result) result.status = 'Tekst z Twojej biblioteki';
      if (!result) {
        if (!record.title || !record.artist || !record.duration || record.duration > 3600) {
          status.textContent = 'Brak tekstu dla tego nagrania.'; return;
        }
        const params = new URLSearchParams({track_name:record.title, artist_name:record.artist, album_name:record.album || '', duration:record.duration});
        const found = await json('https://lrclib.net/api/get?' + params, abort.signal);
        if (request !== generation || user !== account()) return;
        if (!found || !Number.isFinite(found.duration) || Math.abs(found.duration - record.duration) > 2) {
          status.textContent = 'Nie znaleziono tekstu dla tej wersji utworu.'; retryButton.hidden = false; return;
        }
        if (found.instrumental) { status.textContent = 'Utwór instrumentalny · LRCLIB'; return; }
        result = found.syncedLyrics ? timing.parse(found.syncedLyrics) : null;
        if (!result?.lines.length) {
          if (!found.plainLyrics?.trim()) { status.textContent = 'Brak tekstu dla tego nagrania · LRCLIB'; return; }
          result = {synced:false, offset:0, lines:found.plainLyrics.split('\n').map(value => ({value}))};
        }
        result.status = result.synced ? 'Tekst synchronizowany · LRCLIB' : 'Tekst bez synchronizacji · LRCLIB';
      }
      if (request !== generation || user !== account()) return;
      render(result);
      try {
        const keys = Object.keys(localStorage).filter(k => k.startsWith('pf-lyrics:') && !k.endsWith(':offset'));
        if (keys.length >= 30) localStorage.removeItem(keys[0]);
        localStorage.setItem(cacheKey, JSON.stringify({...result, at:Date.now()}));
      } catch (_) { /* Storage may be full or disabled; lyrics still work. */ }
    } catch (e) {
      if (request === generation && dialog.open) { status.textContent = 'Nie udało się pobrać tekstu. Spróbuj ponownie.'; retryButton.hidden = false; }
    } finally { clearTimeout(timeout); }
  }
  function tick() {
    if (!dialog.open) return;
    const song = current();
    const nextKey = account() + '|' + (song?.id || '');
    if (key !== nextKey) {
      key = nextKey; track = song; info = { title: song?.title || '', artist: song?.artist || '' };
      if (song) load(song);
      else { generation++; abort?.abort(); data = null; content.replaceChildren(); title.textContent = 'Tekst utworu'; status.textContent = 'Włącz utwór, aby zobaczyć tekst.'; controls(); }
    }
    paint(); timer = setTimeout(tick, 200);
  }
  function boot() {
    if (!document.body) { setTimeout(boot, 300); return; }
    const css = node('link', '', document.head); css.rel = 'stylesheet'; css.href = '/piterfy-ui/lyrics.css?v=1';
    const open = button('♫ Tekst', document.body, () => { dialog.showModal(); key = ''; tick(); }); open.id = 'pf-lyrics-button';
    dialog = node('dialog', '', document.body); dialog.id = 'pf-lyrics-dialog'; dialog.setAttribute('aria-label', 'Tekst utworu');
    const header = node('header', '', dialog); title = node('h2', 'Tekst utworu', header);
    button('Zamknij', header, () => dialog.close());
    status = node('p', '', dialog, 'pf-lyrics-source'); status.setAttribute('role', 'status');
    content = node('div', '', dialog, 'pf-lyrics-content');
    ['wheel','touchmove'].forEach(type => content.addEventListener(type, () => { follow = false; stopSpring(); controls(); }, {passive:true}));
    content.addEventListener('keydown', event => { if (['ArrowDown','ArrowUp','PageDown','PageUp','Home','End'].includes(event.key)) {follow=false; controls();} });
    const footer = node('footer', '', dialog);
    followButton = button('Śledzenie włączone', footer, () => {follow=true; controls(); paint(true);});
    correctionButton = button('Synchronizacja', footer, () => {
      const adjustmentKey = key;
      const correctionDialog = node('dialog', '', document.body, 'pf-lyrics-adjust');
      node('h3', 'Dopasuj synchronizację', correctionDialog);
      const change = amount => {
        if (key === adjustmentKey && track) {
          correction = Math.max(-10000, Math.min(10000, amount));
          try { localStorage.setItem('pf-lyrics:' + account() + ':' + track.id + ':offset', correction); } catch (_) {}
          controls(); paint(true);
        }
        correctionDialog.close();
      };
      button('0,5 s wcześniej', correctionDialog, () => change(correction + 500));
      button('0,5 s później', correctionDialog, () => change(correction - 500));
      button('Wyzeruj', correctionDialog, () => change(0));
      button('Zamknij', correctionDialog, () => correctionDialog.close());
      correctionDialog.addEventListener('close', () => correctionDialog.remove()); correctionDialog.showModal();
    });
    translateButton = button('Tłumaczenie', footer, () => {
      try { localStorage.setItem('pf-lyrics-translate', translateOn() ? '0' : '1'); } catch (_) { /* bez zapisu działa do zamknięcia */ }
      if (transNote && !translateButton.disabled) showNote('');
      ensureTranslation();
    });
    translateButton.hidden = true; translateButton.setAttribute('aria-pressed', 'false');
    shareButton = button('Udostępnij linijkę', footer, shareLine);
    retryButton = button('Spróbuj ponownie', footer, () => { if (track) load(track, true); }); retryButton.hidden = true;
    dialog.addEventListener('close', () => { clearTimeout(timer); cancelAnimationFrame(karaokeFrame); karaokeFrame = 0; stopSpring(); generation++; abort?.abort(); });
  }
  boot();
})();
