package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.PiterfyLyricCardLayout

/**
 * Piterfy: karta z jedną linijką piosenki do udostępnienia (4:5, rysunek 1080 × 1350 skalowany do szerokości): rozmyta okładka
 * w tle, duży cytat dopasowany do ramki, opcjonalnie tłumaczenie, a na dole okładka, tytuł, wykonawca i znak „piterfy”.
 * Układ i kolory jak karta ze strony (connect/web/lyrics.js `renderCard`); zawijanie: PiterfyLyricCardLayout.
 */
object PiterfyLyricCard {
    private const val W = 1080f
    private const val H = 1350f
    private const val P = 96f

    private fun face(ctx: Context, weight: Int): Typeface {
        val base = ResourcesCompat.getFont(ctx, R.font.figtree_family) ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    @JvmStatic @JvmOverloads
    fun render(ctx: Context, text: String, translation: String?, title: String?, artist: String?, cover: Bitmap?, width: Int = 1080): Bitmap {
        val scale = width / W
        val bitmap = Bitmap.createBitmap(width, Math.round(H * scale), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        paintAll(ctx, canvas, text, translation?.takeIf { it.isNotBlank() }, title.orEmpty(), artist.orEmpty(), cover)
        return bitmap
    }

    private fun paintAll(ctx: Context, canvas: Canvas, text: String, translation: String?, title: String, artist: String, cover: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun style(weight: Int, size: Float, color: Int) = paint.apply {
            reset(); isAntiAlias = true; typeface = face(ctx, weight); textSize = size; this.color = color; textAlign = Paint.Align.LEFT
        }
        canvas.drawColor(0xFF101A15.toInt())
        if (cover != null && cover.width > 0 && cover.height > 0) {
            // rozmycie: mały kwadrat rozciągnięty z filtrem (tanio i na każdym API)
            val side = minOf(cover.width, cover.height)
            val tiny = Bitmap.createBitmap(18, 22, Bitmap.Config.ARGB_8888)
            Canvas(tiny).drawBitmap(cover, Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2), Rect(0, 0, 18, 22), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(tiny, null, RectF(0f, 0f, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
            tiny.recycle()
        }
        canvas.drawRect(0f, 0f, W, H, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0x4D000000, 0x80000000.toInt(), 0xC7000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        style(800, 260f, 0x38FFFFFF); canvas.drawText("“", P - 12, 330f, paint)

        val area = H - 330f - 330f
        val hasTranslation = translation != null
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)
        val quote = PiterfyLyricCardLayout.fit(text, W - P * 2, area * (if (hasTranslation) 0.64f else 1f),
            { size, t -> measure.typeface = face(ctx, 800); measure.textSize = size; measure.measureText(t) }, 104f, 44f)
        var y = 330f
        style(800, quote.size, Color.WHITE).setShadowLayer(24f, 0f, 0f, 0x59000000)
        quote.lines.forEachIndexed { i, line -> canvas.drawText(line, P, y + quote.size * 1.05f + i * quote.size * 1.22f, paint) }
        y += quote.height + 36f
        if (translation != null) {
            val tr = PiterfyLyricCardLayout.fit(translation, W - P * 2, maxOf(120f, 330f + area - y),
                { size, t -> measure.typeface = face(ctx, 600); measure.textSize = size; measure.measureText(t) },
                Math.round(quote.size * 0.55f).toFloat(), 32f, 4f, 1.3f)
            style(600, tr.size, 0xC7FFFFFF.toInt())
            tr.lines.forEachIndexed { i, line -> canvas.drawText(line, P, y + tr.size + i * tr.size * 1.3f, paint) }
        }

        // stopka: okładka, tytuł, wykonawca i znak Piterfy
        val base = H - P - 150f
        if (cover != null && cover.width > 0 && cover.height > 0) {
            val side = minOf(cover.width, cover.height)
            val box = RectF(P, base, P + 150f, base + 150f)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(box, 26f, 26f, Path.Direction.CW) })
            canvas.drawBitmap(cover, Rect((cover.width - side) / 2, (cover.height - side) / 2, (cover.width + side) / 2, (cover.height + side) / 2), box, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        val tx = if (cover != null) P + 150f + 28f else P
        val room = W - P - tx - 160f
        fun fitted(value: String, weight: Int, size: Float): String {
            style(weight, size, Color.WHITE)
            var out = value
            while (out.length > 1 && paint.measureText(out) > room) out = out.dropLast(1)
            return if (out == value) out else out.trimEnd() + "…"
        }
        val titleText = fitted(title, 800, 42f)
        style(800, 42f, Color.WHITE); canvas.drawText(titleText, tx, base + 62f, paint)
        val artistText = fitted(artist, 600, 32f)
        style(600, 32f, 0xB8FFFFFF.toInt()); canvas.drawText(artistText, tx, base + 112f, paint)
        style(700, 30f, 0xB3FFFFFF.toInt()).textAlign = Paint.Align.RIGHT
        canvas.drawText("piterfy", W - P, base + 140f, paint)
    }
}
