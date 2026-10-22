/* A second view of the existing player. No second audio element, queue or credentials. */
(() => {
  'use strict';
  if (window.piterfyMiniLoaded) return;
  window.piterfyMiniLoaded = true;
  const assetVersion = '20261022-3';
  const cssURL = new URL('/piterfy-ui/miniplayer.css?v=' + assetVersion, location.origin).href;
  const icons = {
    mini: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M13 12h6v6h-6z"/>',
    play: '<path d="m9 5 11 7-11 7z"/>', pause: '<path d="M8 5h3v14H8zm6 0h3v14h-3z"/>',
    previous: '<path d="M5 5v14m14-14L8 12l11 7z"/>', next: '<path d="M19 5v14M5 5l11 7-11 7z"/>',
    volume: '<path d="m11 5-6 4H2v6h3l6 4zM15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>', size: '<path d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/>',
    computer: '<rect x="3" y="3" width="18" height="13" rx="2"/><path d="M8 21h8m-4-5v5"/>',
    music: '<path d="M9 18V5l11-2v13M9 8l11-2"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="17" cy="16" rx="3" ry="2"/>'
  };
  let pip, view, timer, preview, opening = false, returnFocus;
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  const native = selector => document.querySelector('.music-player-panel ' + selector);
  const icon = name => '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' + icons[name] + '</svg>';
  function node(tag, cls, parent, text) {
    const element = (parent?.ownerDocument || document).createElement(tag);
    element.className = cls || ''; if (text) element.textContent = text; parent?.append(element); return element;
  }
  function button(parent, name, label, click) {
    const element = node('button', 'pf-mini-icon', parent); element.type = 'button';
    element.innerHTML = icon(name); element.setAttribute('aria-label', label); element.title = label;
    element.addEventListener('click', click); return element;
  }
  function format(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
    const value = Math.floor(seconds); return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0');
  }
  function current() {
    const remote = window.PiterfyPlayback?.current();
    if (remote?.remote) return remote;
    const element = audio();
    return {
      title: native('.songTitle')?.textContent || '', artist: native('.songArtist')?.textContent || '',
      position: (element?.currentTime || 0) * 1000, duration: element?.duration,
      playing: !!element && !element.paused && !element.ended, volume: element?.muted ? 0 : element?.volume ?? 1,
      available: !!element?.getAttribute('src'), remote: false
    };
  }
  function control(action, value) {
    const state = current();
    try {
      let result;
      if (typeof window.PiterfyPlayback?.control === 'function') result = window.PiterfyPlayback.control(action, value);
      else if (state.remote) return;
      else if (action === 'play') result = audio()?.play();
      else if (action === 'pause') audio()?.pause();
      else if (action === 'volume' && audio()) audio().volume = value;
      else native(action === 'next' ? '.next-audio' : '.prev-audio')?.click();
      Promise.resolve(result).catch(() => { if (view) view.notice.textContent = 'Nie można uruchomić utworu. Wróć do karty Piterfy.'; });
    } catch (_) { if (view) view.notice.textContent = 'Sterowanie jest chwilowo niedostępne.'; }
  }
  function seek(seconds) {
    if (window.PiterfyPlayback) window.PiterfyPlayback.seek(seconds * 1000);
    else if (audio()) audio().currentTime = seconds;
  }
  function update() {
    if (!view) return;
    if (location.hash.startsWith('#/login') || view.account !== localStorage.getItem('username')) { close(); return; }
    const state = current(), available = state.remote || state.available;
    const title = state.title || 'Wybierz utwór w Piterfy';
    if (view.title.textContent !== title) { view.title.textContent = title; view.title.title = title; view.notice.textContent = ''; }
    view.artist.textContent = state.artist || 'Twoja muzyka, zawsze pod ręką';
    view.artist.title = state.artist || '';
    const cover = !state.remote && native('.img-content');
    const background = cover ? getComputedStyle(cover).backgroundImage : 'none';
    if (background !== view.background) { view.background = background; view.art.style.backgroundImage = background; }
    view.art.classList.toggle('pf-mini-no-art', background === 'none');
    const playLabel = state.playing ? 'Wstrzymaj' : 'Odtwarzaj';
    if (view.play.getAttribute('aria-label') !== playLabel) {
      view.play.innerHTML = icon(state.playing ? 'pause' : 'play'); view.play.setAttribute('aria-label', playLabel); view.play.title = playLabel;
    }
    view.play.disabled = !available; view.previous.disabled = view.next.disabled = !available;
    const duration = Number(state.duration), position = Math.max(0, state.position / 1000);
    const timed = Number.isFinite(duration) && duration > 0;
    view.seek.disabled = !timed || !available;
    view.seek.max = timed ? String(duration) : '1';
    if (!view.seeking) view.seek.value = timed ? String(Math.min(duration, position)) : '0';
    view.elapsed.textContent = format(view.seeking ? Number(view.seek.value) : position);
    view.duration.textContent = timed ? format(duration) : available ? 'NA ŻYWO' : '—';
    view.seek.setAttribute('aria-valuetext', view.elapsed.textContent + ' z ' + view.duration.textContent);
    view.seek.style.setProperty('--progress', timed ? Number(view.seek.value) / duration * 100 + '%' : '0%');
    if (!view.volumeEditing) view.volume.value = Math.round((state.volume ?? 1) * 100);
    view.volume.style.setProperty('--progress', view.volume.value + '%');
    view.volume.disabled = !available;
    view.output.textContent = state.remote ? 'Gra na: ' + state.output : 'Ten komputer';
    if (pip && !pip.closed) pip.document.title = title + ' · Piterfy';
  }
  function close() {
    if (pip && !pip.closed) { pip.close(); return; }
    cleanup();
  }
  function cleanup() {
    if (timer) (pip || window).clearInterval(timer);
    timer = null; pip = null; view = null; preview?.remove(); preview = null;
    document.querySelectorAll('.pf-mini-launch').forEach(el => { el.setAttribute('aria-pressed', 'false'); el.setAttribute('data-mini-status', 'closed'); });
    returnFocus?.focus();
  }
  function build(parent, isPreview) {
    const root = node('section', 'pf-mini-player', parent);
    root.setAttribute('aria-label', 'Mini-odtwarzacz Piterfy');
    const header = node('header', 'pf-mini-header', root);
    node('span', 'pf-mini-brand', header, '◉ PITERFY');
    const tools = node('div', 'pf-mini-tools', header);
    if (!isPreview) button(tools, 'size', 'Przełącz rozmiar: kompaktowy / z okładką', () => {
      if (pip) pip.resizeTo(pip.innerHeight < 300 ? 360 : 420, pip.innerHeight < 300 ? 520 : 220);
    });
    button(tools, 'computer', 'Pomoc: okienko na wszystkich pulpitach', () => { view.help.hidden = !view.help.hidden; });
    button(tools, 'close', 'Zamknij mini-odtwarzacz', close);
    const art = node('div', 'pf-mini-art', root); art.innerHTML = icon('music'); art.setAttribute('aria-hidden', 'true');
    const meta = node('div', 'pf-mini-meta', root);
    const title = node('h1', '', meta), artist = node('p', '', meta);
    const timeline = node('div', 'pf-mini-timeline', root);
    const range = node('input', 'pf-mini-range', timeline); range.type = 'range'; range.min = '0'; range.step = '0.1'; range.setAttribute('aria-label', 'Pozycja utworu');
    range.addEventListener('input', () => { view.seeking = true; update(); });
    range.addEventListener('change', () => { seek(Number(range.value)); view.seeking = false; update(); });
    range.addEventListener('blur', () => { if (view) view.seeking = false; });
    const times = node('div', 'pf-mini-times', timeline), elapsed = node('span', '', times), duration = node('span', '', times);
    const controls = node('div', 'pf-mini-controls', root);
    const previous = button(controls, 'previous', 'Poprzedni utwór', () => control('previous'));
    const play = button(controls, 'play', 'Odtwarzaj', () => control(current().playing ? 'pause' : 'play')); play.classList.add('pf-mini-play');
    const next = button(controls, 'next', 'Następny utwór', () => control('next'));
    const footer = node('footer', 'pf-mini-footer', root);
    const output = node('span', 'pf-mini-output', footer);
    const volumeBox = node('label', 'pf-mini-volume', footer); volumeBox.innerHTML = icon('volume');
    const volume = node('input', 'pf-mini-range', volumeBox); volume.type = 'range'; volume.min = '0'; volume.max = '100'; volume.setAttribute('aria-label', 'Głośność');
    volume.addEventListener('input', () => { view.volumeEditing = true; control('volume', Number(volume.value) / 100); });
    volume.addEventListener('change', () => { view.volumeEditing = false; update(); });
    volume.addEventListener('blur', () => { if (view) view.volumeEditing = false; });
    const notice = node('p', 'pf-mini-notice', root); notice.setAttribute('role', 'status');
    const help = node('aside', 'pf-mini-help', root); help.hidden = true;
    node('strong', '', help, isPreview ? 'Podgląd w tej karcie' : 'Twoja muzyka pod ręką');
    node('p', '', help, 'Zmień rozmiar, przeciągając krawędź okienka. Zamknięcie mini-odtwarzacza nie zatrzymuje muzyki. Karta Piterfy musi pozostać otwarta.');
    node('p', '', help, 'Wirtualne pulpity Windows: Win + Tab → prawy przycisk na oknie → „Pokaż to okno na wszystkich pulpitach”, jeśli przeglądarka udostępnia tę opcję. Strona nie może sama przypiąć okna.');
    const dismiss = node('button', '', help, 'Rozumiem'); dismiss.addEventListener('click', () => { help.hidden = true; });
    view = { root, title, artist, art, background: '', seek: range, elapsed, duration, play, previous, next, output, volume, help, notice, account: localStorage.getItem('username') };
    root.addEventListener('keydown', event => {
      if (event.target.matches('input,button')) return;
      if (event.code === 'Space') { event.preventDefault(); control(current().playing ? 'pause' : 'play'); }
    });
    update(); timer = (pip || window).setInterval(update, 250);
  }
  function previewInPage() {
    cleanup(); preview = node('div', 'pf-mini-preview', document.body);
    preview.setAttribute('role', 'region'); preview.setAttribute('aria-label', 'Podgląd mini-odtwarzacza w karcie');
    build(preview, true);
  }
  function unavailable(message) {
    const dialog = node('dialog', 'pf-mini-unavailable', document.body);
    node('h2', '', dialog, 'Mini-odtwarzacz w osobnym oknie');
    node('p', '', dialog, message);
    node('p', '', dialog, 'Otwórz Piterfy w aktualnym Chrome lub Edge na komputerze. Okienko Picture-in-Picture pozostaje nad innymi aplikacjami i można zmieniać jego rozmiar.');
    const previewButton = node('button', '', dialog, 'Podgląd w tej karcie'); previewButton.addEventListener('click', () => { dialog.close(); previewInPage(); });
    const dismiss = node('button', '', dialog, 'Zamknij'); dismiss.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => dialog.remove()); dialog.showModal();
  }
  async function open(event) {
    if (opening) return;
    if (pip && !pip.closed) { pip.focus(); return; }
    returnFocus = event.currentTarget;
    if (!('documentPictureInPicture' in window)) { unavailable('Ta przeglądarka nie obsługuje osobnego okienka dla odtwarzacza.'); return; }
    opening = true;
    returnFocus?.setAttribute('data-mini-status', 'opening');
    let expired = false, timeout;
    try {
      const requested = window.documentPictureInPicture.requestWindow({ width:360, height:520 });
      const target = await Promise.race([
        requested.then(result => { if (expired) { result.close(); throw Error('Timed out'); } return result; }),
        new Promise((_, reject) => { timeout = setTimeout(() => { expired = true; reject(Error('Timed out')); }, 8000); })
      ]);
      clearTimeout(timeout);
      cleanup(); pip = target;
      pip.document.documentElement.lang = 'pl'; pip.document.body.className = 'pf-mini-body';
      const css = pip.document.createElement('link'); css.rel = 'stylesheet'; css.href = cssURL; pip.document.head.append(css);
      // Keep a usable dark background while the stylesheet is loading.
      pip.document.body.style.background = '#121212'; pip.document.body.style.color = '#fff';
      build(pip.document.body, false);
      document.querySelectorAll('.pf-mini-launch').forEach(el => el.setAttribute('aria-pressed', 'true'));
      pip.addEventListener('pagehide', cleanup, {once:true});
      returnFocus?.setAttribute('data-mini-status', 'open');
      returnFocus?.setAttribute('data-mini-last-open', String(Date.now()));
      pip.focus();
    } catch (_) {
      returnFocus?.setAttribute('data-mini-status', 'unavailable');
      unavailable('Przeglądarka nie pozwoliła otworzyć okienka. Spróbuj ponownie bezpośrednio w karcie Piterfy.');
    }
    finally { clearTimeout(timeout); opening = false; }
  }
  function mount() {
    const controls = native('.player-content');
    if (!controls || controls.querySelector('.pf-mini-launch')) return;
    const launcher = button(controls, 'mini', 'Otwórz mini-odtwarzacz w osobnym oknie', open);
    launcher.classList.add('group', 'pf-mini-launch'); launcher.setAttribute('aria-pressed', String(!!pip));
    controls.insertBefore(launcher, controls.querySelector('.destroy-btn'));
  }
  const css = node('link', '', document.head); css.rel = 'stylesheet'; css.href = cssURL;
  mount(); setInterval(mount, 1000);
  window.addEventListener('pagehide', () => { if (pip && !pip.closed) pip.close(); cleanup(); });
})();
