package com.eddyizm.tempus.util

/**
 * Piterfy: polecenia głosowe i wyszukiwanie w Android Auto. Czysta logika bez Androida (testy jednostkowe).
 *
 * Asystent przekazuje całe zdanie („zagraj album Wolność Taco Hemingway”), a serwer szuka po nazwach. Tu zdanie
 * rozbijamy na rodzaj (utwór/album/wykonawca/playlista) i szukaną frazę, a wyniki układamy tak, żeby pierwszy był tym, o co
 * chodziło: dokładne dopasowanie ponad „zawiera”, rodzaj z polecenia ponad resztą, polubione i często grane ponad nieznanymi.
 * Dopasowanie nie zależy od wielkości liter ani polskich znaków (PiterfySmartPolicy.fold).
 */
object PiterfyAutoSearch {
    enum class Kind { ANY, SONG, ALBUM, ARTIST, PLAYLIST }

    data class Parsed(val kind: Kind, val term: String, val artist: String?) {
        /** Puste polecenie („zagraj muzykę”, „play”): nie ma czego szukać, wznawiamy albo gramy z ulubionych. */
        val isEmpty: Boolean get() = term.isBlank()

        /** Tekst do zapytania na serwerze: tytuł i wykonawca razem, bez spójnika „od”/„by” (przeszkadzałby w dopasowaniu). */
        val queryText: String get() = listOfNotNull(term.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    data class Candidate(
        val id: String,
        val title: String,
        val artist: String = "",
        val kind: Kind = Kind.SONG,
        val starred: Boolean = false,
        val playCount: Long = 0,
    )

    private val opt = RegexOption.IGNORE_CASE

    // Koniec słowa: `\b` nie widzi granicy po polskich literach na końcu („muzykę”, „coś”, „puść”), więc sprawdzamy, że dalej
    // nie ma litery ani cyfry (działa tak samo na JVM i na Androidzie).
    private const val END = "(?![\\p{L}\\p{N}])"

    private val lead = Regex("^(?:(?:hej|ok|okej|proszę|prosze)\\s+)*", opt)
    private val verb = Regex(
        "^(?:zagraj|zagrać|odtwórz|odtworz|włącz|wlacz|puść|pusc|uruchom|odpal|play|put on|listen to|shuffle)$END(?:\\s+mi$END)?\\s*", opt)
    private val filler = Regex("^(?:muzykę|muzyke|muzyka|coś|cos|music)$END\\s*", opt)
    private val onlyBy = Regex("^(?:od|by|from)\\s+(.+)$", opt)
    private val fromPrefix = Regex("^(?:z|from)\\s+", opt)
    private val artistWords = Regex(
        "^(?:(?:utwory|piosenki|przeboje|hity|songs|hits|best of|coś|cos)\\s+)?(?:artystę|artyste|artysty|artysta|wykonawcę|wykonawce|wykonawcy|wykonawca|zespół|zespol|zespołu|zespolu|artist|band)$END\\s*", opt)
    private val albumWords = Regex("^(?:album|albumu|płytę|plyte|płyta|plyta|płyty|plyty)$END\\s*", opt)
    private val songWords = Regex("^(?:piosenkę|piosenke|piosenka|utwór|utwor|kawałek|kawalek|song|track)$END\\s*", opt)
    private val playlistWords = Regex("^(?:playlistę|playliste|playlista|playlisty|listę|liste|playlist)$END\\s*", opt)
    private val musicBy = Regex("^(?:muzykę|muzyke|muzyka|utwory|piosenki|przeboje|hity|songs|music|hits)\\s+(?:od|zespołu|zespolu|wykonawcy|artysty|by|from)\\s+(.+)$", opt)
    private val titleBy = Regex("^(.+?)\\s+(?:od|by|autorstwa)\\s+(.+)$", opt)

    @JvmStatic
    fun parse(raw: String?): Parsed {
        var text = (raw ?: "").trim().replace(Regex("\\s+"), " ")
        text = text.replace(lead, "")
        while (true) {
            val next = text.replace(verb, "")
            if (next == text) break
            text = next
        }
        musicBy.matchEntire(text)?.let { return Parsed(Kind.ARTIST, it.groupValues[1].trim(), null) }
        // Słowo wypełniające („muzykę”, „coś”, „music”) znaczy coś tylko samo albo przed „od …”: w środku tytułu
        // („Music Sounds Better With You”) zostaje częścią nazwy.
        val withoutFiller = text.replace(filler, "")
        if (withoutFiller != text) {
            if (withoutFiller.isBlank()) return Parsed(Kind.ANY, "", null)
            onlyBy.matchEntire(withoutFiller)?.let { return Parsed(Kind.ARTIST, it.groupValues[1].trim(), null) }
        }
        text = text.replace(fromPrefix, "")

        var kind = Kind.ANY
        fun strip(words: Regex, as_: Kind): Boolean {
            val next = text.replace(words, "")
            if (next == text) return false
            text = next
            kind = as_
            return true
        }
        // „z albumu X”: po „z” zostaje słowo „albumu”
        if (!strip(artistWords, Kind.ARTIST) && !strip(albumWords, Kind.ALBUM) && !strip(songWords, Kind.SONG)) {
            strip(playlistWords, Kind.PLAYLIST)
        }
        text = text.trim().trim('"', '„', '”', '\'').trim()

        var artist: String? = null
        if (kind == Kind.ANY || kind == Kind.SONG || kind == Kind.ALBUM) {
            titleBy.matchEntire(text)?.let {
                val title = it.groupValues[1].trim()
                val by = it.groupValues[2].trim()
                if (title.isNotEmpty() && by.isNotEmpty()) {
                    text = title
                    artist = by
                }
            }
        }
        return Parsed(kind, text, artist)
    }

    private fun matchScore(field: String, query: String): Double {
        if (field.isEmpty() || query.isEmpty()) return 0.0
        return when {
            field == query -> 100.0
            field.startsWith("$query ") || field.startsWith(query) -> 70.0
            " $query " in " $field " -> 55.0
            field.contains(query) -> 30.0
            else -> {
                val words = query.split(' ').filter { it.isNotEmpty() }
                if (words.isNotEmpty() && words.all { it in field }) 25.0 else 0.0
            }
        }
    }

    /** Wynik pozycji względem polecenia; wyższy znaczy lepiej, 0 i mniej to brak dopasowania. */
    @JvmStatic
    fun score(candidate: Candidate, parsed: Parsed): Double {
        val query = PiterfySmartPolicy.fold(parsed.term)
        if (query.isEmpty()) return 0.0
        val title = PiterfySmartPolicy.fold(candidate.title)
        val artist = PiterfySmartPolicy.fold(candidate.artist)
        var s = maxOf(matchScore(title, query), matchScore(artist, query) * 0.8)
        if (s <= 0.0) return 0.0
        parsed.artist?.let {
            val hint = PiterfySmartPolicy.fold(it)
            s += if (hint.isNotEmpty() && (artist.contains(hint) || title.contains(hint))) 40.0 else -25.0
        }
        if (parsed.kind != Kind.ANY) {
            s += if (candidate.kind == parsed.kind) 30.0 else -15.0
        } else if (s >= 70.0) {
            s += when (candidate.kind) { // bez wskazówki: nazwa wykonawcy lub playlisty zwykle znaczy „zagraj to”
                Kind.ARTIST -> 8.0
                Kind.PLAYLIST -> 6.0
                Kind.ALBUM -> 4.0
                else -> 0.0
            }
        }
        if (candidate.starred) s += 3.0
        s += minOf(candidate.playCount, 50L) / 50.0 * 2.0
        return s
    }

    /** Wyniki od najlepszego (stabilnie przy remisie). Pozycje bez dopasowania zostają na końcu w dotychczasowym porządku. */
    @JvmStatic
    fun rank(candidates: List<Candidate>, parsed: Parsed): List<Candidate> =
        candidates.withIndex().sortedWith(compareByDescending<IndexedValue<Candidate>> { score(it.value, parsed) }.thenBy { it.index })
            .map { it.value }

    /** Najlepszy wynik, o ile naprawdę pasuje (próg odcina przypadkowe trafienia); inaczej null. */
    @JvmStatic
    fun best(candidates: List<Candidate>, parsed: Parsed, minScore: Double = 25.0): Candidate? =
        rank(candidates, parsed).firstOrNull()?.takeIf { score(it, parsed) >= minScore }
}
