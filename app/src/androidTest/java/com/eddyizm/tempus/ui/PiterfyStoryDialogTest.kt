package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.dialog.PiterfyStoryDialog
import com.eddyizm.tempus.util.PiterfyLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.graphics.Bitmap as Bmp
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.eddyizm.tempus.ui.components.PiterfyStoryCoverSource
import com.eddyizm.tempus.ui.components.PiterfyStoryRenderer
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStoryPlan
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test na urządzeniu: „Wrapped story” otwiera się z zapamiętanej odpowiedzi serwera, każdy slajd się rysuje,
 * nawigacja działa, a zamknięcie niczego nie zostawia. Dane jak w PiterfyStatsDialogTest (/data/local/tmp/piterfy_stats.json,
 * pomijany bez pliku). Zrzuty slajdów lądują w katalogu plików aplikacji (stats-shots/story-*.png).
 */
@RunWith(AndroidJUnit4::class)
class PiterfyStoryDialogTest {
    @Test fun everySlideRendersAndNavigationWorks() {
        val source = File("/data/local/tmp/piterfy_stats.json")
        assumeTrue("brak $source", source.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("piterfy_library", 0).edit().putString("stats|${PiterfyLibrary.account()}", source.readText()).commit()
        context.getSharedPreferences("piterfy_story", 0).edit().putBoolean("muted", true).commit()   // test nie gra muzyki
        val out = File(context.getExternalFilesDir(null), "stats-shots").apply { mkdirs() }

        fun shot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(3800)  // animacje wjazdu slajdu
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = PiterfyStoryDialog.newInstance("2026-11")
            scenario.onActivity { dialog.show(it.supportFragmentManager, PiterfyStoryDialog.TAG) }
            instrumentation.waitForIdleSync()
            var count = 0
            scenario.onActivity { assertTrue(dialog.isAdded); count = dialog.slideCount }
            assertTrue("za mało slajdów: $count", count >= 3)
            for (i in 0 until count) {
                scenario.onActivity { dialog.goTo(i); assertEquals(i, dialog.currentIndex) }
                shot("story-$i")
            }
            scenario.onActivity { dialog.goTo(count + 5); assertEquals(count - 1, dialog.currentIndex) }  // poza zakresem: bez zmian
            scenario.onActivity { dialog.onTapBack(); assertEquals(count - 2, dialog.currentIndex); dialog.onTapForward(); assertEquals(count - 1, dialog.currentIndex) }
            scenario.onActivity { dialog.onSwipe(forward = false); assertEquals(count - 2, dialog.currentIndex) }

            // muzyka niewyciszona, ale bez sieci/serwera: błąd ma zostać połknięty, historyjka dalej działa
            context.getSharedPreferences("piterfy_story", 0).edit().putBoolean("muted", false).commit()
            scenario.onActivity { dialog.goTo(3) }
            Thread.sleep(2500)
            scenario.onActivity { assertTrue(dialog.isAdded); dialog.goTo(1); assertEquals(1, dialog.currentIndex) }
            scenario.onActivity { dialog.dismissAllowingStateLoss() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertFalse(dialog.isAdded) }
        }
    }

    /** Ścieżka z prawdziwymi okładkami (sztuczny gradient zamiast sieci): rozmyte tło, okładka, akcent; zapis do PNG. */
    @Test fun exportWithCoversRendersFullSizeImages() {
        val source = File("/data/local/tmp/piterfy_stats.json")
        assumeTrue("brak $source", source.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val period = PiterfyStatsModel.parse(source.readText())!!.periods.first()
        val art = Bmp.createBitmap(600, 600, Bmp.Config.ARGB_8888).also {
            Canvas(it).drawRect(0f, 0f, 600f, 600f, Paint().apply {
                shader = LinearGradient(0f, 0f, 600f, 600f, intArrayOf(0xFFE0407F.toInt(), 0xFF3A2BA8.toInt(), 0xFFF2A33A.toInt()), null, Shader.TileMode.CLAMP)
            })
        }
        val blurred = Bmp.createScaledBitmap(art, 18, 32, true)
        val fake = object : PiterfyStoryCoverSource {
            override fun cover(id: String?) = art
            override fun blurred(id: String?) = blurred
            override fun accent(id: String?) = 0xFFFF6FA3.toInt()
        }
        val out = File(context.getExternalFilesDir(null), "stats-shots").apply { mkdirs() }
        val renderer = PiterfyStoryRenderer(context, fake)
        for (kind in PiterfyStoryPlan.slides(period).map { it.kind }) {
            val bitmap = renderer.export(period, kind)
            assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height)
            if (kind == PiterfyStoryPlan.Kind.ARTIST || kind == PiterfyStoryPlan.Kind.TRACKS) {
                File(out, "export-${kind.name.lowercase()}.png").outputStream().use { bitmap.compress(Bmp.CompressFormat.PNG, 90, it) }
            }
            bitmap.recycle()
        }
    }
}
