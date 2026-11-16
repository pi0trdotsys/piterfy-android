package com.eddyizm.tempus.service

import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.connect.ConnectSession
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.repository.SongRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyMedia
import com.eddyizm.tempus.util.PiterfySmartPolicy
import com.eddyizm.tempus.util.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

/**
 * Piterfy: inteligentne odtwarzanie. Dobiera utwory z biblioteki różnorodnie (kilka ostatnich wykonawców jako
 * wzór, pamięć zagranych, ranking gustu z serwera), a w tle prosi serwer o ściągnięcie nowych, podobnych utworów,
 * których jeszcze nie ma w bibliotece (tryb „radio” w Piterfy DJ), i dokłada je do kolejki po jednym.
 *
 * Czysta logika wyboru: [PiterfySmartPolicy]. Ten sam algorytm działa w przeglądarce (connect/web/library.js).
 */
@UnstableApi
object PiterfySmartPlayback {
    private const val EVERY = 3                 // co ile utworów wstawiamy jeden polecony jako następny
    private const val HISTORY_SIZE = 12
    private const val RADIO_REFRESH_MS = 240_000L
    private const val RADIO_POLL_MS = 45_000L

    private var lastItemId = ""
    private var played = 0
    private var since = 0
    private var busy = false
    private val pending = ArrayList<Child>()   // nowe utwory z serwera czekające na wplecenie w kolejkę
    private var radioStart = 0L
    private var radioPoll = 0L
    private var radioBusy = false

    private fun prefs() = App.getContext().getSharedPreferences("piterfy_smart", 0)
    private fun account() = PiterfyLibrary.account()

    // ── pamięć i historia (osobno dla każdego konta) ──

    private fun loadMemory(): Map<String, Long> = runCatching {
        val json = JSONObject(prefs().getString("memory:" + account(), "{}")!!)
        json.keys().asSequence().associateWith { json.getLong(it) }
    }.getOrDefault(emptyMap())

    private fun rememberIds(ids: Collection<String>) {
        val next = PiterfySmartPolicy.remember(loadMemory(), ids, System.currentTimeMillis())
        prefs().edit().putString("memory:" + account(), JSONObject(next).toString()).apply()
    }

    private fun storedHistory(): List<Child> = runCatching {
        val rows = JSONArray(prefs().getString("history:" + account(), "[]")!!)
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            Child(row.getString("id")).also {
                it.artist = row.optString("artist"); it.artistId = row.optString("artistId").ifEmpty { null }
                it.title = row.optString("title"); it.genre = row.optString("genre")
            }
        }
    }.getOrDefault(emptyList())

    private fun rememberPlayed(song: Child) {
        val rows = JSONArray()
        (listOf(song) + storedHistory().filter { it.id != song.id }).take(HISTORY_SIZE).forEach {
            rows.put(JSONObject().put("id", it.id).put("artist", it.artist ?: "").put("artistId", it.artistId ?: "")
                .put("title", it.title ?: "").put("genre", it.genre ?: ""))
        }
        prefs().edit().putString("history:" + account(), rows.toString()).apply()
        rememberIds(listOf(song.id))
    }

    /** Ostatnio grane, najnowsze pierwsze: z kolejki odtwarzacza i z pamięci między sesjami. */
    fun history(player: Player): List<Child> {
        val at = player.currentMediaItemIndex
        val fromQueue = if (at == C.INDEX_UNSET) emptyList() else
            (maxOf(0, at - 7)..at).reversed().mapNotNull { runCatching { MappingUtil.mapToChild(player.getMediaItemAt(it)) }.getOrNull() }
        return (fromQueue + storedHistory()).filter { !PiterfyMedia.isPodcast(it) }.distinctBy { it.id }.take(14)
    }

    private fun exploration() = PiterfyLibrary.cached().optInt("exploration", 50)

    // ── wybór ──

    /** Dobiera [count] utworów z biblioteki, które pasują do ostatnio granych i jeszcze nie ma ich w kolejce. */
    @JvmStatic
    fun recommend(target: MediaManager.QueueTarget, count: Int, done: Consumer<List<Child>>) {
        val player = target.livePlayer() ?: return done.accept(emptyList())
        val history = history(player)
        val seeds = PiterfySmartPolicy.seeds(history, 4)
        val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        SongRepository().getSmartCandidateLists(
            seeds.filter { !it.artistId.isNullOrEmpty() }, seeds.take(2), history.firstOrNull()?.genre, true,
        ) { similar, random ->
            var rows = PiterfySmartPolicy.merge(similar)
            if (rows.size < count * 4) {  // mało podobnych: dołóż losowe z biblioteki, ale z mniejszą wagą
                val have = rows.map { it.song.id }.toSet()
                rows = rows + PiterfySmartPolicy.merge(listOf(random)).filter { it.song.id !in have }.map { it.copy(hits = 0.5) }
            }
            if (rows.isEmpty()) { done.accept(emptyList()); return@getSmartCandidateLists }
            PiterfyLibrary.rankSongs(rows.map { it.song }) { ranked ->  // odrzuca „mniej takich”, premiuje lubiane
                val order = ranked.withIndex().associate { it.value.id to it.index }
                val ranked2 = rows.filter { order.containsKey(it.song.id) }.map { it.copy(rank = order[it.song.id]) }
                val now = System.currentTimeMillis()
                done.accept(PiterfySmartPolicy.pick(ranked2, history, queueIds, PiterfySmartPolicy.recentIds(loadMemory(), now),
                    count, exploration()))
            }
        }
    }

    // ── śledzenie odtwarzania ──

    private fun eligible(player: Player, item: MediaItem): Boolean {
        if (!Preferences.isContinuousPlayEnabled() || LanRemoteSession.isActive()) return false
        val extras = item.mediaMetadata.extras
        if (extras?.getString("type") in listOf("podcast", "radio") || PiterfyMedia.isPodcast(extras?.getString("genre"))) return false
        // gdy kolejką steruje DJ albo ręczne odkrywanie, nie dokładamy własnych utworów
        return !PiterfyDJSession.hasPlayback("dj") && !PiterfyDJSession.hasPlayback("discover")
    }

    /** Wywoływane przy każdym nowym utworze w usłudze odtwarzania. */
    @JvmStatic
    fun onTrackStarted(item: MediaItem, target: MediaManager.QueueTarget) {
        val player = target.livePlayer() ?: return
        if (item.mediaId == lastItemId) return
        lastItemId = item.mediaId
        if (!eligible(player, item)) {
            if (radioStart != 0L && !Preferences.isContinuousPlayEnabled()) stopRadio()
            return
        }
        runCatching { MappingUtil.mapToChild(item) }.getOrNull()?.let { rememberPlayed(it) }
        played += 1
        since += 1
        radioTick(target, false)
        if (pending.isNotEmpty() && since >= EVERY) {
            since = 0
            injectRadio(target)
            return  // w tym kroku wystarczy jedna nowość
        }
        if (player.nextMediaItemIndex == C.INDEX_UNSET) return  // koniec kolejki obsługuje MediaManager.continuousPlay
        if (played >= EVERY && !busy) {
            played = 0
            busy = true
            recommend(target, 1) { songs ->
                busy = false
                if (songs.isNotEmpty() && target.livePlayer() != null) {
                    rememberIds(songs.map { it.id })
                    MediaManager.enqueue(target, songs, true)
                }
            }
        }
    }

    /** Po dodaniu poleconych utworów do kolejki: zapamiętaj, żeby nie wracały przez kilka dni. */
    @JvmStatic fun remember(songs: List<Child>) = rememberIds(songs.map { it.id })

    // ── nowe utwory spoza biblioteki (tryb „radio” serwera) ──

    private fun radioSeeds(history: List<Child>): JSONArray {
        val rows = JSONArray()
        PiterfySmartPolicy.seeds(history, 5).filter { !it.artist.isNullOrEmpty() && !it.title.isNullOrEmpty() }.forEach {
            rows.put(JSONObject().put("id", it.id).put("artist", it.artist).put("title", it.title).put("genre", it.genre ?: ""))
        }
        return rows
    }

    private fun radioTick(target: MediaManager.QueueTarget, force: Boolean) {
        if (radioBusy || LanRemoteSession.isActive()) return
        val player = target.livePlayer() ?: return
        val now = SystemClock.elapsedRealtime()
        val refresh = force || radioStart == 0L || now - radioStart > RADIO_REFRESH_MS
        if (!refresh && now - radioPoll < RADIO_POLL_MS) return
        val body = JSONObject().put("mode", "radio").put("device", ConnectSession.deviceId())
        if (refresh) {
            val seeds = radioSeeds(history(player))
            if (seeds.length() == 0) return
            body.put("action", "start").put("seeds", seeds).put("exploration", exploration())
        } else {
            body.put("action", "get")
        }
        radioBusy = true
        PiterfyLibrary.dj(body, { state ->
            radioPoll = SystemClock.elapsedRealtime()
            if (refresh) radioStart = radioPoll
            takeReadyBlock(state)
        }, { radioBusy = false })
    }

    private fun takeReadyBlock(state: JSONObject) {
        val blocks = state.optJSONArray("blocks") ?: JSONArray()
        val block = (0 until blocks.length()).map { blocks.getJSONObject(it) }.firstOrNull {
            it.optBoolean("ready", true) && !it.optBoolean("consumed") && it.optString("id") != state.optString("currentBlock") &&
                it.optString("source") == "discovery" && (it.optJSONArray("tracks")?.length() ?: 0) > 0
        }
        if (block == null) { radioBusy = false; return }
        val select = JSONObject().put("action", "select").put("mode", "radio").put("session", state.optString("id"))
            .put("block", block.optString("id")).put("device", ConnectSession.deviceId())
        PiterfyLibrary.dj(select, {
            radioBusy = false
            val memory = loadMemory()
            val fresh = PiterfyDJSession.decode(block.getJSONArray("tracks")).filter { it.id !in memory && !PiterfyMedia.isPodcast(it) }
            if (fresh.isNotEmpty()) {
                pending.addAll(fresh)
                since = EVERY  // pierwszy nowy utwór od razu jako następny
                Toast.makeText(App.getContext(), "Znalazłem ${fresh.size} nowych podobnych utworów — dorzucę je do kolejki", Toast.LENGTH_SHORT).show()
            }
        }, { radioBusy = false })
    }

    private fun injectRadio(target: MediaManager.QueueTarget) {
        val song = pending.removeFirstOrNull() ?: return
        if (target.livePlayer() == null) return
        rememberIds(listOf(song.id))
        MediaManager.enqueue(target, listOf(song), true)
        Toast.makeText(App.getContext(), "Nowość na serwerze: ${song.artist ?: ""} — ${song.title ?: ""}", Toast.LENGTH_SHORT).show()
    }

    private fun stopRadio() {
        pending.clear()
        radioStart = 0L
        radioPoll = 0L
        PiterfyLibrary.dj(JSONObject().put("action", "stop").put("mode", "radio").put("device", ConnectSession.deviceId()), null, null)
    }
}
