package com.eddyizm.tempus.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eddyizm.tempus.App
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Piterfy: klient radaru nowości — GET /piterfy-api/radar i POST /piterfy-api/radar/fetch (connect/stack/discover/radar.py).
 * Tak jak [PiterfyLyricTranslate] loguje się tokenem Navidrome (tylko HTTPS), nic nie wysyła na inny adres niż serwer
 * Piterfy, a wyniki wracają na głównym wątku wyłącznie dla konta, które o nie prosiło. Ostatnia lista jest pamiętana lokalnie
 * (SharedPreferences), żeby półka miała co pokazać zaraz po otwarciu.
 */
object PiterfyRadarClient {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private var token = ""
    private var tokenAccount = ""
    private var tokenAt = 0L
    private fun prefs() = App.getContext().getSharedPreferences("piterfy_radar", 0)

    /** Ostatnia odpowiedź serwera dla tego konta (surowy JSON, sparsowany) albo null. */
    @JvmStatic
    fun cached(): PiterfyRadarModel.Listing? = PiterfyRadarModel.parse(prefs().getString("radar|${PiterfyLibrary.account()}", null))

    internal fun origin(): String? = runCatching {
        (Preferences.getServer() ?: "").toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    }.getOrNull()?.takeIf { it.startsWith("https://") }

    /** Wywołanie z jednym ponowieniem po 401 (token mógł wygasnąć); zwraca (kod HTTP, treść). */
    @Synchronized
    internal fun call(origin: String, identity: String, user: String, password: String, build: (Request.Builder) -> Request.Builder): Pair<Int, String> {
        var attempt = 0
        while (true) {
            check(PiterfyLibrary.account() == identity)
            if (tokenAccount != identity || SystemClock.elapsedRealtime() - tokenAt > 1_800_000) token = ""
            if (token.isEmpty()) {
                val login = Request.Builder().url("$origin/auth/login")
                    .post(JsonObject().apply { addProperty("username", user); addProperty("password", password) }.toString().toRequestBody(json)).build()
                token = http.newCall(login).execute().use {
                    check(it.isSuccessful)
                    JsonParser.parseString(it.body?.string() ?: "").asJsonObject.get("token").asString
                }
                tokenAccount = identity; tokenAt = SystemClock.elapsedRealtime()
            }
            val request = build(Request.Builder().header("X-ND-Authorization", "Bearer $token")).build()
            val answer = http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            if (answer.first == 401 && attempt++ == 0) { token = ""; continue }
            return answer
        }
    }

    /**
     * Pobiera listę nowości w tle; [done] wraca na głównym wątku. [failed] dostaje „https” (adres serwera bez HTTPS albo brak
     * adresu) albo „network”.
     */
    @JvmStatic
    fun load(done: Consumer<PiterfyRadarModel.Listing>, failed: Consumer<String>?) {
        val identity = PiterfyLibrary.account()
        val origin = origin()
        if (origin == null) { failed?.accept("https"); return }
        val user = Preferences.getUser() ?: ""; val password = Preferences.getPassword() ?: ""
        io.execute {
            val listing = try {
                val (code, body) = call(origin, identity, user, password) { it.url("$origin/piterfy-api/radar").get() }
                (if (code in 200..299) PiterfyRadarModel.parse(body) else null)?.also { prefs().edit().putString("radar|$identity", body).apply() }
            } catch (_: Exception) { null }
            main.post {
                if (PiterfyLibrary.account() == identity) {
                    if (listing != null) done.accept(listing) else failed?.accept("network")
                }
            }
        }
    }

    /**
     * „Dociągnij”: prosi serwer o pobranie wydania [id]; [done] dostaje zaktualizowaną pozycję i kod (queued, already, disabled),
     * [failed] komunikat serwera (może być pusty) albo „https”.
     */
    @JvmStatic
    fun fetch(id: String, done: Consumer<PiterfyRadarModel.Requested>, failed: Consumer<String>?) {
        val identity = PiterfyLibrary.account()
        val origin = origin()
        if (origin == null) { failed?.accept("https"); return }
        val user = Preferences.getUser() ?: ""; val password = Preferences.getPassword() ?: ""
        io.execute {
            var requested: PiterfyRadarModel.Requested? = null
            var error = ""
            try {
                val body = JsonObject().apply { addProperty("id", id) }.toString().toRequestBody(json)
                val (code, text) = call(origin, identity, user, password) { it.url("$origin/piterfy-api/radar/fetch").post(body) }
                requested = if (code in 200..299) PiterfyRadarModel.parseFetch(text) else null
                if (requested == null) error = runCatching { JsonParser.parseString(text).asJsonObject.get("error").asString }.getOrDefault("")
            } catch (_: Exception) { /* błąd sieci: pusty komunikat */ }
            main.post {
                if (PiterfyLibrary.account() == identity) {
                    val r = requested
                    if (r != null) done.accept(r) else failed?.accept(error)
                }
            }
        }
    }
}
