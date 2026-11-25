package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
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
}
