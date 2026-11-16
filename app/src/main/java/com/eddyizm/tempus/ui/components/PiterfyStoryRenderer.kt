package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.PiterfyStatsFormat
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.PiterfyStoryPlan.Kind
import com.eddyizm.tempus.util.PiterfyStoryPlan.appear
import com.eddyizm.tempus.util.PiterfyStoryPlan.stagger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Piterfy: rysuje slajdy „Wrapped story” na Canvasie w układzie 1080 × 1920 (9:16). [draw] dostaje czas [t] w ms od
 * początku slajdu i z niego liczy wszystkie animacje (wjazdy kaskadą, licznik godzin, rosnące słupki), więc ten sam
 * kod rysuje i żywy slajd, i eksport do PNG ([export] rysuje stan końcowy). Kolory pochodzą z aktywnego motywu,
 * a slajdy z okładką biorą z niej rozmyte tło i akcent.
 */
class PiterfyStoryRenderer(private val ctx: Context, private val covers: PiterfyStoryCoverSource = PiterfyStoryCoverSource.None) {
    private class Colors(val bg: Int, val deep: Int, val accent: Int, val accent2: Int, val text: Int, val muted: Int)

    private val base: Colors = run {
        val t = PiterfyAppearance.effectiveTheme()
        val bg = t?.background?.toInt() ?: 0xFF000000.toInt()
        val accent = t?.accent?.toInt() ?: 0xFF1DB954.toInt()
        val text = t?.text?.toInt() ?: Color.WHITE
        Colors(bg, ColorUtils.blendARGB(bg, accent, 0.28f), accent, t?.accent2?.toInt() ?: 0xFFA4EDC2.toInt(), text,
            ColorUtils.setAlphaComponent(text, 175))
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faces = HashMap<Int, Typeface>()
    private val up = 0xFF5FF0A3.toInt()
    private val down = 0xFFFF9A9A.toInt()

    // ── narzędzia ──
    private fun face(weight: Int): Typeface = faces.getOrPut(weight) {
        val font = ResourcesCompat.getFont(ctx, R.font.figtree_family) ?: Typeface.DEFAULT
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(font, weight, false)
        else Typeface.create(font, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun text(weight: Int, size: Float, color: Int, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT): Paint = paint.apply {
        reset(); isAntiAlias = true
        typeface = face(weight); textSize = size; this.color = color; letterSpacing = spacing; textAlign = align
    }

    private fun measure(value: String) = paint.measureText(value)

    private fun fill(color: Int): Paint = paint.apply { reset(); isAntiAlias = true; this.color = color }

    private fun ellipsize(value: String, width: Float): String {
        if (measure(value) <= width) return value
        var out = value
        while (out.length > 1 && measure("$out…") > width) out = out.dropLast(1)
        return out.trimEnd() + "…"
    }

    /** Największy rozmiar z [max]..[min], przy którym [value] mieści się w [width] w jednej linii. */
    private fun fitSize(value: String, weight: Int, max: Float, min: Float, width: Float): Float {
        var size = max
        while (size > min) { text(weight, size, Color.WHITE); if (measure(value) <= width) return size; size -= 4f }
        return min
    }

    /** Zawija napis tak, by mieścił się w [maxLines] liniach bez skracania; zwraca rozmiar czcionki i linie. */
    private fun fitWrap(value: String, weight: Int, max: Float, min: Float, width: Float, maxLines: Int): Pair<Float, List<String>> {
        var size = max
        while (true) {
            text(weight, size, Color.WHITE)
            val lines = F.wrap(value, width, 0) { measure(it) }
            val fits = lines.size <= maxLines && lines.all { measure(it) <= width }
            if (fits || size <= min) {
                text(weight, size, Color.WHITE)
                return size to (if (lines.size > maxLines) F.wrap(value, width, maxLines) { measure(it) } else lines)
            }
            size -= 6f
        }
    }

    private fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * a.coerceIn(0f, 1f)).toInt())

    /** Rysuje [block] z przezroczystością [a] i przesunięciem w górę o (1 - a) * [rise] (wjazd elementu). */
    private inline fun enter(canvas: Canvas, a: Float, rise: Float, l: Float, tp: Float, r: Float, b: Float, block: () -> Unit) {
        if (a <= 0f) return
        val dy = (1f - a) * rise
        val save = canvas.saveLayerAlpha(l, tp + dy - 20f, r, b + dy + 20f, (255 * a).toInt().coerceIn(0, 255))
        canvas.translate(0f, dy)
        block()
        canvas.restoreToCount(save)
    }

    private fun roundRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, fill(color))
    }

    private fun drawCover(canvas: Canvas, id: String?, l: Float, t: Float, size: Float, radius: Float, accent: Int) {
        val bitmap = covers.cover(id)
        if (bitmap == null) {
            roundRect(canvas, l, t, l + size, t + size, radius, alpha(accent, 0.22f))
            text(800, size * 0.5f, alpha(accent, 0.7f), 0f, Paint.Align.CENTER)
            canvas.drawText("♪", l + size / 2, t + size * 0.66f, paint)
            return
        }
        val side = min(bitmap.width, bitmap.height)
        val src = Rect((bitmap.width - side) / 2, (bitmap.height - side) / 2, (bitmap.width + side) / 2, (bitmap.height + side) / 2)
        val save = canvas.save()
        val clip = Path().apply { addRoundRect(RectF(l, t, l + size, t + size), radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, src, RectF(l, t, l + size, t + size), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restoreToCount(save)
    }

    // ── tło ──
    private fun background(canvas: Canvas, lw: Float, lh: Float, kind: Kind, blurId: String?, accent: Int) {
        val blurred = covers.blurred(blurId)
        if (blurred != null) {
            canvas.drawBitmap(blurred, null, RectF(0f, 0f, lw, lh), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawRect(0f, 0f, lw, lh, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, lh, intArrayOf(alpha(base.bg, 0.55f), alpha(base.bg, 0.78f), alpha(base.bg, 0.92f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
            canvas.drawRect(0f, 0f, lw, lh, fill(alpha(accent, 0.12f)))
            return
        }
        canvas.drawRect(0f, 0f, lw, lh, Paint().apply {
            shader = LinearGradient(0f, 0f, lw, lh, intArrayOf(base.deep, base.bg, base.bg), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        // dwie miękkie plamy koloru, przesunięte zależnie od slajdu, żeby kolejne nie wyglądały identycznie
        val n = kind.ordinal
        val spots = listOf(
            Triple(if (n % 2 == 0) lw * 0.95f else lw * 0.05f, lh * (0.12f + 0.06f * (n % 3)), 640f) to (accent to 0.34f),
            Triple(if (n % 2 == 0) -40f else lw + 40f, lh * (0.66f + 0.05f * (n % 4)), 560f) to (base.accent2 to 0.15f),
        )
        spots.forEach { (circle, tint) ->
            canvas.drawRect(0f, 0f, lw, lh, Paint().apply {
                shader = RadialGradient(circle.first, circle.second, circle.third,
                    intArrayOf(alpha(tint.first, tint.second), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun brandBars(canvas: Canvas, x: Float, y: Float, scale: Float, color: Int, grow: Float = 1f) {
        val bars = listOf(0f to 46f, 26f to 80f, 52f to 56f, 78f to 34f)
        bars.forEach { (dx, h) ->
            val height = h * scale * grow
            roundRect(canvas, x + dx * scale, y + (80f * scale - height), x + (dx + 14f) * scale, y + 80f * scale, 7f * scale, color)
        }
    }

    private fun kicker(canvas: Canvas, value: String, x: Float, y: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, width: Float = W - 2 * P) {
        var size = 30f
        text(700, size, color, 0.2f, align)
        while (measure(value) > width && size > 18f) { size -= 2f; text(700, size, color, 0.2f, align) }
        canvas.drawText(value, x, y, paint)
    }

    // ── publiczne API ──
    /**
     * Rysuje slajd [kind] w prostokącie [lw] × [lh] (logiczne piksele; widok na telefonie jest wyższy niż 9:16, więc tło
     * wypełnia całość, a treść 1080 × 1920 leży na środku). [musicOn] zapala animowany korektor przy utworze nr 1.
     */
    fun draw(canvas: Canvas, p: PiterfyStatsModel.Period, kind: Kind, t: Long, lw: Float = W, lh: Float = H, musicOn: Boolean = false) {
        val accentId = when (kind) {
            Kind.ARTIST -> p.topArtists.firstOrNull()?.cover
            Kind.TRACKS -> p.topTracks.firstOrNull()?.cover
            else -> null
        }
        val accent = covers.accent(accentId) ?: base.accent
        background(canvas, lw, lh, kind, accentId, accent)
        val save = canvas.save()
        canvas.translate((lw - W) / 2f, (lh - H) / 2f)
        when (kind) {
            Kind.INTRO -> intro(canvas, p, t, accent)
            Kind.TIME -> time(canvas, p, t, accent)
            Kind.ARTIST -> artist(canvas, p, t, accent)
            Kind.TRACKS -> tracks(canvas, p, t, accent, musicOn)
            Kind.GENRES -> genres(canvas, p, t, accent)
            Kind.LISTENER -> listener(canvas, p, t, accent)
            Kind.COMPARE -> compare(canvas, p, t, accent)
            Kind.FINALE -> finale(canvas, p, t, accent)
        }
        if (kind != Kind.FINALE) {
            text(600, 28f, alpha(base.muted, 0.8f))
            canvas.drawText("piterfy", P, H - 64f, paint)
        }
        canvas.restoreToCount(save)
    }

    /** Slajd jako obrazek 1080 × 1920 w stanie końcowym (do zapisu i udostępnienia). */
    fun export(p: PiterfyStatsModel.Period, kind: Kind): Bitmap {
        val bitmap = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), p, kind, PiterfyStoryPlan.END_STATE_MS)
        return bitmap
    }

    // ── slajdy ──
    private fun intro(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        // znak Piterfy rośnie słupek po słupku
        val grow = (0..3).map { appear(t, 80L + it * 90L, 700L) }
        val bars = listOf(0f to 46f, 26f to 80f, 52f to 56f, 78f to 34f)
        val scale = 3.4f
        bars.forEachIndexed { i, (dx, h) ->
            val height = h * scale * grow[i]
            roundRect(canvas, P + dx * scale, 560f - height, P + (dx + 14f) * scale, 560f, 7f * scale, accent)
        }
        val a = appear(t, 500)
        enter(canvas, a, 50f, 0f, 640f, W, 760f) { kicker(canvas, s(R.string.stats_card_kicker), P, 720f, accent) }
        val title = p.label
        val (size, lines) = fitWrap(title, 800, 190f, 96f, W - 2 * P, 2)
        lines.forEachIndexed { i, line ->
            val la = appear(t, 700L + i * 140L, 800L)
            enter(canvas, la, 70f, 0f, 760f + i * size * 1.05f, W, 780f + (i + 1) * size * 1.1f) {
                text(800, size, base.text); canvas.drawText(line, P - 4f, 720f + size * 1.02f * (i + 1) + 20f, paint)
            }
        }
        val titleBottom = 720f + size * 1.02f * lines.size + 20f
        if (p.partial) {
            val pa = appear(t, 1300)
            enter(canvas, pa, 40f, 0f, titleBottom + 20f, W, titleBottom + 150f) {
                text(700, 40f, accent)
                val label = s(R.string.stats_card_running)
                val w = measure(label) + 64f
                roundRect(canvas, P, titleBottom + 50f, P + w, titleBottom + 122f, 36f, alpha(accent, 0.22f))
                text(700, 40f, accent); canvas.drawText(label, P + 32f, titleBottom + 100f, paint)
            }
        }
        enter(canvas, appear(t, 1500), 40f, 0f, 1480f, W, 1620f) {
            text(500, 44f, base.muted)
            F.wrap(s(R.string.story_intro_sub), W - 2 * P, 2) { measure(it) }.forEachIndexed { i, line -> canvas.drawText(line, P, 1540f + i * 58f, paint) }
        }
    }

    private fun time(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        enter(canvas, appear(t, 60), 40f, 0f, 200f, W, 320f) { kicker(canvas, s(R.string.story_time_kicker), P, 290f, accent) }
        // wartość końcowa wyznacza rozmiar, żeby licznik nie „oddychał” w trakcie liczenia
        val finalText = PiterfyStoryPlan.listeningAt(p.minutes, 1f)
        val bigSize = fitSize(finalText, 800, 300f, 120f, W - 2 * P)
        val progress = PiterfyStoryPlan.ease(t / 1700f)
        val shown = PiterfyStoryPlan.listeningAt(p.minutes, progress)
        enter(canvas, appear(t, 150, 400), 30f, 0f, 300f, W, 300f + bigSize * 1.3f) {
            text(800, bigSize, accent); canvas.drawText(shown, P - 8f, 330f + bigSize * 0.92f, paint)
        }
        val afterBig = 330f + bigSize * 0.92f
        enter(canvas, appear(t, 1500), 40f, 0f, afterBig + 20f, W, afterBig + 140f) {
            text(600, 52f, base.muted); canvas.drawText(s(R.string.story_plays, F.num(p.plays)), P, afterBig + 100f, paint)
        }
        val rows = listOf(
            p.tracks.toLong() to R.string.story_stat_tracks,
            p.artists.toLong() to R.string.story_stat_artists,
            p.activeDays.toLong() to R.string.story_stat_days,
        )
        val top = afterBig + 260f
        rows.forEachIndexed { i, (value, label) ->
            val a = appear(t, 1700L + i * 170L)
            val y = top + i * 230f
            enter(canvas, a, 60f, 0f, y - 20f, W, y + 210f) {
                roundRect(canvas, P, y, W - P, y + 190f, 40f, alpha(base.text, 0.07f))
                val count = (value * PiterfyStoryPlan.ease((t - 1700L - i * 170L) / 1100f)).toLong()
                text(800, 100f, base.text); canvas.drawText(F.num(count), P + 44f, y + 128f, paint)
                val numberWidth = measure(F.num(value))
                text(600, 42f, base.muted)
                val room = W - 2 * P - 44f - numberWidth - 40f - 30f
                canvas.drawText(ellipsize(s(label), room), P + 44f + numberWidth + 36f, y + 122f, paint)
            }
        }
        if (p.partial) enter(canvas, appear(t, 2400), 30f, 0f, 1680f, W, 1800f) {
            text(500, 34f, alpha(base.muted, 0.9f))
            F.wrap(s(R.string.story_running), W - 2 * P, 2) { measure(it) }.forEachIndexed { i, line -> canvas.drawText(line, P, 1740f + i * 46f, paint) }
        }
    }

    private fun artist(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        val top = p.topArtists.first()
        enter(canvas, appear(t, 60), 40f, 0f, 160f, W, 280f) { kicker(canvas, s(R.string.story_artist_kicker), W / 2f, 230f, accent, Paint.Align.CENTER) }
        // okładka wjeżdża z lekkim powiększeniem
        val a = appear(t, 200, 900)
        val sz = 560f
        if (a > 0f) {
            val save = canvas.saveLayerAlpha(0f, 240f, W, 900f, (255 * a).toInt().coerceIn(0, 255))
            val sc = 0.86f + 0.14f * a
            canvas.scale(sc, sc, W / 2f, 530f)
            if (covers.cover(top.cover) != null) roundRect(canvas, (W - sz) / 2f + 10f, 300f + 24f, (W + sz) / 2f + 10f, 300f + sz + 24f, 56f, 0x59000000)
            drawCover(canvas, top.cover, (W - sz) / 2f, 300f, sz, 56f, accent)
            canvas.restoreToCount(save)
        }
        val (size, lines) = fitWrap(top.label, 800, 128f, 64f, W - 2 * P, 2)
        val nameTop = 960f
        lines.forEachIndexed { i, line ->
            val la = appear(t, 650L + i * 130L)
            enter(canvas, la, 60f, 0f, nameTop + i * size * 1.05f, W, nameTop + (i + 1) * size * 1.2f) {
                text(800, size, base.text, 0f, Paint.Align.CENTER); canvas.drawText(line, W / 2f, nameTop + size * 0.95f + i * size * 1.05f, paint)
            }
        }
        val y = nameTop + size * 0.95f + (lines.size - 1) * size * 1.05f + 78f
        enter(canvas, appear(t, 900), 40f, 0f, y - 60f, W, y + 30f) {
            text(700, 50f, accent, 0f, Paint.Align.CENTER); canvas.drawText(s(R.string.story_plays, F.num(top.n)), W / 2f, y, paint)
        }
        val rest = p.topArtists.drop(1).take(4)
        val listTop = max(y + 90f, 1300f)
        rest.forEachIndexed { i, artist ->
            val ry = listTop + i * 98f
            enter(canvas, appear(t, stagger(i, 1100L, 120L)), 50f, 0f, ry - 60f, W, ry + 40f) {
                text(800, 42f, accent); canvas.drawText((i + 2).toString(), P, ry, paint)
                text(700, 46f, base.text)
                val room = W - 2 * P - 70f - 170f
                canvas.drawText(ellipsize(artist.label, room), P + 70f, ry, paint)
                text(600, 38f, base.muted, 0f, Paint.Align.RIGHT); canvas.drawText("${artist.n}×", W - P, ry, paint)
            }
        }
    }

    private fun tracks(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int, musicOn: Boolean) {
        val first = p.topTracks.first()
        enter(canvas, appear(t, 60), 40f, 0f, 160f, W, 280f) { kicker(canvas, s(R.string.story_tracks_kicker), P, 230f, accent) }
        val a = appear(t, 180, 900)
        val sz = 480f
        if (a > 0f) {
            val save = canvas.saveLayerAlpha(0f, 240f, W, 840f, (255 * a).toInt().coerceIn(0, 255))
            val sc = 0.88f + 0.12f * a
            canvas.scale(sc, sc, P + sz / 2f, 520f)
            if (covers.cover(first.cover) != null) roundRect(canvas, P + 8f, 290f + 22f, P + sz + 8f, 290f + sz + 22f, 48f, 0x59000000)
            drawCover(canvas, first.cover, P, 290f, sz, 48f, accent)
            canvas.restoreToCount(save)
        }
        // prawa kolumna: wielka jedynka, liczba odsłuchów i korektor
        val rx = P + sz + 56f
        val room = W - P - rx
        enter(canvas, appear(t, 450), 50f, rx, 290f, W, 700f) {
            text(800, 190f, accent); canvas.drawText("#1", rx - 6f, 290f + 170f, paint)
            text(800, 84f, base.text); canvas.drawText("${first.plays}×", rx, 290f + 290f, paint)
            text(600, 36f, base.muted); canvas.drawText(ellipsize(s(R.string.story_plays, "").trim().ifEmpty { "plays" }, room), rx, 290f + 340f, paint)
        }
        if (musicOn) {
            val barsA = appear(t, 900)
            enter(canvas, barsA, 20f, rx, 700f, W, 780f) {
                for (i in 0..4) {
                    val h = 18f + 40f * (0.5f + 0.5f * sin(t / 170.0 + i * 1.7)).toFloat()
                    roundRect(canvas, rx + i * 26f, 770f - h, rx + i * 26f + 14f, 770f, 7f, accent)
                }
            }
        }
        val (size, lines) = fitWrap(first.title, 800, 84f, 52f, W - 2 * P, 2)
        val titleTop = 880f
        enter(canvas, appear(t, 600), 50f, 0f, titleTop - 20f, W, titleTop + size * 2.4f) {
            text(800, size, base.text)
            lines.forEachIndexed { i, line -> canvas.drawText(line, P, titleTop + size * 0.9f + i * size * 1.08f, paint) }
        }
        val artistY = titleTop + size * 0.9f + (lines.size - 1) * size * 1.08f + 64f
        enter(canvas, appear(t, 720), 40f, 0f, artistY - 50f, W, artistY + 30f) {
            text(600, 44f, base.muted); canvas.drawText(ellipsize(first.artist, W - 2 * P), P, artistY, paint)
        }
        val rows = p.topTracks.drop(1).take(4)
        val listTop = max(artistY + 90f, 1130f)
        rows.forEachIndexed { i, track ->
            val ry = listTop + i * 150f
            enter(canvas, appear(t, stagger(i, 900L, 130L)), 60f, 0f, ry - 10f, W, ry + 130f) {
                text(800, 40f, accent, 0f, Paint.Align.LEFT); canvas.drawText((i + 2).toString(), P, ry + 72f, paint)
                drawCover(canvas, track.cover, P + 56f, ry, 112f, 22f, accent)
                val tx = P + 56f + 112f + 28f
                val width = W - P - tx - 130f
                text(700, 42f, base.text); canvas.drawText(ellipsize(track.title, width), tx, ry + 50f, paint)
                text(500, 34f, base.muted); canvas.drawText(ellipsize(track.artist, width), tx, ry + 96f, paint)
                text(700, 38f, base.muted, 0f, Paint.Align.RIGHT); canvas.drawText("${track.plays}×", W - P, ry + 70f, paint)
            }
        }
    }

    private fun genres(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        val list = p.topGenres.filter { it.label.isNotBlank() && it.n > 0 }.take(5)
        val maxN = list.maxOf { it.n }
        enter(canvas, appear(t, 60), 40f, 0f, 160f, W, 320f) { kicker(canvas, s(R.string.story_genres_kicker), P, 290f, accent) }
        val topLabel = list.first().label
        val (size, lines) = fitWrap(topLabel, 800, 170f, 84f, W - 2 * P, 2)
        lines.forEachIndexed { i, line ->
            enter(canvas, appear(t, 200L + i * 120L, 800), 60f, 0f, 330f + i * size * 1.05f, W, 330f + (i + 1) * size * 1.2f) {
                text(800, size, base.text); canvas.drawText(line, P - 4f, 330f + size * 0.95f + i * size * 1.05f, paint)
            }
        }
        val subY = 330f + size * 0.95f + (lines.size - 1) * size * 1.05f + 74f
        val share = F.percent(list.first().n.toDouble(), p.plays.toDouble())
        enter(canvas, appear(t, 500), 40f, 0f, subY - 60f, W, subY + 30f) {
            text(600, 42f, base.muted)
            canvas.drawText(ellipsize(s(R.string.story_genre_sub, F.num(list.first().n), share), W - 2 * P), P, subY, paint)
        }
        val top = max(subY + 120f, 820f)
        list.forEachIndexed { i, genre ->
            val ry = top + i * 176f
            val delay = stagger(i, 650L, 140L)
            enter(canvas, appear(t, delay), 50f, 0f, ry - 20f, W, ry + 150f) {
                text(700, 44f, base.text); canvas.drawText(ellipsize(genre.label, W - 2 * P - 190f), P, ry + 42f, paint)
                text(700, 42f, base.muted, 0f, Paint.Align.RIGHT); canvas.drawText(F.percent(genre.n.toDouble(), p.plays.toDouble()), W - P, ry + 42f, paint)
                val barTop = ry + 70f
                roundRect(canvas, P, barTop, W - P, barTop + 40f, 20f, alpha(base.text, 0.12f))
                val w = (W - 2 * P) * PiterfyStoryPlan.barFraction(genre.n, maxN) * PiterfyStoryPlan.ease((t - delay - 200L) / 1000f)
                if (w > 0f) roundRect(canvas, P, barTop, P + max(w, 40f), barTop + 40f, 20f, if (i == 0) accent else alpha(accent, 0.65f))
            }
        }
    }

    private fun listener(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        val l = p.listener ?: return
        // wielka poświata za nazwą
        val glow = appear(t, 0, 1400)
        canvas.drawRect(0f, 0f, W, H, Paint().apply {
            shader = RadialGradient(W / 2f, 760f, 780f, intArrayOf(alpha(accent, 0.30f * glow), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        })
        enter(canvas, appear(t, 60), 40f, 0f, 200f, W, 340f) { kicker(canvas, s(R.string.stats_card_listener), P, 310f, accent) }
        val (size, lines) = fitWrap(l.name, 800, 210f, 96f, W - 2 * P, 2)
        lines.forEachIndexed { i, line ->
            enter(canvas, appear(t, 260L + i * 140L, 850), 80f, 0f, 360f + i * size * 1.05f, W, 360f + (i + 1) * size * 1.2f) {
                text(800, size, base.text); canvas.drawText(line, P - 4f, 360f + size * 0.98f + i * size * 1.05f, paint)
            }
        }
        var y = 360f + size * 0.98f + (lines.size - 1) * size * 1.05f + 110f
        val blurb = F.wrap(l.blurb, W - 2 * P, 5) { text(500, 50f, base.muted); measure(it) }
        enter(canvas, appear(t, 800), 50f, 0f, y - 60f, W, y + blurb.size * 70f) {
            text(500, 50f, base.muted)
            blurb.forEachIndexed { i, line -> canvas.drawText(line, P, y + i * 70f, paint) }
        }
        y += blurb.size * 70f + 40f
        val why = l.why.take(3)
        if (why.isNotEmpty()) {
            val h = why.size * 86f + 56f
            enter(canvas, appear(t, 1100), 60f, 0f, y, W, y + h) {
                roundRect(canvas, P, y, W - P, y + h, 44f, alpha(base.text, 0.08f))
                why.forEachIndexed { i, line ->
                    roundRect(canvas, P + 40f, y + 56f + i * 86f - 14f, P + 56f, y + 56f + i * 86f + 2f, 8f, accent)
                    text(600, 40f, base.text)
                    canvas.drawText(ellipsize(line, W - 2 * P - 70f - 40f), P + 80f, y + 56f + i * 86f + 4f, paint)
                }
            }
        }
    }

    private fun compare(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        val c = p.compare ?: return
        enter(canvas, appear(t, 60), 40f, 0f, 160f, W, 320f) {
            kicker(canvas, s(if (p.kind == "month") R.string.story_compare_month_kicker else R.string.story_compare_year_kicker), P, 290f, accent)
        }
        val rows = listOfNotNull(
            c.plays?.let { Triple(s(R.string.stats_plays, F.num(c.prevPlays), F.num(p.plays)), it, 0) },
            c.minutes?.let { Triple(s(R.string.stats_minutes), it, 1) },
            c.artists?.let { Triple(s(R.string.stats_artists_distinct), it, 2) },
        )
        val top = 420f
        rows.forEachIndexed { i, (label, pct, _) ->
            val y = top + i * 440f
            val delay = stagger(i, 300L, 260L)
            enter(canvas, appear(t, delay), 70f, 0f, y - 20f, W, y + 410f) {
                roundRect(canvas, P, y, W - P, y + 400f, 56f, alpha(base.text, 0.07f))
                text(600, 40f, base.muted); canvas.drawText(ellipsize(label, W - 2 * P - 96f), P + 48f, y + 84f, paint)
                val color = if (pct > 0) up else if (pct < 0) down else base.text
                val finalText = PiterfyStoryPlan.deltaAt(pct, 1f)
                val size = fitSize(finalText, 800, 190f, 90f, W - 2 * P - 96f)
                val shown = PiterfyStoryPlan.deltaAt(pct, PiterfyStoryPlan.ease((t - delay - 150L) / 1300f))
                text(800, size, color); canvas.drawText(shown, P + 44f, y + 270f, paint)
                if (pct == 0) { text(600, 40f, base.muted); canvas.drawText(s(R.string.stats_delta_none), P + 48f, y + 346f, paint) }
            }
        }
    }

    private fun finale(canvas: Canvas, p: PiterfyStatsModel.Period, t: Long, accent: Int) {
        enter(canvas, appear(t, 60), 40f, 0f, 160f, W, 320f) { kicker(canvas, s(R.string.story_finale_kicker), P, 280f, accent) }
        val (size, lines) = fitWrap(p.label, 800, 150f, 84f, W - 2 * P, 2)
        lines.forEachIndexed { i, line ->
            enter(canvas, appear(t, 200L + i * 120L, 800), 60f, 0f, 320f + i * size * 1.05f, W, 320f + (i + 1) * size * 1.2f) {
                text(800, size, base.text); canvas.drawText(line, P - 3f, 320f + size * 0.98f + i * size * 1.05f, paint)
            }
        }
        var y = 320f + size * 0.98f + (lines.size - 1) * size * 1.05f + 70f
        val cards = ArrayList<Pair<String, String>>()
        val streak = if (p.longestStreak > 1) " · " + s(R.string.story_finale_streak, p.longestStreak) else ""
        cards += s(R.string.story_finale_days_label) to (s(R.string.story_finale_days, F.num(p.activeDays), F.num(PiterfyStoryPlan.daysIn(p.start, p.end, max(p.activeDays, 1)))) + streak)
        if ((p.newArtists ?: 0) > 0) cards += s(R.string.story_finale_new_label) to (F.num(p.newArtists) + " " + s(R.string.story_finale_new))
        if (p.busiestDay != null) cards += s(R.string.story_finale_busiest) to s(R.string.story_finale_busiest_value, F.day(p.busiestDay), F.num(p.busiestPlays))
        cards.forEachIndexed { i, (label, value) ->
            val cy = y + i * 218f
            enter(canvas, appear(t, stagger(i, 700L, 150L)), 60f, 0f, cy - 10f, W, cy + 200f) {
                roundRect(canvas, P, cy, W - P, cy + 190f, 44f, alpha(base.text, 0.08f))
                text(700, 28f, base.muted, 0.18f); canvas.drawText(ellipsize(label, W - 2 * P - 80f), P + 44f, cy + 62f, paint)
                val vs = fitSize(value, 800, 62f, 34f, W - 2 * P - 88f)
                text(800, vs, base.text); canvas.drawText(ellipsize(value, W - 2 * P - 88f), P + 44f, cy + 142f, paint)
            }
        }
        // znak Piterfy z napisem
        val ly = max(y + cards.size * 218f + 90f, 1480f)
        val la = appear(t, 1300, 900)
        enter(canvas, la, 40f, 0f, ly - 20f, W, ly + 200f) {
            brandBars(canvas, P, ly, 1.7f, accent)
            text(800, 112f, base.text); canvas.drawText("piterfy", P + 78f * 1.7f + 62f, ly + 80f * 1.7f - 6f, paint)
        }
    }

    companion object {
        const val W = 1080f
        const val H = 1920f
        private const val P = 84f
    }
}
