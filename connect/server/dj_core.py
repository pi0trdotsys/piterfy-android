"""Durable account-scoped DJ sessions and bounded acquisition jobs. No credentials."""
import hashlib
import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from connect_core import Rejected
from library_core import number, text


class Sessions:
    def __init__(self, directory):
        Path(directory).mkdir(parents=True, exist_ok=True)
        self.path = str(Path(directory) / 'dj.sqlite')
        with self.db() as db:
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS sessions(owner TEXT, mode TEXT, data TEXT,
                    PRIMARY KEY(owner, mode));
                CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, owner TEXT, mode TEXT,
                    session TEXT, created REAL, state TEXT, data TEXT);
                CREATE TABLE IF NOT EXISTS acquired(key TEXT PRIMARY KEY, at REAL, state TEXT, data TEXT);
                CREATE TABLE IF NOT EXISTS block_archive(owner TEXT, session TEXT, block TEXT, data TEXT, created REAL,
                    PRIMARY KEY(owner,session,block));
                CREATE TABLE IF NOT EXISTS speech_memory(owner TEXT, key TEXT, data TEXT, at REAL,
                    PRIMARY KEY(owner,key));
            ''')

    @contextmanager
    def db(self):
        connection = sqlite3.connect(self.path, timeout=10)
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    @staticmethod
    def owner(user):
        return hashlib.sha256(user.encode()).hexdigest()

    def script_history(self, owner):
        with self.db() as db:
            rows=db.execute('SELECT data FROM speech_memory WHERE owner=? AND at>? ORDER BY at DESC LIMIT 80',(owner,time.time()-30*86400)).fetchall()
        return [json.loads(row[0]) for row in reversed(rows)]

    def handle(self, user, body):
        owner = self.owner(user)
        mode = body.get('mode', 'dj')
        if mode not in ('dj', 'discover'):
            raise Rejected('invalid_mode', 400)
        action = body.get('action', 'get')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?', (owner, mode)).fetchone()
            state = json.loads(row[0]) if row else {'active': False, 'mode': mode, 'blocks': [], 'voice': mode == 'dj', 'revision': 0}
            if action == 'start':
                seeds = body.get('seeds', [])
                if not isinstance(seeds, list) or not 1 <= len(seeds) <= 12:
                    raise Rejected('seeds_required', 400)
                clean = []
                for seed in seeds:
                    if not isinstance(seed, dict):
                        raise Rejected('invalid_seed', 400)
                    clean.append({k: text(seed.get(k, ''), 200) for k in ('id', 'artist', 'title', 'genre')})
                if not any(s['artist'] for s in clean):
                    raise Rejected('artist_required', 400)
                # A repeated start resumes instead of spending another download budget.
                if not state['active']:
                    state = dict(active=True, id=uuid.uuid4().hex, mode=mode, seeds=clean, blocks=[],
                                 summaryEmail=state.get('summaryEmail',True), summaryTelegram=state.get('summaryTelegram',True),
                                 exploration=int(number(body.get('exploration', 50), 0, 100)),
                                 voice=state.get('voice', mode == 'dj'), talkLevel=state.get('talkLevel','radio'), revision=state['revision'] + 1, updated=time.time())
                else:
                    state['seeds'] = clean
                    state['exploration'] = int(number(body.get('exploration', state['exploration']), 0, 100))
            elif action == 'stop':
                for block in state.get('blocks',[]):
                    db.execute('INSERT OR REPLACE INTO block_archive VALUES(?,?,?,?,?)',(owner,state['id'],block['id'],json.dumps(dict(block=block,mode=mode,summaryEmail=state.get('summaryEmail',True),summaryTelegram=state.get('summaryTelegram',True)),ensure_ascii=False),time.time()))
                db.execute('DELETE FROM block_archive WHERE created<?',(time.time()-30*86400,))
                state['active'] = False
                db.execute("UPDATE jobs SET state='cancelled' WHERE owner=? AND mode=? AND state='queued'", (owner, mode))
            elif action == 'settings':
                if 'talkLevel' in body:
                    if body['talkLevel'] not in ('sparse','balanced','radio'):raise Rejected('invalid_talk_level',400)
                    state['talkLevel']=body['talkLevel']
                for setting in ('summaryEmail','summaryTelegram'):
                    if setting in body:
                        if type(body[setting]) is not bool: raise Rejected('invalid_setting',400)
                        state[setting]=body[setting]
                if 'voice' in body:
                    if not isinstance(body['voice'], bool):
                        raise Rejected('invalid_voice', 400)
                    state['voice'] = body['voice']
                if 'exploration' in body:
                    state['exploration'] = int(number(body['exploration'], 0, 100))
            elif action == 'claim':
                block = next((b for b in state['blocks'] if b['id'] == body.get('block')), None)
                if not block:
                    raise Rejected('block_missing', 404)
                # Claim is durable before delivery: transfers never replay a spoken introduction.
                clip=block
                track=body.get('track')
                if track and track!=block.get('tracks',[{}])[0].get('id'):
                    clip=next((a for a in block.get('announcements',[]) if a['track']==track),None)
                    if clip is None:raise Rejected('announcement_missing',404)
                result = state['active'] and block.get('ready',True) and not clip.get('announced', False) and state.get('voice', False)
                track_index=next((i for i,song in enumerate(block.get('tracks',[])) if song.get('id')==track),0)
                talk=state.get('talkLevel','radio')
                result=result and (track_index==0 or talk=='radio' or talk=='balanced' and track_index%2==0)
                if state['active'] and block.get('ready',True) and state.get('voice',False) and not clip.get('announced',False) and not result:
                    # Suppressed speech is consumed too, so a later poll/receiver cannot
                    # repeatedly pause music trying to claim the same quiet transition.
                    clip['announced']=True
                    clip['skippedByTalkLevel']=True
                if result:
                    clip['announced'] = True
                    variant=clip.get('variant') or clip.get('openingVariant') or 'cached:'+hashlib.sha256(clip['text'].encode()).hexdigest()[:16]
                    heard=dict(openingVariant=variant,lastTrack=(block.get('tracks') or [{}])[track_index],text=clip['text'])
                    db.execute('INSERT OR REPLACE INTO speech_memory VALUES(?,?,?,?)',(owner,variant,json.dumps(heard,ensure_ascii=False),time.time()))
                    db.execute('DELETE FROM speech_memory WHERE owner=? AND key NOT IN (SELECT key FROM speech_memory WHERE owner=? ORDER BY at DESC LIMIT 80)',(owner,owner))
                db.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?)', (owner, mode, json.dumps(state)))
                return {'speak': result, 'text': clip['text'] if result else '', 'voiceKey': clip.get('voiceKey') if result else None}
            elif action not in ('get', 'refill', 'next'):
                raise Rejected('unknown_action', 400)
            if action in ('start', 'refill', 'next') and state['active']:
                busy = db.execute("SELECT 1 FROM jobs WHERE owner=? AND mode=? AND state IN ('queued','running')", (owner, mode)).fetchone()
                recent = db.execute("SELECT COUNT(*) FROM jobs WHERE owner=? AND created>?", (owner, time.time()-3600)).fetchone()[0]
                if not busy and recent < 8:
                    db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?)', (uuid.uuid4().hex, owner, mode, state['id'], time.time(), 'queued', '{}'))
                    state['status'] = 'queued'
                    state['progress'] = dict(percent=0,stage='queued',completed=0,total=0,complete=False)
                elif not busy:
                    state['status'] = 'hourly_limit'
                    cached=any(b.get('ready',True) for b in state['blocks'])
                    state['progress']=dict(stage='ready' if cached else 'hourly_limit',percent=100 if cached else 0,completed=0,total=0,failed=0,complete=cached,cached=cached)
            if action != 'get':
                state['revision'] += 1
                db.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?)', (owner, mode, json.dumps(state, ensure_ascii=False)))
            return state

    def progress(self,job,stage,percent,completed=0,total=0,failed=0,complete=False):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            state=self.state_for(job)
            if state.get('id')!=job[3] or not state.get('active'):return
            row=db.execute('SELECT state FROM jobs WHERE id=?',(job[0],)).fetchone()
            if row and row[0] not in ('queued','running') and not complete:return
            state['progress']=dict(stage=stage,percent=percent,completed=completed,total=total,failed=failed,complete=complete)
            state['revision']+=1
            db.execute('UPDATE sessions SET data=? WHERE owner=? AND mode=?',(json.dumps(state,ensure_ascii=False),job[1],job[2]))

    def take(self):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT id,owner,mode,session FROM jobs WHERE state='queued' ORDER BY created LIMIT 1").fetchone()
            if row:
                db.execute("UPDATE jobs SET state='running' WHERE id=?", (row[0],))
            return row

    def state_for(self, job):
        with self.db() as db:
            row = db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?', (job[1], job[2])).fetchone()
            return json.loads(row[0]) if row else {}

    def finish(self, job, block=None, error=None, final=True):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            state = json.loads(db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?', (job[1], job[2])).fetchone()[0])
            if final:
                db.execute('UPDATE jobs SET state=? WHERE id=?', ('failed' if error else 'done', job[0]))
            if state.get('id') != job[3] or not state['active']:
                return
            if block:
                block['ready']=final
                state['blocks'] = (state['blocks'] + [block])[-30:]
            state['status'] = error or ('ready' if final else 'downloading')
            if error:
                state['progress']=dict(stage='unavailable',percent=100,complete=True,failed=1,completed=0,total=0)
            state['revision'] += 1
            db.execute('UPDATE sessions SET data=? WHERE owner=? AND mode=?', (json.dumps(state, ensure_ascii=False), job[1], job[2]))
