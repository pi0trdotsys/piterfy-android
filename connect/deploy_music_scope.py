"""Patch the existing Piterfy shell, preserving its other features and backing up first."""
from pathlib import Path
import datetime,re,shutil,subprocess

def patch(source,core,catalog):
    marker='  // piterfy-media-kinds-start\n'
    source=re.sub(r'  // piterfy-media-kinds-start\n.*?  // piterfy-media-kinds-end\n','',source,flags=re.S)
    anchor='  const podcasts = '
    assert source.count(anchor)==1
    source=source.replace(anchor,marker+core+'\n  // piterfy-media-kinds-end\n'+anchor,1)
    start=source.index('  const isPodcastSong = ');end=source.index('\n\n',start)
    source=source[:start]+"  const isPodcastSong = (s) => window.PiterfyMediaKinds.isPodcast(s) || !!s && podcasts.albums.has(s.albumId);"+source[end:]
    if '  // piterfy-music-catalog-start' in source:
        source=re.sub(r'  // piterfy-music-catalog-start.*?  // piterfy-music-catalog-end',lambda _:catalog.strip('\n'),source,flags=re.S)
    else:
        start=source.index('  function ensurePodcasts()');end=source.index('  // Widoki muzyczne',start)
        source=source[:start]+catalog+'\n'+source[end:]
    source=source.replace('function likedSongs() {\n    const q = liked.query.trim().toLowerCase();\n    const all = (liked.data && liked.data.songs) || [];',
        'function likedSongs() {\n    const state=activeSongState();\n    const q = state.query.trim().toLowerCase();\n    const all = (state.data && state.data.songs) || [];')
    source=source.replace('    return songs.map((s, i) =>', '    const visible=root.dataset.pfRoute===\"utwory\"?songs.slice(0,musicTracks.visible):songs;\n    return visible.map((s, i) =>')
    # Only the likedRows function gets a load-more control.
    start=source.index('  function likedRows()');end=source.index('  function renderLiked(',start)
    part=source[start:end]
    if 'music-more' not in part:
        assert part.count('.join("");')==1
        part=part.replace('.join("");','.join("")+(root.dataset.pfRoute===\"utwory\"&&songs.length>visible.length?\'<button type="button" class="pf-btn-ghost" data-pf-act="music-more">Pokaż kolejne 100</button>\':\"\");')
    part=part.replace('liked.query','activeSongState().query')
    part=part.replace('"Nie masz jeszcze polubionych utworów. Kliknij serduszko przy utworze — trafi tutaj (podcasty nie trafiają do Ulubionych)."',
        '(root.dataset.pfRoute===\"utwory\"?\"Brak utworów muzycznych. Podcasty znajdziesz w osobnej kategorii.\":\"Nie masz jeszcze polubionych utworów. Kliknij serduszko przy utworze — trafi tutaj.\")')
    source=source[:start]+part+source[end:]
    source=source.replace('<h1>Ulubione</h1>', '<h1>\' + (root.dataset.pfRoute===\"utwory\"?\"Utwory\":\"Ulubione\") + \'</h1>')
    source=source.replace('esc(liked.query)','esc(activeSongState().query)').replace('Szukaj w ulubionych','Szukaj utworu')
    if 'utwory: { state: musicTracks' not in source:
        anchor='  const PAGES = {';assert source.count(anchor)==1
        source=source.replace(anchor,anchor+'\n    utwory: { state: musicTracks, ttl:120000, load:loadMusicTracks, render:renderLiked, sig:d=>d.songs.map(s=>s.id).join() },',1)
    source=source.replace('if (!target || root.dataset.pfRoute !== "ulubione") return;', 'if (!target || !["ulubione","utwory"].includes(root.dataset.pfRoute)) return;')
    if 'if(act===\"music-more\")' not in source:
        source=source.replace('    if (act) playSongs(likedSongs(), null, act === "liked-shuffle");',
            '    if(act===\"music-more\"){musicTracks.visible+=100;document.querySelector(\".pf-songs\").innerHTML=likedRows();return;}\n    if (act) playSongs(likedSongs(), null, act === "liked-shuffle");')
    source=source.replace('    liked.query = e.target.value;', '    activeSongState().query = e.target.value;musicTracks.visible=100;')
    source=source.replace('    const list = songs.slice();','    const list = songs.filter(s=>!isPodcastSong(s));\n    if(!list.length)return;')
    if 'location.replace("#/utwory")' not in source:
        source=source.replace('      addStyles();','      if(/^#\\/song(?:\\?.*)?$/.test(location.hash)){location.replace("#/utwory");return;}\n      addStyles();',1)
    source=source.replace('const chips = [["Muzyka", HOME, true], ["Podcasty"', 'const chips = [["Muzyka", HOME, true], ["Utwory", "#/utwory"], ["Podcasty"')
    assert 'utwory: { state: musicTracks' in source and 'window.PiterfyMediaKinds.isPodcast(s)' in source
    return source

if __name__=='__main__':
    stage=Path('/home/user/piterfy-stack/discover/connectserver');ui=stage.parent/'navidrome-ui';main=ui/'piterfy.js'
    source=patch(main.read_text('utf-8'),(stage/'media-kind-core.js').read_text('utf-8'),(stage/'music-catalog.inc.js').read_text('utf-8'))
    backup=stage/('before-music-scope-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));backup.mkdir();shutil.copy2(main,backup/main.name)
    candidate=stage/'music-scope-candidate.js';candidate.write_text(source,'utf-8')
    # Node may be absent on the server; the candidate was syntax-checked before upload.
    main.write_text(source,'utf-8')
    try:subprocess.run(['python3',str(stage/'deploy_library.py')],check=True)
    except Exception:shutil.copy2(backup/main.name,main);raise
    print('Music/podcast scope deployed; backup:',backup)
