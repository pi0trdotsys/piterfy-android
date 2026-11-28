package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.PiterfyCoverTheme
import com.eddyizm.tempus.util.PiterfyDisplayPlan
import com.eddyizm.tempus.util.PiterfyMotionPlan
import com.google.android.material.color.MaterialColors

/**
 * Piterfy: mała makieta „Teraz gra” na stronie Wygląd. Odzwierciedla na żywo to, co da się pokazać bez przebudowy aplikacji:
 * szkło (przezroczystość i krawędź karty), kolory z okładki (tint tła), prawdziwą czerń OLED, rozmiar tekstu, gęstość i ruch
 * (pasek postępu płynie tylko wtedy, gdy ruch jest włączony). Wszystko wyliczane w [PiterfyDisplayPlan.preview].
 */
class PiterfyLookPreview(context: Context) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = Math.round(v * d)

    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val cover = View(context)
    private val title = TextView(context).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
    private val artist = TextView(context).apply { maxLines = 1; alpha = 0.7f }
    private val track = FrameLayout(context)
    private val fill = View(context)
    private val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
    private val dots = List(3) { View(context) }
    private var animator: ValueAnimator? = null
    private var progress = 0.35f

    init {
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        title.setText(R.string.ux_preview_title)
        artist.setText(R.string.ux_preview_artist)
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(cover, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title)
        texts.addView(artist)
        top.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) })
        card.addView(top)
        track.addView(fill, LayoutParams(0, LayoutParams.MATCH_PARENT))
        card.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f)))
        dots.forEachIndexed { i, dot ->
            val size = if (i == 1) 40f else 30f
            buttons.addView(dot, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(8f); marginEnd = dp(8f) })
        }
        card.addView(buttons)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentDescription = context.getString(R.string.ux_section_preview)
        track.post { applyProgress() }
        track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyProgress() }
    }

    private fun applyProgress() {
        val lp = fill.layoutParams
        lp.width = (track.width * progress).toInt()
        fill.layoutParams = lp
    }

    private fun round(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1f), stroke)
    }

    /** Odświeża makietę po zmianie dowolnego ustawienia. */
    fun update(p: PiterfyDisplayPlan.Preview, accent: Int, sample: Int) {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0xFF121212.toInt())
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val base = if (p.trueBlack) Color.BLACK else surface
        // tło strony: z kolorem okładki jako gradient (jak nagłówki albumów), inaczej gładkie
        background = if (p.coverTint) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, PiterfyCoverTheme.headerGradient(sample, base, false)).apply { cornerRadius = dp(18f).toFloat() }
        } else round(base, 18f)
        val cardColor = PiterfyCoverTheme.withAlpha(if (p.trueBlack) 0xFF101010.toInt() else PiterfyCoverTheme.mix(base, onSurface, 0.08f), p.glassAlpha)
        card.background = round(cardColor, 16f, if (p.glass) PiterfyCoverTheme.withAlpha(Color.WHITE, 0.28f) else 0)
        val pad = Math.round(dp(12f) * p.density)
        card.setPadding(pad, pad, pad, pad)
        cover.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(sample, accent)).apply { cornerRadius = dp(10f).toFloat() }
        title.setTextColor(onSurface); artist.setTextColor(onSurface)
        title.textSize = 16f * p.textScale
        artist.textSize = 13f * p.textScale
        track.background = round(PiterfyCoverTheme.withAlpha(onSurface, 0.18f), 2f)
        fill.background = round(accent, 2f)
        dots.forEachIndexed { i, dot -> dot.background = round(if (i == 1) accent else PiterfyCoverTheme.withAlpha(onSurface, 0.22f), 20f) }
        (buttons.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Math.round(dp(12f) * p.density)
        (track.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Math.round(dp(12f) * p.density)
        runAnimation(p.animated)
    }

    private fun runAnimation(on: Boolean) {
        animator?.cancel()
        animator = null
        if (!on) { progress = 0.35f; applyProgress(); return }
        animator = ValueAnimator.ofFloat(0.1f, 0.9f).apply {
            duration = PiterfyMotionPlan.SLOW_MS * 12
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { progress = it.animatedValue as Float; applyProgress() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
