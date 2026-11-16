package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.PiterfyStatsFormat
import com.eddyizm.tempus.util.PiterfyStatsModel

/**
 * Piterfy: karta „Wrapped” jako obrazek do udostępnienia (4:5). Rysunek jest w układzie 1080 × 1350 i skalowany do
 * żądanej szerokości, w kolorach aktywnego motywu (także okolicznościowego). Odpowiednik karty z connect/web/stats.js.
 */
object PiterfyWrappedCard {
    private const val W = 1080f
    private const val H = 1350f
    private const val P = 84f

    private class Colors(val bg: Int, val deep: Int, val accent: Int, val accent2: Int, val text: Int, val muted: Int)

    private fun colors(): Colors {
        val t = PiterfyAppearance.effectiveTheme()
        val bg = t?.background?.toInt() ?: 0xFF000000.toInt()
        val accent = t?.accent?.toInt() ?: 0xFF1DB954.toInt()
        val accent2 = t?.accent2?.toInt() ?: 0xFFA4EDC2.toInt()
        val text = t?.text?.toInt() ?: Color.WHITE
        return Colors(bg, ColorUtils.blendARGB(bg, accent, 0.28f), accent, accent2, text, ColorUtils.setAlphaComponent(text, 170))
    }

    private fun face(ctx: Context, weight: Int): Typeface {
        val base = ResourcesCompat.getFont(ctx, R.font.figtree_family) ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    @JvmStatic @JvmOverloads fun render(ctx: Context, period: PiterfyStatsModel.Period, width: Int = 1080): Bitmap {
        val scale = width / W
        val bitmap = Bitmap.createBitmap(width, Math.round(H * scale), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        paintAll(ctx, canvas, period)
        return bitmap
    }

    private fun paintAll(ctx: Context, canvas: Canvas, p: PiterfyStatsModel.Period) {
        val c = colors()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(weight: Int, size: Float, color: Int, spacing: Float = 0f) = paint.apply {
            reset(); isAntiAlias = true
            typeface = face(ctx, weight); textSize = size; this.color = color; letterSpacing = spacing; textAlign = Paint.Align.LEFT
        }
        fun draw(value: String, x: Float, y: Float) = canvas.drawText(value, x, y, paint)
        fun measure(value: String) = paint.measureText(value)
        fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

        // tło: przekątny gradient motywu i dwie miękkie plamy koloru
        canvas.drawRect(0f, 0f, W, H, Paint().apply { shader = LinearGradient(0f, 0f, W, H, intArrayOf(c.deep, c.bg, c.bg), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
        listOf(Triple(W * 0.92f to 90f, 420f, c.accent to 0.35f), Triple(-40f to H * 0.62f, 380f, c.accent2 to 0.16f)).forEach { (at, radius, tint) ->
            val spot = Paint().apply {
                shader = RadialGradient(at.first, at.second, radius, intArrayOf(ColorUtils.setAlphaComponent(tint.first, (255 * tint.second).toInt()), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, W, H, spot)
        }
        // znak Piterfy: cztery słupki
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.accent }
        listOf(0f to 46f, 26f to 80f, 52f to 56f, 78f to 34f).forEach { (dx, h) ->
            canvas.drawRoundRect(RectF(W - P - 104 + dx, P + 4 + (80 - h), W - P - 104 + dx + 14, P + 84), 7f, 7f, bar)
        }

        text(700, 28f, c.accent, 0.2f); draw(s(R.string.stats_card_kicker), P, P + 28)
        text(800, 68f, c.text)
        val title = p.label + if (p.partial) " · " + s(R.string.stats_card_running) else ""
        draw(title, P, P + 112)

        // duża liczba zawsze mieści się w szerokości (np. „412 godz.” przy 210 px byłoby za szerokie)
        val big = PiterfyStatsFormat.listening(p.minutes)
        var bigSize = 210f
        while (bigSize > 90f) { text(800, bigSize, c.accent); if (measure(big) <= W - P * 2) break; bigSize -= 10f }
        val bigBase = 112f + 84f + bigSize * 0.92f
        text(800, bigSize, c.accent); draw(big, P - 6, bigBase + 40)
        var y = bigBase + 112
        text(600, 38f, c.muted); draw(s(R.string.stats_card_listening, PiterfyStatsFormat.num(p.plays)), P, y)

        val compare = p.compare
        if (compare?.plays != null) {
            val up = compare.plays > 0
            val flat = compare.plays == 0
            val label = if (flat) s(R.string.stats_delta_flat) else s(R.string.stats_delta_plays, PiterfyStatsFormat.delta(compare.plays))
            text(700, 30f, if (flat) c.text else if (up) 0xFF5FF0A3.toInt() else 0xFFFF9A9A.toInt())
            val w = measure(label) + 52
            val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (flat) 0x24FFFFFF else if (up) 0x383DDC84 else 0x38FF7878 }
            canvas.drawRoundRect(RectF(P, y + 28, P + w, y + 86), 29f, 29f, chip)
            draw(label, P + 26, y + 68)
            y += 86
        }

        fun label(value: String, ly: Float) { text(700, 24f, c.muted, 0.2f); draw(value, P, ly) }
        y += 78
        p.listener?.let { listener ->
            label(s(R.string.stats_card_listener), y)
            text(800, 78f, c.text); draw(listener.name, P, y + 80)
            text(500, 33f, c.muted)
            val lines = PiterfyStatsFormat.wrap(listener.blurb, W - P * 2, 2) { measure(it) }
            lines.forEachIndexed { i, line -> draw(line, P, y + 132 + i * 44) }
            y += 132 + (lines.size - 1).coerceAtLeast(0) * 44 + 70
        }

        label(s(R.string.stats_card_top), y)
        p.topArtists.take(3).forEachIndexed { i, artist ->
            val ry = y + 62 + i * 56
            text(800, 38f, c.accent); draw((i + 1).toString(), P, ry)
            text(700, 40f, c.text)
            val room = W - P * 2 - 62 - 150
            var name = artist.label
            while (name.length > 1 && measure(name) > room) name = name.dropLast(1)
            draw(if (name == artist.label) name else name.trimEnd() + "…", P + 56, ry)
            text(600, 32f, c.muted).textAlign = Paint.Align.RIGHT
            draw("${artist.n}×", W - P, ry)
        }
        y += 62 + (p.topArtists.take(3).size - 1).coerceAtLeast(0) * 56 + 70

        p.topTracks.firstOrNull()?.takeIf { y < H - 190 }?.let { track ->
            label(s(R.string.stats_card_track), y)
            text(800, 42f, c.text)
            draw(PiterfyStatsFormat.wrap(track.title, W - P * 2, 1) { measure(it) }.firstOrNull().orEmpty(), P, y + 54)
            text(500, 32f, c.muted)
            draw(PiterfyStatsFormat.wrap("${track.artist} · ${track.plays}×", W - P * 2, 1) { measure(it) }.firstOrNull().orEmpty(), P, y + 98)
        }
        text(600, 28f, ColorUtils.setAlphaComponent(c.muted, 200)); draw("piterfy", P, H - 50)
    }
}
