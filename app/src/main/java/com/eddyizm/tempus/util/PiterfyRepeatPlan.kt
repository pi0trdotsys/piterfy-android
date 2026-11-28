package com.eddyizm.tempus.util

import androidx.media3.common.Player

/**
 * Piterfy: powtarzanie jednego utworu ma trwać do ręcznej zmiany utworu. Czysta logika bez Androida (testy jednostkowe).
 *
 * Ręczna zmiana (następny, poprzedni, wybór z kolejki, z Android Auto, z powiadomienia, z widżetu) przychodzi do odtwarzacza
 * jako przejście z powodem [Player.MEDIA_ITEM_TRANSITION_REASON_SEEK]. Zwykłe powtórzenie tego samego utworu ma powód
 * REPEAT, a naturalne przejście na następny AUTO, więc żadne z nich nie wyłącza powtarzania. Zmiana kolejki (PLAYLIST_CHANGED),
 * np. przy przywracaniu zapisanej kolejki po starcie, też go nie rusza.
 */
object PiterfyRepeatPlan {
    /**
     * @param previousId identyfikator utworu sprzed przejścia (null, gdy nic nie grało)
     * @param newId identyfikator utworu po przejściu
     */
    @JvmStatic
    fun shouldTurnOff(repeatMode: Int, reason: Int, previousId: String?, newId: String?): Boolean =
        repeatMode == Player.REPEAT_MODE_ONE &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
            !newId.isNullOrEmpty() && newId != previousId
}
