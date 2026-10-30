package com.eddyizm.tempus.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eddyizm.tempus.App
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import com.eddyizm.tempus.subsonic.models.Child
import org.json.JSONArray

/** Shared, authenticated discovery and podcast settings with an account-scoped local snapshot. */
object PiterfyLibrary {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val type = "application/json; charset=utf-8".toMediaType()
    private var token = ""
    private var tokenAccount = ""
    private var tokenAt = 0L
    private fun prefs() = App.getContext().getSharedPreferences("piterfy_library", 0)
    @JvmStatic fun account() = "${Preferences.getServerId()}|${Preferences.getUser()}"
    @JvmStatic fun cached(): JSONObject = runCatching { JSONObject(prefs().getString(account(), "{}")!!) }.getOrDefault(JSONObject())
    private fun execute(url: String, body: JSONObject, bearer: String = ""): JSONObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(type))
        if (bearer.isNotBlank()) request.header("X-ND-Authorization", "Bearer $bearer")
        http.newCall(request.build()).execute().use { response ->
            if (response.code == 401) token = ""
            check(response.isSuccessful) { "Serwer jest chwilowo niedostępny." }
            return JSONObject(response.body?.string() ?: "{}")
        }
    }
    @JvmStatic fun request(body: JSONObject, done: Consumer<JSONObject>?, failed: Consumer<String>?) {
        val identity = account()
        val origin = runCatching { (Preferences.getServer() ?: "").toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/') }.getOrNull()
        if (origin == null || !origin.startsWith("https://")) { failed?.accept("Potrzebny jest adres HTTPS serwera Piterfy."); return }
        val user = Preferences.getUser(); val password = Preferences.getPassword()
        io.execute {
            try {
                check(account() == identity)
                if (tokenAccount != identity || SystemClock.elapsedRealtime() - tokenAt > 1800000) token = ""
                if (token.isEmpty()) {
                    token = execute("$origin/auth/login", JSONObject().put("username", user).put("password", password)).getString("token")
                    tokenAccount = identity; tokenAt = SystemClock.elapsedRealtime()
                }
                val result = execute("$origin/piterfy-api/connect/library", body, token)
                check(account() == identity)
                if (result.has("exploration")) prefs().edit().putString(identity, result.toString()).apply()
                main.post { if (account() == identity) done?.accept(result) }
            } catch (_: Exception) { main.post { if (account() == identity) failed?.accept("Nie udało się zapisać zmian. Spróbuj ponownie po połączeniu.") } }
        }
    }
    @JvmStatic fun refresh(done: Consumer<JSONObject>?) = request(JSONObject().put("action", "get"), done, null)
    @JvmStatic fun rankSongs(songs: List<Child>, done: Consumer<List<Child>>) {
        val music=songs.filter{!PiterfyMedia.isPodcast(it.genre)}.distinctBy{it.id}.take(1000)
        val items=JSONArray()
        music.forEach{items.put(JSONObject().put("id",it.id).put("artist",it.artist?:"").put("familiar",(it.playCount?:0)>0))}
        request(JSONObject().put("action","rank").put("items",items), {result->
            val rows=result.optJSONArray("items")?:JSONArray(); val byId=music.associateBy{it.id}
            done.accept((0 until rows.length()).mapNotNull{byId[rows.optJSONObject(it)?.optString("id")]})
        }, {done.accept(music.filter{cached().optJSONObject("feedback")?.optJSONObject(it.id)?.optInt("vote")!=-1})})
    }
    @JvmStatic fun feedback(id: String, artist: String, vote: Int, done: Consumer<JSONObject>?, failed: Consumer<String>?) =
        request(JSONObject().put("action", "feedback").put("id", id).put("artist", artist).put("vote", vote).put("event", UUID.randomUUID().toString()), done, failed)
    @JvmStatic fun skip(id: String, artist: String, position: Long, duration: Long) {
        if (duration <= 0 || position < 3000 || position >= minOf(30000.0, duration * .3)) return
        request(JSONObject().put("action", "skip").put("id", id).put("artist", artist).put("position", position).put("duration", duration).put("event", UUID.randomUUID().toString()), null, null)
    }
}
