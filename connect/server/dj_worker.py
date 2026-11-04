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
from dj_buffer import BufferedSessions as Sessions, BRIDGES, cached_announcements
from dj_reviews import Reviews
from dj_scripts import compose
from connect_core import Rejected

ROOT = Path(os.environ.get('DJ_STATE_DIR', '/state/dj'))
sessions = Sessions(ROOT)
reviews = Reviews(sessions)
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
    return not sessions.valid(job)


voice_lock=threading.Lock()

def voice(text):
    with voice_lock:
        return generate_voice(text)

def generate_voice(text):
    import hashlib
    key = hashlib.sha256((os.environ.get('DJ_VOICE_ENGINE','multilingual')+'-andrew-v3:' + text).encode()).hexdigest()
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


def block(songs, title, intro, spoken,job=None,history=()):
    plan=compose(songs,title,history)
    announcements=plan['announcements']
    first=voice(plan['text']) if spoken else None
    for index,announcement in enumerate(announcements,1):
        announcement['voiceKey']=voice(announcement['text']) if spoken else None
        if job:sessions.progress(job,'voice',90+round(index/max(1,len(songs)-1)*9),index+1,len(songs))
    return dict(id=uuid.uuid4().hex, title=title, text=plan['text'],openingVariant=plan['openingVariant'],tracks=songs,announcements=announcements,
                voiceKey=first, voiceVersion=6, announced=False,ready=True)


def wait_downloads(sl,jobs,job):
    done=threading.Event()
    def monitor():
        while not done.is_set():
            completed=sum(j['status']=='done' for j in jobs)
            failed=sum(j['status'] not in ('done','pending') for j in jobs)
            sessions.progress(job,'downloading',35+round(35*(completed+failed)/max(1,len(jobs))),completed,len(jobs),failed)
            done.wait(3)
    thread=threading.Thread(target=monitor,daemon=True);thread.start()
    try:music.wait_for_downloads(sl,jobs)
    finally:done.set();thread.join(timeout=1)


def allowed(song):
    return 'podcast' not in str(song.get('genre', '')).lower() and bool(song.get('id'))


def run_job(job):
    state = sessions.state_for(job)
    if cancelled(job):
        sessions.finish(job); return
    nd = music.Navidrome()
    sessions.progress(job,'matching',5)
    seeds = state['seeds']
    heard = {t['id'] for b in state['blocks'] for t in b['tracks']}
    random_songs = music.as_list(nd.call('getRandomSongs', size=120).get('randomSongs', {}).get('song'))
    if not seeds:seeds=[dict(id=s['id'],artist=s.get('artist',''),title=s.get('title',''),genre=s.get('genre','')) for s in random_songs if allowed(s)][:3]
    pool = [s for s in random_songs if allowed(s) and s['id'] not in heard]
    # Apply the exact shared artist/track preferences before selecting library fallback.
    from library_core import Library, taste_scores
    library = Library(os.environ.get('CONNECT_STATE_DIR', '/connect'))
    # The library owner was hashed once by Sessions. Read directly using that existing hash.
    pref_path = library.directory / (job[1] + '.json')
    try: prefs = json.loads(pref_path.read_text())
    except (OSError, ValueError): prefs = {}
    feedback = prefs.get('feedback', {})
    artist_scores = reviews.preferences(job[1])
    track_artists,genre_scores=taste_scores(feedback)
    for artist,score in track_artists.items():artist_scores[artist]=artist_scores.get(artist,0)+score
    pool = [s for s in pool if feedback.get(s['id'], {}).get('vote') != -1]
    seed_artists_known = {music.norm(s['artist']) for s in seeds}
    seed_genres = {s['genre'].lower() for s in seeds if s['genre']}
    pool.sort(key=lambda s: artist_scores.get(music.norm(s.get('artist', '')), 0) - feedback.get(s['id'], {}).get('skips', 0)
              + (2 if music.norm(s.get('artist', '')) in seed_artists_known else 0)
              + (1 if str(s.get('genre', '')).lower() in seed_genres else 0)+genre_scores.get(str(s.get('genre','')).casefold(),0), reverse=True)
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
    sessions.progress(job,'searching',15)
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
            wait_downloads(sl,jobs,job)
        # Share capacity with the daily downloader, not merely this worker's batch.
        transfers = sl.downloads()
        active = [t for t in transfers.values() if not any(end in t.get('state','') for end in ('Completed','Succeeded','Cancelled','Errored','Failed'))]
        if len(active) >= 2: break
        try: queued = music.search_and_queue(sl, candidate)
        except (OSError,RuntimeError,ValueError): break
        jobs.append(queued)
        sessions.progress(job,'searching',15+min(20,len(jobs)*4),len(jobs),5)
        with sessions.db() as db:
            db.execute('UPDATE acquired SET state=?,data=? WHERE key=?',
                       (queued['status'],json.dumps({'artist':candidate['artist'],'title':candidate['title'],'owner':job[1]}),candidate['key']))
    if jobs: wait_downloads(sl,jobs,job)
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
        sessions.progress(job,'importing',70+round(20*len(imported)/max(1,len(jobs))),len(imported),len(jobs))
        if len(imported) >= len([j for j in jobs if j['status'] in ('done', 'pending')]): break
        # Fast incremental scan after beets moves files; do not wait for the daily daemon.
        if time.time()-last_scan >= 90:
            last_scan = time.time()
            if not nd.call('getScanStatus').get('scanStatus',{}).get('scanning'):
                nd.call('startScan')
        time.sleep(15)
    if cancelled(job):sessions.finish(job);return
    if imported:
        # Announce a genre only when a track actually selected through that tag was imported.
        if not any(j.get('via') == genre and nd.find_song(j['artist'],j['title']) for j in jobs): genre = ''
        title = 'Nowe odkrycia' + (' · '+genre if genre else '')
        intro = (random.choice(['Czas na świeże odkrycia! ', 'Okej, teraz ruszamy trochę dalej! ', 'Mam coś nowego do sprawdzenia! ']) + ('Zajrzymy w '+genre+'. ' if genre else '') +
                 'Na początek '+imported[0].get('artist','')[:65]+', i '+imported[0].get('title','')[:65]+'. Daj znać po bloku, czy złapaliśmy Twój klimat. A teraz muzyka!')
        songs=imported
    else:
        songs=[];title='Z Twojej biblioteki'
        intro='Gotowy na kolejny dobry numer? Złapmy ten klimat! '+(songs[0].get('artist','')[:65]+', i utwór '+songs[0].get('title','')[:65]+'. Posłuchajmy!' if songs else '')
    failed=max(0,len(jobs)-len(imported))
    latest=sessions.state_for(job)
    reserved={t['id'] for b in latest.get('blocks',[]) for t in b.get('tracks',[])}
    songs=[s for s in songs if s['id'] not in reserved]
    if songs:
        sessions.progress(job,'voice',90,0,len(songs),failed)
        ready=block(songs,title,intro,state.get('voice',False),job,state['blocks'][-4:]+sessions.script_history(job[1]))
        sessions.finish(job,ready)
        sessions.progress(job,'ready',100,len(imported),len(jobs),failed,complete=True)
    else:
        sessions.finish(job,error='no_new_tracks')
        sessions.progress(job,'unavailable',100,0,len(jobs),failed,complete=True)


WELCOME='Hej! Dobrze Cię słyszeć. Muzyka już czeka, więc zaczynajmy!'
ALTERNATIVE='Zmieńmy muzyczny kierunek. Mam już gotowy inny klimat. Posłuchajmy!'
CONTINUE='Kolejny zestaw jest gotowy. Sprawdźmy, dokąd tym razem zaprowadzi nas muzyka!'
library_snapshot=[]

def library_block(job):
    """No search, download, scan or speech synthesis on the fast preparation lane."""
    state=sessions.state_for(job)
    if cancelled(job):sessions.finish(job);return
    pool=list(library_snapshot)
    if not pool:
        pool=music.as_list(music.Navidrome().call('getRandomSongs',size=160).get('randomSongs',{}).get('song'))
    from library_core import Library, taste_scores
    path=Library(os.environ.get('CONNECT_STATE_DIR','/connect')).directory/(job[1]+'.json')
    try:prefs=json.loads(path.read_text())
    except (OSError,ValueError):prefs={}
    feedback=prefs.get('feedback',{})
    scores=reviews.preferences(job[1])
    track_artists,genre_scores=taste_scores(feedback)
    for artist,score in track_artists.items():scores[artist]=scores.get(artist,0)+score
    heard={t['id'] for b in state['blocks'] for t in b.get('tracks',[])}
    pool=[s for s in pool if allowed(s) and s['id'] not in heard and feedback.get(s['id'],{}).get('vote')!=-1]
    seeds=state.get('seeds',[])
    anchor=next((b for b in state.get('blocks',[]) if b['id']==state.get('currentBlock')),None)
    if not anchor:anchor=next((b for b in state.get('blocks',[]) if not b.get('consumed') and b.get('variant')!='alternative'),None)
    if anchor:seeds=anchor.get('tracks',[])[:5]
    artists={music.norm(s.get('artist','')) for s in seeds}
    genres={s.get('genre','').lower() for s in seeds if s.get('genre')}
    alternative=json.loads(job[4]).get('variant')=='alternative'
    random.shuffle(pool)
    def rank(s):
        artist=music.norm(s.get('artist',''));f=feedback.get(s['id'],{})
        familiarity=(2 if artist in artists else 0)+(1 if s.get('genre','').lower() in genres else 0)
        return scores.get(artist,0)+genre_scores.get(str(s.get('genre','')).casefold(),0)+f.get('vote',0)*2-min(5,f.get('skips',0))*.5+(-familiarity if alternative else familiarity)
    pool=[s for s in pool if scores.get(music.norm(s.get('artist','')),0)>-2]
    pool.sort(key=rank,reverse=True)
    if alternative and genres:
        different=[s for s in pool if s.get('genre') and s['genre'].lower() not in genres]
        if different:
            genre=different[0]['genre'].lower()
            coherent=[s for s in different if s.get('genre','').lower()==genre]
            pool=coherent+[s for s in pool if s not in coherent]
    songs=pool[:5]
    if not songs:sessions.finish(job,error='no_new_tracks');return
    # The generic greeting is generated once in the voice lane. Never wait for TTS here.
    continuation=bool(state.get('currentBlock')) or any(not b.get('consumed') and b.get('variant')!='alternative' for b in state.get('blocks',[]))
    phrase=ALTERNATIVE if alternative else CONTINUE if continuation else WELCOME
    key=generic_keys.get(phrase) or welcome_key
    if not state.get('voice'):key=None
    ready=dict(id=uuid.uuid4().hex,title=('Inny klimat · '+str(songs[0].get('genre') or 'z biblioteki')) if alternative else 'Gotowe do słuchania · z biblioteki',
        tracks=songs,text=phrase if generic_keys.get(phrase) else WELCOME if key else '',voiceKey=key,voiceVersion=0,announced=False,
        announcements=cached_announcements(songs,generic_keys,generic_keys.get(CONTINUE),CONTINUE) if state.get('voice') else [])
    sessions.finish(job,ready)


welcome_key=None
generic_keys={}

def fast_work():
    while True:
        job=sessions.take('library')
        if not job:time.sleep(.2);continue
        try:library_block(job)
        except Exception:sessions.finish(job,error='temporarily_unavailable')


def maintenance():
    global library_snapshot
    refreshed=0
    while True:
        try:
            if time.time()-refreshed>600:
                library_snapshot=[s for s in music.as_list(music.Navidrome().call('getRandomSongs',size=200).get('randomSongs',{}).get('song')) if allowed(s)]
                refreshed=time.time()
            # Feedback from mail, Telegram and either app invalidates stale reserves too.
            from library_core import Library, taste_scores
            import hashlib
            with sessions.db() as db:owners=list(db.execute('SELECT owner,mode,data FROM sessions'))
            for owner,mode,raw in owners:
                state=json.loads(raw)
                path=Library(os.environ.get('CONNECT_STATE_DIR','/connect')).directory/(owner+'.json')
                try:feedback=json.loads(path.read_text()).get('feedback',{})
                except (OSError,ValueError):feedback={}
                stamp=hashlib.sha256(json.dumps([feedback,reviews.preferences(owner)],sort_keys=True).encode()).hexdigest()
                if state.get('tasteStamp')!=stamp:
                    with sessions.db() as db:
                        db.execute('BEGIN IMMEDIATE')
                        latest=json.loads(db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?',(owner,mode)).fetchone()[0])
                        if latest.get('tasteStamp') and latest['tasteStamp']!=stamp:
                            sessions.invalidate(db,owner,mode,latest);sessions.schedule(db,owner,mode,latest)
                        latest['tasteStamp']=stamp;sessions.save(db,owner,mode,latest)
            # Bounded speech cache: retain referenced clips, evict old unreferenced audio first.
            with sessions.db() as db:states=[json.loads(r[0]) for r in db.execute('SELECT data FROM sessions')]
            used={welcome_key,*generic_keys.values()}
            for s in states:
                for b in s.get('blocks',[]):
                    if b.get('preparedAt',0)<time.time()-86400 and b.get('id')!=s.get('currentBlock'):continue
                    used.add(b.get('voiceKey'));used.update(a.get('voiceKey') for a in b.get('announcements',[]))
            files=sorted((p for p in (ROOT/'voice').glob('*.wav') if not p.name.endswith('.tmp.wav')),key=lambda p:p.stat().st_mtime)
            total=sum(p.stat().st_size for p in files)
            for p in files:
                if p.stem not in used and (total>256*1024*1024 or p.stat().st_mtime<time.time()-7*86400):
                    total-=p.stat().st_size;p.unlink(missing_ok=True)
            with sessions.db() as db:
                db.execute("DELETE FROM jobs WHERE created<? AND state NOT IN ('queued','running')",(time.time()-7*86400,))
                db.execute('DELETE FROM acquired WHERE at<?',(time.time()-30*86400,))
        except Exception:pass
        time.sleep(10)


def voice_work():
    global welcome_key
    while True:
        try:
            if not welcome_key:welcome_key=voice(WELCOME)
            for phrase in (WELCOME,ALTERNATIVE,CONTINUE,*BRIDGES):
                if phrase not in generic_keys:
                    key=voice(phrase)
                    if key:generic_keys[phrase]=key
            refresh_voices()
        except Exception:pass
        time.sleep(10)


def refresh_voices():
    """Refresh only two recent blocks; preserve every claim, including concurrent claims."""
    with sessions.db() as db:
        rows=list(db.execute('SELECT owner,mode,data FROM sessions'))
    for owner,mode,raw in rows:
        state=json.loads(raw)
        if state.get('warmUntil',0)<time.time() or not state.get('voice'):continue
        for old in state.get('blocks',[])[-4:]:
            if old.get('voiceVersion')==6 or not old.get('tracks'):continue
            plan=compose(old['tracks'],old.get('title',''),state.get('blocks',[])[-5:]+sessions.script_history(owner))
            opening=None if old.get('announced') else voice(plan['text'])
            previous={a['track']:a for a in old.get('announcements',[])}
            clips=[]
            for announcement in plan['announcements']:
                if previous.get(announcement['track'],{}).get('announced'):continue
                key=voice(announcement['text'])
                if key:clips.append(dict(announcement,voiceKey=key))
            with sessions.db() as db:
                db.execute('BEGIN IMMEDIATE')
                latest=json.loads(db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?',(owner,mode)).fetchone()[0])
                if latest.get('id')!=state.get('id') or latest.get('bufferEpoch',0)!=state.get('bufferEpoch',0):continue
                for current in latest.get('blocks',[]):
                    if current['id']!=old['id']:continue
                    if opening and not current.get('announced'):
                        current.update(text=plan['text'],openingVariant=plan['openingVariant'],voiceKey=opening)
                    existing={a['track']:a for a in current.get('announcements',[])}
                    for clip in clips:
                        if not existing.get(clip['track'],{}).get('announced'):existing[clip['track']]=clip
                    current.update(announcements=list(existing.values()),voiceVersion=6)
                latest['revision']+=1
                db.execute('UPDATE sessions SET data=? WHERE owner=? AND mode=?',(json.dumps(latest,ensure_ascii=False),owner,mode))


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
            if request.get('action') == 'review-page':
                result = {'html':reviews.page(request.get('token'),request.get('vote'))}
            elif request.get('action') == 'complete':
                result = reviews.complete(user,request)
            elif request.get('action') == 'block-feedback':
                if type(request.get('vote')) is not int or request['vote'] not in (-1,0,1):raise Rejected('invalid_vote',400)
                state=sessions.handle(user,{'mode':request.get('mode','dj')})
                reviews.complete(user,dict(request,session=state.get('id')),notify=False)
                with sessions.db() as db:
                    token=db.execute('SELECT token FROM reviews WHERE owner=? AND session=? AND block=?',(sessions.owner(user),state['id'],request.get('block'))).fetchone()[0]
                reviews.resolve(token,request.get('vote'))
                sessions.handle(user,dict(action='invalidate',mode=request.get('mode','dj')))
                result={'ok':True}
            elif request.get('action') == 'list':
                result = {mode: sessions.handle(user, dict(mode=mode)) for mode in ('dj', 'discover')}
            elif request.get('action') == 'audio':
                # Resolve only a clip belonging to this account, never an arbitrary file key.
                state = sessions.handle(user, dict(mode=request.get('mode', 'dj')))
                key = request.get('key')
                if not key or not any(b.get('voiceKey') == key or any(a.get('voiceKey')==key for a in b.get('announcements',[])) for b in state['blocks']): raise Rejected('clip_missing', 404)
                result = {'audio': base64.b64encode((ROOT/'voice'/(key+'.wav')).read_bytes()).decode()}
            else: result = sessions.handle(user, request)
            code = 200
        except Rejected as e: code, result = e.status, {'error': e.reason}
        except (ValueError, KeyError, TypeError, OSError): code, result = 400, {'error': 'invalid_request'}
        payload = json.dumps(result, ensure_ascii=False).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(payload))); self.end_headers(); self.wfile.write(payload)


if __name__ == '__main__':
    from dj_notify import loop
    threading.Thread(target=loop,args=(reviews,music),daemon=True).start()
    threading.Thread(target=maintenance,daemon=True).start()
    threading.Thread(target=voice_work,daemon=True).start()
    threading.Thread(target=fast_work,daemon=True).start()
    threading.Thread(target=work, daemon=True).start()
    ThreadingHTTPServer(('0.0.0.0', 8092), Handler).serve_forever()
