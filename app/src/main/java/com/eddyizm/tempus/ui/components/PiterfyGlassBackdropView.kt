package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.CanvasScene
import com.eddyizm.tempus.util.PiterfyCanvasScene
import com.eddyizm.tempus.util.PiterfyGlassPlan
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * Piterfy: tło pod szkłem — wolno dryfujące poświaty w kolorach motywu (scena „aurora” z PiterfyCanvasScene, ta sama co na stronie).
 * Szkło musi mieć co rozmywać; bez poświat byłoby szare. Najwyżej 30 klatek/s, a przy oszczędzaniu energii i wyłączonych
 * animacjach systemu rysujemy jedną nieruchomą klatkę.
 */
class PiterfyGlassBackdropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var scene: CanvasScene? = null
    private var strength = 0.6f
    private var animate = false
    private var looping = false
    private var clock = 0.0
    private var lastMs = 0L
    private val choreographer: Choreographer = Choreographer.getInstance()
    private val callback = Choreographer.FrameCallback { onFrame(it / 1_000_000) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glows = HashMap<Int, RadialGradient>()

    /** Ustawia kolory z motywu i siłę z planu; odświeża pętlę animacji. */
    fun apply(plan: PiterfyGlassPlan.Plan) {
        val accent = PiterfyAppearance.effectiveTheme()?.accent?.toInt()
            ?: MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0xFF1DB954.toInt())
        val hex = "#%06x".format(accent and 0xFFFFFF)
        scene = PiterfyCanvasScene.buildScene("aurora", PiterfyCanvasScene.hash32("piterfy-glass"), PiterfyCanvasScene.palette(hex))
        strength = 0.45f + 0.55f * (plan.sheenAlpha / 0.22f).coerceIn(0f, 1f)
        animate = plan.animate
        updateLoop()
        invalidate()
    }

    private fun updateLoop() {
        val run = animate && scene != null && isShown && isAttachedToWindow
        if (run && !looping) { looping = true; lastMs = 0L; choreographer.postFrameCallback(callback) }
        else if (!run && looping) { looping = false; choreographer.removeFrameCallback(callback) }
    }

    private fun onFrame(nowMs: Long) {
        if (!looping) return
        choreographer.postFrameCallback(callback)
        if (lastMs == 0L) { lastMs = nowMs; return }
        val gap = nowMs - lastMs
        if (gap < 1000L / PiterfyCanvasScene.FPS - 4) return
        clock += min(0.25, gap / 1000.0) * 0.6   // wolniej niż Canvas odtwarzacza: to tło, nie widowisko
        lastMs = nowMs
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); updateLoop() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateLoop() }

    override fun onDraw(canvas: Canvas) {
        val sc = scene ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = max(w, h) * 0.9f   // plamy względem dłuższego boku: na wysokim ekranie nie giną
        for (op in PiterfyCanvasScene.layout(sc, clock)) {
            if (op.kind != "blob") continue
            val color = PiterfyCanvasScene.argb(op.color)
            val glow = glows.getOrPut(color) { RadialGradient(0f, 0f, 1f, color or (0xFF shl 24), color and 0x00FFFFFF, Shader.TileMode.CLAMP) }
            paint.shader = glow
            paint.alpha = (max(0.0, min(1.0, op.alpha * strength * 0.55)) * 255).toInt()
            canvas.save()
            canvas.translate((op.x * w).toFloat(), (op.y * h).toFloat())
            val r = (op.r * unit).toFloat()
            canvas.scale(r, r)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        paint.shader = null
    }
}
