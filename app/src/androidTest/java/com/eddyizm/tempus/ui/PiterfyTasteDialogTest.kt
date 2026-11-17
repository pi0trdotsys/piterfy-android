package com.eddyizm.tempus.ui

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.dialog.PiterfyTasteDialog
import com.eddyizm.tempus.util.PiterfyLibrary
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Smoke test na urządzeniu: ekran „Mój gust” otwiera się z zapamiętanej odpowiedzi serwera i rysuje wszystkie sekcje.
 * Dane: /data/local/tmp/piterfy_taste.json (taste.json) i opcjonalnie /data/local/tmp/piterfy_taste_baseline.json
 * (wgrywane przez adb push; bez pierwszego pliku test jest pomijany). Zrzuty lądują w katalogu plików aplikacji.
 */
@RunWith(AndroidJUnit4::class)
class PiterfyTasteDialogTest {
    @Test fun everySectionRendersFromCachedTaste() {
        val source = File("/data/local/tmp/piterfy_taste.json")
        assumeTrue("brak $source", source.canRead())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = PiterfyLibrary.account()
        val edit = context.getSharedPreferences("piterfy_library", 0).edit().putString("taste|$account", source.readText())
        val baseline = File("/data/local/tmp/piterfy_taste_baseline.json")
        if (baseline.canRead()) edit.putString("taste-baseline|$account", baseline.readText()) else edit.remove("taste-baseline|$account")
        edit.commit()
        val out = File(context.getExternalFilesDir(null), "taste-shots").apply { mkdirs() }

        fun shot(name: String) {
            instrumentation.waitForIdleSync()
            Thread.sleep(1500)  // animacje wejścia
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = PiterfyTasteDialog()
            scenario.onActivity { dialog.show(it.supportFragmentManager, PiterfyTasteDialog.TAG) }
            instrumentation.waitForIdleSync()
            shot("taste-0")
            for (step in 1..7) { scenario.onActivity { dialog.scrollContentTo(step * 1300) }; shot("taste-$step") }
            scenario.onActivity { assertTrue(dialog.isAdded) }
        }
    }
}
