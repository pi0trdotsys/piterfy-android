"""Bounded look-ahead queue. Preparation never grants permission to play audio."""
import json
import time
import uuid
from dj_core import Sessions
from connect_core import Rejected
from library_core import number, text

TTL = 24 * 3600
IDLE = 2 * 3600


class BufferedSessions(Sessions):
    @staticmethod
    def available(state):
        return [b for b in state.get('blocks', []) if b.get('ready', True)
                and not b.get('consumed') and b.get('id') != state.get('currentBlock')
                and b.get('preparedAt', 0) > time.time() - TTL]

    def describe(self, state):
        available = self.available(state)
        main = sorted([b for b in available if b.get('variant') != 'alternative'], key=lambda b:b.get('source')!='discovery')
        state['startBlock'] = (main or available)[0]['id'] if available else None
        state['buffer'] = dict(ready=len(main), target=2,
            alternatives=[b['id'] for b in available if b.get('variant') == 'alternative'])
        return state

    def schedule(self, db, owner, mode, state):
        if state.get('warmUntil', 0) < time.time():
            return
        rows = list(db.execute("SELECT data FROM jobs WHERE owner=? AND mode=? AND state IN ('queued','running')", (owner, mode)))
        purposes = [json.loads(row[0]).get('kind', 'acquire') for row in rows]
        available = self.available(state)
        def enqueue(kind, variant='main'):
            payload = dict(kind=kind, variant=variant, epoch=state.get('bufferEpoch', 0))
            db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?)',
                (uuid.uuid4().hex, owner, mode, state['id'], time.time(), 'queued', json.dumps(payload)))
        main = sum(b.get('variant') != 'alternative' for b in available)
        alternatives = sum(b.get('variant') == 'alternative' for b in available)
        if 'library' not in purposes and (main < 2 or alternatives < 1):
            enqueue('library', 'main' if main < 2 else 'alternative')
        recent = db.execute("SELECT COUNT(*) FROM jobs WHERE owner=? AND created>? AND COALESCE(json_extract(data,'$.kind'),'acquire')='acquire'", (owner, time.time()-3600)).fetchone()[0]
        # One acquired block can replace one library reserve; no endless imports when paused.
        discoveries = sum(b.get('source')=='discovery' and b.get('variant')!='alternative' for b in available)
        if state.get('active') and discoveries < 2 and 'acquire' not in purposes and recent < 8:
            enqueue('acquire')

    def save(self, db, owner, mode, state):
        state['revision'] = state.get('revision', 0) + 1
        self.describe(state)
        db.execute('INSERT OR REPLACE INTO sessions VALUES(?,?,?)', (owner, mode, json.dumps(state, ensure_ascii=False)))

    def handle(self, user, body):
        mode = body.get('mode', 'dj')
        if mode not in ('dj', 'discover'):
            raise Rejected('invalid_mode', 400)
        action = body.get('action', 'get')
        if action in ('claim', 'settings'):
            result = super().handle(user, body)
            if action == 'settings' and ('exploration' in body or 'voice' in body):
                return self.handle(user, dict(mode=mode, action='invalidate'))
            return result
        if action not in ('get', 'warm', 'start', 'refill', 'next', 'select', 'stop', 'invalidate'):
            raise Rejected('unknown_action', 400)
        owner = self.owner(user)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?', (owner, mode)).fetchone()
            state = json.loads(row[0]) if row else dict(active=False, mode=mode, blocks=[], voice=mode=='dj', revision=0)
            state.setdefault('id', uuid.uuid4().hex)
            state.setdefault('seeds', [])
            state.setdefault('exploration', 50)
            state.setdefault('bufferEpoch', 0)
            if not state.get('buffered'):
                for b in state['blocks']:
                    b['consumed'] = True
                if state.get('active') and state['blocks']:
                    state['currentBlock'] = state['blocks'][-1]['id']
                state['buffered'] = True
            if action in ('start', 'warm'):
                if 'seeds' in body:
                    seeds = body['seeds']
                    if not isinstance(seeds, list) or len(seeds) > 12 or any(not isinstance(s, dict) for s in seeds):
                        raise Rejected('invalid_seed', 400)
                    state['seeds'] = [{k:text(s.get(k,''),200) for k in ('id','artist','title','genre')} for s in seeds]
                if 'exploration' in body:
                    value = int(number(body['exploration'],0,100))
                    if value != state['exploration']:
                        self.invalidate(db, owner, mode, state)
                    state['exploration'] = value
                state['warmUntil'] = time.time() + IDLE
                if action == 'start':
                    state['active'] = True
            elif action == 'select':
                if not state.get('active') or body.get('session') != state['id']:
                    raise Rejected('session_changed', 409)
                chosen = next((b for b in state['blocks'] if b['id']==body.get('block')), None)
                if not chosen or not chosen.get('ready', True):
                    raise Rejected('block_missing', 404)
                previous = state.get('currentBlock')
                for b in state['blocks']:
                    if b['id'] == previous and previous != chosen['id']:
                        b['consumed'] = True
                state['currentBlock'] = chosen['id']
                state['warmUntil'] = time.time() + IDLE
            elif action in ('refill', 'next'):
                if state.get('active'):
                    state['warmUntil'] = time.time() + IDLE
            elif action == 'invalidate':
                self.invalidate(db, owner, mode, state)
            elif action == 'stop':
                for b in state['blocks']:
                    db.execute('INSERT OR REPLACE INTO block_archive VALUES(?,?,?,?,?)', (owner,state['id'],b['id'],json.dumps(dict(block=b,mode=mode,summaryEmail=state.get('summaryEmail',True),summaryTelegram=state.get('summaryTelegram',True))),time.time()))
                    if b['id'] == state.get('currentBlock'):
                        b['consumed'] = True
                state.update(active=False, currentBlock=None, warmUntil=0)
                # Other devices may retain an old arm token. A new session cannot inherit it.
                state['id']=uuid.uuid4().hex
                db.execute("UPDATE jobs SET state='cancelled' WHERE owner=? AND mode=? AND state='queued'", (owner,mode))
                db.execute('DELETE FROM block_archive WHERE created<?',(time.time()-30*86400,))
            # Expired reserves are removed, but the current block and recent playback history stay.
            state['blocks'] = [b for b in state['blocks'] if b['id']==state.get('currentBlock') or b.get('consumed') or b.get('preparedAt',0)>time.time()-TTL][-30:]
            if action != 'get':
                self.schedule(db, owner, mode, state)
            self.save(db, owner, mode, state)
            return state

    @staticmethod
    def invalidate(db, owner, mode, state):
        state['bufferEpoch'] = state.get('bufferEpoch',0) + 1
        state['blocks'] = [b for b in state['blocks'] if b.get('consumed') or b['id']==state.get('currentBlock')]
        db.execute("UPDATE jobs SET state='cancelled' WHERE owner=? AND mode=? AND state='queued'", (owner,mode))

    def take(self, kind='acquire'):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT id,owner,mode,session,data FROM jobs WHERE state='queued' AND COALESCE(json_extract(data,'$.kind'),'acquire')=? ORDER BY created LIMIT 1", (kind,)).fetchone()
            if row:
                db.execute("UPDATE jobs SET state='running' WHERE id=?", (row[0],))
            return row

    def valid(self, job):
        state = self.state_for(job)
        payload = json.loads(job[4]) if len(job)>4 else {}
        return (state.get('id')==job[3] and state.get('bufferEpoch',0)==payload.get('epoch',0)
                and state.get('warmUntil',0)>time.time())

    def finish(self, job, block=None, error=None, final=True):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT data FROM sessions WHERE owner=? AND mode=?',(job[1],job[2])).fetchone()
            if not row:
                return
            state = json.loads(row[0])
            db.execute('UPDATE jobs SET state=? WHERE id=?', ('failed' if error else 'done',job[0]))
            payload = json.loads(job[4]) if len(job)>4 else {}
            if state.get('id')!=job[3] or state.get('bufferEpoch',0)!=payload.get('epoch',0) or state.get('warmUntil',0)<time.time():
                self.schedule(db,job[1],job[2],state)
                return
            if block:
                reserved={t['id'] for b in state['blocks'] for t in b.get('tracks',[])}
                if any(t['id'] in reserved for t in block.get('tracks',[])):
                    # A concurrent preparation lane won the reservation. Never publish an
                    # overlapping queue or rewrite a spoken title after synthesis.
                    self.save(db,job[1],job[2],state)
                    return
                block.update(ready=True,preparedAt=time.time(),variant=payload.get('variant','main'),source='library' if payload.get('kind')=='library' else 'discovery')
                # Discoveries replace a reserve from the library, never the playing block.
                if block['source']=='discovery':
                    replace=next((b for b in self.available(state) if b.get('source')=='library' and b.get('variant')!='alternative'),None)
                    if replace:state['blocks'].remove(replace)
                state['blocks']=(state['blocks']+[block])[-30:]
                main=[b for b in self.available(state) if b.get('variant')!='alternative']
                while len(main)>2:
                    discard=next((b for b in main if b.get('source')=='library'),main[0])
                    state['blocks'].remove(discard);main.remove(discard)
                state['status']='ready'
            elif error:
                state['status']=error
            # A failed local build must not spin; retry after the next explicit warm/refill.
            if block:
                self.schedule(db,job[1],job[2],state)
            self.save(db,job[1],job[2],state)
