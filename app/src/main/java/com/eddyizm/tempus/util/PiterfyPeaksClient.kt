package com.eddyizm.tempus.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Piterfy: klient fali dźwięku — GET /piterfy-api/peaks/{id} (serwer Piterfy). Logowanie, tylko HTTPS i brak przekierowań
 * dzielimy z [PiterfyRadarClient]. Pobrane fale są pamiętane w pamięci (ostatnie [CACHE_SIZE] utworów), „pending” z serwera
 * ponawiamy z rosnącym opóźnieniem ([PiterfyPeaksModel.retryDelayMs]), a wynik wraca na głównym wątku tylko dla konta,
 * które o niego prosiło, i tylko jeśli prośba nie została anulowana (zmiana utworu).
 */
object PiterfyPeaksClient {
    private const val CACHE_SIZE = 40
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, PiterfyPeaksModel.Peaks>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PiterfyPeaksModel.Peaks>?) = size > CACHE_SIZE
    }

    /** Uchwyt prośby: [cancel] kończy ponawianie i blokuje wywołanie zwrotne. */
    class Request internal constructor() {
        @Volatile internal var cancelled = false
        internal var pending: Runnable? = null
        fun cancel() {
            cancelled = true
            pending?.let { main.removeCallbacks(it) }
            pending = null
        }
    }

    private fun key(id: String) = "${PiterfyLibrary.account()}|$id"

    /** Fala z pamięci, jeśli już ją mamy. */
    @JvmStatic
    fun cached(songId: String): PiterfyPeaksModel.Peaks? = synchronized(cache) { cache[key(songId)] }

    /**
     * Prosi o falę utworu [songId]; [done] dostaje ją na głównym wątku (od razu, gdy jest w pamięci). Gdy fali nie będzie
     * (brak HTTPS, błąd, stary serwer, poddanie się po kilku „pending”), [done] nie jest wołane — pasek zostaje zwykły.
     */
    @JvmStatic
    fun load(songId: String, done: Consumer<PiterfyPeaksModel.Peaks>): Request {
        val request = Request()
        cached(songId)?.let { done.accept(it); return request }
        val identity = PiterfyLibrary.account()
        val origin = PiterfyRadarClient.origin() ?: return request
        val user = Preferences.getUser() ?: ""
        val password = Preferences.getPassword() ?: ""
        val encoded = android.net.Uri.encode(songId)
        fun attempt(n: Int) {
            io.execute {
                if (request.cancelled) return@execute
                val outcome = try {
                    val (code, body) = PiterfyRadarClient.call(origin, identity, user, password) {
                        it.url("$origin/piterfy-api/peaks/$encoded").get()
                    }
                    PiterfyPeaksModel.parse(code, body)
                } catch (_: Exception) { PiterfyPeaksModel.Outcome.Missing }
                when (outcome) {
                    is PiterfyPeaksModel.Outcome.Ready -> {
                        synchronized(cache) { cache[key(songId)] = outcome.peaks }
                        main.post {
                            if (!request.cancelled && PiterfyLibrary.account() == identity) done.accept(outcome.peaks)
                        }
                    }
                    PiterfyPeaksModel.Outcome.Pending -> {
                        val delay = PiterfyPeaksModel.retryDelayMs(n) ?: return@execute
                        main.post {
                            if (request.cancelled) return@post
                            val again = Runnable { attempt(n + 1) }
                            request.pending = again
                            main.postDelayed(again, delay)
                        }
                    }
                    PiterfyPeaksModel.Outcome.Missing -> Unit
                }
            }
        }
        attempt(0)
        return request
    }
}
