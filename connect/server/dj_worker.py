"""Private DJ worker. Existing acquisition adapters are imported without running their daemon.

Only Connect calls this service. Never expose its port on the host or reverse proxy.
"""
import base64
import importlib.util
import json
import os
import random
import subprocess
import threading
import time
import uuid
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from dj_core import Sessions
from connect_core import Rejected

ROOT = Path(os.environ.get('DJ_STATE_DIR', '/state/dj'))
sessions = Sessions(ROOT)
sys.path.insert(0, '/legacy')
spec = importlib.util.spec_from_file_location('acquisition', '/legacy/discover.py')
music = importlib.util.module_from_spec(spec)
spec.loader.exec_module(music)
# Bound each interactive batch; never invoke run(), mail, playlists, cleanup or daemon state.
music.DOWNLOAD_TIMEOUT = 300
music.MAX_ATTEMPTS = 2
music.SEARCH_PAUSE = max(3, music.SEARCH_PAUSE)
NEIGHBOURS = {'phonk': ['wave', 'drift phonk', 'dark trap'], 'hip-hop': ['trip-hop', 'jazz rap', 'alternative hip-hop'],
              'rap': ['cloud rap', 'jazz rap', 'alternative hip-hop'], 'pop': ['synthpop', 'indie pop', 'dream pop'],
              'rock': ['post-rock', 'indie rock', 'shoegaze'], 'electronic': ['downtempo', 'uk garage', 'ambient'],
              'metal': ['post-metal', 'progressive metal'], 'jazz': ['nu jazz', 'jazz fusion']}


class InteractiveSlskd(music.Slskd):
    """A short search budget; daily searches retain their existing retry policy."""
    def search(self, query):
        time.sleep(max(0, self.last_search+music.SEARCH_PAUSE-time.time()))
        self.last_search=time.time(); identity=str(uuid.uuid4())
        try:
            self.api('POST','searches',body={'id':identity,'searchText':query,'searchTimeout':15000,
                'responseLimit':100,'fileLimit':10000,'filterResponses':True})
            deadline=time.time()+25
            while time.time()<deadline:
                time.sleep(2)
                state=self.api('GET','searches/'+identity)
                if state.get('isComplete') or 'Completed' in str(state.get('state','')): break
            return self.api('GET','searches/'+identity+'/responses') or []
        finally:
            try:self.api('DELETE','searches/'+identity)
            except OSError:pass


def cancelled(job):
    state = sessions.state_for(job)
    return not state.get('active') or state.get('id') != job[3]


def voice(text):
    import hashlib
    key = hashlib.sha256(('pl-darkman-v1:' + text).encode()).hexdigest()
    folder = ROOT / 'voice'; folder.mkdir(exist_ok=True)
    target = folder / (key + '.wav')
    if not target.exists():
        temp = folder / (key + '.tmp.wav')
        # A separate process bounds memory and generation time. Failure is music-only fallback.
        try:
            subprocess.run(['python', '/app/dj_voice.py', str(temp)], input=text.encode(),
                           check=True, timeout=45, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            temp.replace(target)
        except (OSError, subprocess.SubprocessError):
            temp.unlink(missing_ok=True)
            return None
    return key


def block(songs, title, intro, spoken):
    return dict(id=uuid.uuid4().hex, title=title, text=intro, tracks=songs,
                voiceKey=voice(intro) if spoken else None, announced=False)


def allowed(song):
    return 'podcast' not in str(song.get('genre', '')).lower() and bool(song.get('id'))


def run_job(job):
    state = sessions.state_for(job)
    if cancelled(job):
        sessions.finish(job); return
    nd = music.Navidrome()
    seeds = state['seeds']
    heard = {t['id'] for b in state['blocks'] for t in b['tracks']}
    random_songs = music.as_list(nd.call('getRandomSongs', size=120).get('randomSongs', {}).get('song'))
    pool = [s for s in random_songs if allowed(s) and s['id'] not in heard]
    # Apply the exact shared artist/track preferences before selecting library fallback.
    from library_core import Library
    library = Library(os.environ.get('CONNECT_STATE_DIR', '/connect'))
    # The library owner was hashed once by Sessions. Read directly using that existing hash.
    pref_path = library.directory / (job[1] + '.json')
    try: prefs = json.loads(pref_path.read_text())
    except (OSError, ValueError): prefs = {}
    feedback = prefs.get('feedback', {})
    artist_scores = {}
    for row in feedback.values():
        artist_scores[row['artist']] = artist_scores.get(row['artist'], 0) + row.get('vote', 0)*2 - min(5, row.get('skips', 0))*.25
    pool = [s for s in pool if feedback.get(s['id'], {}).get('vote') != -1]
    seed_artists_known = {music.norm(s['artist']) for s in seeds}
    seed_genres = {s['genre'].lower() for s in seeds if s['genre']}
    pool.sort(key=lambda s: artist_scores.get(music.norm(s.get('artist', '')), 0) - feedback.get(s['id'], {}).get('skips', 0)
              + (2 if music.norm(s.get('artist', '')) in seed_artists_known else 0)
              + (1 if str(s.get('genre', '')).lower() in seed_genres else 0), reverse=True)
    if pool:
        songs = pool[:5]
        intro = 'To Piterfy DJ. Ten blok otwiera '+songs[0].get('artist','')[:65]+', utworem '+songs[0].get('title','')[:65]+'. W tle przygotowuję nowe odkrycia dopasowane do Twoich wyborów.'
        sessions.finish(job, block(songs, 'Blisko Twojego gustu', intro, state.get('voice', False)), final=False)
    if cancelled(job):
        sessions.finish(job); return
    seed_tracks = [dict(artist=s['artist'], title=s['title'], weight=1) for s in seeds if s['artist'] and s['title']][:3]
    seed_artists = [dict(name=s['artist'], weight=1) for s in seeds if s['artist']][:3]
    candidates = music.recommend(seed_tracks, seed_artists)
    genre = ''
    if state['exploration'] >= 35 and music.LASTFM_API_KEY:
        genres = [s['genre'].lower() for s in seeds if s['genre'] and 'podcast' not in s['genre'].lower()]
        base = next((k for k in NEIGHBOURS if any(k in g for g in genres)), None)
        if base:
            choices = NEIGHBOURS[base]
            genre = choices[len(state['blocks']) % len(choices)]
            rows = music.LastFm().call('tag.getTopTracks', tag=genre, limit=30).get('tracks', {}).get('track', [])
            extras = [dict(artist=t['artist']['name'], title=t['name'], score=2, duration=None,
                           via=genre, key=music.track_key(t['artist']['name'], t['name'])) for t in music.as_list(rows)]
            random.shuffle(extras)
            take = max(1, round(5 * state['exploration']/100))
            candidates = extras[:take] + candidates
    candidates = [c for c in candidates if artist_scores.get(music.norm(c['artist']), 0) > -2]
    # Global cooldown and one worker prevent duplicate interactive searches and downloads.
    jobs = []
    sl = InteractiveSlskd()
    search_deadline=time.time()+240
    for candidate in candidates[:20]:
        if cancelled(job) or len(jobs) >= 5 or time.time()>search_deadline: break
        if nd.find_song(candidate['artist'], candidate['title']): continue
        with sessions.db() as db:
            old = db.execute('SELECT at FROM acquired WHERE key=?', (candidate['key'],)).fetchone()
            if old and time.time()-old[0] < 2*86400: continue
            db.execute('INSERT OR REPLACE INTO acquired VALUES(?,?,?,?)', (candidate['key'], time.time(), 'searching', '{}'))
        if len([j for j in jobs if j['status'] == 'pending']) >= 2:
            music.wait_for_downloads(sl, jobs)
        # Share capacity with the daily downloader, not merely this worker's batch.
        transfers = sl.downloads()
        active = [t for t in transfers.values() if not any(end in t.get('state','') for end in ('Completed','Succeeded','Cancelled','Errored','Failed'))]
        if len(active) >= 2: break
        try: queued = music.search_and_queue(sl, candidate)
        except (OSError,RuntimeError,ValueError): break
        jobs.append(queued)
        with sessions.db() as db:
            db.execute('UPDATE acquired SET state=?,data=? WHERE key=?',
                       (queued['status'],json.dumps({'artist':candidate['artist'],'title':candidate['title'],'owner':job[1]}),candidate['key']))
    if jobs: music.wait_for_downloads(sl, jobs)
    # Import is owned by the existing beets service. Only indexed tracks can enter a queue.
    deadline = time.time()+300
    imported = []
    last_scan = 0
    while time.time() < deadline and jobs and not cancelled(job):
        imported = []
        for acquired in jobs:
            identifier = nd.find_song(acquired['artist'], acquired['title'])
            if identifier:
                song = nd.call('getSong', id=identifier).get('song', {})
                if allowed(song): imported.append(song)
        if len(imported) >= len([j for j in jobs if j['status'] in ('done', 'pending')]): break
        # Fast incremental scan after beets moves files; do not wait for the daily daemon.
        if time.time()-last_scan >= 90:
            last_scan = time.time()
            if not nd.call('getScanStatus').get('scanStatus',{}).get('scanning'):
                nd.call('startScan')
        time.sleep(15)
    if imported:
        # Announce a genre only when a track actually selected through that tag was imported.
        if not any(j.get('via') == genre and nd.find_song(j['artist'],j['title']) for j in jobs): genre = ''
        title = 'Nowe odkrycia' + (' · '+genre if genre else '')
        intro = ('Teraz nowe odkrycia. ' + ('Sprawdzimy gatunek '+genre+'. ' if genre else '') +
                 'Zaczyna '+imported[0].get('artist','')[:65]+', utworem '+imported[0].get('title','')[:65]+'. Jeśli Ci się spodoba, wybierz więcej takich.')
        sessions.finish(job, block(imported, title, intro, state.get('voice', False)))
    else:
        sessions.finish(job, error='no_new_tracks')


def work():
    # Interrupted jobs are not retried blindly: acquisition records retain their cooldown.
    with sessions.db() as db:
        interrupted=list(db.execute("SELECT id,owner,mode,session FROM jobs WHERE state='running'"))
    for job in interrupted:sessions.finish(job,error='interrupted')
    while True:
        job = sessions.take()
        if not job: time.sleep(2); continue
        try: run_job(job)
        except Exception:
            sessions.finish(job, error='temporarily_unavailable')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_POST(self):
        try:
            size = int(self.headers.get('Content-Length', 0))
            if not 0 < size <= 16384: raise Rejected('body_limit', 413)
            body = json.loads(self.rfile.read(size))
            user = body['user']; request = body['request']
            if not isinstance(user, str) or not user or len(user)>512: raise Rejected('account_required', 400)
            if not isinstance(request, dict): raise Rejected('invalid_request', 400)
            if request.get('action') == 'list':
                result = {mode: sessions.handle(user, dict(mode=mode)) for mode in ('dj', 'discover')}
            elif request.get('action') == 'audio':
                # Resolve only a clip belonging to this account, never an arbitrary file key.
                state = sessions.handle(user, dict(mode=request.get('mode', 'dj')))
                key = request.get('key')
                if not key or not any(b.get('voiceKey') == key for b in state['blocks']): raise Rejected('clip_missing', 404)
                result = {'audio': base64.b64encode((ROOT/'voice'/(key+'.wav')).read_bytes()).decode()}
            else: result = sessions.handle(user, request)
            code = 200
        except Rejected as e: code, result = e.status, {'error': e.reason}
        except (ValueError, KeyError, TypeError, OSError): code, result = 400, {'error': 'invalid_request'}
        payload = json.dumps(result, ensure_ascii=False).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)


if __name__ == '__main__':
    threading.Thread(target=work, daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0', 8092), Handler).serve_forever()
