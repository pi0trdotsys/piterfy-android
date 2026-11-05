package com.eddyizm.tempus.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.subsonic.base.ApiResponse
import com.eddyizm.tempus.util.LyricsTiming
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.MusicUtil
import com.eddyizm.tempus.util.PiterfyLyricsLoader
import com.eddyizm.tempus.util.PiterfyQueues
import com.eddyizm.tempus.util.Preferences
import com.google.android.gms.cast.framework.CastSession
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executors

/** Only the attached Cast session can request library data. Credentials remain on Android. */
@UnstableApi
internal class PiterfyCastLibrary(private val player: Player) {
    private val namespace = "urn:x-cast:eu.prosinski.piterfy.library"
    private val lyrics = PiterfyLyricsLoader()
    private val worker = Executors.newSingleThreadExecutor()
    private var session: CastSession? = null
    private var pending: Call<ApiResponse>? = null
    private var revision = 0

    fun attach(next: CastSession?) {
        if (next === session) return
        revision++
        pending?.cancel()
        pending = null
        lyrics.cancel()
        session?.let { runCatching { it.removeMessageReceivedCallbacks(namespace) } }
        session = next
        next ?: return
        runCatching {
            next.setMessageReceivedCallbacks(namespace) { _, _, message -> receive(next, message) }
            next.sendMessage(namespace, """{"type":"ready"}""")
        }
    }

    fun close() {
        attach(null)
        lyrics.close()
        worker.shutdownNow()
    }

    private fun receive(source: CastSession, message: String) {
        if (source !== session || !source.isConnected || message.length > 1024) return
        val request = runCatching { JSONObject(message) }.getOrNull() ?: return
        val id = request.optInt("requestId", -1)
        if (id < 0) return
        val account = account()
        val operation = request.optString("type")
        val offset = request.optInt("offset").coerceAtLeast(0)
        val playlistId = request.optString("playlistId").take(128)
        fun send(body: JSONObject) {
            if (session === source && source.isConnected && account == account()) {
                runCatching { source.sendMessage(namespace, body.put("requestId", id).toString()) }
            }
        }
        fun error() = send(JSONObject().put("error", "Nie udało się wykonać tej operacji. Spróbuj ponownie."))

        when (operation) {
            "queue" -> {
                val count = player.mediaItemCount
                val page = offset.coerceAtMost(count)
                val rows = JSONArray()
                for (index in page until minOf(page + 20, count)) {
                    val item = player.getMediaItemAt(index)
                    rows.put(row(item.mediaId, item.mediaMetadata.title, item.mediaMetadata.artist)
                        .put("index", index).put("playing", index == player.currentMediaItemIndex))
                }
                send(page(rows, count, page))
            }
            "queueJump" -> {
                val index = request.optInt("index", -1)
                if (index !in 0 until player.mediaItemCount ||
                    player.getMediaItemAt(index).mediaId != request.optString("id")) return error()
                player.seekTo(index, 0)
                player.play()
                send(JSONObject())
            }
            "lyrics" -> {
                val item = player.currentMediaItem ?: return send(JSONObject())
                val song = MappingUtil.mapToChild(item)
                lyrics.load(song, account + "|" + song.id, false) { result ->
                    val structured = LyricsTiming.preferred(result.lines())
                    val rows = JSONArray()
                    var remaining = 8000
                    val lines = structured?.line.orEmpty().filterNotNull()
                    for (line in if (structured?.synced == true) lines.sortedBy { it.start ?: Int.MAX_VALUE } else lines) {
                        if (structured?.synced == true && line.start == null) continue
                        val value = line.value.orEmpty().take(minOf(remaining, 250))
                        remaining -= value.length + 1
                        rows.put(JSONObject().put("text", value).put("ms", line.start))
                        if (remaining <= 0 || rows.length() >= 350) break
                    }
                    send(JSONObject().put("lines", rows).put("synced", structured?.synced == true)
                        .put("offset", structured?.offset ?: 0).put("text", result.text()?.take(8000))
                        .put("status", result.status()))
                }
            }
            "playlists", "playlist", "playlistPlay" -> {
                val client = App.getSubsonicClientInstance(false).playlistClient
                if (operation != "playlists" && playlistId.isBlank()) return error()
                val call = if (operation == "playlists") client.getPlaylists() else client.getPlaylist(playlistId)
                pending?.cancel()
                pending = call
                val version = ++revision
                call.enqueue(object : Callback<ApiResponse> {
                    override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                        if (version == revision && !call.isCanceled) error()
                    }

                    override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                        if (version != revision || session !== source || account != account()) return
                        val data = response.body()?.subsonicResponse ?: return error()
                        val rows = JSONArray()
                        if (operation == "playlists") {
                            val lists = data.playlists?.playlists ?: return error()
                            lists.drop(offset).take(20).forEach { rows.put(row(it.id, it.name, "${it.songCount} utworów")) }
                            send(page(rows, lists.size, offset))
                            return
                        }
                        val playlist = data.playlist ?: return error()
                        val entries = playlist.entries.orEmpty()
                        if (operation == "playlist") {
                            entries.drop(offset).take(20).forEachIndexed { index, song ->
                                rows.put(row(song.id, song.title, song.artist).put("index", offset + index))
                            }
                            send(page(rows, entries.size, offset).put("title", playlist.name))
                            return
                        }
                        val index = request.optInt("index", -1)
                        val selected = entries.getOrNull(index) ?: return error()
                        if (selected.id != request.optString("id")) return error()
                        val group = PiterfyQueues.partition(entries, index)
                        val start = entries.take(index).count { PiterfyQueues.podcast(it) == PiterfyQueues.podcast(selected) }
                        val first = (start - 100).coerceAtLeast(0)
                        worker.execute {
                            val items = group.drop(first).take(200).mapIndexedNotNull { position, song ->
                                runCatching {
                                    val item = MappingUtil.mapMediaItem(song) ?: return@runCatching null
                                    val uri = MusicUtil.getStreamUri(song.id)
                                    position to item.buildUpon().setUri(uri)
                                        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()).build()
                                }.getOrNull()
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (version != revision || session !== source || account != account()) return@post
                                val target = items.indexOfFirst { it.first == start - first }
                                if (target < 0) return@post error()
                                player.setMediaItems(items.map { it.second }, target, 0)
                                player.prepare()
                                player.play()
                                send(JSONObject())
                            }
                        }
                    }
                })
            }
        }
    }

    private fun account() = "${Preferences.getServer()}|${Preferences.getUser()}"

    private fun row(id: String?, title: CharSequence?, subtitle: CharSequence?) = JSONObject()
        .put("id", id).put("title", title?.toString()?.take(160)).put("subtitle", subtitle?.toString()?.take(160))

    private fun page(rows: JSONArray, count: Int, offset: Int) = JSONObject()
        .put("rows", rows).put("count", count).put("offset", offset)
}
