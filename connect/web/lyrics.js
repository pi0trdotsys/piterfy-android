(() => {
  'use strict';
  if (window.piterfyLyricsLoaded) return;
  window.piterfyLyricsLoaded = true;
  const timing = window.PiterfyLyricsTiming;
  if (!timing) return;
  let dialog, title, status, content, followButton, correctionButton, retryButton;
  let key = '', track = null, data = null, abort, generation = 0, active = -2, follow = true, correction = 0, timer;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
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
  function paint(force = false) {
    if (!data?.synced) return;
    const next = timing.active(data.lines, current()?.position || 0, data.offset + correction);
    if (!force && next === active) return;
    active = next;
    [...content.children].forEach((line, i) => {
      line.classList.toggle('active', i === active); line.classList.toggle('past', i < active);
      if (i === active) line.setAttribute('aria-current', 'true'); else line.removeAttribute('aria-current');
    });
    if (follow && active >= 0) content.children[active]?.scrollIntoView({block:'center', behavior:reduced ? 'auto' : 'smooth'});
  }
  function render(result) {
    data = result; active = -2; content.replaceChildren();
    status.textContent = result.status;
    result.lines.forEach(line => {
      const el = node(result.synced ? 'button' : 'p', line.value.trim() || '♪', content, 'pf-lyric-line');
      if (result.synced) {
        el.type = 'button';
        el.addEventListener('click', () => { seek(Math.max(0, line.start - result.offset - correction)); follow = true; controls(); paint(true); });
      }
    });
    controls(); paint(true);
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
    data = null; active = -2; follow = true; content.replaceChildren(); controls(); retryButton.hidden = true;
    status.textContent = 'Szukam tekstu…'; title.textContent = song.title || 'Tekst utworu';
    correction = Number(localStorage.getItem(cacheKey + ':offset')) || 0;
    try {
      let cached;
      try { cached = !refresh && JSON.parse(localStorage.getItem(cacheKey) || 'null'); } catch (_) { /* Ignore damaged cache entries. */ }
      if (cached?.lines && Date.now() - cached.at < 7 * 86400000) { render(cached); return; }
      const record = await json('/api/song/' + encodeURIComponent(song.id), abort.signal, true);
      if (request !== generation || user !== account()) return;
      title.textContent = [record.title, record.artist].filter(Boolean).join(' · ');
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
      key = nextKey; track = song;
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
    ['wheel','touchmove'].forEach(type => content.addEventListener(type, () => { follow = false; controls(); }, {passive:true}));
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
    retryButton = button('Spróbuj ponownie', footer, () => { if (track) load(track, true); }); retryButton.hidden = true;
    dialog.addEventListener('close', () => { clearTimeout(timer); generation++; abort?.abort(); });
  }
  boot();
})();
