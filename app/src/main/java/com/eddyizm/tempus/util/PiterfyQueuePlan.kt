package com.eddyizm.tempus.util

/**
 * Piterfy: czysta logika „Cofnij” dla kolejki (bez Androida, testy: PiterfyQueuePlanTest). Cofnięcie jest bezpieczne tylko
 * wtedy, gdy kolejka wygląda dokładnie tak, jak tuż po akcji — inaczej wstawilibyśmy utwory w złe miejsce albo usunęli cudzy
 * utwór, więc każda decyzja porównuje bieżące identyfikatory z oczekiwanymi.
 */
object PiterfyQueuePlan {
    /** Zakres [from, to) do wyczyszczenia: wszystko po bieżącym utworze; null, gdy nie ma czego czyścić. */
    @JvmStatic
    fun clearRange(current: Int, size: Int): IntArray? {
        val from = (current + 1).coerceAtLeast(0)
        return if (size > from) intArrayOf(from, size) else null
    }

    /** Identyfikatory kolejki po usunięciu zakresu [from, to) — to musi pokazać kolejka, żeby cofnięcie było dozwolone. */
    @JvmStatic
    fun idsAfterRemoving(ids: List<String>, from: Int, to: Int): List<String> {
        if (from < 0 || to > ids.size || from >= to) return ids
        return ids.subList(0, from) + ids.subList(to, ids.size)
    }

    /** Czy wolno przywrócić usunięte utwory w miejsce [at]: kolejka dokładnie taka, jak oczekujemy, i [at] w jej zakresie. */
    @JvmStatic
    fun canRestore(actual: List<String>, expected: List<String>, at: Int): Boolean = actual == expected && at in 0..actual.size

    /** Gdzie na osi czasu wylądował dodany utwór: zaraz po bieżącym ([next]) albo na końcu. */
    @JvmStatic
    fun enqueuedIndex(next: Boolean, current: Int, count: Int): Int = if (next) current + 1 else count - 1

    /**
     * Czy wolno cofnąć dodanie utworu [songId]: na oczekiwanym miejscu faktycznie leży ten utwór (nic się nie przesunęło) i
     * nie jest to aktualnie grany utwór. Zwraca indeks do usunięcia albo -1.
     */
    @JvmStatic
    fun undoEnqueueIndex(ids: List<String>, songId: String, next: Boolean, current: Int): Int {
        val index = enqueuedIndex(next, current, ids.size)
        return if (index in ids.indices && index != current && ids[index] == songId) index else -1
    }
}
