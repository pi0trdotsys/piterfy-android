"""Varied Polish DJ copy grounded only in the tracks' actual library metadata."""
import random


OPENINGS = [
    'Zaczynamy od {artist}. Pierwszy numer to {title}.',
    '{title}. Od tego utworu zacznie się nasz blok, a zagra {artist}.',
    'Mam już gotowy zestaw. Na otwarcie {artist}, z utworem {title}.',
    'Oddajmy na chwilę głos muzyce. Przed nami {title}, wykonanie: {artist}.',
    'Ruszamy! {artist} otwiera ten zestaw utworem {title}.',
    'Pierwszy przystanek w tej części audycji: {artist} i {title}.',
    'Ten blok zaczniemy od numeru {title}. Za nim stoi {artist}.',
    'Ułóż się wygodnie. Najpierw posłuchamy {artist}, a konkretnie {title}.',
    'Zestaw gotowy, możemy słuchać. Na początek {title}, od {artist}.',
    'Otwieramy nowy muzyczny rozdział. {artist}, i utwór {title}.',
    'Dajmy się poprowadzić kolejnym dźwiękom. Zaczyna {artist}: {title}.',
    'Gotowe! Pierwszy utwór w tym bloku to {title}. Wykonawca: {artist}.',
]
BRIDGES = [
    'Przed nami {title}. Tym razem zagra {artist}.',
    'Kolejny numer to {title}, od {artist}.',
    '{artist}. Teraz posłuchamy utworu {title}.',
    'Następny przystanek: {title}, wykonanie: {artist}.',
    'Przekazuję mikrofon muzyce. {artist}, z utworem {title}.',
    'W tej części zestawu mam {title}. To numer od {artist}.',
    'Zobaczmy, jak zabrzmi obok poprzedniego utworu: {artist} i {title}.',
    'Za chwilę usłyszysz {title}. Za ten numer odpowiada {artist}.',
    'Czas na {artist}. W kolejce czeka {title}.',
    'Następny utwór nosi tytuł {title}. Zagra {artist}.',
    'Do tego zestawu dołącza {title}, od {artist}.',
    'Pora sprawdzić następny dźwięk w naszej układance. {artist}, {title}.',
    'Zostawmy miejsce kolejnemu numerowi: {title}, wykonanie {artist}.',
    'Włączam {title}. Wykonawcą jest {artist}.',
    'Przejdźmy dalej. Tym razem {artist}, z numerem {title}.',
    'Jeszcze jeden muzyczny trop: {artist} i {title}.',
]
SAME_ARTIST = [
    'Zostańmy jeszcze z {artist}. Kolejny utwór to {title}.',
    'Ten sam wykonawca, inny numer. {artist}, {title}.',
    'Poznajmy kolejną stronę {artist}. Teraz {title}.',
    'Jeszcze jeden utwór od {artist}: {title}.',
    'Nie zmieniamy wykonawcy. Od {artist} usłyszymy teraz {title}.',
    'Po poprzednim numerze mam jeszcze {title}, również od {artist}.',
]
GENRE_CHANGE = [
    'Mały skręt: z {previous_genre} przechodzimy w {genre}. {artist}, {title}.',
    'Zmieniamy gatunkowy kierunek na {genre}. Teraz {title}, od {artist}.',
    'Teraz sprawdzimy brzmienie {genre}. To {title}, wykonanie {artist}.',
    'Sprawdźmy inny klimat: {genre}. {artist}, z numerem {title}.',
]


def fields(song):
    return {name:str(song.get(name) or '').strip()[:limit] for name,limit in
            [('artist',80),('title',90),('album',90),('genre',50),('year',4)]}


def compose(songs,title,history=()):
    recent=[key for b in history for key in [b.get('openingVariant'),*(a.get('variant') for a in b.get('announcements',[]))] if key]
    used=set(recent)
    rng=random.SystemRandom()
    def choose(options):
        choices=[(key,text) for key,text in options if key not in used] or [(key,text) for key,text in options if key not in recent[-4:]] or options
        key,template=rng.choice(choices);used.add(key);recent.append(key)
        return key,template
    first=fields(songs[0]);opening_options=[('opening:'+str(i),t) for i,t in enumerate(OPENINGS)]
    previous=next((fields(b['lastTrack']) for b in reversed(history) if b.get('lastTrack')),None)
    if previous and previous['genre'] and first['genre'] and previous['genre'].casefold()!=first['genre'].casefold():
        first['previous_genre']=previous['genre'];opening_options=[('genre:'+str(i),t) for i,t in enumerate(GENRE_CHANGE)]
    elif previous and first['artist'] and previous['artist']==first['artist']:
        opening_options=[('same:'+str(i),t) for i,t in enumerate(SAME_ARTIST)]
    opening,template=choose(opening_options)
    text=template.format(**first)
    if title.startswith('Nowe odkrycia'):text='Świeże odkrycia są gotowe. '+text
    announcements=[]
    for index,song in enumerate(songs[1:],1):
        f=fields(song);previous=fields(songs[index-1]);f['previous_genre']=previous['genre']
        options=[('bridge:'+str(i),t) for i,t in enumerate(BRIDGES)]
        if f['artist']==previous['artist']:options += [('same:'+str(i),t) for i,t in enumerate(SAME_ARTIST)]
        if f['genre'] and previous['genre'] and f['genre'].casefold()!=previous['genre'].casefold():
            options += [('genre:'+str(i),t) for i,t in enumerate(GENRE_CHANGE)]
        key,template=choose(options);spoken=template.format(**f)
        # Optional facts vary the content without inventing biographies, mood or popularity.
        facts=[]
        if f['album']:facts.append('Pochodzi z albumu '+f['album']+'.')
        if f['year'].isdigit() and 1900<=int(f['year'])<=2100:facts.append('To wydanie z '+f['year']+' roku.')
        if f['genre'] and 'genre:' not in key:facts.append('Tym razem gatunek: '+f['genre']+'.')
        if facts and index%2:spoken+=' '+rng.choice(facts)
        announcements.append(dict(track=song['id'],text=spoken,variant=key,announced=False))
    return dict(text=text,openingVariant=opening,announcements=announcements)
