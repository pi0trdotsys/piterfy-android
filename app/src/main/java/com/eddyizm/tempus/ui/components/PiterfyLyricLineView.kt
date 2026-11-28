package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.util.PiterfyLyricsKaraoke
import kotlin.math.max
import kotlin.math.min

/**
 * Piterfy: jedna linijka tekstu piosenki w stylu Apple Music. Rysuje tekst sama (StaticLayout), żeby wypełnienie „karaoke”
 * mogło iść wiersz po wierszu gradientem, a rozmycie, skala i przezroczystość to zwykłe właściwości widoku
 * (animuje je PlayerLyricsFragment). Logika postępu i stylu: PiterfyLyricsKaraoke.
 */
class PiterfyLyricLineView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics)
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 800, false) else Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private var layout: StaticLayout? = null
    private var shown = ""
    // tłumaczenie pod linijką: mniejsze, przygaszone, bez wypełnienia „karaoke”
    private val translationPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD
        color = ColorUtils.setAlphaComponent(Color.WHITE, 150)
    }
    private var translationLayout: StaticLayout? = null
    private var shownTranslation = ""
    private val translationGap = (4 * density).toInt()
    private val padH = (14 * density).toInt()
    private val padV = (7 * density).toInt()
    private var activeLine = false
    private var progress = 0f
    private var blurAppliedDp = -1f

    /** Odległość od aktywnej linii, dla której ostatnio ustawiono styl (-1 = jeszcze nie ustawiano). */
    var styledFor = Int.MIN_VALUE

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(padH, padV, padH, padV)
    }

    var text: CharSequence
        get() = shown
        set(value) {
            shown = value.toString()
            contentDescription = if (shownTranslation.isEmpty()) shown else "$shown. $shownTranslation"
            layout = null
            requestLayout(); invalidate()
        }

    /** Tłumaczenie pod linijką (null lub puste: bez tłumaczenia). */
    var translation: CharSequence?
        get() = shownTranslation.ifEmpty { null }
        set(value) {
            val next = value?.toString()?.trim().orEmpty()
            if (next == shownTranslation) return
            shownTranslation = next
            contentDescription = if (next.isEmpty()) shown else "$shown. $next"
            translationLayout = null
            requestLayout(); invalidate()
        }

    val isActiveLine: Boolean get() = activeLine

    /** Aktywna linia: pogrubiona poświata i wypełnienie „karaoke”; pozostałe są jednolicie białe (przygasza je alfa widoku). */
    fun setActiveLine(active: Boolean) {
        if (activeLine == active) return
        activeLine = active
        paint.setShadowLayer(if (active) 10f * density else 0f, 0f, 0f, ColorUtils.setAlphaComponent(Color.WHITE, 70))
        invalidate()
    }

    /** Postęp wypełnienia 0–1 (tylko dla aktywnej linii); drobne zmiany pomijamy, żeby nie rysować bez potrzeby. */
    fun setProgress(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(v - progress) < 0.002f && v != 0f && v != 1f) return
        progress = v
        if (activeLine) invalidate()
    }

    /** Szklana pigułka pod aktywną linią (Liquid Glass); null zdejmuje tło. */
    fun setGlassPill(on: Boolean, fillAlpha: Int, edgeAlpha: Int) {
        background = if (!on) null else GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(ColorUtils.setAlphaComponent(Color.WHITE, fillAlpha.coerceIn(0, 255)))
            setStroke(max(1, density.toInt()), ColorUtils.setAlphaComponent(Color.WHITE, edgeAlpha.coerceIn(0, 255)))
        }
    }

    /** Rozmycie w dp (RenderEffect, tylko API 31+; niżej nic nie robi — wystarcza przygaszenie). */
    fun setBlurDp(dp: Float) {
        if (Build.VERSION.SDK_INT < 31 || dp == blurAppliedDp) return
        blurAppliedDp = dp
        applyBlur(dp)
    }

    @RequiresApi(31)
    private fun applyBlur(dp: Float) {
        setRenderEffect(if (dp <= 0f) null else RenderEffect.createBlurEffect(dp * density, dp * density, Shader.TileMode.CLAMP))
    }

    private fun buildLayout(width: Int): StaticLayout {
        val inner = max(width - paddingLeft - paddingRight, 1)
        val built = StaticLayout.Builder.obtain(shown, 0, shown.length, paint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f * density, 1f).setIncludePad(false).build()
        layout = built
        translationLayout = if (shownTranslation.isEmpty()) null
        else StaticLayout.Builder.obtain(shownTranslation, 0, shownTranslation.length, translationPaint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1f * density, 1f).setIncludePad(false).build()
        return built
    }

    private fun translationHeight(): Int = translationLayout?.let { it.height + translationGap } ?: 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val l = buildLayout(width)
        setMeasuredDimension(width, l.height + translationHeight() + paddingTop + paddingBottom)
        pivotX = 0f
        pivotY = measuredHeight / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = 0f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: buildLayout(width)
        drawLine(canvas, l)
        translationLayout?.let { tr ->
            translationPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, if (activeLine) 210 else 150)
            canvas.save(); canvas.translate(paddingLeft.toFloat(), (paddingTop + l.height + translationGap).toFloat()); tr.draw(canvas); canvas.restore()
        }
    }

    private fun drawLine(canvas: Canvas, l: StaticLayout) {
        if (!activeLine || progress >= 1f) {
            paint.shader = null
            paint.color = Color.WHITE
            canvas.save(); canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat()); l.draw(canvas); canvas.restore()
            return
        }
        val widths = FloatArray(l.lineCount) { l.getLineWidth(it) }
        val fills = PiterfyLyricsKaraoke.fillPerLine(widths, progress)
        val dim = ColorUtils.setAlphaComponent(Color.WHITE, 107)   // ~0.42 jak na stronie
        val soft = 22f * density
        for (i in 0 until l.lineCount) {
            val x = paddingLeft + l.getLineLeft(i)
            val w = max(widths[i], 1f)
            val baseline = (paddingTop + l.getLineBaseline(i)).toFloat()
            val edge = min(soft / w, 0.4f)
            val fill = fills[i]
            paint.shader = when {
                fill <= 0f -> null
                else -> LinearGradient(x, 0f, x + w, 0f, intArrayOf(Color.WHITE, Color.WHITE, dim, dim),
                    floatArrayOf(0f, fill, min(1f, fill + edge), 1f), Shader.TileMode.CLAMP)
            }
            paint.color = if (fill <= 0f) dim else Color.WHITE
            canvas.drawText(shown, l.getLineStart(i), l.getLineEnd(i), x, baseline, paint)
        }
        paint.shader = null
    }
}
