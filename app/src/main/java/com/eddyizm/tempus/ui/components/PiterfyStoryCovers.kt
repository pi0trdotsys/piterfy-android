package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.Preferences

/** Skąd renderer slajdów bierze okładki; pusta implementacja ([None]) rysuje zastępniki (eksport bez sieci, testy). */
interface PiterfyStoryCoverSource {
    fun cover(id: String?): Bitmap?
    /** Mocno rozmyta okładka w proporcjach 9:16 (tło slajdu). */
    fun blurred(id: String?): Bitmap?
    fun accent(id: String?): Int?

    object None : PiterfyStoryCoverSource {
        override fun cover(id: String?): Bitmap? = null
        override fun blurred(id: String?): Bitmap? = null
        override fun accent(id: String?): Int? = null
    }
}

/**
 * Piterfy: okładki dla „Wrapped story”, pobierane przez Glide tym samym adresem Subsonic co reszta aplikacji
 * ([CustomGlideRequest.createUrl]); bitmapy, rozmyte tła i kolory akcentu są liczone raz i zapamiętane. Gdy okładka
 * dojedzie, woła [onLoaded], żeby widok się przerysował. W trybie oszczędzania danych bierze tylko to, co już jest w cache.
 */
class PiterfyStoryCovers(context: Context, private val onLoaded: () -> Unit) : PiterfyStoryCoverSource {
    private val app = context.applicationContext
    private val bitmaps = HashMap<String, Bitmap>()
    private val blurs = HashMap<String, Bitmap>()
    private val accents = HashMap<String, Int?>()
    private val requested = HashSet<String>()
    private val targets = ArrayList<CustomTarget<Bitmap>>()
    private var released = false

    /** Zaczyna pobieranie podanych okładek (powtórzenia i puste identyfikatory są pomijane). */
    fun preload(ids: Collection<String?>) {
        ids.filterNotNull().filter { it.isNotBlank() }.distinct().forEach { load(it) }
    }

    private fun load(id: String) {
        if (released || !requested.add(id)) return
        val target = object : CustomTarget<Bitmap>(SIZE, SIZE) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (released) return
                bitmaps[id] = resource
                runCatching { blurs[id] = blur(resource) }
                accents[id] = runCatching { sampleAccent(resource) }.getOrNull()
                onLoaded()
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        targets += target
        runCatching {
            var request = Glide.with(app).asBitmap().load(CustomGlideRequest.createUrl(id, SIZE))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
            if (Preferences.isDataSavingMode()) request = request.onlyRetrieveFromCache(true)
            request.into(target)
        }
    }

    override fun cover(id: String?): Bitmap? = id?.let { bitmaps[it] }
    override fun blurred(id: String?): Bitmap? = id?.let { blurs[it] }
    override fun accent(id: String?): Int? = id?.let { accents[it] }

    fun release() {
        released = true
        targets.forEach { runCatching { Glide.with(app).clear(it) } }
        targets.clear(); bitmaps.clear(); blurs.clear()
    }

    private fun squareCrop(src: Bitmap): Rect {
        val side = minOf(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    /**
     * Tło 9:16: pionowy wycinek ze środka okładki zmniejszany w kilku krokach (żeby uśredniać piksele, a nie próbkować)
     * do 18×32 px; widok rozciąga go z filtrem dwuliniowym, co daje gładkie rozmycie.
     */
    private fun blur(src: Bitmap): Bitmap {
        val crop = squareCrop(src)
        val w = (crop.height() * BLUR_W / BLUR_H.toFloat()).toInt().coerceIn(1, crop.width())
        val left = crop.left + (crop.width() - w) / 2
        var cur = Bitmap.createBitmap(src, left, crop.top, w, crop.height())
        while (cur.height > BLUR_H * 2) cur = Bitmap.createScaledBitmap(cur, maxOf(cur.width / 2, 1), cur.height / 2, true)
        return Bitmap.createScaledBitmap(cur, BLUR_W, BLUR_H, true)
    }

    private fun sampleAccent(src: Bitmap): Int? {
        val small = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, squareCrop(src), Rect(0, 0, 16, 16), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        small.recycle()
        return PiterfyStoryPlan.accentOf(pixels)
    }

    companion object {
        private const val SIZE = 600
        private const val BLUR_W = 18
        private const val BLUR_H = 32
    }
}
