package com.eddyizm.tempus.util

/**
 * Piterfy: stronicowanie list dla Android Auto. Samochód prosi o `page`/`pageSize`; bez tego długie listy (albumy, utwory,
 * playlisty — do 500 pozycji) przychodziły w całości, co wolne zestawy głośnomówiące pokazują z opóźnieniem albo ucinają.
 * Czysta logika bez Androida (testy jednostkowe).
 */
object PiterfyAutoPaging {
    /** Numer strony liczony od 0; `pageSize` <= 0 albo `Int.MAX_VALUE` oznacza „wszystko” (tylko strona 0). */
    @JvmStatic
    fun <T> slice(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return if (page <= 0) items else emptyList()
        val from = page.coerceAtLeast(0).toLong() * pageSize
        if (from >= items.size) return emptyList()
        val to = minOf(from + pageSize, items.size.toLong())
        return items.subList(from.toInt(), to.toInt())
    }
}
