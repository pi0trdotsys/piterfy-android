"""Account-scoped discovery preferences and podcast state. Contains no stream URLs."""
import hashlib
import json
import math
import os
import threading
import time
from pathlib import Path
from connect_core import Rejected


def text(value, limit=200):
    if not isinstance(value, str) or len(value) > limit:
        raise Rejected('invalid_text', 400)
    return value.strip()


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise Rejected('invalid_number', 400)
    return value


def artist_key(value):
    return ' '.join(text(value).casefold().split())


class Library:
    def __init__(self, directory):
        self.directory = Path(directory) / 'library'
        self.lock = threading.RLock()

    def handle(self, user, body):
        with self.lock:
            path = self.directory / (hashlib.sha256(user.encode()).hexdigest() + '.json')
            try:
                data = json.loads(path.read_text('utf-8'))
            except FileNotFoundError:
                data = {'exploration': 50, 'feedback': {}, 'episodes': {}, 'subscriptions': [], 'events': []}
            action = body.get('action', 'get')
            changed = False
            if action == 'settings':
                data['exploration'] = int(number(body.get('exploration'), 0, 100))
                changed = True
            elif action in ('feedback', 'skip'):
                identifier = text(body.get('id'))
                artist = artist_key(body.get('artist', ''))
                if not identifier:
                    raise Rejected('missing_id', 400)
                event = text(body.get('event', ''), 100)
                if not event:
                    raise Rejected('missing_event', 400)
                if action == 'skip':
                    position = number(body.get('position'), 0, 86400000)
                    duration = number(body.get('duration'), 1, 86400000)
                    if not 3000 <= position < min(30000, duration * .3):
                        return {key: data[key] for key in ('exploration', 'feedback', 'episodes', 'subscriptions')}
                elif body.get('vote') not in (-1, 0, 1) or isinstance(body.get('vote'), bool):
                    raise Rejected('invalid_vote', 400)
                if event not in data['events']:
                    row = data['feedback'].setdefault(identifier, {'artist': artist, 'vote': 0, 'skips': 0})
                    row['artist'] = artist
                    if action == 'feedback':
                        row['vote'] = int(number(body.get('vote'), -1, 1))
                    else:
                        # Only deliberate, early track changes count. Seeking, buffering and
                        # interrupted connections must not become a negative preference.
                        position = number(body.get('position'), 0, 86400000)
                        duration = number(body.get('duration'), 1, 86400000)
                        if 3000 <= position < min(30000, duration * .3):
                            row['skips'] = min(20, row['skips'] + 1)
                    row['updatedAt'] = int(time.time() * 1000)
                    data['events'] = (data['events'] + [event])[-500:]
                    # Bound storage while retaining the most recent explicit preferences.
                    if len(data['feedback']) > 5000:
                        oldest = min(data['feedback'], key=lambda key: data['feedback'][key].get('updatedAt', 0))
                        del data['feedback'][oldest]
                    changed = True
            elif action == 'progress':
                identifier = text(body.get('id'))
                if not identifier:
                    raise Rejected('missing_id', 400)
                old = data['episodes'].get(identifier, {})
                revision = body.get('revision', 0)
                if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
                    raise Rejected('invalid_revision', 400)
                if revision != old.get('revision', 0):
                    return {'conflict': True, 'episode': old}
                duration = int(number(body.get('duration'), 1, 86400000))
                position = int(number(body.get('position'), 0, duration))
                data['episodes'][identifier] = {'id': identifier, 'position': position, 'duration': duration,
                    'completed': duration - position <= min(15000, duration * .05), 'revision': revision + 1,
                    'updatedAt': int(time.time() * 1000)}
                if len(data['episodes']) > 2000:
                    oldest = min(data['episodes'], key=lambda key: data['episodes'][key]['updatedAt'])
                    del data['episodes'][oldest]
                changed = True
            elif action == 'subscribe':
                identifier = text(body.get('id'))
                if not identifier or not isinstance(body.get('enabled'), bool):
                    raise Rejected('invalid_subscription', 400)
                subscriptions = set(data['subscriptions'])
                if body['enabled']:
                    subscriptions.add(identifier)
                else:
                    subscriptions.discard(identifier)
                if len(subscriptions) > 200:
                    raise Rejected('subscription_limit', 400)
                data['subscriptions'] = sorted(subscriptions)
                changed = True
            elif action == 'rank':
                candidates = body.get('items')
                if not isinstance(candidates, list) or len(candidates) > 1000:
                    raise Rejected('candidate_limit', 400)
                artists = {}
                for row in data['feedback'].values():
                    key = row['artist']
                    artists[key] = artists.get(key, 0) + row['vote'] * 2 - min(row['skips'], 5) * .25
                ranked = []
                for index, candidate in enumerate(candidates):
                    identifier = text(candidate.get('id'))
                    artist = artist_key(candidate.get('artist', ''))
                    row = data['feedback'].get(identifier, {})
                    if row.get('vote') == -1:
                        continue
                    familiar = artist in artists or candidate.get('familiar') is True
                    novelty = (data['exploration'] - 50) / 25 * (-1 if familiar else 1)
                    score = 2 + novelty + max(-4, min(4, artists.get(artist, 0))) + row.get('vote', 0) * 3 - min(row.get('skips', 0), 5)
                    reason = 'Więcej podobnych do Twoich wyborów' if artists.get(artist, 0) > 0 else (
                        'Nowe brzmienie do sprawdzenia' if not familiar else 'Blisko Twojego gustu')
                    ranked.append({'id': identifier, 'score': score, 'reason': reason, '_index': index})
                ranked.sort(key=lambda row: (-row['score'], row['_index']))
                return {'items': [{k: v for k, v in row.items() if k != '_index'} for row in ranked]}
            elif action != 'get':
                raise Rejected('unknown_action', 400)
            if changed:
                self.directory.mkdir(parents=True, exist_ok=True)
                temporary = path.with_suffix('.tmp')
                temporary.write_text(json.dumps(data, ensure_ascii=False), 'utf-8')
                os.replace(temporary, path)
            return {key: data[key] for key in ('exploration', 'feedback', 'episodes', 'subscriptions')}
