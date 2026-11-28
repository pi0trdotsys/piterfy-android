package com.eddyizm.tempus.util

import kotlin.math.abs

/**
 * Piterfy: czysta logika przejść z morfem (bez Androida, testy: PiterfyMorphPlanTest).
 * Cover — okładka „leci” z miejsca mini okładki na miejsce dużej, w miarę rozwijania panelu odtwarzacza.
 * List — karta/pozycja listy rozwija się w stronę albumu, wykonawcy albo playlisty (MaterialContainerTransform).
 */
object PiterfyMorphPlan {
    /** Prostokąt we współrzędnych układu panelu odtwarzacza (piksele). */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val isEmpty get() = width <= 0f || height <= 0f
    }

    // ── okładka: mini odtwarzacz → pełny odtwarzacz ──

    /** Poniżej tego przesunięcia panelu duch okładki jest niewidoczny (panel zwinięty), powyżej 1 − EPS panel jest rozwinięty. */
    const val EPS = 0.004f
    /** Ostatnia część drogi, w której duch przechodzi w prawdziwą dużą okładkę (przenikanie, żeby nie było „pyknięcia”). */
    const val BLEND_FROM = 0.85f

    @JvmStatic fun ghostVisible(offset: Float) = offset > EPS && offset < 1f - EPS

    @JvmStatic fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    @JvmStatic fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * clamp01(t)

    @JvmStatic fun lerp(from: Rect, to: Rect, t: Float) = Rect(
        lerp(from.left, to.left, t), lerp(from.top, to.top, t), lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t),
    )

    /** Przezroczystość prawdziwej dużej okładki: 0 przez większość drogi, od BLEND_FROM płynnie do 1 (w odwrotną stronę tak samo). */
    @JvmStatic fun bigCoverAlpha(offset: Float): Float {
        val t = clamp01((offset - BLEND_FROM) / (1f - BLEND_FROM))
        return t * t * (3f - 2f * t)
    }

    /** Przezroczystość ducha: dopełnienie prawdziwej dużej okładki. */
    @JvmStatic fun ghostAlpha(offset: Float) = 1f - bigCoverAlpha(offset)

    /** Promień rogów ducha (piksele) w danej chwili: od zaokrąglenia mini okładki do zaokrąglenia dużej. */
    @JvmStatic fun radiusAt(offset: Float, miniPx: Float, bigPx: Float) = lerp(miniPx, bigPx, offset)

    /**
     * Czy w ogóle robić morf okładki. Wszystko, co nietypowe (inny układ, duża okładka poza panelem, bo np. jest strona
     * tekstów, wyłączone animacje, telewizor), kończy się „nie”, i zostaje dotychczasowa animacja panelu.
     */
    @JvmStatic fun shouldMorphCover(
        motionEnabled: Boolean, television: Boolean, mini: Rect?, big: Rect?, sheetWidth: Int, sheetHeight: Int,
    ): Boolean {
        if (!motionEnabled || television || mini == null || big == null) return false
        if (mini.isEmpty || big.isEmpty || sheetWidth <= 0 || sheetHeight <= 0) return false
        val slack = 2f
        val inside = big.left >= -slack && big.top >= -slack && big.right <= sheetWidth + slack && big.bottom <= sheetHeight + slack
        if (!inside) return false                                         // duża okładka poza panelem (inna strona pagera)
        if (!isSquare(mini) || !isSquare(big)) return false                // okładki są kwadratowe; inaczej układ jest nietypowy
        return big.width >= mini.width * 1.5f                              // morf ma sens tylko przy wyraźnie większej okładce
    }

    private fun isSquare(r: Rect): Boolean = abs(r.width / r.height - 1f) < 0.12f

    // ── listy: karta → strona szczegółów ──

    /** Dotknięcie, z którego wyliczamy klikniętą kartę, jest aktualne tylko chwilę (nie przy klawiaturze ani starym dotyku). */
    const val TOUCH_FRESH_MS = 3000L

    @JvmStatic fun touchFresh(now: Long, touchAt: Long) = touchAt > 0L && now - touchAt in 0..TOUCH_FRESH_MS

    /** Nazwa przejścia: ta sama na klikniętej karcie i na korzeniu strony docelowej (unikalna w obrębie jednego ekranu). */
    @JvmStatic fun transitionName(destinationId: Int, recyclerId: Int, position: Int) = "pf_morph_${destinationId}_${recyclerId}_$position"

    @JvmStatic fun shouldMorphList(motionEnabled: Boolean, television: Boolean, touchFresh: Boolean, hasTarget: Boolean) =
        motionEnabled && !television && touchFresh && hasTarget

    /** Powrót na ekran, z którego wyszliśmy: wtedy karta na liście dostaje tę samą nazwę, żeby strona „zwinęła się” do niej. */
    @JvmStatic fun isReturnTo(pendingSourceId: Int, destinationId: Int) = pendingSourceId != 0 && pendingSourceId == destinationId

    /** Przejście zawsze się kończy: po tylu ms start jest wymuszony, nawet gdy lista jeszcze się nie zbudowała. */
    const val POSTPONE_TIMEOUT_MS = 350L

    /** Czas przejścia kontenera i okładki w milisekundach (380–450 ms według specyfikacji). */
    const val DURATION_MS = PiterfyMotionPlan.SLOW_MS
}
