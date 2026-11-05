(function (root) {
  'use strict';

  function artwork(images) {
    for (const image of Array.isArray(images) ? images : []) {
      try {
        const url = new URL(image.url);
        if (url.protocol === 'https:' || url.protocol === 'http:') return url.href;
      } catch (_) { /* Ignore missing or local-only cover art. */ }
    }
    return '';
  }

  function snapshot(previous, { media, state, time, duration, seekable }) {
    if (state === 'IDLE') return null;
    const metadata = media?.metadata;
    const track = metadata ? {
      identity: media.entity || media.contentId || '',
      title: metadata.title || 'Utwór bez tytułu',
      artist: metadata.artist || metadata.albumArtist || metadata.subtitle || '',
      album: metadata.albumName || '',
      cover: artwork(metadata.images),
    } : previous;
    if (!track) return null;
    const live = media?.streamType === 'LIVE' || (!media && previous?.live);
    // Keep the last timeline when CAF briefly drops media during a transition.
    const waiting = state === 'BUFFERING' || state === 'LOADING';
    const remembered = waiting && !media ? previous : null;
    const length = live ? 0 : Number.isFinite(duration) && duration > 0 ? duration : remembered?.duration || 0;
    const position = Number.isFinite(time) && time >= 0 && !(remembered && time === 0)
      ? time : remembered?.position || 0;
    return { ...track, live: !!live, state, duration: length,
      position: length ? Math.min(position, length) : position,
      seekable: !!((seekable || remembered?.seekable) && length) };
  }

  const formatTime = seconds => {
    const total = Math.floor(seconds);
    const minutes = Math.floor(total / 60);
    const rest = String(total % 60).padStart(2, '0');
    return minutes < 60 ? minutes + ':' + rest :
      Math.floor(minutes / 60) + ':' + String(minutes % 60).padStart(2, '0') + ':' + rest;
  };
  if (typeof module === 'object' && module.exports) {
    module.exports = { snapshot, formatTime };
    return;
  }

  const doc = root.document, $ = id => doc.getElementById(id);
  const cover = $('cover'), backdrop = $('backdrop');
  const controls = $('controls');
  let current = null, coverURL = '', player, playbackError = false, library;
  const text = (id, value) => { if ($(id).textContent !== value) $(id).textContent = value; };

  backdrop.onload = () => { backdrop.hidden = !coverURL || backdrop.src !== coverURL; };
  backdrop.onerror = () => { backdrop.hidden = true; };
  cover.onload = () => {
    if (!coverURL || cover.src !== coverURL) return;
    cover.hidden = false;
    backdrop.src = coverURL;
  };
  cover.onerror = () => { cover.hidden = true; backdrop.hidden = true; };

  doc.addEventListener('keydown', event => {
    if (controls.hidden || !['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    if (doc.activeElement !== doc.body && !controls.contains(doc.activeElement)) return;
    const buttons = [...controls.querySelectorAll('button')].filter(button => !button.hidden);
    const selected = buttons.indexOf(doc.activeElement);
    const step = event.key === 'ArrowLeft' ? -1 : 1;
    const next = selected < 0 ? $('play') : buttons[Math.max(0, Math.min(buttons.length - 1, selected + step))];
    event.preventDefault();
    next.focus();
  });

  function draw(track, message) {
    current = track;
    doc.body.dataset.state = track?.state?.toLowerCase() || 'idle';
    text('state', message || (track?.live && track.state === 'PLAYING' ? 'Na żywo' : '') || ({ PLAYING: 'Teraz odtwarzane', PAUSED: 'Wstrzymano',
      BUFFERING: 'Buforowanie', LOADING: 'Ładowanie utworu' })[track?.state] || 'Gotowe do przesyłania');
    text('title', track?.title || 'Piterfy na TV');
    text('artist', track ? track.artist : 'Wybierz utwór w aplikacji Piterfy.');
    text('album', track?.album || '');
    if ((track?.cover || '') !== coverURL) {
      coverURL = track?.cover || '';
      cover.hidden = true;
      backdrop.hidden = true;
      backdrop.removeAttribute('src');
      if (coverURL) cover.src = coverURL;
      else cover.removeAttribute('src');
    }
    $('timeline').hidden = !track?.duration;
    if (track?.duration) {
      $('progress').max = track.duration;
      $('progress').value = track.position;
      text('elapsed', formatTime(track.position));
      text('duration', formatTime(track.duration));
    }
    const playable = track?.state === 'PLAYING' || track?.state === 'PAUSED';
    controls.hidden = !track;
    const focusedSeek = [$('rewind'), $('forward')].includes(doc.activeElement);
    $('rewind').hidden = $('forward').hidden = !track?.seekable;
    if (track && focusedSeek && !track.seekable) $('play').focus();
    for (const button of controls.querySelectorAll('button')) button.setAttribute('aria-disabled', String(!playable));
    text('play', track?.state === 'PAUSED' ? '▶  Odtwórz' : playable ? 'Ⅱ  Pauza' : 'Oczekiwanie');
    library?.update(track);
  }

  if (new URLSearchParams(root.location.search).get('preview') === '1') {
    library = root.PiterfyCastLibrary?.init(null);
    const sample = { metadata: { title: 'Nocna panorama', artist: 'Piterfy Sessions',
      albumName: 'W drodze', images: [{ url: new URL('cast-preview-art.svg', root.location.href).href }] } };
    let state = 'PLAYING', time = 92;
    const update = () => draw(snapshot(current, { media: sample, state, time, duration: 246, seekable: true }));
    $('hint').textContent = 'Podgląd · przykładowy utwór, bez przesyłania dźwięku';
    $('play').onclick = () => { state = state === 'PLAYING' ? 'PAUSED' : 'PLAYING'; update(); };
    $('rewind').onclick = () => { time = Math.max(0, time - 15); update(); };
    $('forward').onclick = () => { time = Math.min(246, time + 15); update(); };
    update();
    return;
  }

  function refresh() {
    const state = player.getPlayerState();
    const track = snapshot(current, { media: player.getMediaInformation(), state,
      time: player.getCurrentTimeSec(), duration: player.getDurationSec(),
      seekable: player.getCurrentSupportedMediaCommands() & root.cast.framework.messages.Command.SEEK });
    draw(track, playbackError ? 'Nie udało się odtworzyć. Wybierz utwór ponownie na telefonie.' : '');
  }

  const sdk = doc.createElement('script');
  sdk.src = 'https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js';
  sdk.onerror = () => text('state', 'Nie udało się uruchomić Google Cast.');
  sdk.onload = () => {
    const framework = root.cast.framework, context = framework.CastReceiverContext.getInstance();
    library = root.PiterfyCastLibrary?.init(context);
    player = context.getPlayerManager();
    player.setMediaElement($('cast-audio'));
    const events = framework.events.EventType;
    player.addEventListener(events.ERROR, () => { playbackError = true; refresh(); });
    if (events.PLAYER_LOAD_COMPLETE) player.addEventListener(events.PLAYER_LOAD_COMPLETE, () => { playbackError = false; refresh(); });
    player.addEventListener(events.PLAYING, () => { playbackError = false; refresh(); });
    $('play').onclick = () => { if (current?.state === 'PAUSED') player.play(); else if (current?.state === 'PLAYING') player.pause(); };
    for (const [id, offset] of [['rewind', -15], ['forward', 15]]) {
      $(id).onclick = () => { if (current?.seekable && ['PLAYING', 'PAUSED'].includes(current.state)) player.seek(Math.max(0, Math.min(current.duration, player.getCurrentTimeSec() + offset))); };
    }
    context.start({ uiConfig: { touchScreenOptimizedApp: true } });
    refresh();
    root.setInterval(refresh, 500);
  };
  doc.head.appendChild(sdk);
})(typeof window === 'undefined' ? globalThis : window);
