package com.eddyizm.tempus.util

/**
 * Piterfy: wznawianie odtwarzania w samochodzie. Czysta logika bez Androida (testy jednostkowe).
 *
 * Auto prosi o wznowienie, gdy łączy się z telefonem albo gdy wciśnięto „play” przy pustym odtwarzaczu. Wtedy wraca zapisana
 * kolejka od ostatnio granego utworu i miejsca, w którym przerwano.
 */
object PiterfyAutoResume {
    data class Start(val index: Int, val positionMs: Long)

    /** Gdzie zacząć w zapisanej kolejce; null, gdy kolejka jest pusta. Nieznany ostatni utwór = początek kolejki. */
    @JvmStatic
    fun start(ids: List<String>, lastPlayedId: String?, savedPositionMs: Long): Start? {
        if (ids.isEmpty()) return null
        val found = if (lastPlayedId.isNullOrEmpty()) -1 else ids.indexOf(lastPlayedId)
        return if (found >= 0) Start(found, savedPositionMs.coerceAtLeast(0L)) else Start(0, 0L)
    }

    private const val MIN_MS = 5_000L
    private const val FINISHED_TAIL_MS = 10_000L

    /**
     * Pozycja startu odcinka podcastu / audiobooka z „Kontynuuj”. Początek (< 5 s) i prawie koniec (ostatnie 10 s) nie
     * wznawiają, bo to zwykle odcinek już wysłuchany. Nieznana długość (0) pozwala wznowić dowolne miejsce dalej niż 5 s.
     */
    @JvmStatic
    fun continuePosition(savedMs: Long, durationMs: Long): Long {
        if (savedMs < MIN_MS) return 0L
        if (durationMs > 0 && savedMs >= durationMs - FINISHED_TAIL_MS) return 0L
        return savedMs
    }

    /** Postęp 0–1 do paska przy pozycji na liście; null, gdy długość jest nieznana albo nic nie odsłuchano. */
    @JvmStatic
    fun completion(savedMs: Long, durationMs: Long): Double? {
        if (durationMs <= 0 || savedMs <= 0) return null
        return (savedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    /** Czas utworu w ms z liczby sekund zapisanej w dodatkowych polach (0 = nieznany, więc null). */
    @JvmStatic
    fun durationMs(extrasDurationSeconds: Int): Long? =
        if (extrasDurationSeconds > 0) extrasDurationSeconds * 1000L else null
}
