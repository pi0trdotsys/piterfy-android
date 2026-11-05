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
      title: metadata.title || 'Utwór bez tytułu',
      artist: metadata.artist || metadata.albumArtist || metadata.subtitle || '',
      album: metadata.albumName || '',
      cover: artwork(metadata.images),
    } : previous;
    if (!track) return null;
    const live = media?.streamType === 'LIVE' || (!media && previous?.live);
    const length = !live && Number.isFinite(duration) && duration > 0 ? duration : 0;
    const position = Number.isFinite(time) && time >= 0 ? time : 0;
    return { ...track, live: !!live, state, duration: length,
      position: length ? Math.min(position, length) : position,
      seekable: !!(seekable && length) };
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
  const cover = $('cover');
  let current = null, coverURL = '', player, playbackError = false;
  const text = (id, value) => { if ($(id).textContent !== value) $(id).textContent = value; };

  cover.onload = () => { if (coverURL) cover.hidden = false; };
  cover.onerror = () => { cover.hidden = true; };

  function draw(track, message) {
    current = track;
    doc.body.dataset.state = track?.state?.toLowerCase() || 'idle';
    text('state', message || ({ PLAYING: 'Teraz odtwarzane', PAUSED: 'Wstrzymano',
      BUFFERING: 'Buforowanie', LOADING: 'Ładowanie utworu' })[track?.state] || 'Gotowe do przesyłania');
    text('title', track?.title || 'Piterfy na TV');
    text('artist', track ? track.artist : 'Wybierz utwór w aplikacji Piterfy.');
    text('album', track?.album || '');
    if ((track?.cover || '') !== coverURL) {
      coverURL = track?.cover || '';
      cover.hidden = true;
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
    $('controls').hidden = !playable;
    $('rewind').hidden = $('forward').hidden = !track?.seekable;
    text('play', track?.state === 'PAUSED' ? 'Odtwórz' : 'Pauza');
    if (track?.live && track.state === 'PLAYING') text('state', 'Na żywo');
  }

  if (new URLSearchParams(root.location.search).get('preview') === '1') {
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
    player = context.getPlayerManager();
    player.setMediaElement($('cast-audio'));
    const events = framework.events.EventType;
    player.addEventListener(events.ERROR, () => { playbackError = true; refresh(); });
    if (events.PLAYER_LOAD_COMPLETE) player.addEventListener(events.PLAYER_LOAD_COMPLETE, () => { playbackError = false; refresh(); });
    player.addEventListener(events.PLAYING, () => { playbackError = false; refresh(); });
    $('play').onclick = () => { if (current?.state === 'PAUSED') player.play(); else if (current?.state === 'PLAYING') player.pause(); };
    for (const [id, offset] of [['rewind', -15], ['forward', 15]]) {
      $(id).onclick = () => { if (current?.seekable) player.seek(Math.max(0, Math.min(current.duration, player.getCurrentTimeSec() + offset))); };
    }
    context.start({ uiConfig: { touchScreenOptimizedApp: true } });
    refresh();
    root.setInterval(refresh, 500);
  };
  doc.head.appendChild(sdk);
})(typeof window === 'undefined' ? globalThis : window);
