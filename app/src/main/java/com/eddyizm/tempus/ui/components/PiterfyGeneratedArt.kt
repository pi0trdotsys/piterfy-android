package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.PowerManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import com.eddyizm.tempus.util.PiterfyCoverTheme
import com.eddyizm.tempus.util.PiterfyMotion

/**
 * Piterfy: okładki generowane dla playlist tworzonych przez serwer (miksy, listy przebojów, soundtracki gier) — te same
 * gradienty i emoji co na stronie (connect/web/piterfy.css, data-pf-art). Mapowanie nazwa → kolory/emoji jest czystą
 * funkcją ([spec]), a rysuje je [PiterfyGeneratedArtView]: gradient 135°, nazwa u góry i duże emoji w prawym dolnym rogu.
 */
object PiterfyGeneratedArt {
    enum class Kind { MIX, CHART, GAME }

    /** Dwa kolory gradientu (ARGB), emoji i podpis na okładce. */
    data class Spec(val from: Int, val to: Int, val emoji: String, val label: String)

    private const val MIX_PREFIX = "Piterfy Mix"
    private const val CHART_PREFIX = "Piterfy Chart"
    private const val GAME_PREFIX = "Piterfy Game"
    private const val GAME_NAME_PREFIX = "Soundtrack: "
    private const val FALLBACK_EMOJI = "🎵"

    private fun c(rgb: Int) = 0xFF000000.toInt() or rgb

    private val MIXES = mapOf(
        "happy" to Spec(c(0xFFB800), c(0xFF5E62), "😊", ""),
        "dark" to Spec(c(0x0F0C29), c(0x7B2FF7), "🌑", ""),
        "electronic" to Spec(c(0x00D4FF), c(0x7209B7), "⚡", ""),
        "bass boost" to Spec(c(0xFF0844), c(0x5B21B6), "🔊", ""),
        "chill" to Spec(c(0x11998E), c(0x38EF7D), "🌊", ""),
        "workout" to Spec(c(0xF12711), c(0xF5AF19), "🔥", ""),
        "rock" to Spec(c(0x232526), c(0xC31432), "🎸", ""),
        "hip-hop" to Spec(c(0xF7971E), c(0x8E2DE2), "🎤", ""),
        "french music" to Spec(c(0x0055A4), c(0xEF4135), "🥖", ""),
        "latin music" to Spec(c(0xFF512F), c(0xF09819), "💃", ""),
        "polska muzyka" to Spec(c(0xDC143C), c(0xFFFFFF), "🦅", ""),
        "polski rap" to Spec(c(0x8E0E00), c(0x1F1C18), "🎙️", ""),
        "german music" to Spec(c(0x232526), c(0xFFCE00), "🍺", ""),
        "asia pop" to Spec(c(0xFF9A9E), c(0xA18CD1), "🌸", ""),
        "brazil music" to Spec(c(0x00B09B), c(0xF9D423), "🥁", ""),
        "lata 80." to Spec(c(0xFC00FF), c(0x00DBDE), "📼", ""),
        "lata 90." to Spec(c(0x4776E6), c(0x8E54E9), "💿", ""),
        "lata 2000." to Spec(c(0x00C6FF), c(0x0072FF), "📀", ""),
        "phonk" to Spec(c(0x0F0C29), c(0xFF0844), "👻", ""),
        "rok temu o tej porze" to Spec(c(0x3A1C71), c(0xFFAF7B), "⏳", ""),
    )
    private val CHART = Spec(c(0xB30024), c(0xFFD1D9), "🏆", "")
    private val GAME = Spec(c(0x1D976C), c(0x93F9B9), "🎮", "")

    /** Rodzaj playlisty po początku komentarza ustawianym przez serwer (null — to zwykła playlista). */
    @JvmStatic
    fun kindOf(comment: String?): Kind? = when {
        comment == null -> null
        comment.startsWith(MIX_PREFIX) -> Kind.MIX
        comment.startsWith(CHART_PREFIX) -> Kind.CHART
        comment.startsWith(GAME_PREFIX) -> Kind.GAME
        else -> null
    }

    /** Klucz miksu do tabeli: małe litery, bez końcowego słowa „Mix” („Hip-Hop Mix” → „hip-hop”). */
    internal fun mixKey(name: String?): String =
        (name ?: "").trim().lowercase().removeSuffix(" mix").trim()

    /** Podpis okładki: miks bez słowa „Mix”, gra bez „Soundtrack: ”, lista przebojów bez zmian. */
    @JvmStatic
    fun label(name: String?, kind: Kind): String {
        val full = (name ?: "").trim()
        return when (kind) {
            Kind.MIX -> {
                val short = full.replace(Regex("\\s*mix$", RegexOption.IGNORE_CASE), "").trim()
                short.ifEmpty { full }
            }
            Kind.GAME -> full.removePrefix(GAME_NAME_PREFIX).trim().ifEmpty { full }
            Kind.CHART -> full
        }
    }

    /** Kolory, emoji i podpis dla playlisty o danej nazwie i rodzaju; nieznany miks dostaje stały kolor z hasha nazwy. */
    @JvmStatic
    fun spec(name: String?, kind: Kind): Spec {
        val text = label(name, kind)
        return when (kind) {
            Kind.CHART -> CHART.copy(label = text)
            Kind.GAME -> GAME.copy(label = text)
            Kind.MIX -> MIXES[mixKey(name)]?.copy(label = text) ?: hashed(name ?: "", text)
        }
    }

    /** Deterministyczny gradient dla nieznanej nazwy (ten sam napis zawsze daje te same kolory). */
    internal fun hashed(name: String, text: String): Spec {
        val h = name.trim().lowercase().hashCode()
        val hue1 = (h and 0x7FFFFFFF) % 360
        val hue2 = (hue1 + 40 + ((h ushr 9) and 0x3F)) % 360
        return Spec(
            PiterfyCoverTheme.fromHsv(hue1.toDouble(), 0.75, 0.85),
            PiterfyCoverTheme.fromHsv(hue2.toDouble(), 0.80, 0.55),
            FALLBACK_EMOJI,
            text,
        )
    }
}

/**
 * Widok okładki: gradient 135° delikatnie „płynie” (tylko gdy animacje są włączone, widok jest widoczny i nie ma
 * oszczędzania energii), nazwa u góry po lewej, duże emoji w prawym dolnym rogu (nad przyciskiem play karty).
 * Emoji rysujemy tekstem; gdy czcionka systemu go nie ma (stare Androidy), po prostu go pomijamy.
 */
class PiterfyGeneratedArtView(context: Context, private val art: PiterfyGeneratedArt.Spec) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        setShadowLayer(6f * density, 0f, 2f * density, 0x55000000)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 17f * density
        setShadowLayer(6f * density, 0f, 1f * density, 0x66000000)
    }
    private val matrix = Matrix()
    private var shader: LinearGradient? = null
    private var label: StaticLayout? = null
    private var emojiVisible = false
    private var phase = 0.5f
    private var animator: ValueAnimator? = null
    private val tmp = Rect()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        emojiVisible = runCatching { emojiPaint.hasGlyph(art.emoji) }.getOrDefault(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // gradient dwa razy większy od widoku i lustrzany: przesuwanie go daje płynną zmianę barw (jak background-size 220% na stronie)
        shader = LinearGradient(0f, 0f, w * 2f, h * 2f, art.from, art.to, Shader.TileMode.MIRROR)
        fill.shader = shader
        emojiPaint.textSize = h * 0.38f
        val pad = (12f * density).toInt()
        val width = ((w - 2 * pad) * 0.78f).toInt().coerceAtLeast(1)
        label = StaticLayout.Builder.obtain(art.label, 0, art.label.length, labelPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        applyPhase()
    }

    private fun applyPhase() {
        val s = shader ?: return
        matrix.setTranslate(-phase * width * 0.9f, -phase * height * 0.9f)
        s.setLocalMatrix(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val pad = 12f * density
        label?.let {
            canvas.save()
            canvas.translate(pad, pad)
            it.draw(canvas)
            canvas.restore()
        }
        if (emojiVisible) {
            // wyżej niż przycisk play (40 dp + 8 dp marginesu), żeby go nie zasłaniał
            val baseline = height - 50f * density
            canvas.drawText(art.emoji, width - 10f * density, baseline, emojiPaint)
        }
    }

    private fun animationAllowed(): Boolean {
        if (!PiterfyMotion.enabled()) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return power?.isPowerSaveMode != true
    }

    private fun startFlow() {
        if (animator != null || !animationAllowed() || !isShown) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                // karty poza ekranem (przewinięta półka) nie przerysowują się
                if (getGlobalVisibleRect(tmp)) {
                    applyPhase()
                    invalidate()
                }
            }
            start()
        }
    }

    private fun stopFlow() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFlow()
    }

    override fun onDetachedFromWindow() {
        stopFlow()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startFlow() else stopFlow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startFlow() else stopFlow()
    }
}
