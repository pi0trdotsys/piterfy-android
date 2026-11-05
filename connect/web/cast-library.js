(function (root) {
  'use strict';
  const namespace = 'urn:x-cast:eu.prosinski.piterfy.library';
  const pageSize = 20;

  function init(context) {
    const doc = root.document;
    const $ = id => doc.getElementById(id);
    const panel = $('library');
    const content = $('library-content');
    const navigation = [...doc.querySelectorAll('.library-nav button')];
    const tabs = navigation.filter(button => button.dataset.view);
    const title = $('library-title');
    const status = $('library-status');
    const headings = { lyrics: 'Tekst utworu', queue: 'Kolejka', playlists: 'Playlisty' };
    let view = '', playlistId = '', offset = 0, count = 0, senderId = '', requestId = 0;
    let timer, track, trackKey = '', lyrics = null, activeLine = -1, rowsKey = '';

    function setStatus(message) {
      if (status.textContent !== message) status.textContent = message;
    }

    function preview(type, options) {
      const sample = [
        { id: 'night', title: 'Nocna panorama', subtitle: 'Piterfy Sessions', index: 0, playing: true },
        { id: 'lights', title: 'Światła miasta', subtitle: 'Piterfy Sessions', index: 1 },
        { id: 'dawn', title: 'Przed świtem', subtitle: 'Piterfy Sessions', index: 2 },
      ];
      if (type === 'lyrics') return { synced: true, lines: [
        { ms: 80000, text: 'Nad miastem zapalają się światła' },
        { ms: 90000, text: 'Noc prowadzi nas przez puste ulice' },
        { ms: 100000, text: 'Jeszcze chwila, zanim wstanie dzień' },
      ] };
      if (type === 'playlists') return { count: 2, rows: [
        { id: 'drive', title: 'W drodze', subtitle: '3 utwory' },
        { id: 'quiet', title: 'Spokojny wieczór', subtitle: '3 utwory' },
      ] };
      if (type === 'playlist') return { title: options.playlistId === 'drive' ? 'W drodze' : 'Spokojny wieczór', count: 3, rows: sample };
      if (type === 'queue') return { count: 3, rows: sample };
      return { previewOnly: true };
    }

    function request(type, options = {}, quiet = false) {
      const id = ++requestId;
      root.clearTimeout(timer);
      if (!quiet) setStatus('Wczytuję…');
      if (!context) {
        apply(type, preview(type, options));
        return;
      }
      const senders = context.getSenders();
      if (!senders.some(sender => sender.id === senderId)) senderId = '';
      const sender = senderId || senders[0]?.id;
      if (!sender) {
        setStatus('Połącz Piterfy na telefonie.');
        return;
      }
      pendingType = type;
      try {
        context.sendCustomMessage(namespace, sender, { ...options, type, requestId: id });
        timer = root.setTimeout(() => {
          if (id === requestId) setStatus('Telefon nie odpowiedział. Otwórz aktualne Piterfy i spróbuj ponownie.');
        }, 18000);
      } catch (_) {
        setStatus('Brak połączenia z telefonem.');
      }
    }

    let pendingType = '';
    context?.addCustomMessageListener(namespace, event => {
      let data = event.data;
      if (typeof data === 'string') {
        try { data = JSON.parse(data); } catch (_) { return; }
      }
      if (data?.type === 'ready') {
        if (senderId === event.senderId) return;
        senderId = event.senderId;
        if (view) load();
      } else if (data?.requestId === requestId && (!senderId || senderId === event.senderId)) {
        root.clearTimeout(timer);
        apply(pendingType, data);
      }
    });

    function load(quiet = false) {
      if (view === 'lyrics' && !track) {
        setStatus('Uruchom utwór, aby wyświetlić tekst.');
        return;
      }
      const type = view === 'playlists' && playlistId ? 'playlist' : view;
      if (type) request(type, { offset, playlistId }, quiet);
    }

    function open(next) {
      view = next;
      playlistId = '';
      offset = count = 0;
      lyrics = null;
      activeLine = -1;
      rowsKey = '';
      content.replaceChildren();
      content.scrollTop = 0;
      $('library-pages').hidden = true;
      title.textContent = headings[next];
      panel.hidden = !next;
      const main = doc.querySelector('main');
      main.classList.toggle('library-open', !!next && next !== 'lyrics');
      main.classList.toggle('lyrics-open', next === 'lyrics');
      tabs.forEach(tab => tab.setAttribute('aria-expanded', String(tab.dataset.view === next)));
      if (next) {
        load();
        $('library-back').focus();
      } else {
        requestId++;
        root.clearTimeout(timer);
      }
    }

    function drawLyrics() {
      if (!lyrics?.synced || !track) return;
      let index = -1;
      const position = track.position * 1000 + (Number(lyrics.offset) || 0);
      for (const [i, line] of lyrics.lines.entries()) {
        if (Number.isFinite(line.ms) && line.ms <= position) index = i;
      }
      if (activeLine === index) return;
      content.children[activeLine]?.classList.remove('current-line');
      activeLine = index;
      const line = content.children[index];
      if (line) {
        line.classList.add('current-line');
        content.scrollTop = line.offsetTop - content.offsetTop - content.clientHeight / 3;
      }
    }

    function apply(type, data) {
      if (!view) return;
      if (data.error) return setStatus(data.error);
      if (data.previewOnly) return setStatus('W podglądzie można przeglądać listę. Odtwarzanie jest dostępne po połączeniu z telefonem.');
      if (type === 'queueJump' || type === 'playlistPlay') {
        open('queue');
        return;
      }
      if (type === 'lyrics') {
        lyrics = { ...data, lines: data.lines || [] };
        const lines = lyrics.lines.length ? lyrics.lines : (data.text || '').split('\n').filter(Boolean).map(text => ({ text }));
        content.replaceChildren(...lines.map(line => {
          const item = doc.createElement('p');
          item.className = 'lyric-line';
          item.textContent = line.text;
          return item;
        }));
        activeLine = -1;
        content.scrollTop = 0;
        drawLyrics();
        setStatus(lines.length ? '' : data.status || 'Brak tekstu dla tego utworu.');
        return;
      }
      const rows = data.rows || [];
      const key = JSON.stringify([type, offset, rows]);
      count = data.count || 0;
      if (data.title) title.textContent = data.title;
      setStatus(rows.length ? '' : view === 'queue' ? 'Kolejka jest pusta.' : 'Brak pozycji do wyświetlenia.');
      $('library-pages').hidden = count <= pageSize;
      $('library-page').textContent = `${Math.floor(offset / pageSize) + 1} / ${Math.ceil(count / pageSize)}`;
      $('library-prev').disabled = offset === 0;
      $('library-next').disabled = offset + pageSize >= count;
      if (key === rowsKey) return;
      rowsKey = key;
      const focused = [...content.children].indexOf(doc.activeElement);
      const scroll = content.scrollTop;
      content.replaceChildren(...rows.map(row => {
        const button = doc.createElement('button');
        button.className = 'library-row';
        button.type = 'button';
        const name = doc.createElement('strong');
        name.textContent = row.title || 'Bez tytułu';
        const subtitle = doc.createElement('span');
        subtitle.textContent = row.playing ? 'Teraz odtwarzane' : row.subtitle || '';
        if (row.playing) button.setAttribute('aria-current', 'true');
        button.append(name, subtitle);
        button.onclick = () => {
          if (type === 'playlists') {
            playlistId = row.id;
            title.textContent = row.title;
            offset = 0;
            content.replaceChildren();
            $('library-back').focus();
            load();
          } else {
            request(type === 'queue' ? 'queueJump' : 'playlistPlay', { playlistId, index: row.index, id: row.id });
          }
        };
        return button;
      }));
      content.scrollTop = scroll;
      if (focused >= 0) (content.children[focused] || $('library-back')).focus();
    }

    function back() {
      if (view === 'playlists' && playlistId) {
        open('playlists');
      } else {
        const tab = tabs.find(button => button.dataset.view === view);
        open('');
        tab?.focus();
      }
    }

    tabs.forEach(tab => { tab.onclick = () => view === tab.dataset.view ? back() : open(tab.dataset.view); });
    $('library-back').onclick = back;
    $('library-prev').onclick = () => { offset = Math.max(0, offset - pageSize); content.scrollTop = 0; load(); };
    $('library-next').onclick = () => { if (offset + pageSize < count) { offset += pageSize; content.scrollTop = 0; load(); } };
    doc.addEventListener('keydown', event => {
      const active = doc.activeElement;
      const tabIndex = navigation.indexOf(active);
      if (tabIndex >= 0 && ['ArrowLeft', 'ArrowRight'].includes(event.key)) {
        const index = tabIndex + (event.key === 'ArrowLeft' ? -1 : 1);
        navigation[Math.max(0, Math.min(navigation.length - 1, index))].focus();
        event.preventDefault();
      } else if (!view && event.key === 'ArrowDown' && active.closest('#controls')) {
        tabs[0].focus();
        event.preventDefault();
      } else if (!view && tabIndex >= 0 && event.key === 'ArrowUp' && !$('controls').hidden) {
        $('play').focus();
        event.preventDefault();
      } else if (view && event.key === 'Escape') {
        back();
        event.preventDefault();
      } else if (view && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
        if (view === 'lyrics' && active === $('library-back') && event.key === 'ArrowUp' && !$('controls').hidden) {
          $('play').focus();
          event.preventDefault();
          return;
        }
        if (active === content && view === 'lyrics') {
          if (event.key === 'ArrowUp' && content.scrollTop === 0) $('library-back').focus();
          else if (event.key === 'ArrowDown' && content.scrollTop + content.clientHeight >= content.scrollHeight - 1) tabs[0].focus();
          else content.scrollTop += event.key === 'ArrowDown' ? 100 : -100;
        } else {
          const nodes = [$('library-back'), ...(view === 'lyrics' ? [content] : content.children),
            ...(!$('library-pages').hidden ? [$('library-prev'), $('library-next')].filter(button => !button.disabled) : []), ...navigation];
          const index = nodes.indexOf(active) + (event.key === 'ArrowDown' ? 1 : -1);
          nodes[Math.max(0, Math.min(nodes.length - 1, index))].focus();
        }
        event.preventDefault();
      }
    });

    return {
      update(next) {
        track = next;
        const key = next ? next.identity || [next.title, next.artist, next.album].join('\u0000') : '';
        if (key !== trackKey) {
          trackKey = key;
          if (view === 'lyrics') {
            requestId++;
            root.clearTimeout(timer);
            lyrics = null;
            content.replaceChildren();
            load();
          } else if (view === 'queue') load(true);
        }
        if (view === 'lyrics') drawLyrics();
      },
    };
  }
  root.PiterfyCastLibrary = { init };
})(window);
