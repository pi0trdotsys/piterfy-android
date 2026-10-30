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
                                 exploration=int(number(body.get('exploration', 50), 0, 100)),
                                 voice=state.get('voice', mode == 'dj'), revision=state['revision'] + 1, updated=time.time())
            elif action == 'stop':
                state['active'] = False
                db.execute("UPDATE jobs SET state='cancelled' WHERE owner=? AND mode=? AND state='queued'", (owner, mode))
            elif action == 'settings':
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
                result = state['active'] and not block.get('announced', False) and state.get('voice', False)
                block['announced'] = True
                db.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?)', (owner, mode, json.dumps(state)))
                return {'speak': result, 'text': block['text'] if result else '', 'voiceKey': block.get('voiceKey') if result else None}
            elif action not in ('get', 'refill', 'next'):
                raise Rejected('unknown_action', 400)
            if action in ('start', 'refill', 'next') and state['active']:
                busy = db.execute("SELECT 1 FROM jobs WHERE owner=? AND mode=? AND state IN ('queued','running')", (owner, mode)).fetchone()
                recent = db.execute("SELECT COUNT(*) FROM jobs WHERE owner=? AND created>?", (owner, time.time()-3600)).fetchone()[0]
                if not busy and recent < 8:
                    db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?)', (uuid.uuid4().hex, owner, mode, state['id'], time.time(), 'queued', '{}'))
                    state['status'] = 'queued'
                elif not busy:
                    state['status'] = 'hourly_limit'
            if action != 'get':
                state['revision'] += 1
                db.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?)', (owner, mode, json.dumps(state, ensure_ascii=False)))
            return state

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
                state['blocks'] = (state['blocks'] + [block])[-30:]
            state['status'] = error or ('ready' if final else 'downloading')
            state['revision'] += 1
            db.execute('UPDATE sessions SET data=? WHERE owner=? AND mode=?', (json.dumps(state, ensure_ascii=False), job[1], job[2]))
