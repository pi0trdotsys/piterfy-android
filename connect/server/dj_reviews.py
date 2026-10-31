"""Durable block reports and capability links. GET never changes a preference."""
import json
import secrets
import time
from html import escape
from urllib.parse import quote
from connect_core import Rejected


class Reviews:
    def __init__(self, sessions):
        self.sessions = sessions
        with sessions.db() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS reviews(owner TEXT, session TEXT, block TEXT,
                    token TEXT UNIQUE, data TEXT, created REAL, vote INTEGER,
                    PRIMARY KEY(owner,session,block));
                CREATE TABLE IF NOT EXISTS deliveries(token TEXT, channel TEXT, state TEXT,
                    PRIMARY KEY(token,channel));
            ''')

    def complete(self, user, body, notify=True):
        owner = self.sessions.owner(user)
        mode = body.get('mode', 'dj')
        state = self.sessions.handle(user, {'mode': mode})
        block = next((b for b in state['blocks'] if b['id'] == body.get('block')), None)
        if not block or body.get('session') != state.get('id'):
            # The receiver may report completion just after Stop/Start replaced its session.
            with self.sessions.db() as db:
                archived=db.execute('SELECT data FROM block_archive WHERE owner=? AND session=? AND block=? AND created>?',
                    (owner,body.get('session'),body.get('block'),time.time()-30*86400)).fetchone()
            if not archived:raise Rejected('block_missing',404)
            state=json.loads(archived[0]);block=state['block'];state['id']=body['session']
            if state['mode']!=mode:raise Rejected('block_missing',404)
        # A UI/receiver may retry or hand off: one report per actual block, across devices.
        with self.sessions.db() as db:
            db.execute('INSERT OR IGNORE INTO reviews VALUES(?,?,?,?,?,?,NULL)',
                       (owner,state['id'],block['id'],secrets.token_urlsafe(32),
                        json.dumps(dict(block=block,mode=mode,user=user,completed=notify,
                            email=state.get('summaryEmail',True),telegram=state.get('summaryTelegram',True)),ensure_ascii=False),time.time()))
            if notify:
                raw=db.execute('SELECT data FROM reviews WHERE owner=? AND session=? AND block=?',(owner,state['id'],block['id'])).fetchone()[0]
                data=json.loads(raw)
                if not data.get('completed'):
                    data.update(completed=True,email=state.get('summaryEmail',True),telegram=state.get('summaryTelegram',True))
                    db.execute('UPDATE reviews SET data=? WHERE owner=? AND session=? AND block=?',(json.dumps(data,ensure_ascii=False),owner,state['id'],block['id']))
        return {'ok': True}

    def resolve(self, token, vote=None):
        if not isinstance(token,str) or not 32 <= len(token) <= 80:
            raise Rejected('review_missing',404)
        with self.sessions.db() as db:
            row = db.execute('SELECT owner,data,created,vote FROM reviews WHERE token=?',(token,)).fetchone()
            if not row or row[2] < time.time()-30*86400:
                raise Rejected('review_expired',404)
            if vote is not None:
                if type(vote) is not int or vote not in (-1,0,1):
                    raise Rejected('invalid_vote',400)
                db.execute('UPDATE reviews SET vote=? WHERE token=?',(vote,token))
            return json.loads(row[1]), row[3] if vote is None else vote

    def preferences(self, owner):
        """The latest block vote is a soft signal, never an artist-wide ban."""
        scores = {}
        with self.sessions.db() as db:
            rows = db.execute('SELECT data,vote FROM reviews WHERE owner=? AND vote IS NOT NULL ORDER BY created DESC LIMIT 200',(owner,))
            for raw,vote in rows:
                for track in json.loads(raw)['block'].get('tracks',[]):
                    artist = ' '.join(track.get('artist','').casefold().split())
                    scores[artist] = max(-1.5,min(1.5,scores.get(artist,0)+vote*.5))
        return scores

    @staticmethod
    def links(base, token):
        return base.rstrip('/')+'/piterfy-api/connect/dj-feedback?token='+quote(token)

    def page(self, token, vote=None):
        data, saved = self.resolve(token,vote)
        title = escape(data['block'].get('title','Twój blok'))
        tracks = ''.join('<li>'+escape(t.get('artist','')+' — '+t.get('title',''))+'</li>' for t in data['block'].get('tracks',[]))
        buttons = ''.join(f'<button name="vote" value="{value}">{label}</button>' for value,label in [(1,'Było OK · więcej takich'),(-1,'Nie mój klimat · mniej takich'),(0,'Cofnij ocenę')])
        message = 'Ocena cofnięta.' if vote==0 else ('Zapisano ocenę. Uwzględnię ją przy doborze kolejnych bloków.' if vote is not None else 'Jak Ci się słuchało? Ocena pomoże dobrać kolejne bloki.')
        message += ' Obecna ocena: '+{1:'OK — więcej takich.',-1:'nie mój klimat — mniej takich.',0:'neutralna.',None:'jeszcze nie oceniono.'}[saved]
        return f'''<!doctype html><html lang="pl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Oceń blok · Piterfy DJ</title>
<style>body{{background:#101719;color:#edf8f3;font:16px/1.6 system-ui;margin:0;padding:24px}}main{{max-width:600px;margin:5vh auto}}button,a{{display:inline-block;background:#b7f3ce;color:#12241a;border:0;border-radius:24px;padding:14px 20px;margin:6px;font:inherit;cursor:pointer}}li{{margin:12px 0}}p{{color:#b8ccc2}}</style>
<main><small>PITERFY DJ · TWÓJ BLOK</small><h1>{title}</h1><p>{message}</p><ul>{tracks}</ul>
<form method="post"><input type="hidden" name="token" value="{escape(token,quote=True)}">{buttons}</form><a href="/app/#/start">Wróć do Piterfy</a></main></html>'''

    def pending(self):
        with self.sessions.db() as db:
            return list(db.execute('SELECT token,data FROM reviews WHERE created>? ORDER BY created LIMIT 200',(time.time()-86400,)))

    def deliver(self, token, channel, send):
        # SMTP/Telegram have no idempotency key. Claim before sending to avoid duplicate mail.
        # Ambiguous network errors remain visible as failed instead of being blindly resent.
        with self.sessions.db() as db:
            cursor=db.execute('INSERT OR IGNORE INTO deliveries VALUES(?,?,?)',(token,channel,'sending'))
            if not cursor.rowcount:return
        try:
            send()
            status='sent'
        except Exception:
            status='failed'
        with self.sessions.db() as db:
            db.execute('UPDATE deliveries SET state=? WHERE token=? AND channel=?',(status,token,channel))

    def report(self, token, data, base):
        block=data['block'];link=self.links(base,token)
        title=block.get('title','Twój blok')
        tracks='\n'.join('• '+t.get('artist','')+' — '+t.get('title','') for t in block.get('tracks',[]))
        plain=f'Piterfy DJ · {title}\n\n{tracks}\n\nJak było? Oceń blok: {link}'
        html=f'''<!doctype html><html lang="pl"><meta charset="utf-8"><body style="background:#101719;color:#edf8f3;font:16px/1.6 Arial;padding:24px"><main style="max-width:600px;margin:auto"><p style="color:#a4edc2">PITERFY DJ / PODSUMOWANIE BLOKU</p><h1>{escape(title)}</h1><p style="white-space:pre-line">{escape(tracks)}</p><p>Jak Ci się słuchało? Twój głos pomoże dobrać kolejne odkrycia.</p><a style="display:inline-block;background:#b7f3ce;color:#12241a;border-radius:24px;padding:14px 24px;text-decoration:none" href="{escape(link,quote=True)}">Oceń ten blok</a></main></body></html>'''
        return plain,html,link
