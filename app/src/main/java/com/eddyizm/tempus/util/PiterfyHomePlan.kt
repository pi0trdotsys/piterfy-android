package com.eddyizm.tempus.util

/**
 * Piterfy: czysta (bez Androida, testowana na JVM) logika strony głównej zależnej od kontekstu: pora dnia, powitanie,
 * kolejność miksów i sekcji oraz postęp wznowienia. Zegar jest wstrzykiwany (godzina), więc testy nie zależą od czasu.
 *
 * Zasada wobec ręcznego układu: kolejność sekcji z okna „Zmień układ” jest święta. Pora dnia przesuwa sekcje tylko wtedy,
 * gdy użytkownik nigdy nie zmieniał układu (lista sekcji jest dokładnie domyślna).
 */
object PiterfyHomePlan {
    enum class Part(val key: String) { MORNING("morning"), DAY("day"), EVENING("evening"), NIGHT("night") }

    /** 5–10 rano, 11–17 dzień, 18–22 wieczór, 23–4 noc. */
    @JvmStatic
    fun part(hour: Int): Part = when (((hour % 24) + 24) % 24) {
        in 5..10 -> Part.MORNING
        in 11..17 -> Part.DAY
        in 18..22 -> Part.EVENING
        else -> Part.NIGHT
    }

    /** Domyślna kolejność sekcji (HomeRearrangementViewModel); układ użytkownika różny od niej uznajemy za jego własny. */
    val DEFAULT_SECTORS = listOf(
        Constants.HOME_SECTOR_DISCOVERY, Constants.HOME_SECTOR_CONTINUE_LISTENING, Constants.HOME_SECTOR_MADE_FOR_YOU,
        Constants.HOME_SECTOR_BEST_OF, Constants.HOME_SECTOR_RADIO_STATION, Constants.HOME_SECTOR_TOP_SONGS,
        Constants.HOME_SECTOR_STARRED_TRACKS, Constants.HOME_SECTOR_STARRED_ALBUMS, Constants.HOME_SECTOR_STARRED_ARTISTS,
        Constants.HOME_SECTOR_NEW_RELEASES, Constants.HOME_SECTOR_FLASHBACK, Constants.HOME_SECTOR_MOST_PLAYED,
        Constants.HOME_SECTOR_LAST_PLAYED, Constants.HOME_SECTOR_RECENTLY_ADDED, Constants.HOME_SECTOR_PINNED_PLAYLISTS,
        Constants.HOME_SECTOR_SHARED,
    )

    private val PROMOTED = mapOf(
        Part.MORNING to listOf(Constants.HOME_SECTOR_CONTINUE_LISTENING, Constants.HOME_SECTOR_NEW_RELEASES),
        Part.DAY to listOf(Constants.HOME_SECTOR_DISCOVERY, Constants.HOME_SECTOR_MADE_FOR_YOU),
        Part.EVENING to listOf(Constants.HOME_SECTOR_CONTINUE_LISTENING, Constants.HOME_SECTOR_RADIO_STATION, Constants.HOME_SECTOR_MADE_FOR_YOU),
        Part.NIGHT to listOf(Constants.HOME_SECTOR_RADIO_STATION, Constants.HOME_SECTOR_CONTINUE_LISTENING),
    )

    /**
     * Kolejność sekcji [ids] (tak, jak zapisał ją użytkownik) po uwzględnieniu pory dnia. Układ użytkownika zostaje nietknięty;
     * tylko niezmieniony układ domyślny dostaje na początek sekcje pasujące do pory dnia, reszta zachowuje kolejność.
     * Sekcje ukryte przez użytkownika nie są tu przekazywane, więc nigdy się nie pojawią.
     */
    @JvmStatic
    fun orderSectors(ids: List<String>, part: Part, enabled: Boolean, savedLayout: List<String>): List<String> {
        if (!enabled || !isUntouched(savedLayout)) return ids
        val promoted = PROMOTED.getValue(part).filter { it in ids }
        return promoted + ids.filter { it !in promoted }
    }

    /** Czy zapisany układ to dokładnie układ domyślny (z dowolną liczbą sekcji doklejonych na końcu przez migracje: nie). */
    @JvmStatic
    fun isUntouched(savedLayout: List<String>): Boolean = savedLayout == DEFAULT_SECTORS

    // słowa kluczowe w nazwach miksów (mixes.py) i ich waga o danej porze: wyżej = wcześniej
    private val MIX_WEIGHTS = mapOf(
        Part.MORNING to listOf("chill" to 3, "happy" to 2, "lata 80" to 1, "lata 90" to 1, "dark" to -2, "phonk" to -2, "bass" to -1),
        Part.DAY to listOf("workout" to 3, "happy" to 2, "rock" to 1, "polsk" to 1, "dark" to -1),
        Part.EVENING to listOf("electronic" to 3, "hip-hop" to 2, "bass" to 2, "rock" to 1, "dark" to 1, "chill" to -1),
        Part.NIGHT to listOf("dark" to 3, "phonk" to 3, "electronic" to 2, "chill" to 2, "workout" to -2, "happy" to -1),
    )

    private fun weight(name: String, part: Part): Int {
        val lower = name.lowercase()
        return MIX_WEIGHTS.getValue(part).firstOrNull { lower.contains(it.first) }?.second ?: 0
    }

    /** Indeksy [names] w nowej kolejności: miksy pasujące do pory dnia na początku; remisy zachowują dotychczasową kolejność. */
    @JvmStatic
    fun orderMixes(names: List<String>, part: Part, enabled: Boolean): List<Int> {
        val indices = names.indices.toList()
        if (!enabled) return indices
        return indices.sortedByDescending { weight(names[it], part) }   // sortedBy jest stabilne
    }

    /** Postęp wznowienia 0–1 (pozycja / długość); 0, gdy długość nieznana. */
    @JvmStatic
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
