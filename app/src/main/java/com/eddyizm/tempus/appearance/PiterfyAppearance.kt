package com.eddyizm.tempus.appearance

import android.content.Context
import com.eddyizm.tempus.App
import java.util.Calendar

/**
 * Piterfy: wybrany wygląd (motyw kolorystyczny i ikona aplikacji) oraz automatyczne zmiany w dni okolicznościowe.
 * Gdy automat jest włączony, okazja (od 2 dni przed do 2 dni po święcie) ma pierwszeństwo przed własnym wyborem.
 */
object PiterfyAppearance {
    const val CLASSIC = "classic"
    const val KEY_AUTO = "piterfy_appearance_auto"
    const val KEY_THEME = "piterfy_theme_preset"
    const val KEY_ICON = "piterfy_icon"

    private fun prefs() = App.getInstance().preferences

    @JvmStatic fun autoEnabled(): Boolean = prefs().getBoolean(KEY_AUTO, true)
    @JvmStatic fun setAutoEnabled(on: Boolean) = prefs().edit().putBoolean(KEY_AUTO, on).apply()
    @JvmStatic fun chosenTheme(): String = prefs().getString(KEY_THEME, CLASSIC) ?: CLASSIC
    @JvmStatic fun setChosenTheme(id: String) = prefs().edit().putString(KEY_THEME, id).apply()
    @JvmStatic fun chosenIcon(): String = prefs().getString(KEY_ICON, CLASSIC) ?: CLASSIC
    @JvmStatic fun setChosenIcon(id: String) = prefs().edit().putString(KEY_ICON, id).apply()

    /** Dzisiejsza data jako (rok, miesiąc, dzień); `Calendar` działa od API 1 (minSdk 24 nie ma java.time bez desugaringu). */
    private fun today(): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @JvmStatic
    fun activeOccasion(): PiterfyOccasions.Match? {
        if (!autoEnabled()) return null
        val (y, m, d) = today()
        return PiterfyOccasions.active(y, m, d)
    }

    @JvmStatic fun effectiveThemeId(): String = activeOccasion()?.occasion?.themeId ?: knownTheme(chosenTheme())
    @JvmStatic fun effectiveIconId(): String = activeOccasion()?.occasion?.iconId ?: knownIcon(chosenIcon())

    private fun knownTheme(id: String) = if (PiterfyCatalog.themes.any { it.id == id }) id else CLASSIC
    private fun knownIcon(id: String) = if (PiterfyCatalog.icons.any { it.id == id }) id else CLASSIC

    /** Motyw do nałożenia na aplikację albo null, gdy działają zwykłe ustawienia koloru. */
    @JvmStatic fun effectiveTheme(): PiterfyCatalog.Theme? = PiterfyCatalog.themes.firstOrNull { it.id == effectiveThemeId() }

    /** Nazwa okazji do podpisu w ustawieniach: „Dziś: …” albo informacja o następnej. */
    fun summary(context: Context): String {
        val res = context.resources
        fun name(id: String) = res.getIdentifier("appearance_occasion_$id", "string", context.packageName).let { if (it != 0) res.getString(it) else id }
        activeOccasion()?.let { return res.getString(com.eddyizm.tempus.R.string.appearance_today, name(it.occasion.id)) }
        val (y, m, d) = today()
        val next = PiterfyOccasions.next(y, m, d) ?: return ""
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, next.second) }
        val date = "%d.%02d".format(start.get(Calendar.DAY_OF_MONTH), start.get(Calendar.MONTH) + 1)
        return res.getString(com.eddyizm.tempus.R.string.appearance_none_today, name(next.first.id), date)
    }
}
