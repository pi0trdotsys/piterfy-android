/* Navidrome 0.64 / navidrome-music-player 4.25 adapter. No server credentials are copied. */
(() => {
  'use strict';
  if (window.PiterfyCrossfade) return;
  const core = window.PiterfyCrossfadeCore;
  if (!core) return;
  const nativeAudio = () => document.querySelector('audio.music-player-audio, .react-jinke-music-player-main audio');
  let store, context, account = '', config = { enabled: true, seconds: 5 }, dialog, launcher, notice = '', attached;
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
      if (fiber.memoizedProps?.store?.getState?.().player) return (store = fiber.memoizedProps.store);
      if (fiber.child) stack.push(fiber.child);
      if (fiber.sibling) stack.push(fiber.sibling);
    }
    return null;
  }
  function settingsKey() { return 'piterfy.crossfade.v1:' + account; }
  function readAccount() {
    const name = localStorage.getItem('username') || '';
    if (name === account) return;
    engine.cancel(); account = name;
    try { const saved = JSON.parse(localStorage.getItem(settingsKey()) || '{}'); config = {
      enabled: saved.enabled !== false, seconds: core.seconds(saved.seconds ?? 5) }; }
    catch (_) { config = { enabled: true, seconds: 5 }; }
    render();
  }
  function state() {
    const s = findStore()?.getState();
    return { ...s?.player, account,
      allowed: !!account && !!localStorage.getItem('token') && !location.hash.startsWith('#/login') &&
        !!nativeAudio()?.playByIndex && context?.state === 'running' &&
        window.PiterfyPlayback?.canCrossfade?.() !== false && window.PiterfyDJ?.canCrossfade?.() !== false };
  }
  function unlock() {
    if (!config.enabled) return;
    try {
      context ||= new (window.AudioContext || window.webkitAudioContext)();
      if (context.state === 'suspended') context.resume().catch(() => {});
    } catch (_) { notice = 'Ta przeglądarka nie obsługuje płynnych przejść.'; render(); }
  }
  // Only the auxiliary deck uses this graph. Navidrome's ReplayGain graph is left intact.
  function deck(track) {
    const audio = new Audio();
    audio.crossOrigin = 'anonymous';
    audio.hidden = true; audio.dataset.piterfyCrossfade = 'incoming';
    audio.setAttribute('aria-hidden', 'true'); document.body.append(audio);
    const source = context.createMediaElementSource(audio), gain = context.createGain();
    gain.gain.value = 0; source.connect(gain); gain.connect(context.destination);
    const info = findStore()?.getState().replayGain || {}, song = track.song || {};
    const mode = info.gainMode === 'album' ? 'Album' : info.gainMode === 'track' ? 'Track' : '';
    const rg = song['rg' + mode + 'Gain'], peak = song['rg' + mode + 'Peak'];
    const normalization = mode && Number.isFinite(rg) && Number.isFinite(peak) && peak > 0
      ? Math.min(10 ** ((rg + (Number(info.preAmp) || 0)) / 20), 1 / peak) : 1;
    let disposed = false;
    return { audio,
      gain(value, volume) { if (!disposed) { gain.gain.setValueAtTime(value * volume * normalization, context.currentTime); audio.dataset.fadeGain = value.toFixed(3); } },
      dispose() { disposed = true; audio.pause(); audio.removeAttribute('src'); audio.load(); audio.remove(); source.disconnect(); gain.disconnect(); }
    };
  }
  const engine = new core.Engine({
    audio: nativeAudio, state, settings: () => config, now: () => performance.now(), deck,
    envelope: core.envelope,
    async resolve(track) {
      const raw = typeof track.musicSrc === 'function' ? await track.musicSrc() : track.musicSrc;
      return core.streamURL(raw, location.href);
    },
    select(uuid) {
      const index = findStore()?.getState().player.queue.findIndex(x => x.uuid === uuid);
      if (!(index >= 0)) throw Error('Queue changed');
      nativeAudio().playByIndex(index);
    },
    status(text) { notice = text; render(); }
  });
  function cancel() { engine.cancel(); }
  window.PiterfyCrossfade = { cancel, isHandoff: () => engine.job?.phase === 'handoff' };
  function attach(audio) {
    if (!audio || attached === audio) return;
    attached = audio;
    audio.addEventListener('pause', () => { if (engine.job?.phase !== 'handoff') cancel(); }, true);
    audio.addEventListener('seeking', () => { if (engine.job?.phase !== 'handoff') cancel(); }, true);
    audio.addEventListener('ended', event => {
      if (engine.commit()) { event.stopImmediatePropagation(); event.preventDefault(); }
    }, true);
    audio.addEventListener('error', cancel, true);
  }
  function el(tag, text, parent, cls) {
    const element = document.createElement(tag);
    if (text) element.textContent = text;
    if (cls) element.className = cls;
    parent?.append(element); return element;
  }
  function save() {
    cancel(); config.enabled = dialog.querySelector('#pf-fade-enabled').checked;
    config.seconds = core.seconds(dialog.querySelector('#pf-fade-seconds').value);
    try { localStorage.setItem(settingsKey(), JSON.stringify(config)); notice = ''; }
    catch (_) { notice = 'Ustawienie działa do zamknięcia tej karty — przeglądarka blokuje zapis.'; }
    unlock(); render();
  }
  function render() {
    if (launcher) {
      launcher.textContent = config.enabled ? 'Przejście · ' + config.seconds + ' s' : 'Przejścia';
      launcher.setAttribute('aria-label', 'Płynne przejścia: ' + (config.enabled ? config.seconds + ' sekund' : 'wyłączone'));
      launcher.classList.toggle('pf-fade-active', config.enabled);
    }
    if (!dialog) return;
    dialog.querySelector('#pf-fade-enabled').checked = config.enabled;
    const range = dialog.querySelector('#pf-fade-seconds');
    range.disabled = !config.enabled; range.value = config.seconds;
    range.setAttribute('aria-valuetext', config.seconds + ' sekund');
    dialog.querySelector('output').textContent = config.seconds + ' s';
    dialog.querySelector('.pf-fade-demo').style.setProperty('--fade', config.seconds / 10);
    dialog.querySelector('[role=status]').textContent = notice ||
      (window.PiterfyPlayback?.current()?.remote ? 'Ustawienie dotyczy muzyki odtwarzanej w tej przeglądarce.' : 'Zapisane dla tego konta w tej przeglądarce.');
  }
  function open() {
    readAccount(); unlock();
    if (!dialog) {
      dialog = el('dialog', '', document.body, 'pf-fade-dialog');
      dialog.setAttribute('aria-labelledby', 'pf-fade-heading');
      const heading = el('div', '', dialog, 'pf-fade-heading');
      el('h2', 'Płynne przejścia', heading).id = 'pf-fade-heading';
      const close = el('button', '×', heading, 'pf-fade-close');
      close.type = 'button'; close.setAttribute('aria-label', 'Zamknij ustawienia przejść'); close.onclick = () => dialog.close();
      el('p', 'Następny utwór zaczyna grać, zanim skończy się poprzedni.', dialog, 'pf-fade-intro');
      const toggle = el('label', '', dialog, 'pf-fade-toggle');
      el('span', 'Przenikanie utworów', toggle);
      const checkbox = el('input', '', toggle); checkbox.type = 'checkbox'; checkbox.id = 'pf-fade-enabled'; checkbox.onchange = save;
      const label = el('label', 'Długość przejścia', dialog, 'pf-fade-label'); label.htmlFor = 'pf-fade-seconds';
      el('output', '', label).htmlFor = 'pf-fade-seconds';
      const slider = el('input', '', dialog); slider.id = 'pf-fade-seconds'; slider.type = 'range'; slider.min = '1'; slider.max = '10'; slider.step = '1'; slider.oninput = save;
      const ends = el('div', '', dialog, 'pf-fade-ends'); el('span', '1 s · subtelnie', ends); el('span', '10 s · długie przenikanie', ends);
      const demo = el('div', '', dialog, 'pf-fade-demo'); demo.setAttribute('aria-hidden', 'true'); el('span', 'Bieżący utwór', demo); el('span', 'Następny', demo);
      el('p', 'Początek kolejnego utworu nakłada się na wyciszany koniec poprzedniego. Nie usuwamy fragmentów nagrania.', dialog, 'pf-fade-help');
      el('p', 'Dotyczy muzyki. Podcasty, radio i powtarzanie jednego utworu grają bez przejść. Przy wolnym połączeniu przejście może zostać pominięte.', dialog, 'pf-fade-help');
      el('p', '', dialog, 'pf-fade-status').setAttribute('role', 'status');
      dialog.addEventListener('close', () => launcher?.focus());
    }
    render(); dialog.showModal();
  }
  function mount() {
    readAccount(); attach(nativeAudio());
    const controls = document.querySelector('.music-player-panel .player-content, .react-jinke-music-player-mobile-operation');
    if (!controls || controls.querySelector('.pf-fade-launch')) return;
    launcher = el('button', '', controls, 'group pf-fade-launch'); launcher.type = 'button'; launcher.onclick = open;
    controls.insertBefore(launcher, controls.querySelector('.destroy-btn')); render();
  }
  const css = el('link', '', document.head); css.rel = 'stylesheet'; css.href = '/piterfy-ui/crossfade.css?v=20261030-2';
  document.addEventListener('pointerdown', event => {
    unlock();
    if (event.target.closest?.('.next-audio, .prev-audio, .play-btn, .pause-btn, .destroy-btn, .rc-slider, .audio-lists-panel, .react-jinke-music-player-mobile-toggle')) cancel();
  }, true);
  document.addEventListener('keydown', event => { unlock(); if ([' ', 'MediaPlayPause', 'MediaTrackNext', 'MediaTrackPrevious'].includes(event.key)) cancel(); }, true);
  window.addEventListener('pagehide', cancel);
  window.addEventListener('storage', event => {
    if (event.key === settingsKey()) { const name = account; account = ''; readAccount(); account ||= name; }
    else if (event.key === 'token' || event.key === 'username') cancel();
  });
  mount(); setInterval(mount, 1000);
  setInterval(() => { try { engine.tick(); } catch (_) { engine.cancel('Przejście pominięte. Utwory zagrają kolejno.'); } }, 50);
})();
