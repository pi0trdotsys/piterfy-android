package com.eddyizm.tempus.util

import kotlin.math.floor
import kotlin.math.max

/**
 * Piterfy: układ cytatu na karcie z linijką piosenki (czysta logika bez Androida, testy: PiterfyLyricCardLayoutTest).
 * Odpowiednik `wrap` i `fitCard` z connect/web/lyrics-core.js, więc karta ze strony i z aplikacji zawija tekst tak samo.
 */
object PiterfyLyricCardLayout {
    /** Wynik dopasowania: rozmiar czcionki, zawinięte wiersze i wysokość bloku (wiersze × rozmiar × interlinia). */
    class Fit(val size: Float, val lines: List<String>, val height: Float, val truncated: Boolean)

    /** Zachłanne zawijanie po słowach; słowo dłuższe niż wiersz (np. tekst bez spacji) łamiemy po znakach. */
    @JvmStatic
    fun wrap(text: String?, maxWidth: Float, measure: (String) -> Float): List<String> {
        val words = (text ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var line = ""
        fun push() { if (line.isNotEmpty()) lines.add(line); line = "" }
        for (word in words) {
            val candidate = if (line.isNotEmpty()) "$line $word" else word
            if (measure(candidate) <= maxWidth) { line = candidate; continue }
            push()
            if (measure(word) <= maxWidth) { line = word; continue }
            var piece = ""
            val chars = word.codePoints().toArray().map { String(Character.toChars(it)) }
            for (ch in chars) {
                if (piece.isNotEmpty() && measure(piece + ch) > maxWidth) { lines.add(piece); piece = "" }
                piece += ch
            }
            line = piece
        }
        push()
        return lines
    }

    /**
     * Największy rozmiar czcionki (od [max] w dół do [min], krok [step]), przy którym zawinięty tekst mieści się w ramce
     * [width] × [height]. [measureAt] mierzy szerokość tekstu danym rozmiarem. Gdy nic się nie mieści: [min] i tyle wierszy,
     * ile wchodzi, z wielokropkiem na końcu ostatniego.
     */
    @JvmStatic
    @JvmOverloads
    fun fit(
        text: String?, width: Float, height: Float, measureAt: (Float, String) -> Float,
        max: Float = 104f, min: Float = 44f, step: Float = 4f, lineHeight: Float = 1.22f,
    ): Fit {
        var size = max
        while (size >= min) {
            val s = size
            val lines = wrap(text, width) { measureAt(s, it) }
            val total = lines.size * size * lineHeight
            if (lines.isNotEmpty() && total <= height) return Fit(size, lines, total, false)
            size -= step
        }
        val lines = wrap(text, width) { measureAt(min, it) }
        val room = max(1, floor(height / (min * lineHeight)).toInt())
        val cut = lines.take(room).toMutableList()
        val truncated = lines.size > room
        if (truncated) cut[room - 1] = cut[room - 1].trimEnd { it.isWhitespace() || it in ".,;:!?-" } + "…"
        return Fit(min, cut, cut.size * min * lineHeight, truncated)
    }
}
