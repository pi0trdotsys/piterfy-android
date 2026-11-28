package com.eddyizm.tempus.util

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Piterfy: czysta logika gestów w pełnym odtwarzaczu (bez Androida, testy: PiterfyGesturePlanTest): strefy podwójnego
 * stuknięcia (przewijanie o 10 s), opór „gumy” na końcach kolejki, wykrycie próby przeciągnięcia poza koniec oraz
 * przeliczenie postępu gestu „wstecz” na skalę i przesunięcie okładki. Okładka dostaje swipe (poprzedni/następny) od
 * HorizontalPagera, więc tu jest tylko to, czego pager nie robi.
 */
object PiterfyGesturePlan {
    const val SEEK_STEP_MS = 10_000L
    /** Tak daleko (dp) trzeba pociągnąć poza koniec kolejki, żeby poczuć „odmowę” (impuls haptyczny). */
    const val EDGE_BUMP_DP = 36f
    /** Największe przesunięcie okładki „gumą” (dp), niezależnie od tego, jak daleko pociągniesz. */
    const val RUBBER_MAX_DP = 64f
    /** Najmniejszy ruch (dp), od którego przeciągnięcie przestaje być stuknięciem. */
    const val DRAG_SLOP_DP = 8f

    enum class Zone { LEFT, CENTER, RIGHT }
    enum class Axis { HORIZONTAL, VERTICAL }

    /** Lewa część okładki przewija wstecz, prawa do przodu; wąski środek (20 %) zostawiamy zwykłemu stuknięciu. */
    @JvmStatic
    fun zoneOf(x: Float, width: Float): Zone {
        if (width <= 0f) return Zone.CENTER
        val f = x / width
        return when {
            f < 0.4f -> Zone.LEFT
            f > 0.6f -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    /**
     * Pozycja po podwójnym stuknięciu w [zone]: ±[SEEK_STEP_MS], nie poniżej zera i (gdy czas trwania jest znany) nie dalej
     * niż tuż przed końcem, żeby przewinięcie nie przeskoczyło na następny utwór.
     */
    @JvmStatic
    fun seekTarget(positionMs: Long, durationMs: Long, zone: Zone): Long {
        val step = when (zone) { Zone.LEFT -> -SEEK_STEP_MS; Zone.RIGHT -> SEEK_STEP_MS; Zone.CENTER -> 0L }
        val raw = (positionMs + step).coerceAtLeast(0L)
        return if (durationMs > 0L) raw.coerceAtMost((durationMs - 500L).coerceAtLeast(0L)) else raw
    }

    /** Podpis podpowiedzi na okładce („−10 s” / „+10 s”) albo null dla środka. */
    @JvmStatic
    fun seekLabel(zone: Zone): String? = when (zone) { Zone.LEFT -> "−10 s"; Zone.RIGHT -> "+10 s"; Zone.CENTER -> null }

    /** Oś ruchu po przekroczeniu progu [slopPx]; do tego czasu null (jeszcze nie wiadomo). */
    @JvmStatic
    fun axisLock(dx: Float, dy: Float, slopPx: Float): Axis? {
        if (abs(dx) < slopPx && abs(dy) < slopPx) return null
        return if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /**
     * Przesunięcie okładki „gumą”: na początku prawie 1:1 z palcem, im dalej pociągniesz poza koniec, tym mniej okładka
     * się rusza, aż do [maxPx]. Znak zachowany; zero na zero.
     */
    @JvmStatic
    fun rubberBand(dragPx: Float, maxPx: Float): Float {
        if (dragPx == 0f || maxPx <= 0f) return 0f
        val out = maxPx * (1f - exp(-abs(dragPx) / maxPx))
        return if (dragPx < 0f) -out else out
    }

    /**
     * Czy przeciągnięcie ([dragPx]: dodatnie = palec w prawo) wychodzi poza koniec kolejki: w prawo na pierwszym utworze albo
     * w lewo na ostatnim, o co najmniej [thresholdPx]. Pojedynczy utwór nie ma sąsiadów, więc oba kierunki.
     */
    @JvmStatic
    fun pastEdge(page: Int, pageCount: Int, dragPx: Float, thresholdPx: Float): Boolean {
        if (pageCount <= 0 || abs(dragPx) < thresholdPx) return false
        return (dragPx > 0f && page <= 0) || (dragPx < 0f && page >= pageCount - 1)
    }

    // ── gest „wstecz” (Android 14+): postęp 0–1 z systemu → wygląd okładki ──

    /** Skala dużej okładki w trakcie gestu „wstecz”: od 1 do 0,86 (łagodnie na początku). */
    @JvmStatic
    fun backScale(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - 0.14f * (p * p * (3f - 2f * p))
    }

    /** Przesunięcie okładki w dół (px) w stronę mini odtwarzacza: do [maxPx] przy pełnym gestie. */
    @JvmStatic
    fun backShift(progress: Float, maxPx: Float): Float = maxPx * progress.coerceIn(0f, 1f).pow(1.4f)

    /** Zaokrąglenie rogów okładki (dp) rośnie w miarę gestu, jak okno na ekranie „wstecz” w systemie. */
    @JvmStatic
    fun backCorner(progress: Float, baseDp: Float): Float = baseDp + (28f - baseDp).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)
}
