package com.eddyizm.tempus.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.components.PiterfyCanvasView
import com.eddyizm.tempus.util.PiterfyCanvasScene
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test na urządzeniu: PiterfyCanvasView rysuje każdy z sześciu stylów (zrzuty PNG w katalogu plików aplikacji,
 * "canvas-shots") i naprawdę się porusza, gdy jest uruchomiony. Nie wymaga odtwarzania muzyki ani serwera.
 */
@RunWith(AndroidJUnit4::class)
class PiterfyCanvasViewTest {
    private fun bitmapOf(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        view.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 540, 960)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun distinctColors(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) seen.add(bitmap.getPixel(x, y))
        return seen.size
    }

    @Test fun everyStyleDrawsAndMoves() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "canvas-shots").apply { mkdirs() }
        val ids = LinkedHashMap<String, String>()
        var i = 0
        while (ids.size < PiterfyCanvasScene.STYLES.size && i < 2000) { val id = "song$i"; ids.putIfAbsent(PiterfyCanvasScene.scene(id, null).style, id); i++ }
        assertTrue(ids.size == PiterfyCanvasScene.STYLES.size)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: PiterfyCanvasView
            scenario.onActivity {
                view = PiterfyCanvasView(it)
                it.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            instrumentation.waitForIdleSync()
            for ((style, id) in ids) {
                scenario.onActivity { view.setScene(id, "#3b6fd6"); view.setRunning(false) }
                Thread.sleep(800)  // przejście z poprzedniej sceny
                var still: Bitmap? = null
                scenario.onActivity { still = bitmapOf(view) }
                File(out, "$style-still.png").outputStream().use { still!!.compress(Bitmap.CompressFormat.PNG, 90, it) }
                assertTrue("$style: obraz jest jednolity", distinctColors(still!!) > 20)

                if (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
                    scenario.onActivity { view.setRunning(true) }
                    Thread.sleep(2500)
                    var moved: Bitmap? = null
                    scenario.onActivity { moved = bitmapOf(view); view.setRunning(false) }
                    File(out, "$style-moved.png").outputStream().use { moved!!.compress(Bitmap.CompressFormat.PNG, 90, it) }
                    assertTrue("$style: animacja się nie porusza", !still!!.sameAs(moved!!))
                }
            }
        }
    }
}
