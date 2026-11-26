package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.InnerFragmentPlayerControllerBinding
import com.eddyizm.tempus.ui.components.PiterfyGlassPlayer
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.components.PiterfyGlassDrawable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test na urządzeniu: z włączonym Liquid Glass ekran główny pokazuje tło z poświatami, a dolna nawigacja ma szklane
 * tło; po wyłączeniu wszystko wraca do zwykłego wyglądu. Zrzuty PNG w katalogu plików aplikacji ("glass-shots").
 */
@RunWith(AndroidJUnit4::class)
class PiterfyGlassTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun restore() {
        PiterfyAppearance.setGlassEnabled(false)
        PiterfyAppearance.setGlassIntensity(60)
    }

    private fun shot(name: String, view: View) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "glass-shots").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun glassOnShowsBackdropAndGlassNavigation() {
        PiterfyAppearance.setGlassEnabled(true)
        PiterfyAppearance.setGlassIntensity(80)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val bind = it.binding
                assertEquals(View.VISIBLE, bind.glassBackdrop.visibility)
                assertTrue("nawigacja ma szklane tło", bind.bottomNavigation.background is PiterfyGlassDrawable)
                shot("glass-on", bind.root)
            }
        }
    }

    @Test fun glassOffLeavesTheNormalLook() {
        PiterfyAppearance.setGlassEnabled(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1000)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val bind = it.binding
                assertEquals(View.GONE, bind.glassBackdrop.visibility)
                assertFalse(bind.bottomNavigation.background is PiterfyGlassDrawable)
                shot("glass-off", bind.root)
            }
        }
    }

    /**
     * Widok „Teraz gra” ze szkłem: nadmuchujemy sam układ kontrolera (odtwarzacza nie da się otworzyć bez serwera) na kolorowym
     * tle zamiast okładki, instalujemy szklaną kartę i robimy prawdziwy zrzut okna (z rozmyciem), do obejrzenia po teście.
     */
    @Test fun nowPlayingControllerUnderGlassHasCardAndGlassButtons() {
        com.eddyizm.tempus.util.Preferences.dontAskForOptimization()  // okno o optymalizacji baterii zasłaniałoby zrzut
        PiterfyAppearance.setGlassEnabled(true)
        PiterfyAppearance.setGlassIntensity(70)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1200)
            var handle: PiterfyGlassPlayer.Handle? = null
            lateinit var controller: InnerFragmentPlayerControllerBinding
            scenario.onActivity { activity ->
                val stage = FrameLayout(activity).apply { setBackgroundColor(0xFF121212.toInt()) }
                val backdrop = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF6B35.toInt(), 0xFF7B2FF7.toInt(), 0xFF00B4D8.toInt())))
                }
                stage.addView(backdrop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                stage.addView(View(activity).apply { setBackgroundResource(R.drawable.piterfy_canvas_scrim) },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                controller = InnerFragmentPlayerControllerBinding.inflate(LayoutInflater.from(activity), stage, false)
                stage.addView(controller.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                (activity.window.decorView as ViewGroup).addView(stage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                controller.root.findViewById<TextView>(R.id.player_media_title_label).text = "Dawn FM"
                controller.root.findViewById<TextView>(R.id.player_artist_name_label).text = "The Weeknd"
                controller.nowPlayingMediaControllerView.show()
                val root = controller.root.findViewById<ViewGroup>(R.id.now_playing_media_controller_layout)
                handle = PiterfyGlassPlayer.install(activity, root)
                handle?.sourceOverride = backdrop
                handle?.setPage(1)
            }
            Thread.sleep(1800)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val root = controller.root.findViewById<ViewGroup>(R.id.now_playing_media_controller_layout)
                assertTrue("kontroler ma szklaną kartę", handle != null && root.background != null)
                val play = root.findViewById<View>(R.id.exo_play_pause)
                assertTrue("play/pauza jest szklanym kółkiem", play.background is RippleDrawable)
            }
            val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "glass-shots").apply { mkdirs() }
            val shot = instrumentation.uiAutomation.takeScreenshot()
            File(dir, "now-playing-glass.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
