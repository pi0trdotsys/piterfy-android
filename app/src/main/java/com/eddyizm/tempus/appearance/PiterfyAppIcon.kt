package com.eddyizm.tempus.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Piterfy: ikona aplikacji to alias launchera (<activity-alias> w manifeście, generowane z tools/appearance); dokładnie
 * jeden jest włączony. Domyślnie działa „classic”. Zmiana składników launchera potrafi zamknąć aplikację albo
 * odświeżyć launcher, dlatego [apply] wołamy, gdy aplikacja schodzi z ekranu (onStop) i z zadania w tle.
 */
object PiterfyAppIcon {
    /** Przestrzeń nazw klas aliasów (namespace modułu, a nie applicationId). */
    private const val NS = "com.eddyizm.tempus"

    fun aliasClass(iconId: String): String =
        NS + "." + (PiterfyCatalog.icons.firstOrNull { it.id == iconId } ?: PiterfyCatalog.icons.first()).alias

    /** Ustawienie składnika → czy alias jest włączony (DEFAULT oznacza wartość z manifestu: tylko „classic”). */
    fun isEnabled(setting: Int, manifestDefault: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestDefault
        else -> false
    }

    data class Change(val iconId: String, val enable: Boolean)

    /**
     * Zmiany potrzebne, by tylko alias [target] był włączony. Nowy alias włączamy PIERWSZY, żeby aplikacja nigdy nie
     * została bez ikony w launcherze, a dopiero potem wyłączamy pozostałe. Pusta lista = nic do zrobienia.
     */
    fun plan(target: String, enabled: Map<String, Boolean>, all: List<String>): List<Change> {
        val goal = if (all.contains(target)) target else all.first()
        val changes = ArrayList<Change>()
        val onlyGoal = all.all { (enabled[it] == true) == (it == goal) }
        if (onlyGoal) return changes
        if (enabled[goal] != true) changes.add(Change(goal, true))
        all.filter { it != goal && enabled[it] == true }.forEach { changes.add(Change(it, false)) }
        return changes
    }

    /** Ustawia ikonę zgodnie z [PiterfyAppearance.effectiveIconId]. Zwraca true, gdy coś zmieniono. */
    @JvmStatic
    fun apply(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false  // ikony adaptacyjne istnieją od API 26
        val pm = context.packageManager
        val ids = PiterfyCatalog.icons.map { it.id }
        val enabled = ids.associateWith {
            isEnabled(pm.getComponentEnabledSetting(ComponentName(context, aliasClass(it))), it == PiterfyAppearance.CLASSIC)
        }
        val changes = plan(PiterfyAppearance.effectiveIconId(), enabled, ids)
        for (c in changes) {
            pm.setComponentEnabledSetting(
                ComponentName(context, aliasClass(c.iconId)),
                if (c.enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        return changes.isNotEmpty()
    }

    /** Ikona, która jest teraz włączona (do podświetlenia w oknie wyboru). */
    fun current(context: Context): String {
        val pm = context.packageManager
        return PiterfyCatalog.icons.firstOrNull {
            isEnabled(pm.getComponentEnabledSetting(ComponentName(context, aliasClass(it.id))), it.id == PiterfyAppearance.CLASSIC)
        }?.id ?: PiterfyAppearance.CLASSIC
    }
}
