package com.eddyizm.tempus.util

/**
 * Piterfy: rozpoznawanie playlist tworzonych przez serwer (discover) po nazwach, żeby w Android Auto stały na początku
 * w grupie „Dla Ciebie”: Odkrywaj, „Rok temu o tej porze”, miksy (Happy Mix, Polski Rap, Lata 90...), listy przebojów
 * (Top 100 Polska) i soundtracki gier. Czysta logika bez Androida (testy jednostkowe).
 */
object PiterfyAutoPlaylists {
    enum class Group { DISCOVER, MIX, CHART, SOUNDTRACK, OTHER }

    private val knownMixes = setOf(
        "bass boost", "french music", "latin music", "polska muzyka", "polski rap", "german music", "asia pop", "brazil music",
        "lata 80", "lata 90", "lata 2000",
    )

    private fun key(name: String?): String = PiterfySmartPolicy.fold(name)

    @JvmStatic
    fun group(name: String?): Group {
        val k = key(name)
        return when {
            k.isEmpty() -> Group.OTHER
            k == "odkrywaj" || k.startsWith("odkrywaj ") || k == "rok temu o tej porze" -> Group.DISCOVER
            k.endsWith(" mix") || k in knownMixes -> Group.MIX
            k.startsWith("top ") -> Group.CHART
            k.startsWith("soundtrack ") -> Group.SOUNDTRACK
            else -> Group.OTHER
        }
    }

    @JvmStatic
    fun isPiterfy(name: String?): Boolean = group(name) != Group.OTHER

    /** Stabilna kolejność: grupy Piterfy od góry, potem reszta w dotychczasowym porządku. */
    @JvmStatic
    fun <T> order(items: List<T>, nameOf: (T) -> String?): List<T> =
        items.withIndex().sortedWith(compareBy({ group(nameOf(it.value)).ordinal }, { it.index })).map { it.value }
}
