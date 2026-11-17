package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.dialog.PiterfyStatsDialog
import com.eddyizm.tempus.ui.dialog.PiterfyWrappedDialog
import com.eddyizm.tempus.util.PiterfyLibrary
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test na urządzeniu: ekrany „Statystyki dla nerdów” (zakładki Biblioteka i Serwer) i „Wrapped” otwierają się
 * z zapamiętanej odpowiedzi serwera, rysują się i nic się nie wywraca (w tym karta Wrapped). Dane: /data/local/tmp/piterfy_stats.json (wynik stats.py,
 * wgrywany przez adb push); bez tego pliku test jest pomijany. Zrzuty ekranu lądują w katalogu plików aplikacji.
 */
@RunWith(AndroidJUnit4::class)
class PiterfyStatsDialogTest {
    @Test fun everyTabRendersFromCachedStats() {
        val source = File("/data/local/tmp/piterfy_stats.json")
        assumeTrue("brak $source", source.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("piterfy_library", 0).edit()
            .putString("stats|${PiterfyLibrary.account()}", source.readText()).commit()
        val out = File(context.getExternalFilesDir(null), "stats-shots").apply { mkdirs() }

        fun shot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)  // animacje wejścia
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = PiterfyStatsDialog()
            scenario.onActivity { dialog.show(it.supportFragmentManager, PiterfyStatsDialog.TAG) }
            instrumentation.waitForIdleSync()
            shot("library-0")
            for (step in 1..3) { scenario.onActivity { dialog.scrollContentTo(step * 1500) }; shot("library-$step") }
            scenario.onActivity { dialog.showTab(1) }
            shot("server-0")
            scenario.onActivity { dialog.scrollContentTo(1400) }
            shot("server-1")
            scenario.onActivity { assertTrue(dialog.isAdded) }
        }
    }

    /** Wrapped ma własny ekran: karta, story, typ słuchacza, porównanie i najczęściej słuchane. */
    @Test fun wrappedHasItsOwnScreen() {
        val source = File("/data/local/tmp/piterfy_stats.json")
        assumeTrue("brak $source", source.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("piterfy_library", 0).edit()
            .putString("stats|${PiterfyLibrary.account()}", source.readText()).commit()
        val out = File(context.getExternalFilesDir(null), "stats-shots").apply { mkdirs() }

        fun shot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = PiterfyWrappedDialog()
            scenario.onActivity { dialog.show(it.supportFragmentManager, PiterfyWrappedDialog.TAG) }
            instrumentation.waitForIdleSync()
            shot("wrapped-0")
            scenario.onActivity { dialog.scrollContentTo(1100) }
            shot("wrapped-1")
            scenario.onActivity { dialog.showPeriod("2026") }
            shot("wrapped-year")
            scenario.onActivity { assertTrue(dialog.isAdded) }
        }
    }
}
