package com.eddyizm.tempus.appearance

/**
 * Piterfy: kalendarz dni okolicznościowych (czysta logika, bez Androida, testowana na JVM; ta sama w sieci, library.js).
 * Dzień okolicznościowy trwa od [PiterfyOccasionData.WINDOW_DAYS] dni przed świętem do tylu dni po nim. Gdy okna
 * się zazębiają, wygrywa święto bliższe dzisiejszej daty (przy remisie to wcześniejsze na liście).
 */
object PiterfyOccasions {
    sealed class Rule {
        /** Stała data, np. 14 lutego. */
        data class Fixed(val month: Int, val day: Int) : Rule()
        /** Niedziela Wielkanocna przesunięta o [offsetDays] dni. */
        data class Easter(val offsetDays: Int) : Rule()
    }

    data class Occasion(val id: String, val rule: Rule, val iconId: String, val themeId: String)

    /** [daysFromEvent]: ujemne = przed świętem, 0 = w dniu święta, dodatnie = po. */
    data class Match(val occasion: Occasion, val daysFromEvent: Int)

    /** Liczba dni od 1970-01-01 dla daty kalendarza gregoriańskiego (algorytm Howarda Hinnanta). */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Niedziela Wielkanocna: (miesiąc, dzień). Algorytm anonimowy gregoriański (Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): Pair<Int, Int> {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return month to day
    }

    fun eventDay(rule: Rule, year: Int): Long = when (rule) {
        is Rule.Fixed -> epochDay(year, rule.month, rule.day)
        is Rule.Easter -> easterSunday(year).let { epochDay(year, it.first, it.second) } + rule.offsetDays
    }

    /** Okazja aktywna w podanym dniu albo null. Sprawdza święta z poprzedniego, bieżącego i następnego roku (Sylwester). */
    @JvmStatic
    @JvmOverloads
    fun active(
        year: Int, month: Int, day: Int,
        all: List<Occasion> = PiterfyOccasionData.all,
        window: Int = PiterfyOccasionData.WINDOW_DAYS,
    ): Match? {
        val today = epochDay(year, month, day)
        var best: Match? = null
        for (occasion in all) {
            for (y in year - 1..year + 1) {
                val delta = (today - eventDay(occasion.rule, y)).toInt()
                if (delta < -window || delta > window) continue
                val better = best == null || kotlin.math.abs(delta) < kotlin.math.abs(best.daysFromEvent)
                if (better) best = Match(occasion, delta)
            }
        }
        return best
    }

    /** Najbliższa przyszła okazja (od jutra) i za ile dni zaczyna się jej okno; do podpisu w ustawieniach. */
    @JvmStatic
    @JvmOverloads
    fun next(
        year: Int, month: Int, day: Int,
        all: List<Occasion> = PiterfyOccasionData.all,
        window: Int = PiterfyOccasionData.WINDOW_DAYS,
    ): Pair<Occasion, Int>? {
        val today = epochDay(year, month, day)
        var best: Pair<Occasion, Int>? = null
        for (occasion in all) {
            for (y in year..year + 1) {
                val start = eventDay(occasion.rule, y) - window
                val until = (start - today).toInt()
                if (until > 0 && (best == null || until < best.second)) best = occasion to until
            }
        }
        return best
    }
}
