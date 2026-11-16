"""Selection helpers for the "radio" acquisition mode (smart playback that fetches music not on the server yet).

Pure functions, no network: dj_worker feeds them Last.fm/Deezer candidates and the player's recent history.
"""
import random as _random
import re
import unicodedata

# Recent seeds count more than old ones: the newest listening should steer the next acquisition.
SEED_WEIGHTS = (1.0, 0.85, 0.7, 0.55, 0.45, 0.35)
RADIO_SEEDS = 5
RADIO_JOBS_PER_HOUR = 3


def _norm(text):
    text = unicodedata.normalize('NFKD', str(text or '')).encode('ascii', 'ignore').decode().lower()
    return re.sub(r'[^a-z0-9]+', ' ', text).strip()


def primary_artist(name):
    return _norm(re.split(r'\s*(?:,|;|&|\bfeat\.?|\bft\.?|\bx\b|\bwith\b)\s*', str(name or ''), maxsplit=1)[0])


def seed_weight(index):
    return SEED_WEIGHTS[min(index, len(SEED_WEIGHTS) - 1)]


def order_candidates(candidates, rng=None, limit=40, per_artist=1, blocked=()):
    """Order acquisition candidates so consecutive downloads differ.

    Candidates arrive grouped by the seed that produced them (``via``) in the similarity service's order,
    so taking them as they come repeats the same cluster every time. Here every group is shuffled by
    score with jitter, groups are interleaved round-robin and an artist appears at most ``per_artist`` times.
    """
    rng = rng or _random
    blocked = {primary_artist(a) for a in blocked}
    groups = {}
    for candidate in candidates:
        artist = primary_artist(candidate.get('artist'))
        if not artist or artist in blocked or not candidate.get('title'):
            continue
        weighted = dict(candidate, _score=float(candidate.get('score') or 0) * rng.uniform(0.65, 1.35))
        groups.setdefault(candidate.get('via') or '', []).append(weighted)
    for rows in groups.values():
        rows.sort(key=lambda row: row['_score'], reverse=True)
    order = list(groups)
    rng.shuffle(order)
    result, seen = [], {}
    while order and len(result) < limit:
        for via in list(order):
            rows = groups[via]
            while rows:
                row = rows.pop(0)
                artist = primary_artist(row.get('artist'))
                if seen.get(artist, 0) >= per_artist:
                    continue
                seen[artist] = seen.get(artist, 0) + 1
                result.append({k: v for k, v in row.items() if k != '_score'})
                break
            if not rows:
                order.remove(via)
            if len(result) >= limit:
                break
    return result
