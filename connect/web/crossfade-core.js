/* Two-deck crossfade. The native player remains the queue and playback authority. */
(function (root) {
  'use strict';
  const clamp = (n, low, high) => Math.max(low, Math.min(high, n));
  function seconds(value) { return Number.isFinite(Number(value)) ? clamp(Math.round(Number(value)), 1, 10) : 5; }
  function music(item) {
    const song = item?.song || {};
    return !!item?.uuid && !item.isRadio && !song.isPodcast && String(song.type||'').toLowerCase()!=='podcast' && !/podcast/i.test(song.genre || '') &&
      !/(^|[/\\])podcast[sy]?[/\\]/i.test(song.path || '') && Number(song.duration || item.duration) > 4;
  }
  function next(state, random = Math.random) {
    const queue = state?.queue || [], index = queue.findIndex(x => x.uuid === state.current?.uuid);
    if (index < 0 || !music(queue[index]) || state.mode === 'singleLoop' || queue.length < 2) return null;
    let target;
    if (state.mode === 'shufflePlay') {
      const others = queue.filter(x => x.uuid !== queue[index].uuid);
      target = others[Math.min(others.length - 1, Math.floor(random() * others.length))];
    } else target = queue[index + 1] || (state.mode === 'orderLoop' ? queue[0] : null);
    return music(target) ? target : null;
  }
  function buffered(audio, position, ahead) {
    for (let i = 0; i < audio.buffered.length; i++) {
      if (audio.buffered.start(i) <= position + 0.05 && audio.buffered.end(i) >= position + ahead) return true;
    }
    return false;
  }
  function gains(progress) {
    const angle = clamp(progress, 0, 1) * Math.PI / 2;
    return [Math.cos(angle), Math.sin(angle)];
  }
  function streamURL(raw, origin) {
    if (typeof raw !== 'string' || !raw) throw Error('Missing stream');
    const url = new URL(raw, origin);
    if (url.origin !== new URL(origin).origin || !/\/rest\/(?:stream|getTranscodeStream)(?:\.view)?$/.test(url.pathname))
      throw Error('Unsupported stream');
    return url.href;
  }
  // Expose the user's volume to the player, Connect and mini player while attenuating
  // only the actual audio output. Fade steps must never persist as user volume.
  function envelope(audio) {
    const descriptor = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
    if (Object.hasOwn(audio, 'volume') || !descriptor?.get || !descriptor?.set) throw Error('Unsupported volume');
    let level = descriptor.get.call(audio), gain = 1, notify = false;
    const onVolume = event => { if (!notify) event.stopImmediatePropagation(); notify = false; };
    audio.addEventListener('volumechange', onVolume, true);
    Object.defineProperty(audio, 'volume', { configurable: true,
      get: () => level,
      set(value) {
        value = Number(value);
        if (!Number.isFinite(value) || value < 0 || value > 1) throw new RangeError('Volume outside 0..1');
        level = value; notify = true; descriptor.set.call(audio, level * gain);
      }
    });
    return {
      set(value) { gain = clamp(value, 0, 1); descriptor.set.call(audio, level * gain); },
      restore() {
        delete audio.volume;
        audio.removeEventListener('volumechange', onVolume, true);
        descriptor.set.call(audio, level);
      }
    };
  }
  class Engine {
    constructor(options) {
      this.o = options; this.job = null; this.blocked = ''; this.lastTrack = ''; this.serial = 0;
    }
    cancel(reason = '', block = true) {
      const job = this.job;
      this.job = null; this.serial++;
      if (!job) return;
      if (block) this.blocked = job.from;
      job.deck.dispose();
      job.envelope?.restore();
      this.o.status(reason);
    }
    valid(job, state) {
      return state?.allowed && state.account === job.account && state.mode === job.mode &&
        state.queue.some(x => x.uuid === job.to.uuid) &&
        (state.current?.uuid === job.from || (job.phase === 'handoff' && state.current?.uuid === job.to.uuid));
    }
    async prepare(state, target, audio) {
      const job = { from: state.current.uuid, to: target, mode: state.mode, account: state.account,
        phase: 'loading', deck: this.o.deck(target), audio, src: audio.currentSrc || audio.src,
        created: this.o.now(), rate: audio.playbackRate };
      this.job = job;
      try {
        const url = await this.o.resolve(target);
        if (this.job !== job) return;
        job.deck.audio.src = url;
        job.deck.audio.preload = 'auto'; job.deck.audio.load();
        job.phase = 'ready';
      } catch (_) { if (this.job === job) this.cancel('Nie udało się przygotować przejścia. Utwory zagrają kolejno.'); }
    }
    async start(job, duration) {
      job.phase = 'starting'; job.duration = duration; job.started = this.o.now();
      try {
        job.envelope = this.o.envelope(job.audio);
        job.deck.audio.playbackRate = job.audio.playbackRate;
        job.deck.audio.muted = job.audio.muted;
        job.deck.gain(0, job.audio.volume);
        await job.deck.audio.play();
        if (this.job !== job) return;
        if (job.audio.paused || !this.valid(job, this.o.state())) { this.cancel(); return; }
        job.phase = 'overlap'; job.offset = job.deck.audio.currentTime;
        // A delayed play() must not make the fade extend beyond the outgoing track.
        job.duration = Math.min(duration, (job.audio.duration - job.audio.currentTime) / job.rate);
        if (job.duration < 0.3) { this.cancel(); return; }
        this.o.status('Przejście → ' + (job.to.song?.title || job.to.name || 'następny utwór'));
      } catch (_) { if (this.job === job) this.cancel('Przejście pominięte. Utwory zagrają kolejno.'); }
    }
    commit() {
      const job = this.job;
      if (!job || job.phase !== 'overlap') return false;
      job.phase = 'handoff'; job.deadline = this.o.now() + 8000; job.aligned = false;
      job.envelope.set(0); job.deck.gain(1, job.audio.volume);
      job.audio.pause();
      try { this.o.select(job.to.uuid); }
      catch (_) { this.cancel('Nie udało się zmienić utworu.'); }
      return true;
    }
    tick() {
      const state = this.o.state(), audio = this.o.audio(), config = this.o.settings();
      if (!state?.allowed || !config.enabled || !audio || audio !== (this.job?.audio || audio)) {
        this.cancel(); return;
      }
      const uuid = state.current?.uuid;
      if (uuid !== this.lastTrack) { this.lastTrack = uuid; if (!this.job) this.blocked = ''; }
      const job = this.job;
      if (job) {
        if (!this.valid(job, state) || audio.playbackRate !== job.rate || job.deck.audio.error) { this.cancel(); return; }
        job.deck.audio.muted = audio.muted;
        if (job.phase === 'handoff') {
          if (this.o.now() > job.deadline || job.deck.audio.ended) {
            this.cancel('Przekazano odtwarzanie do głównego odtwarzacza.'); return;
          }
          if (((audio.currentSrc || audio.src) !== job.src || state.current?.uuid === job.to.uuid) && audio.readyState >= 2) {
            const position = job.deck.audio.currentTime;
            if (!job.aligned || (!audio.seeking && Math.abs(audio.currentTime - position) > 0.15 && this.o.now() - job.alignedAt > 700)) {
              job.aligned = true; job.alignedAt = this.o.now();
              audio.currentTime = position;
              Promise.resolve(audio.play()).catch(() => { if (this.job === job) this.cancel('Kliknij odtwarzanie, aby kontynuować.'); });
            } else if (job.aligned && !audio.paused && !audio.seeking && audio.readyState >= 3 &&
              state.current?.uuid === job.to.uuid && Math.abs(audio.currentTime - position) <= 0.15) {
              this.cancel('', false);
            }
          }
          return;
        }
        if (audio.paused || audio.ended) { this.cancel(); return; }
        if (job.phase === 'overlap') {
          const incoming = job.deck.audio;
          if (incoming.paused || incoming.readyState < 3 || incoming.ended || audio.readyState < 3) {
            this.cancel('Przejście pominięte z powodu buforowania.'); return;
          }
          const elapsed = (incoming.currentTime - job.offset) / job.rate;
          const progress = elapsed / job.duration;
          const [outGain, inGain] = gains(progress);
          job.envelope.set(outGain); job.deck.gain(inGain, audio.volume);
          if (progress >= 1 || (audio.duration - audio.currentTime) / job.rate < 0.12) this.commit();
        } else if (job.phase === 'starting' && this.o.now() - job.started > 2500) {
          this.cancel('Przejście pominięte. Utwory zagrają kolejno.');
        } else if (job.phase === 'ready') {
          const remaining = (audio.duration - audio.currentTime) / audio.playbackRate;
          const length = Math.min(seconds(config.seconds), audio.duration / audio.playbackRate / 2,
            (job.to.song?.duration || job.to.duration) / audio.playbackRate / 2);
          if (remaining <= length && remaining > 0.5 && job.deck.audio.readyState >= 3 &&
            buffered(job.deck.audio, 0, Math.min(length + 1, job.deck.audio.duration))) this.start(job, remaining);
        }
        return;
      }
      if (audio.paused || audio.ended || audio.seeking || audio.readyState < 3 || !Number.isFinite(audio.duration) ||
        audio.duration < 5 || this.blocked === uuid) return;
      const remaining = (audio.duration - audio.currentTime) / audio.playbackRate;
      if (remaining > Math.max(25, seconds(config.seconds) + 15) || remaining < 0.6) return;
      const target = next(state, this.o.random);
      if (target) this.prepare(state, target, audio);
    }
  }
  const api = { Engine, seconds, music, next, gains, buffered, envelope, streamURL };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.PiterfyCrossfadeCore = api;
})(typeof window === 'object' ? window : globalThis);
