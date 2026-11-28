"""Nauka gustu w podcastach i cykliczne dociąganie nowych kanałów (poza ręcznie prowadzonym podcasts.txt).

Logika jest czysta (bez sieci, dysku i zegara — czas i pobieranie wstrzykuje wywołujący), żeby dało się ją testować:

1. `show_affinity` — ocena kanału z odsłuchów: odsłuchane odcinki, ★ i oceny (4–5 plus, 1 mocny minus) oraz odcinki, które leżą
   nietknięte od kilkunastu dni (minus). Świeższe zdarzenia liczą się mocniej (półokres 30 dni). Od 3 rozstrzygniętych odcinków
   kanał jest „lubiany” albo „nielubiany”, wcześniej „nieznany”.
2. `build_profile` — profil gustu: języki, kategorie Apple i słowa z tytułów kanałów, ważone ocenami (ręcznie dodane kanały bez
   historii dają słaby punkt wyjścia).
3. Wyszukiwanie w publicznym API iTunes (bez klucza; wysyłamy tylko ogólne hasła: kategorie, słowa, kraj), filtrowanie kandydatów,
   punktacja podobieństwa do profilu i różnorodność (najwyżej jeden nowy kanał z kategorii na przebieg).
4. Cykl życia: nowy kanał jest na próbie (`podcasts-auto.json`); po oknie próby z odsłuchem zostaje na stałe, bez odsłuchu znika
   razem z nieodsłuchanymi odcinkami i trafia na listę blokad (`podcasts-block.json`); kanał mocno nielubiany znika od razu.
"""

import json
import math
import re
import unicodedata
import urllib.parse
from datetime import datetime, timedelta, timezone
from pathlib import Path

HALF_LIFE_DAYS = 30
IGNORE_DAYS = 14                 # odcinek nietknięty tyle dni liczy się jako „zignorowany”
IGNORED_VALUE = -0.6
MIN_EXPOSURE = 3                 # tyle rozstrzygniętych odcinków, żeby ocenić kanał
LIKED_AT, DISLIKED_AT = 0.35, -0.35
STRONG_NEGATIVE = -0.6
PRIOR_WEIGHT = 0.25              # ręcznie dodany kanał bez historii: słaby punkt wyjścia profilu
TRIAL_LAST, KEPT_LAST = 3, 8
MAX_AGE_DAYS = 90                # kandydat musi mieć odcinek nie starszy niż tyle dni
MIN_EPISODES = 10
STOPWORDS = {
    "podcast", "podcasty", "show", "radio", "with", "from", "that", "this", "your", "the", "and", "for", "dla", "oraz", "czyli",
    "audycja", "program", "episode", "odcinek", "news", "daily", "weekly", "official", "learn", "learning",
}
LANG_COUNTRY = {"pl": "PL", "en": "US", "de": "DE", "es": "ES", "fr": "FR", "it": "IT", "nl": "NL", "tr": "TR", "ru": "RU",
                "el": "GR", "ca": "ES", "pt": "PT", "sv": "SE", "cs": "CZ", "uk": "UA", "ja": "JP", "ko": "KR", "zh": "CN"}
ICONS = {"added": "🎙", "kept": "✅", "dropped": "🗑"}
GENERIC = {"education", "society culture"}      # kategorie przypisywane prawie wszystkiemu: liczą się słabiej
GENERIC_FACTOR, SINGLE_WORD_FACTOR = 0.4, 0.4
# główne kategorie Apple: zbyt ogólne, żeby rozróżniać kanały
TOP_LEVEL = {"arts", "business", "comedy", "education", "fiction", "government", "health fitness", "history", "kids family", "leisure",
             "music", "news", "religion spirituality", "science", "society culture", "sports", "technology", "true crime", "tv film"}


LETTERS = {ord("ł"): "l", ord("đ"): "d", ord("ø"): "o", ord("ß"): "ss", ord("æ"): "ae", ord("œ"): "oe"}   # nie rozkładają się w NFKD


def fold(text):
    text = unicodedata.normalize("NFKD", text or "")
    text = "".join(c for c in text if not unicodedata.combining(c)).lower().translate(LETTERS)
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def norm_url(url):
    """Adres kanału bez schematu, parametrów i końcowego ukośnika — do rozpoznawania tego samego kanału."""
    parts = urllib.parse.urlsplit((url or "").strip().lower())
    return (parts.netloc + parts.path).rstrip("/")


def lang_code(value):
    return re.split(r"[-_]", (value or "").strip().lower())[0][:3]


def parse_time(value):
    try:
        moment = datetime.fromisoformat((value or "").replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


def keywords(title):
    words = []
    for word in fold(title).split():
        if len(word) >= 4 and not word.isdigit() and word not in STOPWORDS and word not in words:
            words.append(word)
    return words


# ── 1. ocena kanału ────────────────────────────────────────────────────────────

def episode_records(show_state, songs_by_key, now):
    """Rozstrzygnięte odcinki kanału: [{"ts", "value"}]. Wartość: odsłuchany +1, ★ lub 4–5 +1,5, ocena 1 −3, ocena 2 −1,
    nietknięty od IGNORE_DAYS dni −0,6. Odcinki młodsze i nietknięte jeszcze nic nie mówią (pomijane)."""
    records = []
    for episode in (show_state or {}).get("episodes", {}).values():
        status = episode.get("status")
        if status not in ("done", "deleted"):
            continue
        song = songs_by_key.get(unicodedata.normalize("NFC", episode.get("file") or ""))
        fetched = parse_time(episode.get("fetched")) or now
        played = status == "deleted" or bool(song and song.get("playCount"))   # skasowane sprząta tylko po odsłuchu
        rating = int((song or {}).get("rating") or 0)
        starred = bool(song and song.get("starred"))
        ts = (parse_time((song or {}).get("playDate")) if played and song else None) or fetched
        value = 0.0
        if rating == 1:
            value = -3.0
        elif rating == 2:
            value = -1.0
        elif starred or rating >= 4:
            value = 1.5
        elif played:
            value = 1.0
        elif (now - fetched).days >= IGNORE_DAYS:
            value = IGNORED_VALUE
        if value:
            records.append({"ts": ts, "value": value, "strong": rating == 1})
    return records


def show_affinity(records, now, half_life=HALF_LIFE_DAYS, min_exposure=MIN_EXPOSURE):
    """Ocena kanału: {"score": −1…1, "label": liked|disliked|neutral|unknown, "exposed": n, "played": n}."""
    exposed = len(records)
    played = sum(1 for r in records if r["value"] > 0)
    weights = [0.5 ** (max(0.0, (now - r["ts"]).total_seconds() / 86400) / half_life) for r in records]
    total = sum(weights)
    score = sum(w * r["value"] for w, r in zip(weights, records)) / total if total else 0.0
    score = max(-1.0, min(1.0, score))
    strong = sum(1 for r in records if r["strong"])
    if exposed < min_exposure and not strong:
        label = "unknown"
    elif score <= STRONG_NEGATIVE or strong >= 2 or (strong and score <= DISLIKED_AT):
        label = "disliked"
    elif score >= LIKED_AT:
        label = "liked"
    elif score <= DISLIKED_AT:
        label = "disliked"
    else:
        label = "neutral"
    return {"score": round(score, 3), "label": label, "exposed": exposed, "played": played}


def summarize_shows(state_podcasts, songs, now):
    """Ocena każdego kanału ze stanu discovera i listy utworów z Navidrome (`podcasts.library_key` jako klucz plików)."""
    from podcasts import library_key  # lokalnie: moduł jest czysty, a podcasts ciągnie sieć dopiero przy użyciu
    by_key = {library_key(s): s for s in songs}
    result = {}
    for name, show_state in (state_podcasts or {}).items():
        records = episode_records(show_state, by_key, now)
        result[name] = show_affinity(records, now)
        result[name]["played_episodes"] = sum(1 for r in records if r["value"] > 0)
    return result


# ── 2. profil gustu ────────────────────────────────────────────────────────────

def build_profile(shows, summaries, metas, prior=PRIOR_WEIGHT):
    """Profil z kanałów: `shows` to lista {"name", "url"}, `summaries` oceny, `metas` {nazwa: {language, categories, explicit}}.
    Lubiany kanał waży swoją ocenę (≥ 0,35), nieznany/neutralny `prior`, nielubiany nie wnosi nic (jego kategorie i słowa
    obniżają wagę pozostałych). Zwraca języki, kategorie, słowa oraz źródła (do uzasadnienia wyboru)."""
    profile = {"languages": {}, "categories": {}, "keywords": {}, "by_language": {}, "sources": [], "explicit": 0.0,
               "liked_languages": []}
    total = explicit = 0.0
    labels = {s["name"]: (summaries.get(s["name"]) or {}).get("label") for s in shows}
    liked_total = sum(max(0.35, (summaries.get(n) or {}).get("score", 0.0)) for n, label in labels.items() if label == "liked")
    unknown = sum(1 for label in labels.values() if label not in ("liked", "disliked"))
    if liked_total and unknown:      # gdy znamy już gust z odsłuchów, ręczna lista bez historii nie może go przytłaczać
        prior = min(prior, 0.5 * liked_total / unknown)
    for show in shows:
        name = show["name"]
        meta = metas.get(name) or {}
        affinity = summaries.get(name) or {"label": "unknown", "score": 0.0}
        if affinity["label"] == "liked":
            weight = max(0.35, affinity["score"])
        elif affinity["label"] == "disliked":
            weight = max(-1.0, affinity["score"]) * 0.5
        else:
            weight = prior
        language = lang_code(meta.get("language"))
        categories = [fold(c) for c in meta.get("categories") or [] if fold(c)]
        words = keywords(meta.get("title") or name)
        if weight > 0:
            total += weight
            explicit += weight if meta.get("explicit") else 0.0
            profile["sources"].append({"name": name, "weight": weight, "language": language, "categories": categories,
                                       "keywords": words})
        for bucket, items in (("categories", categories), ("keywords", words)):
            for item in items:
                profile[bucket][item] = profile[bucket].get(item, 0.0) + weight
        if language and weight > 0:       # nielubiany kanał obniża kategorie i słowa, ale nie język (lubisz inne kanały w tym języku)
            profile["languages"][language] = profile["languages"].get(language, 0.0) + weight
            if affinity["label"] == "liked" and language not in profile["liked_languages"]:
                profile["liked_languages"].append(language)
            lang = profile["by_language"].setdefault(language, {"categories": {}, "keywords": {}})
            for item in categories:
                lang["categories"][item] = lang["categories"].get(item, 0.0) + weight
            for item in words:
                lang["keywords"][item] = lang["keywords"].get(item, 0.0) + weight
    for bucket in ("languages", "categories", "keywords"):
        profile[bucket] = {k: round(v, 3) for k, v in profile[bucket].items() if v > 0.05}
    profile["explicit"] = round(explicit / total, 3) if total else 0.0
    profile["sources"].sort(key=lambda s: s["weight"], reverse=True)
    return profile


def language_shares(profile):
    total = sum(profile["languages"].values()) or 1.0
    return {lang: weight / total for lang, weight in profile["languages"].items()}


def accepted_languages(profile, min_share=0.1):
    """Języki, w których szukamy i które przyjmujemy: te z lubianym kanałem (nawet gdy to mały udział, np. nauka hiszpańskiego
    obok polskich audycji) oraz te z udziałem ≥ min_share w profilu."""
    shares = language_shares(profile)
    return set(profile.get("liked_languages") or []) | {lang for lang, share in shares.items() if share >= min_share}


def query_plan(profile, week=0, limit=10, min_share=0.1):
    """Zapytania do iTunes: [{"term", "country", "language"}]. Dla każdego przyjmowanego języka (`accepted_languages`) bierzemy jego
    najważniejsze kategorie i słowa; część zapytań stała, reszta obraca się co tydzień, żeby kolejne przebiegi szukały gdzie indziej."""
    shares = language_shares(profile)
    accepted = accepted_languages(profile, min_share)
    plans = []
    for language, share in sorted(shares.items(), key=lambda x: -x[1]):
        if language not in accepted:
            continue
        data = profile["by_language"].get(language) or {}
        terms = sorted(data.get("categories", {}).items(), key=lambda x: -x[1])[:3] + \
            sorted(data.get("keywords", {}).items(), key=lambda x: -x[1])[:6]
        seen = []
        for term, _ in terms:
            if term not in seen:
                seen.append(term)
        plans.append((language, seen))
    queries = []
    depth = max((len(t) for _, t in plans), default=0)
    for rank in range(depth):     # naprzemiennie po językach: najpierw najlepsze hasła każdego języka
        for language, terms in plans:
            if rank < len(terms):
                queries.append({"term": terms[rank], "country": LANG_COUNTRY.get(language, "US"), "language": language})
    if len(queries) > limit:
        fixed = max(1, limit // 2)
        rest = queries[fixed:]
        shift = week % len(rest) if rest else 0
        queries = queries[:fixed] + (rest[shift:] + rest[:shift])[:limit - fixed]
    return queries


def search_url(term, country, limit=25):
    return "https://itunes.apple.com/search?" + urllib.parse.urlencode(
        {"term": term, "media": "podcast", "entity": "podcast", "country": country, "limit": limit})


def parse_search(payload):
    """Kandydaci z odpowiedzi iTunes: [{"id", "name", "artist", "url", "genres", "tracks", "latest", "explicit"}]."""
    try:
        data = json.loads(payload) if isinstance(payload, (str, bytes)) else payload
        rows = data.get("results") or []
    except (ValueError, AttributeError):
        return []
    out = []
    for row in rows:
        if not isinstance(row, dict) or not row.get("feedUrl") or not row.get("collectionName"):
            continue
        genres = [g for g in (row.get("genres") or []) if g and g.lower() != "podcasts"]
        out.append({"id": row.get("collectionId"), "name": " ".join(str(row["collectionName"]).split())[:120],
                    "artist": row.get("artistName") or "", "url": row["feedUrl"], "genres": genres,
                    "tracks": int(row.get("trackCount") or 0), "latest": row.get("releaseDate"),
                    "explicit": row.get("collectionExplicitness") == "explicit"})
    return out


# ── 3. filtrowanie, punktacja, wybór ────────────────────────────────────────────

def prefilter(candidates, known_urls, known_names, blocked_urls, blocked_names, profile, now,
              max_age=MAX_AGE_DAYS, min_episodes=MIN_EPISODES):
    """Odrzuca to, co znamy lub zablokowaliśmy, kanały zbyt stare, zbyt krótkie i oznaczone jako „explicit”, gdy gust ich nie lubi."""
    seen, out = set(), []
    for c in candidates:
        url, name = norm_url(c["url"]), fold(c["name"])
        if url in known_urls or name in known_names or url in blocked_urls or name in blocked_names or url in seen:
            continue
        latest = parse_time(c.get("latest"))
        if latest and (now - latest).days > max_age:
            continue
        if c.get("tracks") and c["tracks"] < min_episodes:
            continue
        if c.get("explicit") and profile.get("explicit", 0) < 0.3:
            continue
        seen.add(url)
        out.append(c)
    return out


def _overlap(weights, items):
    return sum(weights.get(i, 0.0) for i in items)


def similarity(profile, language, cats, words):
    """(kategorie, słowa) 0…1: podobieństwo do całego profilu albo — gdy wyższe — do gustu w języku kandydata. Dzięki temu
    mniejszościowe zainteresowania (np. nauka i technologia po polsku obok kursów językowych) nie przegrywają z dominującym."""
    def part(cat_weights, kw_weights):
        cat_total, kw_total = sum(cat_weights.values()) or 1.0, sum(kw_weights.values()) or 1.0
        cat_hit = sum(cat_weights.get(c, 0.0) * (GENERIC_FACTOR if c in GENERIC else 1.0) for c in cats)
        hits = sum(1 for w in words if kw_weights.get(w))
        kw_factor = 1.0 if hits >= 2 else SINGLE_WORD_FACTOR     # jedno wspólne słowo (np. „coffee”) to jeszcze nie gust
        return min(1.0, cat_hit / cat_total * 2), min(1.0, _overlap(kw_weights, words) / kw_total * 6) * kw_factor
    cat, kw = part(profile["categories"], profile["keywords"])
    own = (profile.get("by_language") or {}).get(language)
    if own:
        own_cat, own_kw = part(own["categories"], own["keywords"])
        cat, kw = max(cat, own_cat), max(kw, own_kw)
    return cat, kw


def prescore(candidate, profile, now):
    """Wstępna ocena z samych danych iTunes (bez pobierania RSS): kategorie, słowa z tytułu, świeżość i liczba odcinków."""
    cats = [fold(g) for g in candidate["genres"]]
    words = keywords(candidate["name"] + " " + candidate.get("artist", ""))
    cat, kw = similarity(profile, candidate.get("qlang") or "", cats, words)
    latest = parse_time(candidate.get("latest"))
    fresh = max(0.0, 1.0 - (now - latest).days / MAX_AGE_DAYS) if latest else 0.3
    pop = min(1.0, math.log10(max(candidate.get("tracks") or 1, 1)) / 3)
    return 0.5 * cat + 0.3 * kw + 0.1 * fresh + 0.1 * pop


def validate_feed(candidate, feed, profile, now, max_age=MAX_AGE_DAYS, min_episodes=MIN_EPISODES):
    """Sprawdza pobrany kanał (wynik `podcasts.parse_feed`): odcinki z dźwiękiem, świeże, język zgodny z profilem.
    Zwraca (meta, powód odrzucenia): meta albo None."""
    episodes = feed.get("episodes") or []
    if len(episodes) < min_episodes:
        return None, f"tylko {len(episodes)} odcinków"
    newest = episodes[0]["date"]
    if newest.tzinfo is None:
        newest = newest.replace(tzinfo=timezone.utc)
    if (now - newest).days > max_age:
        return None, f"ostatni odcinek sprzed {(now - newest).days} dni"
    meta = dict(feed.get("meta") or {})
    language = lang_code(meta.get("language"))
    accepted = accepted_languages(profile)
    if accepted and language and language not in accepted:
        return None, f"język {language} spoza Twojego gustu"
    if meta.get("explicit") and profile.get("explicit", 0) < 0.3:
        return None, "oznaczony jako explicit"
    meta["episodes"] = len(episodes)
    meta["newest"] = newest.isoformat(timespec="seconds")
    return meta, None


def final_score(candidate, meta, profile, now):
    """Końcowa ocena kandydata po pobraniu RSS: (punkty, kategoria główna, powód, „podobny do”)."""
    cats = [fold(c) for c in (meta.get("categories") or []) + candidate["genres"]]
    cats = list(dict.fromkeys(c for c in cats if c))
    words = keywords((meta.get("title") or "") + " " + candidate["name"])
    language = lang_code(meta.get("language"))
    shares = language_shares(profile)
    cat, kw = similarity(profile, language or candidate.get("qlang") or "", cats, words)
    lang = max(min(1.0, shares.get(language, 0.0) * 2), 0.7 if language in (profile.get("liked_languages") or []) else 0.0) if language else 0.3
    consistency = min(1.0, (meta.get("episodes") or 0) / 100)
    score = 0.45 * cat + 0.25 * kw + 0.15 * lang + 0.05 * min(1.0, math.log10(max(candidate.get("tracks") or 1, 1)) / 3) \
        + 0.05 * consistency + 0.05 * prescore(candidate, profile, now)
    similar = []
    for source in profile["sources"]:
        shared = set(source["categories"]) & set(cats) | set(source["keywords"]) & set(words)
        if shared or (source["language"] and source["language"] == language and cats and set(source["categories"]) & set(cats)):
            similar.append((len(shared) * source["weight"] + 0.01 * source["weight"], source["name"]))
    similar = [name for _, name in sorted(similar, reverse=True)[:3]]
    specific = [c for c in cats if c not in TOP_LEVEL]      # „Language Learning” mówi więcej niż „Education” (i lepiej różnicuje wybór)
    primary = next((c for c in specific + cats if c in profile["categories"]), (specific + cats)[0] if cats else "")
    best = next((c for c in (meta.get("categories") or candidate["genres"]) if fold(c) == primary), primary)
    reason = "podobny do: " + (", ".join(similar) if similar else "Twojego gustu")
    reason += f" — kategoria {best or 'ogólna'}" + (f", język {language}" if language else "")
    return round(score, 4), primary, reason, similar


def pick_diverse(scored, per_run, taken_categories=(), language_penalty=0.85):
    """Z listy [(punkty, kategoria, kandydat, meta, powód)] najwyżej jeden na kategorię i `per_run` w sumie. Każdy kolejny kanał
    w tym samym języku co już wybrany traci 15% punktów przy porównaniu (łagodna różnorodność języków)."""
    chosen, used, languages = [], set(taken_categories), {}
    pool = list(scored)
    while pool and len(chosen) < per_run:
        best = None
        for item in pool:
            if item[1] in used:
                continue
            language = lang_code((item[3] or {}).get("language"))
            effective = item[0] * language_penalty ** languages.get(language, 0)
            if best is None or effective > best[0]:
                best = (effective, item, language)
        if best is None:
            break
        _, item, language = best
        chosen.append(item)
        pool.remove(item)
        used.add(item[1])
        languages[language] = languages.get(language, 0) + 1
    return chosen


def balanced_top(candidates, profile, now, n=12):
    """Do sprawdzania RSS trafia po kolei najlepszy kandydat z każdego języka zapytań (od największego udziału w profilu), żeby
    jeden dominujący język nie zajął wszystkich miejsc. Kandydat niesie język zapytania w polu `qlang`."""
    groups = {}
    for c in sorted(candidates, key=lambda c: prescore(c, profile, now), reverse=True):
        groups.setdefault(c.get("qlang") or "", []).append(c)
    shares = language_shares(profile)
    order = sorted(groups, key=lambda g: -shares.get(g, 0.0))
    picked = []
    while len(picked) < n and any(groups.values()):
        for language in order:
            if groups[language] and len(picked) < n:
                picked.append(groups[language].pop(0))
    return picked


# ── 4. pliki, cykl życia próby ──────────────────────────────────────────────────

def load_json(path, default, log=lambda *_: None):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default
    except (OSError, ValueError) as e:
        log(f"Podcasty: nie czytam {Path(path).name} ({e}) — działam bez niego.")
        return default
    return data if isinstance(data, type(default)) else default


def save_json(path, data):
    path = Path(path)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def load_auto(path, log=lambda *_: None):
    data = load_json(path, {"shows": []}, log)
    rows = data.get("shows") if isinstance(data.get("shows"), list) else []
    shows = [s for s in rows if isinstance(s, dict) and s.get("name") and str(s.get("url", "")).startswith("http")]
    return {"shows": shows}


def load_block(path, log=lambda *_: None):
    data = load_json(path, {"urls": [], "names": []}, log)
    urls = data.get("urls") if isinstance(data.get("urls"), list) else []
    names = data.get("names") if isinstance(data.get("names"), list) else []
    return {"urls": [u for u in urls if isinstance(u, str)], "names": [n for n in names if isinstance(n, str)]}


def block(blocklist, name, url):
    if url and norm_url(url) not in blocklist["urls"]:
        blocklist["urls"].append(norm_url(url))
    if name and fold(name) not in blocklist["names"]:
        blocklist["names"].append(fold(name))


def merge_shows(curated, auto, blocklist, keep_days):
    """Lista do synchronizacji: ręczna z podcasts.txt, potem kanały auto (bez kolizji nazw/adresów i bez zablokowanych)."""
    shows = list(curated)
    names = {fold(s["name"]) for s in shows}
    urls = {norm_url(s["url"]) for s in shows}
    for show in auto.get("shows", []):
        name, url = fold(show["name"]), norm_url(show["url"])
        if name in names or url in urls or url in blocklist["urls"] or name in blocklist["names"]:
            continue
        names.add(name)
        urls.add(url)
        last = show.get("last")
        shows.append({"name": show["name"], "url": show["url"], "last": int(last) if isinstance(last, int) and last > 0 else TRIAL_LAST,
                      "keep": keep_days, "auto": True})
    return shows


def review_trials(auto, summaries, now, trial_days=21):
    """Przegląd kanałów auto: [(działanie, kanał, powód)], gdzie działanie to „kept” albo „dropped”.
    Na próbie po `trial_days` dniach: był odsłuch → zostaje (`kept`, last=KEPT_LAST), nie było → odpada. Mocno nielubiany odpada od razu.
    Funkcja nie zmienia `auto`; robi to `apply_review`."""
    actions = []
    for show in auto.get("shows", []):
        if show.get("status") == "kept":
            continue
        summary = summaries.get(show["name"]) or {}
        added = parse_time(show.get("added")) or now
        if summary.get("label") == "disliked":
            actions.append(("dropped", show, "nie przypadł do gustu"))
        elif (now - added).days >= trial_days:
            if summary.get("played_episodes", summary.get("played", 0)) >= 1:
                actions.append(("kept", show, f"odsłuchałeś {summary.get('played_episodes', summary.get('played', 0))} odc."))
            else:
                actions.append(("dropped", show, f"brak odsłuchu przez {trial_days} dni"))
    return actions


def apply_review(auto, blocklist, actions, now):
    """Nanosi `review_trials` na listę auto i blokad. Zwraca listę usuniętych kanałów (do sprzątania plików)."""
    dropped = []
    for action, show, _ in actions:
        if action == "kept":
            show["status"], show["last"], show["promoted"] = "kept", KEPT_LAST, now.isoformat(timespec="seconds")
        elif action == "dropped":
            auto["shows"] = [s for s in auto["shows"] if s is not show and s.get("name") != show["name"]]
            block(blocklist, show["name"], show["url"])
            dropped.append(show)
    return dropped


def add_trials(auto, picks, now, max_auto=8):
    """Dopisuje wybrane kanały jako próbne (do limitu `max_auto`). `picks`: [(punkty, kategoria, kandydat, meta, powód)]."""
    added = []
    for score, category, candidate, meta, reason in picks:
        if len(auto["shows"]) >= max_auto:
            break
        show = {"name": candidate["name"], "url": candidate["url"], "last": TRIAL_LAST, "status": "trial",
                "added": now.isoformat(timespec="seconds"), "reason": reason, "category": category,
                "language": lang_code(meta.get("language"))}
        auto["shows"].append(show)
        added.append(show)
    return added


def promote(auto, name, now):
    """Ręczne „zostaw”: kanał z listy auto (dopasowanie po fragmencie nazwy) → na stałe. Zwraca kanał albo None."""
    matches = [s for s in auto["shows"] if fold(name) in fold(s["name"])]
    if len(matches) != 1:
        return None
    matches[0].update({"status": "kept", "last": KEPT_LAST, "promoted": now.isoformat(timespec="seconds")})
    return matches[0]


def describe(kind, show, why=""):
    lead = {"added": "Nowy podcast na próbę", "kept": "Podcast zostaje", "dropped": "Podcast usunięty"}[kind]
    text = f"{ICONS[kind]} {lead}: {show['name']}"
    if kind == "added":
        text += f"\n{show.get('reason', '')}\nPobieram 3 najnowsze odcinki; zostanie, jeśli coś odsłuchasz w ciągu 21 dni."
    elif why:
        text += f"\n{why.capitalize()}."
        if kind == "dropped":
            text += " Nieodsłuchane odcinki znikają, kanał nie wróci."
    return text
