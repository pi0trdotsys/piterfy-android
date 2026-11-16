package com.eddyizm.tempus.appearance

/** Piterfy: dni okolicznościowe. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. */
internal object PiterfyOccasionData {
    const val WINDOW_DAYS = 2
    val all: List<PiterfyOccasions.Occasion> = listOf(
        PiterfyOccasions.Occasion("newyear", PiterfyOccasions.Rule.Fixed(12, 31), "newyear", "newyear"),
        PiterfyOccasions.Occasion("valentine", PiterfyOccasions.Rule.Fixed(2, 14), "valentine", "valentine"),
        PiterfyOccasions.Occasion("easter", PiterfyOccasions.Rule.Easter(0), "easter", "easter"),
        PiterfyOccasions.Occasion("music", PiterfyOccasions.Rule.Fixed(6, 21), "neon", "noir_neon"),
        PiterfyOccasions.Occasion("independence", PiterfyOccasions.Rule.Fixed(11, 11), "independence", "independence"),
        PiterfyOccasions.Occasion("halloween", PiterfyOccasions.Rule.Fixed(10, 31), "halloween", "halloween"),
        PiterfyOccasions.Occasion("mikolajki", PiterfyOccasions.Rule.Fixed(12, 6), "christmas", "christmas"),
        PiterfyOccasions.Occasion("christmas", PiterfyOccasions.Rule.Fixed(12, 24), "christmas", "christmas"),
        PiterfyOccasions.Occasion("birthday", PiterfyOccasions.Rule.Fixed(10, 12), "birthday", "birthday"),
    )
}
