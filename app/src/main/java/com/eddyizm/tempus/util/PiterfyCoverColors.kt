package com.eddyizm.tempus.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Piterfy: zapamiętany kolor okładki dla placeholderów. Zamiast szarego kwadratu, który po chwili „wskakuje” w okładkę,
 * lista pokazuje od razu kafel w odcieniu tej okładki (znamy go z poprzedniej wizyty), więc nic nie miga ani się nie przesuwa.
 * Kolor wyliczany jest raz z pobranej okładki (średnia z jednego piksela) i trzymany w pamięci oraz w małym pliku preferencji,
 * bez dodatkowych bibliotek.
 */
object PiterfyCoverColors {
    private const val FILE = "piterfy_cover_colors"
    private const val MAX_ENTRIES = 1500        // po przekroczeniu zaczynamy zapis od zera (to tylko pamięć podręczna)
    private const val NONE = 0

    private val memory = LruCache<String, Int>(800)
    @Volatile private var prefs: SharedPreferences? = null
    private var stored = -1

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        return context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also {
            prefs = it
            stored = it.all.size
        }
    }

    // ── czysta część (JVM) ──

    /**
     * Kolor placeholdera z zapamiętanej średniej okładki: odcień okładki przyciemniony i wymieszany z powierzchnią,
     * żeby kafelek nie krzyczał (pełne kolory dopiero po załadowaniu okładki), ale było widać, jaka to okładka.
     */
    @JvmStatic
    fun tint(average: Int, surface: Int): Int {
        val opaque = average or (0xFF shl 24)
        val surfaceOpaque = surface or (0xFF shl 24)
        return PiterfyCoverTheme.mix(surfaceOpaque, PiterfyCoverTheme.readableHeader(opaque), 0.55f)
    }

    // ── Android ──

    /** Zapamiętany kolor okładki ([coverId]) albo null, jeśli jeszcze go nie znamy. */
    @JvmStatic
    fun get(context: Context, coverId: String?): Int? {
        if (coverId.isNullOrEmpty()) return null
        memory.get(coverId)?.let { return it.takeIf { c -> c != NONE } }
        val value = prefs(context).getInt(coverId, NONE)
        memory.put(coverId, value)
        return value.takeIf { it != NONE }
    }

    /** Placeholder dla okładki: kafel w jej kolorze, a gdy go nie znamy — dotychczasowy [fallback]. */
    @JvmStatic
    fun placeholder(context: Context, coverId: String?, surface: Int, fallback: Drawable): Drawable {
        val average = get(context, coverId) ?: return fallback
        return ColorDrawable(tint(average, surface))
    }

    /** Uczy się koloru z załadowanej okładki (tylko raz na okładkę; tanie: skalowanie do jednego piksela). */
    @JvmStatic
    fun learn(context: Context, coverId: String?, drawable: Drawable?) {
        if (coverId.isNullOrEmpty() || get(context, coverId) != null) return
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return
        val color = average(bitmap) ?: return
        memory.put(coverId, color)
        val p = prefs(context)
        val edit = p.edit()
        if (stored >= MAX_ENTRIES) { edit.clear(); stored = 0 }
        edit.putInt(coverId, color).apply()
        stored++
    }

    private fun average(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val color = pixel.getPixel(0, 0)
        if (pixel !== bitmap) pixel.recycle()
        return (color or (0xFF shl 24)).takeIf { it != NONE }
    }
}
