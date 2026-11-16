package com.eddyizm.tempus.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Piterfy: formatowanie liczb w „Statystykach dla nerdów” (czysta logika, testowana na JVM). Te same zasady co w
 * connect/web/stats-core.js, żeby telefon i strona pokazywały identyczne napisy; po polsku przecinek dziesiętny
 * i twarda spacja jako separator tysięcy, w pozostałych językach kropka i przecinek.
 */
object PiterfyStatsFormat {
    private const val NB = " "
    private val MONTHS_PL = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
    private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    @JvmStatic fun isPolish(): Boolean = Locale.getDefault().language == "pl"

    @JvmStatic @JvmOverloads fun num(value: Number?, polish: Boolean = isPolish()): String {
        val n = (value ?: 0).toDouble().let { if (it.isFinite()) it.roundToLong() else 0L }
        val grouped = abs(n).toString().reversed().chunked(3).joinToString(if (polish) NB else ",").reversed()
        return if (n < 0) "-$grouped" else grouped
    }

    /** Liczba z częścią dziesiętną bez zbędnych zer na końcu („1,5”, „2”, „10”). */
    @JvmStatic @JvmOverloads fun dec(value: Double, digits: Int = 1, polish: Boolean = isPolish()): String {
        var text = String.format(Locale.ROOT, "%.${digits}f", if (value.isFinite()) value else 0.0)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return if (polish) text.replace('.', ',') else text
    }

    @JvmStatic @JvmOverloads fun bytes(bytes: Long, polish: Boolean = isPolish()): String {
        var value = bytes.coerceAtLeast(0).toDouble()
        val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        val digits = if (i == 0) 0 else if (value >= 100) 0 else if (value >= 10) 1 else 2
        return dec(value, digits, polish) + NB + units[i]
    }

    /** „12 d 5 h”, „5 h 12 min”, „12 min”. */
    @JvmStatic fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        return when {
            d > 0 -> "$d${NB}d$NB$h${NB}h"
            h > 0 -> "$h${NB}h$NB$m${NB}min"
            else -> "$m${NB}min"
        }
    }

    /** Czas słuchania: „412 godz.” (po angielsku „412 h”) od 10 godzin, inaczej „95 min”. */
    @JvmStatic @JvmOverloads fun listening(minutes: Long, polish: Boolean = isPolish()): String =
        if (minutes >= 600) num(minutes / 60.0, polish) + NB + (if (polish) "godz." else "h") else num(minutes, polish) + NB + "min"

    @JvmStatic @JvmOverloads fun percent(part: Double, total: Double, digits: Int = 0, polish: Boolean = isPolish()): String =
        if (total > 0) dec(part / total * 100, digits, polish) + "%" else "—"

    /** „▲ 18%”, „▼ 7%”, „=” gdy bez zmian, pusty napis, gdy nie ma z czym porównać. */
    @JvmStatic fun delta(pct: Int?): String = when {
        pct == null -> ""
        pct == 0 -> "="
        pct > 0 -> "▲ $pct%"
        else -> "▼ ${abs(pct)}%"
    }

    /** „5 lis 2026” z „2026-11-05” albo „—”. */
    @JvmStatic @JvmOverloads fun day(iso: String?, polish: Boolean = isPolish()): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso.orEmpty()) ?: return "—"
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return "—"
        return "${m.groupValues[3].toInt()}$NB${(if (polish) MONTHS_PL else MONTHS_EN)[month - 1]}$NB${m.groupValues[1]}"
    }

    /** „16.11” z „2026-11-16”. */
    @JvmStatic fun shortDay(iso: String?): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso.orEmpty()) ?: return ""
        return "${m.groupValues[3].toInt()}.${m.groupValues[2]}"
    }

    /** Długość słupka (0..1) względem największej wartości; najmniejszy widoczny to 2%, zero zostaje zerem. */
    @JvmStatic fun fraction(value: Double, max: Double): Float =
        if (value > 0 && max > 0) (value / max).toFloat().coerceIn(0.02f, 1f) else 0f

    /** Zawija napis do szerokości (mierzonej przez [measure]), najwyżej [maxLines] linii; ostatnia kończy się „…”. */
    @JvmStatic fun wrap(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (measure(next) <= maxWidth || line.isEmpty()) line = next else { lines.add(line); line = word }
        }
        if (line.isNotEmpty()) lines.add(line)
        if (maxLines > 0 && lines.size > maxLines) {
            val kept = lines.take(maxLines).toMutableList()
            var last = kept[maxLines - 1]
            while (last.length > 1 && measure("$last…") > maxWidth) last = last.dropLast(1)
            kept[maxLines - 1] = last.trimEnd(' ', ',', '.', ';', ':', '–', '-') + "…"
            return kept
        }
        return lines
    }
}
