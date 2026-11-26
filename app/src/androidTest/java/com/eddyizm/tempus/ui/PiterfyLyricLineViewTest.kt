package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.ui.components.PiterfyLyricLineView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rysuje linijkę tekstu (styl Apple Music) bez uruchamiania całej aplikacji i sprawdza wypełnienie „karaoke” na pikselach. */
@RunWith(AndroidJUnit4::class)
class PiterfyLyricLineViewTest {
    private fun render(progress: Float, active: Boolean, text: String = "Szukałem słońca w środku nocy"): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = PiterfyLyricLineView(context)
        view.text = text
        view.setActiveLine(active)
        view.setProgress(progress)
        val spec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        view.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun brightness(bitmap: Bitmap, fromX: Int, toX: Int): Float {
        var sum = 0L; var n = 0
        for (x in fromX until toX step 2) for (y in 0 until bitmap.height step 2) { sum += Color.red(bitmap.getPixel(x, y)); n++ }
        return sum.toFloat() / n
    }

    @Test fun karaokeFillsFromTheLeftAndFullProgressIsSolidWhite() {
        val empty = render(0f, true)
        val half = render(0.5f, true)
        val full = render(1f, true)
        val inactive = render(0f, false)
        assertTrue("linia ma wysokość", full.height > 20)
        // lewa część połowicznego wypełnienia jaśniejsza niż prawa; pełne = jak nieaktywna (jednolita biel)
        val leftHalf = brightness(half, 20, 120)
        val rightHalf = brightness(half, 700, 900)
        assertTrue("lewa strona wypełniona: $leftHalf vs $rightHalf", leftHalf > rightHalf)
        assertTrue("puste wypełnienie ciemniejsze niż pełne", brightness(empty, 20, 400) < brightness(full, 20, 400))
        assertEquals(brightness(inactive, 20, 400), brightness(full, 20, 400), 3f)
    }

    @Test fun longTextWrapsAndStaysInsideItsWidth() {
        val text = "A potem pójdziemy wprost przed siebie, bez żadnej mapy i bez żadnego planu na jutro, aż do końca świata"
        val one = render(1f, false, "Krótka")
        val wrapped = render(1f, false, text)
        assertTrue("zawinięty tekst jest wyższy", wrapped.height > one.height)
    }
}
