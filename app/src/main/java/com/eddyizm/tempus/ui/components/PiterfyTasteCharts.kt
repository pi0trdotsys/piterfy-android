package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.util.PiterfyTasteModel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Piterfy: wykresy ekranu „Mój gust”, rysowane bez bibliotek na bazie [AnimatedChart] (wjeżdżają animacją, a przy
 * wyłączonych animacjach systemu pojawiają się od razu). Odpowiedniki wykresów ze strony connect/stack/gust/index.html,
 * dopasowane do szerokości telefonu.
 */

/** Odsłuchy dzień po dniu: cienkie słupki na całą szerokość, weekendy jaśniej, co kilka dni data „d.M”. */
internal class DailyBarsView(
    context: Context, private val days: List<Pair<String, Int>>, private val labels: List<String>,
    private val color: Int, private val track: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weekend = days.map { PiterfyTasteModel.isWeekend(it.first) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), dp(170f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val maxN = days.maxOf { it.second }.coerceAtLeast(1)
        val bottom = height - dp(20f)
        val top = dp(6f)
        val slot = width / days.size.toFloat()
        val every = ceil(days.size / 8.0).toInt().coerceAtLeast(1)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(10.5f)
        days.forEachIndexed { i, (_, n) ->
            val h = max(if (n > 0) dp(3f) else dp(1.2f), (bottom - top) * (n / maxN.toFloat())) * progress
            val x = slot * i
            paint.style = Paint.Style.FILL
            paint.color = if (n > 0) ColorUtils.setAlphaComponent(color, if (weekend[i]) 255 else 205) else track
            val r = min(dp(4f), slot * 0.3f)
            canvas.drawRoundRect(RectF(x + slot * 0.16f, bottom - h, x + slot * 0.84f, bottom), r, r, paint)
            if (i % every == 0) {
                paint.color = muted
                canvas.drawText(labels[i], x + slot / 2, height - dp(5f), paint)
            }
        }
    }
}

/** Półokrągły miernik (energia, taneczność, walencja, tempo): wartość pod łukiem, podpis pod wartością. */
internal class GaugeView(
    context: Context, private val fraction: Float, private val value: String, private val label: String,
    private val color: Int, private val track: Int, private val ink: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(70f).toInt(), widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.5f + dp(34f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = max(dp(7f), width * 0.085f)
        val pad = stroke / 2 + dp(2f)
        val radius = (width - 2 * pad) / 2f
        val cx = width / 2f
        val cy = pad + radius
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
        paint.color = track
        canvas.drawArc(box, 180f, 180f, false, paint)
        paint.color = color
        canvas.drawArc(box, 180f, 180f * fraction.coerceIn(0f, 1f) * progress, false, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = ink; paint.textSize = dp(14f)
        canvas.drawText(value, cx, cy + dp(15f), paint)
        paint.typeface = Typeface.DEFAULT
        paint.color = muted; paint.textSize = dp(11f)
        canvas.drawText(label, cx, cy + dp(31f), paint)
    }
}

/**
 * Mapa nastroju: energia (pionowo) i walencja (poziomo) słuchanych utworów; kropka rośnie z pierwiastkiem odsłuchów,
 * a jej kolor zależy od ćwiartki (euforia, napięcie, spokój, melancholia).
 */
internal class MoodMapView(
    context: Context, private val dots: List<PiterfyTasteModel.Dot>, private val colors: Map<PiterfyTasteModel.Quadrant, Int>,
    private val names: Map<PiterfyTasteModel.Quadrant, String>, private val axisX: String, private val axisY: String,
    private val grid: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maxPlays = dots.maxOfOrNull { it.plays } ?: 0L
    private val ordered = dots.sortedBy { it.plays }
    private val quadrants = listOf(
        Triple(PiterfyTasteModel.Quadrant.NAPIECIE, 0f, 0f), Triple(PiterfyTasteModel.Quadrant.EUFORIA, 0.5f, 0f),
        Triple(PiterfyTasteModel.Quadrant.MELANCHOLIA, 0f, 0.5f), Triple(PiterfyTasteModel.Quadrant.SPOKOJ, 0.5f, 0.5f),
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(200f).toInt(), widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.95f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val left = dp(22f); val right = width - dp(6f); val top = dp(6f); val bottom = height - dp(22f)
        val pw = right - left; val ph = bottom - top
        // tło ćwiartek
        paint.style = Paint.Style.FILL
        quadrants.forEach { (q, vx, ey) ->
            paint.color = ColorUtils.setAlphaComponent(colors.getValue(q), 18)
            canvas.drawRoundRect(RectF(left + vx * pw, top + ey * ph, left + (vx + 0.5f) * pw, top + (ey + 0.5f) * ph), dp(8f), dp(8f), paint)
        }
        // osie środkowe
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = grid
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(6f)), 0f)
        canvas.drawLine(left + pw / 2, top, left + pw / 2, bottom, paint)
        canvas.drawLine(left, top + ph / 2, right, top + ph / 2, paint)
        paint.pathEffect = null
        // kropki (większe na wierzchu, wjeżdżają od środka)
        paint.style = Paint.Style.FILL
        ordered.forEach { d ->
            val q = PiterfyTasteModel.quadrant(d.energy, d.valence)
            val r = (dp(2.4f) + dp(8.5f) * sqrt(PiterfyTasteModel.dotSize(d.plays, maxPlays))) * progress
            val x = left + d.valence.toFloat().coerceIn(0f, 1f) * pw
            val y = top + (1f - d.energy.toFloat().coerceIn(0f, 1f)) * ph
            paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(colors.getValue(q), 140)
            canvas.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = ColorUtils.setAlphaComponent(colors.getValue(q), 230)
            canvas.drawCircle(x, y, r, paint)
        }
        // podpisy ćwiartek i osi
        paint.style = Paint.Style.FILL; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = dp(10.5f); paint.letterSpacing = 0.08f
        fun corner(q: PiterfyTasteModel.Quadrant, x: Float, y: Float, align: Paint.Align) {
            paint.color = colors.getValue(q); paint.textAlign = align
            canvas.drawText(names.getValue(q).uppercase(), x, y, paint)
        }
        corner(PiterfyTasteModel.Quadrant.NAPIECIE, left + dp(8f), top + dp(16f), Paint.Align.LEFT)
        corner(PiterfyTasteModel.Quadrant.EUFORIA, right - dp(8f), top + dp(16f), Paint.Align.RIGHT)
        corner(PiterfyTasteModel.Quadrant.MELANCHOLIA, left + dp(8f), bottom - dp(8f), Paint.Align.LEFT)
        corner(PiterfyTasteModel.Quadrant.SPOKOJ, right - dp(8f), bottom - dp(8f), Paint.Align.RIGHT)
        paint.typeface = Typeface.DEFAULT; paint.letterSpacing = 0f; paint.textSize = dp(10f); paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(axisX, left + pw / 2, height - dp(6f), paint)
        canvas.save(); canvas.rotate(-90f, dp(11f), top + ph / 2)
        canvas.drawText(axisY, dp(11f), top + ph / 2 + dp(3f), paint)
        canvas.restore()
    }
}

/** Zegar słuchania: siatka 7 dni × 24 godziny, jaśniejsza komórka = więcej scrobbli; podpis godziny co trzy. */
internal class ClockHeatView(
    context: Context, private val days: List<String>, private val grid: List<List<Int>>,
    private val color: Int, private val track: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val max = grid.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
    private val labelWidth = dp(24f)
    private val header = dp(16f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(240f).toInt(), widthMeasureSpec)
        val cell = (w - labelWidth) / 24f
        setMeasuredDimension(w, (header + cell * max(grid.size, 1)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cell = (width - labelWidth) / 24f
        paint.textSize = dp(9.5f); paint.color = muted
        paint.textAlign = Paint.Align.CENTER
        for (h in 0 until 24 step 3) canvas.drawText(h.toString(), labelWidth + cell * h + cell / 2, header - dp(4f), paint)
        grid.forEachIndexed { d, row ->
            paint.color = muted; paint.textAlign = Paint.Align.LEFT; paint.textSize = dp(10f)
            canvas.drawText(days.getOrElse(d) { "" }, 0f, header + cell * d + cell * 0.68f, paint)
            row.take(24).forEachIndexed { h, n ->
                val level = PiterfyTasteModel.clockLevel(n, max) * progress
                paint.style = Paint.Style.FILL
                paint.color = if (n > 0) ColorUtils.setAlphaComponent(color, (70 + 185 * level).toInt().coerceIn(0, 255)) else track
                val x = labelWidth + cell * h
                canvas.drawRoundRect(RectF(x + 1.5f, header + cell * d + 1.5f, x + cell - 1.5f, header + cell * (d + 1) - 1.5f), dp(3f), dp(3f), paint)
            }
        }
    }
}

/** Pierścień z kilku części (polubione, w koszu, reszta) i napisem w środku. */
internal class DonutView(
    context: Context, private val parts: List<Pair<Int, Int>>, private val centerText: String, private val caption: String,
    private val track: Int, private val ink: Int, private val muted: Int,
) : AnimatedChart(context, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val total = parts.sumOf { it.first }.coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(132f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(14f)
        val box = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.BUTT
        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)
        var from = -90f
        parts.forEach { (n, color) ->
            val sweep = 360f * n / total * progress
            if (n > 0) { paint.color = color; canvas.drawArc(box, from, max(sweep - 1.5f, 0.5f), false, paint) }
            from += 360f * n / total * progress
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = ink; paint.textSize = dp(24f)
        canvas.drawText(centerText, width / 2f, height / 2f + dp(6f), paint)
        paint.typeface = Typeface.DEFAULT; paint.color = muted; paint.textSize = dp(11f)
        canvas.drawText(caption, width / 2f, height / 2f + dp(22f), paint)
    }
}
