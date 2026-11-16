package com.eddyizm.tempus.appearance

import com.eddyizm.tempus.R

/** Piterfy: katalog motywów i ikon. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. */
object PiterfyCatalog {
    data class Theme(val id: String, val nameRes: Int, val dark: Boolean, val seed: String, val accent: Long, val accent2: Long,
                     val background: Long, val surface: Long, val text: Long, val overlay: Int)
    data class Icon(val id: String, val nameRes: Int, val alias: String, val iconRes: Int)

    val themes: List<Theme> = listOf(
        Theme("noir_neon", R.string.appearance_theme_noir_neon, true, "#FF2E93", 0xFFFF4FA3, 0xFF00E5FF, 0xFF07070D, 0xFF12121C, 0xFFF5F5FA, R.style.ThemeOverlay_PiterfyTheme_NoirNeon),
        Theme("synthwave", R.string.appearance_theme_synthwave, true, "#B026FF", 0xFFFF6EC7, 0xFFFFB000, 0xFF150A28, 0xFF211040, 0xFFFBEFFF, R.style.ThemeOverlay_PiterfyTheme_Synthwave),
        Theme("papier", R.string.appearance_theme_papier, false, "#B5483A", 0xFFB5483A, 0xFF3F6B5C, 0xFFF4EEE2, 0xFFFBF7EE, 0xFF2A2018, R.style.ThemeOverlay_PiterfyTheme_Papier),
        Theme("las", R.string.appearance_theme_las, true, "#3DDC84", 0xFF6FE3A1, 0xFFE3C26F, 0xFF08150F, 0xFF0F2418, 0xFFE8F5EC, R.style.ThemeOverlay_PiterfyTheme_Las),
        Theme("ocean", R.string.appearance_theme_ocean, true, "#1E88E5", 0xFF4FC3F7, 0xFF26E0C1, 0xFF050E1A, 0xFF0A1D33, 0xFFE6F3FF, R.style.ThemeOverlay_PiterfyTheme_Ocean),
        Theme("sakura", R.string.appearance_theme_sakura, false, "#E91E63", 0xFFD81B60, 0xFF8E6AC8, 0xFFFFF4F7, 0xFFFFFFFF, 0xFF3A1B27, R.style.ThemeOverlay_PiterfyTheme_Sakura),
        Theme("terminal", R.string.appearance_theme_terminal, true, "#00FF41", 0xFF39FF14, 0xFF00C8FF, 0xFF000000, 0xFF06100A, 0xFFB8FFC9, R.style.ThemeOverlay_PiterfyTheme_Terminal),
        Theme("zloto", R.string.appearance_theme_zloto, true, "#D4AF37", 0xFFE7C25B, 0xFFB87333, 0xFF0A0803, 0xFF17120A, 0xFFF6ECD2, R.style.ThemeOverlay_PiterfyTheme_Zloto),
        Theme("summer", R.string.appearance_theme_summer, true, "#FF7A3D", 0xFFFF7A3D, 0xFF22C3C3, 0xFF190B1C, 0xFF2A1330, 0xFFFFF0E5, R.style.ThemeOverlay_PiterfyTheme_Summer),
        Theme("winter", R.string.appearance_theme_winter, true, "#4FC3F7", 0xFFA8E0FF, 0xFFFFFFFF, 0xFF08121C, 0xFF11202F, 0xFFEAF6FF, R.style.ThemeOverlay_PiterfyTheme_Winter),
        Theme("halloween", R.string.appearance_theme_halloween, true, "#FF7A00", 0xFFFF8A1F, 0xFF9B5DE5, 0xFF0D0614, 0xFF1A0F24, 0xFFFFEFD9, R.style.ThemeOverlay_PiterfyTheme_Halloween),
        Theme("christmas", R.string.appearance_theme_christmas, true, "#D62828", 0xFFFF4D4D, 0xFF3DDC84, 0xFF0A1410, 0xFF13241B, 0xFFFFF3F0, R.style.ThemeOverlay_PiterfyTheme_Christmas),
        Theme("valentine", R.string.appearance_theme_valentine, true, "#E5195E", 0xFFFF5C8A, 0xFFFFB3C6, 0xFF14050B, 0xFF240B14, 0xFFFFEEF3, R.style.ThemeOverlay_PiterfyTheme_Valentine),
        Theme("newyear", R.string.appearance_theme_newyear, true, "#F5C542", 0xFFF5C542, 0xFFC0C8FF, 0xFF05070F, 0xFF0E1424, 0xFFF8F5E8, R.style.ThemeOverlay_PiterfyTheme_Newyear),
        Theme("easter", R.string.appearance_theme_easter, false, "#7BC96F", 0xFF4FA84A, 0xFFE8789F, 0xFFF6FBF0, 0xFFFFFFFF, 0xFF22331F, R.style.ThemeOverlay_PiterfyTheme_Easter),
        Theme("independence", R.string.appearance_theme_independence, true, "#DC143C", 0xFFE8314F, 0xFFFFFFFF, 0xFF0E0508, 0xFF1E0C11, 0xFFFFF4F5, R.style.ThemeOverlay_PiterfyTheme_Independence),
        Theme("birthday", R.string.appearance_theme_birthday, true, "#1DB954", 0xFF1ED760, 0xFFFFC107, 0xFF000000, 0xFF121212, 0xFFFFFFFF, R.style.ThemeOverlay_PiterfyTheme_Birthday),
    )

    val icons: List<Icon> = listOf(
        Icon("classic", R.string.appearance_icon_classic, "IconClassic", R.mipmap.ic_launcher),
        Icon("neon", R.string.appearance_icon_neon, "IconNeon", R.mipmap.ic_launcher_neon),
        Icon("vinyl", R.string.appearance_icon_vinyl, "IconVinyl", R.mipmap.ic_launcher_vinyl),
        Icon("valentine", R.string.appearance_icon_valentine, "IconValentine", R.mipmap.ic_launcher_valentine),
        Icon("easter", R.string.appearance_icon_easter, "IconEaster", R.mipmap.ic_launcher_easter),
        Icon("summer", R.string.appearance_icon_summer, "IconSummer", R.mipmap.ic_launcher_summer),
        Icon("halloween", R.string.appearance_icon_halloween, "IconHalloween", R.mipmap.ic_launcher_halloween),
        Icon("christmas", R.string.appearance_icon_christmas, "IconChristmas", R.mipmap.ic_launcher_christmas),
        Icon("newyear", R.string.appearance_icon_newyear, "IconNewyear", R.mipmap.ic_launcher_newyear),
        Icon("birthday", R.string.appearance_icon_birthday, "IconBirthday", R.mipmap.ic_launcher_birthday),
        Icon("independence", R.string.appearance_icon_independence, "IconIndependence", R.mipmap.ic_launcher_independence),
    )
}
