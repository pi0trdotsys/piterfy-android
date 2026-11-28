package com.eddyizm.tempus.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eddyizm.tempus.App
import com.google.gson.JsonArray
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
 * Piterfy: tłumaczenie tekstu piosenki obok oryginału. Serwer (POST /piterfy-api/translate, ten sam token Navidrome co reszta)
 * tłumaczy i zapamiętuje wynik na dysku; tu jest klient i czysta logika żądania/odpowiedzi (bez Androida: PiterfyLyricTranslateTest).
 */
object PiterfyLyricTranslate {
    /** Wynik wywołania: tłumaczenie (tyle samo wierszy co oryginał), „już po polsku”, brak usługi na serwerze albo błąd. */
    sealed interface Result {
        class Ok(val lines: List<String>) : Result
        object Skipped : Result
        object Unavailable : Result
        object Failed : Result
    }

    private const val MAX_REMEMBERED = 12

    /** Treść żądania: {"song","target","lines"} (puste wiersze zostają, żeby indeksy zgadzały się z oryginałem). */
    @JvmStatic
    fun requestBody(song: String, target: String, lines: List<String>): String {
        val array = JsonArray()
        lines.forEach { array.add(it.trim()) }
        return JsonObject().apply { addProperty("song", song); addProperty("target", target); add("lines", array) }.toString()
    }

    /** Interpretacja odpowiedzi serwera dla [expected] wierszy oryginału. */
    @JvmStatic
    fun parse(code: Int, body: String?, expected: Int): Result {
        if (code == 503 || code == 501 || code == 404) return Result.Unavailable
        if (code !in 200..299) return Result.Failed
        return try {
            val json = JsonParser.parseString(body ?: "").asJsonObject
            if (json.get("skipped")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return Result.Skipped
            val array = json.get("lines")?.takeIf { it.isJsonArray }?.asJsonArray ?: return Result.Failed
            if (array.size() != expected) return Result.Failed
            Result.Ok(array.map { if (it.isJsonNull) "" else it.asString.trim() })
        } catch (_: Exception) { Result.Failed }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private var token = ""
    private var tokenAccount = ""
    private var tokenAt = 0L
    // Ostatnie udane tłumaczenia w pamięci (klucz: konto|utwór|język), żeby przełączanie nie pytało serwera za każdym razem.
    private val remembered = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > MAX_REMEMBERED
    }

    @JvmStatic fun key(song: String, target: String = "pl") = "${PiterfyLibrary.account()}|$song|$target"
    @Synchronized @JvmStatic fun recalled(song: String, expected: Int, target: String = "pl"): List<String>? =
        remembered[key(song, target)]?.takeIf { it.size == expected }

    /** Pobiera tłumaczenie na wątku w tle; [done] wraca na głównym wątku (nigdy dla innego konta niż przy starcie). */
    @JvmStatic
    fun fetch(song: String, lines: List<String>, target: String = "pl", done: Consumer<Result>) {
        val identity = PiterfyLibrary.account()
        val origin = runCatching { (Preferences.getServer() ?: "").toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/') }.getOrNull()
        if (origin == null || !origin.startsWith("https://")) { done.accept(Result.Unavailable); return }
        val user = Preferences.getUser(); val password = Preferences.getPassword()
        io.execute {
            val result = try {
                var attempt = 0
                var outcome: Result = Result.Failed
                while (true) {
                    check(PiterfyLibrary.account() == identity)
                    if (tokenAccount != identity || SystemClock.elapsedRealtime() - tokenAt > 1800000) token = ""
                    if (token.isEmpty()) {
                        val login = Request.Builder().url("$origin/auth/login")
                            .post(JsonObject().apply { addProperty("username", user); addProperty("password", password) }.toString().toRequestBody(json)).build()
                        token = http.newCall(login).execute().use {
                            check(it.isSuccessful)
                            JsonParser.parseString(it.body?.string() ?: "").asJsonObject.get("token").asString
                        }
                        tokenAccount = identity; tokenAt = SystemClock.elapsedRealtime()
                    }
                    val request = Request.Builder().url("$origin/piterfy-api/translate").header("X-ND-Authorization", "Bearer $token")
                        .post(requestBody(song, target, lines).toRequestBody(json)).build()
                    val (code, body) = http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
                    if (code == 401 && attempt++ == 0) { token = ""; continue }
                    outcome = parse(code, body, lines.size)
                    break
                }
                if (outcome is Result.Ok) synchronized(this) { remembered[key(song, target)] = outcome.lines }
                outcome
            } catch (_: Exception) { Result.Failed }
            main.post { if (PiterfyLibrary.account() == identity) done.accept(result) }
        }
    }
}
