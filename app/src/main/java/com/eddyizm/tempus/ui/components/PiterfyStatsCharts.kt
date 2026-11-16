package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/**
 * Piterfy: małe wykresy do „Statystyk dla nerdów”, rysowane bez bibliotek. Każdy wjeżdża animacją (kolejne elementy
 * z opóźnieniem), a przy wyłączonych animacjach systemu pojawia się od razu w całości.
 */
internal abstract class AnimatedChart(context: Context, private val delayMs: Long) : View(context) {
    protected var progress = 0f
        private set
    private var animator: ValueAnimator? = null

    private fun animationsOn() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        if (!animationsOn()) { progress = 1f; invalidate(); return }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800; startDelay = delayMs; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    protected fun dp(v: Float) = v * resources.displayMetrics.density
}

/** Pasek poziomy: tło i wypełnienie do [fraction] szerokości. */
internal class BarView(context: Context, private val fraction: Float, private val color: Int, private val track: Int, index: Int = 0) :
    AnimatedChart(context, index * 45L) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = track
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        val w = width * fraction * progress
        if (w > 0f) {
            paint.color = color
            val radius = kotlin.math.min(r, w / 2f)
            canvas.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), radius, radius, paint)
        }
    }
}

/** Pasek skumulowany: kolejne części (ułamek szerokości, kolor), z krótkimi przerwami. */
internal class StackView(context: Context, private val parts: List<Pair<Float, Int>>, private val track: Int) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = track
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), dp(8f), dp(8f), paint)
        val clip = canvas.save()
        canvas.clipRect(0f, 0f, width * progress, h)
        var x = 0f
        val gap = dp(2f)
        parts.filter { it.first > 0f }.forEach { (fraction, color) ->
            val w = width * fraction
            paint.color = color
            canvas.drawRoundRect(RectF(x, 0f, x + max(w - gap, 1f), h), dp(6f), dp(6f), paint)
            x += w
        }
        canvas.restoreToCount(clip)
    }
}

/** Wykres kolumnowy z podpisami i wartościami; szerokość rośnie z liczbą kolumn (do przewijania poziomego). */
internal class ColumnsView(
    context: Context, private val items: List<Triple<String, String, Float>>,
    private val color: Int, private val track: Int, private val ink: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colWidth = dp(46f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (items.size * colWidth).toInt()
        setMeasuredDimension(resolveSize(max(wanted, suggestedMinimumWidth), widthMeasureSpec), dp(190f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val slot = max(width / items.size.toFloat(), colWidth)
        val top = dp(18f)
        val bottom = height - dp(22f)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        items.forEachIndexed { i, (label, value, fraction) ->
            val cx = slot * i + slot / 2
            val half = slot * 0.34f
            paint.color = track
            canvas.drawRoundRect(RectF(cx - half, top, cx + half, bottom), dp(6f), dp(6f), paint)
            val barH = (bottom - top) * fraction * progress
            if (barH > 0f) {
                paint.color = color
                canvas.drawRoundRect(RectF(cx - half, bottom - barH, cx + half, bottom), dp(6f), dp(6f), paint)
            }
            paint.textSize = dp(11f)
            paint.color = ink
            if (value.isNotEmpty()) canvas.drawText(value, cx, top - dp(5f), paint)
            paint.color = muted
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(label, cx, height - dp(5f), paint)
            paint.typeface = Typeface.DEFAULT_BOLD
        }
    }
}

/** Pierścień z procentem w środku (np. trafność odkryć). */
internal class RingView(context: Context, private val fraction: Float, private val centerText: String, private val caption: String,
                        private val color: Int, private val track: Int, private val ink: Int, private val muted: Int) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(124f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(12f)
        val box = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)
        paint.color = color
        canvas.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f) * progress, false, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = ink; paint.textSize = dp(22f)
        canvas.drawText(centerText, width / 2f, height / 2f + dp(2f), paint)
        paint.color = muted; paint.textSize = dp(10f); paint.typeface = Typeface.DEFAULT
        canvas.drawText(caption.take(18), width / 2f, height / 2f + dp(17f), paint)
    }
}
