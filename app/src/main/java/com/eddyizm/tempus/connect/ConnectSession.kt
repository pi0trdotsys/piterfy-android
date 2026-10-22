package com.eddyizm.tempus.connect

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.lan.LanRemoteState
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.util.PiterfyOutputMonitor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One account-scoped session, tied to the existing playback service's lifetime. */
@androidx.annotation.OptIn(UnstableApi::class)
object ConnectSession {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val mapper = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    val changes = MutableLiveData(0)
    private var context: Context? = null
    private var player: (() -> Player?)? = null
    private var output: PiterfyOutputMonitor? = null
    private var device = ""
    private var account = ""
    private var busy = false
    private var applying: String? = null
    private var applied = ""
    private var stopped = ""
    private var lastOk = 0L
    @Volatile private var generation = 0
    private var snapshot: JSONObject? = null
    private var token = ""
    private var tokenAccount = ""
    private var tokenAt = 0L
    var error: String = ""
        private set
    var enabled = false
        private set

    fun state(): JSONObject? = snapshot
    fun deviceId() = device
    private fun prefs() = App.getContext().getSharedPreferences("piterfy-connect", Context.MODE_PRIVATE)
    private fun identity() = "${Preferences.getServer()}|${Preferences.getUser()}"
    private fun key() = "enabled-" + identity()
    private fun local() = snapshot?.optString("owner") == device
    private fun changed() { changes.value = (changes.value ?: 0) + 1 }

    fun guardPlayback(candidate: Player) {
        if (!enabled || !candidate.playWhenReady) return
        val state = snapshot ?: return
        if ((!state.isNull("owner") && !local()) || state.optJSONObject("pending")?.optString("from") == device ||
            (local() && SystemClock.elapsedRealtime() - lastOk > 9000)) candidate.pause()
    }

    fun attach(ctx: Context, provider: () -> Player?) {
        detach()
        context = ctx.applicationContext
        player = provider
        // A service instance is a new receiver. Old in-flight commands cannot target it.
        device = "android-" + UUID.randomUUID()
        account = identity()
        output = PiterfyOutputMonitor(ctx) { changed() }
        enabled = prefs().getBoolean(key(), false)
        if (enabled) output?.start()
        main.post(tick)
    }

    fun detach() {
        generation++
        main.removeCallbacks(tick)
        output?.stop(); output = null
        player = null; context = null
        snapshot = null; applied = ""; stopped = ""; applying = null; busy = false
        LanRemoteSession.clearConnect()
    }

    fun enable() {
        prefs().edit().putBoolean(key(), true).apply()
        enabled = true; account = identity(); error = ""
        output?.start()
        main.removeCallbacks(tick); main.post(tick)
        changed()
    }

    @JvmStatic fun disable() {
        if (local()) player?.invoke()?.pause()
        prefs().edit().putBoolean(key(), false).apply()
        enabled = false; generation++; busy = false; applying = null
        snapshot = null; applied = ""; stopped = ""
        output?.stop()
        LanRemoteSession.clearConnect()
        changed()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player == null) return
            if (account != identity()) { disable(); account = identity() }
            if (enabled) {
                if (local() && SystemClock.elapsedRealtime() - lastOk > 9000) player?.invoke()?.pause()
                if (!busy && !LanRemoteSession.hasLocalNetworkTarget()) poll()
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun report(): JSONObject? {
        val p = player?.invoke() ?: return null
        if (p.mediaItemCount > 500) { error = "Connect obsługuje kolejki do 500 utworów."; return null }
        val items = JSONArray()
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            if (item.mediaMetadata.extras?.getString("type") == "radio" || item.mediaId.startsWith("ir_") || item.mediaId.startsWith("local_piterfy_")) return null
            items.put(encode(MappingUtil.mapToChild(item)))
        }
        return JSONObject().put("items", items).put("index", p.currentMediaItemIndex.coerceAtLeast(0))
            .put("position", p.currentPosition.coerceAtLeast(0)).put("playing", p.playWhenReady && p.playbackState != Player.STATE_ENDED)
            .put("repeat", p.repeatMode).put("shuffle", p.shuffleModeEnabled)
            .put("speed", p.playbackParameters.speed.toDouble()).put("volume", p.volume.toDouble())
    }

    private fun poll() {
        val body = JSONObject().put("device", JSONObject().put("id", device).put("name", Build.MODEL.take(80))
            .put("kind", "android").put("output", outputLabel().take(120)))
            .put("version", applied).put("stopAck", stopped)
        if (applying == null) report()?.let { body.put("report", it) }
        request("poll", body, false)
    }

    fun outputLabel(): String {
        val route = output?.destination().orEmpty()
        return if (route.isBlank()) "Ten telefon" else "Ten telefon · $route"
    }

    private data class Login(val origin: String, val user: String, val password: String, val account: String)
    private fun login(): Login {
        val url = (Preferences.getServer() ?: "").toHttpUrl()
        require(url.isHttps) { "Connect wymaga adresu HTTPS serwera." }
        return Login(url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'),
            Preferences.getUser() ?: "", Preferences.getPassword() ?: "", identity())
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401) token = ""
                val reason = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrDefault("")
                throw IllegalStateException(when (reason) {
                    "session_changed" -> "Kolejka się zmieniła. Spróbuj ponownie."
                    "transfer_pending" -> "Trwa przenoszenie odtwarzania."
                    "device_offline" -> "Urządzenie jest teraz niedostępne."
                    "choose_device" -> "Najpierw wybierz urządzenie."
                    "queue_limit" -> "Connect obsługuje kolejki do 500 utworów."
                    else -> if (response.code == 401) "Zaloguj się ponownie w Piterfy." else "Connect jest chwilowo niedostępny."
                })
            }
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun authorize(auth: Login): String {
        if (tokenAccount != auth.account || SystemClock.elapsedRealtime() - tokenAt > 1800000) token = ""
        if (token.isEmpty()) {
            require(auth.password.isNotEmpty()) { "Connect wymaga ponownego zalogowania hasłem do Piterfy." }
            val body = JSONObject().put("username", auth.user).put("password", auth.password)
            token = execute(Request.Builder().url(auth.origin + "/auth/login").post(body.toString().toRequestBody(jsonType)).build()).getString("token")
            tokenAccount = auth.account; tokenAt = SystemClock.elapsedRealtime()
        }
        return token
    }

    private fun request(path: String, body: JSONObject, notify: Boolean) {
        if (busy || !enabled) return
        val auth = try { login() } catch (e: Exception) { error = e.message ?: "Sprawdź adres serwera."; changed(); return }
        busy = true
        val guard = generation
        io.execute {
            try {
                val bearer = authorize(auth)
                val result = execute(Request.Builder().url(auth.origin + "/piterfy-api/connect/" + path)
                    .header("X-ND-Authorization", "Bearer $bearer").post(body.toString().toRequestBody(jsonType)).build())
                main.post {
                    if (guard == generation && account == auth.account && identity() == auth.account && enabled) {
                        busy = false; error = ""; lastOk = SystemClock.elapsedRealtime()
                        accept(result, auth, bearer)
                    }
                }
            } catch (e: Exception) {
                main.post { if (guard == generation && identity() == auth.account) {
                    busy = false
                    error = if (e is IllegalStateException || e is IllegalArgumentException) e.message.orEmpty() else "Brak połączenia z Connect."
                    if (notify) toast(error)
                    if (LanRemoteSession.isConnect()) LanRemoteSession.connectUnavailable()
                    changed()
                } }
            }
        }
    }

    private fun accept(next: JSONObject, auth: Login, bearer: String) {
        val previousOwner = snapshot?.optString("owner")
        snapshot = next
        val pending = next.optJSONObject("pending")
        val version = next.getString("version")
        if (pending?.optString("from") == device) {
            player?.invoke()?.pause()
            stopped = pending.getString("id"); applied = version; applying = null
            main.post { if (!busy && enabled) poll() }
        } else {
            stopped = ""
            if (!local()) {
                if (previousOwner == device || !next.isNull("owner")) player?.invoke()?.pause()
                applying = null
                applied = version
                renderRemote(next)
            } else {
                LanRemoteSession.clearConnect()
                if (applied != version && applying != version) applyLocal(next, auth, bearer, previousOwner != device)
            }
        }
        changed()
    }

    private fun renderRemote(s: JSONObject) {
        if (s.isNull("owner")) { LanRemoteSession.clearConnect(); return }
        val devices = s.getJSONArray("devices")
        val owner = (0 until devices.length()).map { devices.getJSONObject(it) }.find { it.optString("id") == s.optString("owner") }
        val items = decode(s.getJSONArray("items"))
        val index = s.optInt("index")
        val song = items.getOrNull(index)
        val name = if (owner?.optString("kind") == "web") "komputerze" else owner?.optString("name").orEmpty()
        LanRemoteSession.acceptConnect(LanRemoteState(active = true, connected = owner?.optBoolean("online") == true,
            name = name, title = song?.title.orEmpty(), artist = song?.artist.orEmpty(), album = song?.album.orEmpty(),
            coverId = song?.coverArtId.orEmpty(), position = s.optLong("position"), duration = (song?.duration ?: 0) * 1000L,
            playing = s.optBoolean("playing"), playWhenReady = s.optBoolean("playing"), volume = s.optDouble("volume", 1.0).toFloat(),
            index = index, count = items.size, receivedAt = SystemClock.elapsedRealtime(), revision = s.getString("version"),
            shuffle = s.optBoolean("shuffle"), repeat = s.optInt("repeat"), hasNext = index < items.lastIndex || s.optInt("repeat") == 2,
            hasPrevious = index > 0, mediaId = song?.id.orEmpty(), speed = s.optDouble("speed", 1.0).toFloat()), items)
    }

    private fun applyLocal(s: JSONObject, auth: Login, bearer: String, moved: Boolean) {
        val p = player?.invoke() ?: return
        val version = s.getString("version")
        val rows = decode(s.getJSONArray("items"))
        val old = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        val desired = rows.map { it.id }
        val guard = generation
        if (old == desired) {
            configure(p, s, moved)
            applied = version
            return
        }
        applying = version
        p.pause()
        mapper.execute {
            try {
                // Resolve via the phone's own account. Remote snapshots never provide stream URLs.
                val records = mutableMapOf<String, Child>()
                desired.distinct().chunked(40).forEach { ids ->
                    check(guard == generation && identity() == auth.account)
                    val url = (auth.origin + "/api/song").toHttpUrl().newBuilder()
                    ids.forEach { url.addQueryParameter("id", it) }
                    http.newCall(Request.Builder().url(url.build()).header("X-ND-Authorization", "Bearer $bearer").build()).execute().use { response ->
                        check(response.isSuccessful)
                        val result = JSONArray(response.body?.string() ?: "[]")
                        for (i in 0 until result.length()) {
                            val row = result.getJSONObject(i)
                            row.put("cover", row.getString("id")).put("bitrate", row.optInt("bitRate"))
                            records[row.getString("id")] = decode(JSONArray().put(row)).first()
                        }
                    }
                }
                val songs = desired.map { records[it] ?: error("Utwór jest niedostępny na tym koncie.") }
                val media = songs.map { MappingUtil.mapMediaItem(it) }
                main.post {
                    if (guard == generation && identity() == auth.account && enabled && local() && snapshot?.optString("version") == version && SystemClock.elapsedRealtime() - lastOk < 9000) {
                        val live = player?.invoke() ?: return@post
                        if (media.isEmpty()) live.clearMediaItems()
                        else { live.setMediaItems(media, s.optInt("index"), s.optLong("position")); live.prepare() }
                        configure(live, s, true)
                        applied = version; applying = null
                        QueueRepository().insertAll(songs, true, 0)
                        changed()
                    } else if (applying == version) applying = null
                }
            } catch (_: Exception) {
                main.post { if (guard == generation && applying == version) {
                    applying = null; error = "Nie można przygotować kolejki na telefonie."; toast(error); changed()
                } }
            }
        }
    }

    private fun configure(p: Player, s: JSONObject, moved: Boolean) {
        if (p.mediaItemCount > 0) {
            if (moved || p.currentMediaItemIndex != s.optInt("index") || kotlin.math.abs(p.currentPosition - s.optLong("position")) > 1500)
                p.seekTo(s.optInt("index"), s.optLong("position"))
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
        }
        p.repeatMode = s.optInt("repeat")
        p.shuffleModeEnabled = s.optBoolean("shuffle")
        p.playbackParameters = PlaybackParameters(s.optDouble("speed", 1.0).toFloat())
        p.volume = s.optDouble("volume", 1.0).toFloat()
        p.playWhenReady = s.optBoolean("playing") && p.mediaItemCount > 0
    }

    fun transfer(target: String) { send("transfer", JSONObject().put("target", target)) }
    fun adopt() { report()?.let { send("adopt", JSONObject().put("target", device).put("state", it)) } }
    private fun send(action: String, extra: JSONObject = JSONObject()) {
        if (busy) { toast("Poczekaj na potwierdzenie poprzedniej operacji."); return }
        val s = snapshot ?: return
        extra.put("action", action).put("version", s.getString("version")).put("requestId", UUID.randomUUID().toString())
        request("command", extra, true)
    }

    @JvmStatic fun command(name: String, value: Long) {
        val body = JSONObject()
        when (name) {
            "seek" -> body.put("position", value)
            "volume" -> body.put("value", value / 100.0)
            "repeat" -> body.put("value", value)
            "shuffle" -> body.put("enabled", value != 0L)
        }
        send(name, body)
    }
    @JvmStatic fun speed(speed: Float) { send("speed", JSONObject().put("value", speed.toDouble())) }
    @JvmStatic fun songs(songs: List<Child>, action: String, index: Int, position: Long) {
        if (action == "reorder") { toast("Przesuwaj utwory w kolejce pojedynczo."); return }
        if (songs.size > 500) { toast("Connect obsługuje kolejki do 500 utworów."); return }
        val items = JSONArray(); songs.forEach { items.put(encode(it)) }
        send("edit", JSONObject().put("edit", action).put("items", items).put("index", index).put("position", position))
    }
    @JvmStatic fun edit(action: String, from: Int, to: Int) {
        send("edit", JSONObject().put("edit", action).put("from", from).put("to", to).put("index", from))
    }
    @JvmStatic fun unsupported() { toast("Ta funkcja nie jest dostępna dla urządzenia Connect.") }
    private fun toast(message: String) { context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() } }

    private fun encode(song: Child) = JSONObject().put("id", song.id).put("title", song.title.orEmpty().take(512))
        .put("artist", song.artist.orEmpty().take(512)).put("album", song.album.orEmpty().take(512))
        .put("albumId", song.albumId.orEmpty()).put("artistId", song.artistId.orEmpty()).put("cover", song.coverArtId.orEmpty())
        .put("suffix", song.suffix.orEmpty()).put("duration", song.duration ?: 0).put("bitrate", song.bitrate ?: 0)

    private fun decode(items: JSONArray): List<Child> = (0 until items.length()).map { i ->
        val row = items.getJSONObject(i)
        Child(row.getString("id"), title = row.optString("title"), artist = row.optString("artist"), album = row.optString("album"),
            albumId = row.optString("albumId").takeIf { it.isNotBlank() }, artistId = row.optString("artistId").takeIf { it.isNotBlank() },
            coverArtId = row.optString("cover").ifBlank { row.getString("id") }, duration = row.optInt("duration"),
            suffix = row.optString("suffix"), bitrate = row.optInt("bitrate"), genre = row.optString("genre"))
    }
}
