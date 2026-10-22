/* Piterfy Connect adapter for Navidrome 0.64.x. Uses native queue actions and audio.
 * Each tab has its own ephemeral identity; no credentials leave this origin.
 */
(() => {
  'use strict';
  if (window.piterfyConnectLoaded) return;
  window.piterfyConnectLoaded = true;
  const id = 'web-' + crypto.randomUUID();
  const name = /Android|iPhone|iPad/.test(navigator.userAgent) ? 'Przeglądarka mobilna' : 'Komputer · przeglądarka';
  let store, state, enabled = false, busy = false, applying = false, lastOk = 0, applied = '', stopped = '', message = '';
  let identity = '', timer, modal, button, generation = 0;
  const audio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  const local = () => state?.owner === id;
  const safe = value => typeof value === 'string' ? value.slice(0, 512) : '';
  const errors = { session_changed: 'Kolejka się zmieniła. Spróbuj ponownie.', transfer_pending: 'Trwa przenoszenie odtwarzania.',
    device_offline: 'Urządzenie jest teraz niedostępne.', choose_device: 'Najpierw wybierz urządzenie.',
    queue_limit: 'Connect obsługuje kolejki do 500 utworów.', library_tracks_only: 'Connect przenosi utwory z biblioteki.',
    transfer_first: 'Najpierw przenieś odtwarzanie na to urządzenie.' };

  function findStore() {
    if (store) return store;
    const root = document.getElementById('root');
    if (!root) return null;
    const key = Object.keys(root).find(k => k.startsWith('__reactContainer$'));
    const legacy = root._reactRootContainer;
    const stack = [legacy ? (legacy._internalRoot || legacy).current : key && root[key]];
    for (let n = 0; stack.length && n < 20000; n++) {
      const fiber = stack.pop();
      if (!fiber) continue;
      const candidate = fiber.memoizedProps?.store;
      if (candidate?.getState?.().player && candidate.dispatch) return (store = candidate);
      if (fiber.child) stack.push(fiber.child);
      if (fiber.sibling) stack.push(fiber.sibling);
    }
    return null;
  }

  function snapshot() {
    const player = findStore()?.getState().player;
    const element = audio();
    if (!player || !element || player.queue.some(x => x.isRadio)) return null;
    const items = player.queue.map(x => ({ id: safe(x.trackId), title: safe(x.song?.title || x.name),
      artist: safe(x.song?.artist || x.singer), album: safe(x.song?.album), albumId: safe(x.song?.albumId),
      artistId: safe(x.song?.artistId), cover: safe(x.trackId), suffix: safe(x.song?.suffix),
      duration: Number(x.song?.duration || x.duration || 0), bitrate: Number(x.song?.bitRate || 0) }));
    return { items, index: Math.max(0, player.queue.findIndex(x => x.uuid === player.current?.uuid)),
      position: Math.round((element.currentTime || 0) * 1000), playing: !element.paused && !element.ended,
      repeat: player.mode === 'singleLoop' ? 1 : player.mode === 'orderLoop' ? 2 : 0,
      shuffle: player.mode === 'shufflePlay', speed: element.playbackRate || 1, volume: element.volume };
  }

  async function api(path, body) {
    const token = localStorage.getItem('token');
    if (!token) throw Error('Zaloguj się w Piterfy.');
    const response = await fetch('/piterfy-api/connect/' + path, { method: 'POST', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'X-ND-Authorization': 'Bearer ' + token },
      body: JSON.stringify(body), signal: AbortSignal.timeout(7000) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw Error(errors[result.error] || (response.status === 401 ? 'Sesja wygasła. Zaloguj się ponownie.' : 'Connect jest chwilowo niedostępny.'));
    return result;
  }

  const sameQueue = (left, right) => left.length === right.length && left.every((x, i) => x.id === right[i].id);

  async function apply(next) {
    const previousOwner = state?.owner;
    state = next;
    lastOk = performance.now();
    if (next.pending?.from === id) {
      audio()?.pause();
      stopped = next.pending.id;
      applied = next.version;
      return;
    }
    stopped = '';
    if (!local()) {
      if (previousOwner === id || next.owner) audio()?.pause();
      applied = next.version;
      return;
    }
    if (applied === next.version) return;
    const current = snapshot();
    const s = findStore();
    if (!s || !current) throw Error('Uruchom odtwarzacz Piterfy, aby odbierać muzykę.');
    applying = true;
    const token = generation;
    try {
      if (!next.items.length) {
        audio()?.pause();
        s.dispatch({ type: 'PLAYER_CLEAR_QUEUE' });
      } else {
        if (!sameQueue(current.items, next.items) || current.index !== next.index) {
          const records = [];
          // Resolve songs with THIS browser's authenticated account, never accept a remote URL.
          for (let offset = 0; offset < next.items.length; offset += 40) {
            const ids = [...new Set(next.items.slice(offset, offset + 40).map(x => x.id))];
            const r = await fetch('/api/song?' + ids.map(x => 'id=' + encodeURIComponent(x)).join('&'), {
              headers: { 'X-ND-Authorization': 'Bearer ' + localStorage.getItem('token') }, signal: AbortSignal.timeout(7000) });
            if (!r.ok) throw Error('Nie można załadować kolejki.');
            records.push(...await r.json());
          }
          if (token !== generation || !enabled) return;
          if (performance.now() - lastOk >= 9000) throw Error('Przygotowanie kolejki trwało zbyt długo. Spróbuj ponownie.');
          const byId = new Map(records.map(x => [x.id, x]));
          const data = {};
          next.items.forEach((x, i) => {
            if (!byId.has(x.id)) throw Error('Utwór jest niedostępny na tym koncie.');
            data['connect_' + i] = byId.get(x.id);
          });
          audio()?.pause();
          s.dispatch({ type: 'PLAYER_PLAY_TRACKS', data, id: 'connect_' + next.index });
          const deadline = performance.now() + 6500;
          while (performance.now() < deadline) {
            const player = s.getState().player;
            if (player.queue[next.index]?.uuid === player.current?.uuid && audio()?.readyState >= 1) break;
            await new Promise(resolve => setTimeout(resolve, 80));
            if (token !== generation || !enabled) return;
          }
          const player = s.getState().player;
          if (player.queue[next.index]?.uuid !== player.current?.uuid || !audio()?.readyState)
            throw Error('Nie udało się przygotować utworu. Spróbuj ponownie.');
        }
        const element = audio();
        if (token !== generation || !enabled || !local() || performance.now() - lastOk >= 9000) {
          element?.pause();
          throw Error('Odświeżono sesję odtwarzania. Spróbuj ponownie.');
        }
        if (Math.abs(element.currentTime * 1000 - next.position) > 1000 || previousOwner !== id)
          element.currentTime = next.position / 1000;
        element.playbackRate = next.speed;
        element.volume = next.volume;
        s.dispatch({ type: 'PLAYER_SET_MODE', data: { mode: next.shuffle ? 'shufflePlay' : next.repeat === 1 ? 'singleLoop' : next.repeat === 2 ? 'orderLoop' : 'order' } });
        applying = false;
        if (next.playing) {
          try { await element.play(); }
          catch (_) { message = 'Kliknij ▶ w odtwarzaczu, aby zezwolić przeglądarce na dźwięk.'; }
        } else element.pause();
      }
      applied = next.version;
    } finally { applying = false; }
  }

  async function poll() {
    clearTimeout(timer);
    if (!enabled || busy) { timer = setTimeout(poll, 2000); return; }
    const user = localStorage.getItem('username') || '';
    if (identity !== user || !localStorage.getItem('token')) { disable(); return; }
    busy = true;
    const token = generation;
    try {
      const report = !applying ? snapshot() : null;
      const next = await api('poll', { device: { id, name, kind: 'web', output: 'Wyjście dźwięku przeglądarki' },
        version: applied, report, stopAck: stopped });
      if (token === generation && enabled) await apply(next);
    } catch (e) { message = e.message; }
    finally { busy = false; render(); timer = setTimeout(poll, stopped ? 100 : 2000); }
  }

  async function command(action, extra = {}) {
    if (!state || busy) return;
    busy = true;
    const token = generation;
    try {
      const next = await api('command', { action, ...extra, version: state.version, requestId: crypto.randomUUID() });
      message = '';
      if (token === generation && enabled) await apply(next);
    } catch (e) { message = e.message; }
    finally { busy = false; render(); timer = setTimeout(poll, 100); }
  }

  function disable() {
    generation++;
    if (local()) audio()?.pause();
    enabled = false; state = null; applied = ''; stopped = ''; store = null;
    clearTimeout(timer);
    render();
  }

  function label() {
    if (!enabled) return 'Piterfy Connect';
    if (state?.pending) return 'Przenoszenie odtwarzania…';
    if (message) return message;
    const owner = state?.devices.find(x => x.id === state.owner);
    return owner ? (state.playing ? 'Gra na: ' : 'Wstrzymano · ') + (local() ? 'tym komputerze' : owner.name) : 'Wybierz urządzenie';
  }

  function node(tag, text, parent, className) {
    const el = document.createElement(tag);
    if (text) el.textContent = text;
    if (className) el.className = className;
    parent?.appendChild(el);
    return el;
  }

  function action(parent, label, handler, disabled = false) {
    const el = node('button', label, parent);
    el.type = 'button'; el.disabled = disabled;
    el.addEventListener('click', handler);
    return el;
  }

  function render() {
    if (!button) return;
    button.textContent = enabled && state?.owner ? '◉ ' + label() : '◉ Piterfy Connect';
    button.title = label();
    if (!modal?.open) return;
    // Keep focus and range dragging intact across heartbeat updates.
    const content = JSON.stringify([state?.version, state?.devices, enabled, message, busy]);
    if (modal.dataset.render === content) {
      const seek = modal.querySelector('input[type=range]');
      if (seek && document.activeElement !== seek) seek.value = state?.position || 0;
      return;
    }
    modal.dataset.render = content;
    const focus = document.activeElement?.dataset?.focus;
    const queueOpen = modal.querySelector('details')?.open;
    modal.replaceChildren();
    const header = node('div', '', modal, 'pf-connect-heading');
    node('h2', 'Piterfy Connect', header);
    action(header, 'Zamknij', () => modal.close());
    node('p', label(), modal, 'pf-connect-status').setAttribute('role', 'status');
    if (!enabled) {
      node('p', 'Steruj muzyką na swoich urządzeniach lub przenieś tutaj kolejkę i pozycję utworu.', modal);
      action(modal, 'Włącz na tym komputerze', () => {
        enabled = true; identity = localStorage.getItem('username') || ''; message = ''; poll(); render();
      });
      return;
    }
    const devices = node('div', '', modal, 'pf-connect-devices');
    (state?.devices || []).forEach(device => {
      const row = node('div', '', devices, 'pf-connect-device');
      node('strong', device.id === id ? 'Ten komputer' : device.name, row);
      node('small', device.output || (device.online ? 'Dostępne' : 'Offline'), row);
      const el = action(row, device.id === state.owner ? 'Aktywne urządzenie' : device.id === id ? 'Przenieś tutaj' : 'Przenieś odtwarzanie',
        () => command('transfer', { target: device.id }), busy || !device.online || !!state.pending || device.id === state.owner);
      el.dataset.focus = device.id;
    });
    if (state?.items.length) {
      const song = state.items[state.index];
      node('h3', song.title || 'Bieżący utwór', modal);
      node('p', song.artist, modal);
      const controls = node('div', '', modal, 'pf-connect-controls');
      action(controls, 'Poprzedni', () => command('previous'), busy || !state.owner);
      action(controls, state.playing ? 'Pauza' : 'Odtwarzaj', () => command(state.playing ? 'pause' : 'play'), busy || !state.owner);
      action(controls, 'Następny', () => command('next'), busy || !state.owner);
      const seek = node('input', '', modal);
      seek.type = 'range'; seek.min = '0'; seek.max = String(Math.max(1, song.duration * 1000)); seek.value = state.position;
      seek.setAttribute('aria-label', 'Pozycja utworu'); seek.disabled = busy || !state.owner;
      seek.addEventListener('change', () => command('seek', { position: Number(seek.value) }));
      const details = node('details', '', modal);
      details.open = !!queueOpen;
      node('summary', 'Wspólna kolejka · ' + state.items.length, details);
      const queue = node('ol', '', details, 'pf-connect-queue');
      state.items.forEach((item, index) => {
        const row = node('li', '', queue);
        const el = action(row, (index === state.index ? '▶ ' : '') + (item.title || item.id) + ' · ' + item.artist,
          () => command('edit', { edit: 'select', index }), busy || !state.owner);
        el.title = item.title + ' · ' + item.artist;
        action(row, 'Usuń', () => command('edit', { edit: 'remove', from: index, to: index + 1 }), busy || !state.owner);
      });
    } else if (snapshot()?.items.length) {
      action(modal, 'Udostępnij obecną kolejkę', () => command('adopt', { target: id, state: snapshot() }), busy);
    }
    action(modal, 'Wyłącz Connect w tej karcie', disable, busy);
    if (focus) [...modal.querySelectorAll('[data-focus]')].find(x => x.dataset.focus === focus)?.focus();
  }

  function boot() {
    if (!document.body) { setTimeout(boot, 500); return; }
    const css = document.createElement('link'); css.rel = 'stylesheet'; css.href = '/piterfy-ui/connect.css?v=1'; document.head.appendChild(css);
    button = action(document.body, '◉ Piterfy Connect', () => { modal.showModal(); modal.dataset.render = ''; render(); });
    button.id = 'pf-connect-button';
    modal = node('dialog', '', document.body); modal.id = 'pf-connect-dialog'; modal.setAttribute('aria-label', 'Piterfy Connect');
    document.addEventListener('play', event => {
      if (event.target !== audio() || !enabled) return;
      if (applying || (state?.owner && !local()) || state?.pending?.from === id || (local() && performance.now() - lastOk >= 9000)) {
        event.target.pause();
        if (!applying && !local()) { message = 'Wybierz „Przenieś tutaj”, aby słuchać na tym komputerze.'; render(); }
      }
    }, true);
    setInterval(() => {
      // Stop before the server is allowed to reassign an unreachable receiver.
      if (enabled && local() && performance.now() - lastOk > 9000) { audio()?.pause(); message = 'Utracono połączenie. Odtwarzanie wstrzymane.'; render(); }
      if (enabled && (!localStorage.getItem('token') || identity !== (localStorage.getItem('username') || ''))) disable();
    }, 500);
    window.addEventListener('pagehide', () => { if (enabled && local()) audio()?.pause(); });
  }
  // A narrow same-page bridge for the lyrics view; never exposes credentials or stream URLs.
  window.PiterfyPlayback = {
    current() {
      if (enabled && state?.owner && !local()) {
        const song = state.items[state.index];
        return song ? { ...song, position: state.position + (state.playing ? Math.min(9000, performance.now() - lastOk) * state.speed : 0), remote: true } : null;
      }
      const report = snapshot();
      const song = report?.items[report.index];
      return song ? { ...song, position: report.position, remote: false } : null;
    },
    seek(position) {
      if (enabled && state?.owner && !local()) return command('seek', { position });
      if (audio()) audio().currentTime = position / 1000;
    }
  };
  boot();
})();
