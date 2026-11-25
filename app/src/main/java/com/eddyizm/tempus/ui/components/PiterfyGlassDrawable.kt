package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.annotation.RequiresApi
import com.eddyizm.tempus.util.PiterfyGlassPlan
import kotlin.math.abs

/**
 * Piterfy: „Liquid Glass” jako tło widoku. Warstwy od spodu: (opcjonalnie, Android 12+) rozmyta zawartość widoku za spodem,
 * półprzezroczyste wypełnienie w kolorze powierzchni motywu, połysk u góry, „pigułka” zaznaczenia (dla dolnej nawigacji)
 * i obwódka z odblaskiem od lewego górnego rogu. Wartości biorą się z [PiterfyGlassPlan.Plan].
 *
 * Rozmycie rysujemy ze źródła ([blurSource]) przez RenderNode z RenderEffect — tylko dla paska nawigacji i mini odtwarzacza.
 * Błąd rozmycia wyłącza je i zostaje samo półprzezroczyste szkło.
 */
class PiterfyGlassDrawable(
    private val surfaceColor: Int,
    private val radiusPx: Float,
    private val plan: PiterfyGlassPlan.Plan,
    private val density: Float,
    private val topCornersOnly: Boolean = false,
) : Drawable() {
    /** Widok, którego zawartość rozmywamy pod szkłem (rodzeństwo hosta, nigdy jego przodek), i widok, któremu to tło służy. */
    var blurSource: View? = null
    var blurHost: View? = null
    /** Prostokąt zaznaczonej pozycji (we współrzędnych hosta) albo null — rysowany jako „pigułka”, płynnie przesuwana. */
    var pillProvider: (() -> RectF?)? = null

    private var userAlpha = 255
    private var renderNode: RenderNode? = null
    private var blurBroken = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val pill = RectF()
    private var pillAnimator: ValueAnimator? = null
    private var pillTarget: RectF? = null

    /** Kolor z krytyciem a (0–1) pomnożonym przez ogólną przezroczystość drawable (animowaną np. przy rozwijaniu odtwarzacza). */
    private fun withAlpha(color: Int, a: Float): Int {
        val alpha = (a * userAlpha).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun rebuildPath() {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        path.rewind()
        val r = radiusPx
        val radii = if (topCornersOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else floatArrayOf(r, r, r, r, r, r, r, r)
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        rebuildPath()
        val h = bounds.height().toFloat()
        sheen.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.top + h * 0.55f,
            0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x1FFFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(path)
        if (Build.VERSION.SDK_INT >= 31 && plan.blur && !blurBroken) drawBackdropBlur(canvas)
        fill.color = withAlpha(surfaceColor, plan.fillAlpha)
        canvas.drawPath(path, fill)
        sheen.alpha = (plan.sheenAlpha * userAlpha).toInt().coerceIn(0, 255)
        canvas.drawPath(path, sheen)
        drawPill(canvas)
        canvas.restoreToCount(save)
        edge.alpha = (plan.edgeAlpha * userAlpha).toInt().coerceIn(0, 255)
        canvas.drawPath(path, edge)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawBackdropBlur(canvas: Canvas) {
        val src = blurSource ?: return
        val host = blurHost ?: return
        if (!canvas.isHardwareAccelerated || !src.isAttachedToWindow || !host.isAttachedToWindow) return
        try {
            val node = renderNode ?: RenderNode("pf-glass-blur").also { renderNode = it }
            val radius = (plan.blurDp * density).coerceAtLeast(1f)
            node.setPosition(0, 0, bounds.width(), bounds.height())
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val hostLoc = IntArray(2)
            val srcLoc = IntArray(2)
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(srcLoc)
            val rc = node.beginRecording()
            try {
                rc.translate((srcLoc[0] - hostLoc[0] - bounds.left).toFloat(), (srcLoc[1] - hostLoc[1] - bounds.top).toFloat())
                src.draw(rc)
            } finally {
                node.endRecording()
            }
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawRenderNode(node)
            canvas.restore()
        } catch (e: Throwable) {
            blurBroken = true   // nie powtarzamy nieudanego rozmycia w każdej klatce
        }
    }

    private fun drawPill(canvas: Canvas) {
        val target = pillProvider?.invoke() ?: return
        if (pillTarget == null || !plan.animate) {
            pill.set(target)
            pillTarget = RectF(target)
        } else if (abs(target.left - pillTarget!!.left) > 1f || abs(target.right - pillTarget!!.right) > 1f) {
            startPillMove(target)
        }
        pillPaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.10f + plan.sheenAlpha * 0.6f)
        val r = pill.height() / 2f
        canvas.drawRoundRect(pill, r, r, pillPaint)
    }

    private fun startPillMove(target: RectF) {
        pillAnimator?.cancel()
        val from = RectF(pill)
        val to = RectF(target)
        pillTarget = to
        pillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                pill.set(from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f)
                invalidateSelf()
            }
            start()
        }
    }

    override fun getOutline(outline: Outline) {
        if (topCornersOnly) outline.setRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom + radiusPx.toInt(), radiusPx)
        else outline.setRoundRect(bounds, radiusPx)
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) { userAlpha = alpha; invalidateSelf() }
    override fun getAlpha() = userAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
