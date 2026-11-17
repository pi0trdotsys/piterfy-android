package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.PiterfyStoryPlan.Kind
import kotlin.math.abs
import kotlin.math.min

/**
 * Piterfy: powierzchnia „Wrapped story”. Rysuje bieżący slajd przez [PiterfyStoryRenderer] (czas slajdu [t] podaje
 * dialog) i zamienia dotyk na polecenia: dotknięcie lewej/prawej części, przeciągnięcie w bok, przytrzymanie (pauza)
 * i przeciągnięcie w dół (zamknięcie). Przy wyłączonych animacjach systemu rysuje od razu stan końcowy slajdu.
 */
class PiterfyStoryView(context: Context) : View(context) {
    interface Listener {
        fun onTapBack()
        fun onTapForward()
        fun onSwipe(forward: Boolean)
        fun onHold(holding: Boolean)
        fun onDismissGesture()
    }

    var listener: Listener? = null
    var renderer: PiterfyStoryRenderer? = null
    var period: PiterfyStatsModel.Period? = null
    var kind: Kind = Kind.INTRO
    /** Czas w ms od początku slajdu; ustawia go dialog co klatkę. */
    var t = 0L

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private val holdRunnable = Runnable { holding = true; listener?.onHold(true) }

    fun animationsOn(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    override fun onDraw(canvas: Canvas) {
        val p = period ?: return
        val r = renderer ?: return
        val scale = min(width / PiterfyStoryRenderer.W, height / PiterfyStoryRenderer.H)
        if (scale <= 0f) return
        canvas.scale(scale, scale)
        r.draw(canvas, p, kind, if (animationsOn()) t else PiterfyStoryPlan.END_STATE_MS, width / scale, height / scale)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; holding = false
                postDelayed(holdRunnable, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!holding && (abs(e.x - downX) > slop * 2 || abs(e.y - downY) > slop * 2)) removeCallbacks(holdRunnable)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (holding) { holding = false; listener?.onHold(false); return true }
                val dx = e.x - downX
                val dy = e.y - downY
                val d = resources.displayMetrics.density
                when {
                    abs(dx) > 56 * d && abs(dx) > abs(dy) -> listener?.onSwipe(dx < 0)
                    dy > 110 * d && abs(dy) > abs(dx) -> listener?.onDismissGesture()
                    abs(dx) < slop * 3 && abs(dy) < slop * 3 -> {
                        performClick()
                        if (e.x < width / 3f) listener?.onTapBack() else listener?.onTapForward()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                if (holding) { holding = false; listener?.onHold(false) }
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object { private const val HOLD_MS = 220L }
}

/** Segmentowy pasek postępu u góry: ukończone slajdy pełne, bieżący częściowo, kolejne przygaszone. */
class PiterfyStoryProgressView(context: Context, private val count: Int) : View(context) {
    var index = 0
        set(value) { field = value; invalidate() }
    /** Postęp bieżącego slajdu (0..1). */
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (count <= 0) return
        val gap = resources.displayMetrics.density * 4f
        val w = (width - gap * (count - 1)) / count
        val h = height.toFloat()
        for (i in 0 until count) {
            val l = i * (w + gap)
            paint.color = 0x55FFFFFF
            rect.set(l, 0f, l + w, h); canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            val fill = if (i < index) 1f else if (i == index) fraction else 0f
            if (fill > 0f) {
                paint.color = 0xFFFFFFFF.toInt()
                rect.set(l, 0f, l + w * fill, h); canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            }
        }
    }
}
