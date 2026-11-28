package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.eddyizm.tempus.util.PiterfyMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Piterfy: „wybuch” serca przy polubieniu: przycisk sprężyście odbija się od zmniejszonej skali (prawdziwa fizyka sprężyny,
 * nie krzywa) i rozsypuje kilka punktów, które rozchodzą się i gasną. Pomija się przy wyłączonych animacjach systemu.
 */
object PiterfyHeartBurst {
    const val PARTICLES = 8
    private const val DURATION_MS = 520L

    /** Położenie cząstki [index] z [count] po postępie [progress] (0–1): promień rośnie z wyhamowaniem, kąty równo co 360°/count. */
    @JvmStatic
    fun particle(index: Int, count: Int, progress: Float, reach: Float): Pair<Float, Float> {
        val angle = (2.0 * PI * index / count.coerceAtLeast(1)) - PI / 2
        val p = progress.coerceIn(0f, 1f)
        val radius = reach * (1f - (1f - p) * (1f - p))
        return (radius * cos(angle)).toFloat() to (radius * sin(angle)).toFloat()
    }

    /** Przezroczystość cząstki: pełna przez pierwszą połowę, potem gaśnie do zera. */
    @JvmStatic
    fun particleAlpha(progress: Float): Float = (1f - ((progress.coerceIn(0f, 1f) - 0.45f) / 0.55f).coerceIn(0f, 1f))

    @JvmStatic @JvmOverloads
    fun play(view: View, color: Int = 0xFFFF4D6D.toInt()) {
        if (!PiterfyMotion.enabled()) return
        view.scaleX = 0.55f; view.scaleY = 0.55f
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { property ->
            SpringAnimation(view, property, 1f).apply {
                spring = SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(SpringForce.DAMPING_RATIO_HIGH_BOUNCY)
                start()
            }
        }
        val parent = view.parent as? ViewGroup ?: return
        val reach = view.width * 0.85f
        val cx = (view.left + view.right) / 2f
        val cy = (view.top + view.bottom) / 2f
        val dot = view.resources.displayMetrics.density * 2.6f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val drawable = object : Drawable() {
            var progress = 0f
            override fun draw(canvas: Canvas) {
                paint.alpha = (255 * particleAlpha(progress)).toInt()
                for (i in 0 until PARTICLES) {
                    val (dx, dy) = particle(i, PARTICLES, progress, reach)
                    canvas.drawCircle(cx + dx, cy + dy, dot * (1f - 0.4f * progress), paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        drawable.setBounds(0, 0, parent.width, parent.height)
        parent.overlay.add(drawable)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { drawable.progress = it.animatedValue as Float; drawable.invalidateSelf() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { parent.overlay.remove(drawable) }
            })
            start()
        }
    }
}
