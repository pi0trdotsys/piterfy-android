package com.eddyizm.tempus.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.DynamicMediaSourceFactory
import com.eddyizm.tempus.util.PiterfyMedia
import com.eddyizm.tempus.util.SleepTimerManager
import com.eddyizm.tempus.util.ReplayGainAudioProcessor
import com.eddyizm.tempus.util.ReplayGainUtil
import kotlin.math.cos
import kotlin.math.sin

/** One buffered auxiliary deck; the service player keeps its queue, notification and session. */
@UnstableApi
class PiterfyCrossfade(private val context: Context, private val primary: ExoPlayer,
    private val active: () -> Boolean) : Player.Listener {
    companion object {
        @JvmStatic var instance: PiterfyCrossfade? = null
            private set
        @JvmStatic fun reportedVolume(player: Player): Float = instance?.takeIf { it.primary === player && it.mixing }?.level ?: player.volume
        @JvmStatic fun cancelActive() { instance?.cancel() }
        fun durationMillis(value: Int) = value.coerceIn(1, 10) * 1000L
    }
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("piterfy_playback", Context.MODE_PRIVATE)
    private var auxiliary: ExoPlayer? = null
    private var from = ""
    private var target = ""
    private var blocked = ""
    private var handoff = false
    private var aligned = false
    private var deadline = 0L
    private var fadeMs = 5000L
    private var lastSet = 1f
    private var level = 1f
    private var mixing = false
    private var closed = false

    private fun music(item: MediaItem?): Boolean {
        val extras = item?.mediaMetadata?.extras ?: return false
        return extras.getString("type") != "radio" && extras.getString("type") != "podcast" &&
            !PiterfyMedia.isPodcast(extras.getString("genre")) && !item.mediaId.startsWith("local_piterfy_")
    }
    fun cancel() {
        if (auxiliary != null) blocked = from
        auxiliary?.release(); auxiliary = null
        if (mixing) primary.volume = level
        mixing = false; handoff = false; aligned = false
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady && !handoff) cancel() }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (!handoff && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) cancel()
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!handoff) { cancel(); blocked = "" }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            try { update() } catch (_: Exception) { cancel() }
            handler.postDelayed(this, 50)
        }
    }
    init { instance = this; primary.addListener(this); handler.post(tick) }
    private fun update() {
        if (!active() || !preferences.getBoolean("crossfade_enabled", true) || SleepTimerManager.getInstance().isActive ||
            primary.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
            primary.repeatMode == Player.REPEAT_MODE_ONE || !music(primary.currentMediaItem)) { cancel(); return }
        val next = primary.nextMediaItemIndex
        val secondary = auxiliary
        if (secondary == null) {
            if (!primary.isPlaying || primary.duration <= 0 || primary.duration == C.TIME_UNSET ||
                primary.currentMediaItem?.mediaId == blocked || next == C.INDEX_UNSET || next == primary.currentMediaItemIndex) return
            val remaining = (primary.duration - primary.currentPosition) / primary.playbackParameters.speed
            if (remaining > 25000 || remaining < 700 || !music(primary.getMediaItemAt(next))) return
            val item = primary.getMediaItemAt(next)
            from = primary.currentMediaItem!!.mediaId; target = item.mediaId
            val processor=ReplayGainAudioProcessor()
            processor.setGainImmediate(ReplayGainUtil.gainForCrossfade(primary,item))
            val renderers=object:DefaultRenderersFactory(context){
                init {setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)}
                override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(processor)).setEnableFloatOutput(enableFloatOutput).build()
            }
            auxiliary = ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setMediaSourceFactory(DynamicMediaSourceFactory(context))
                .setAudioAttributes(AudioAttributes.DEFAULT, false)
                .setHandleAudioBecomingNoisy(true).build().also {
                    it.volume = 0f; it.playbackParameters = primary.playbackParameters
                    it.setMediaItem(item); it.prepare()
                }
            return
        }
        if (secondary.playerError != null || !primary.playWhenReady ||
            secondary.playbackParameters != primary.playbackParameters) { cancel(); return }
        if (handoff) {
            if (SystemClock.elapsedRealtime() > deadline) { cancel(); return }
            if (primary.currentMediaItem?.mediaId != target) { cancel(); return }
            if (!aligned && primary.playbackState == Player.STATE_READY) {
                aligned = true; primary.seekTo(secondary.currentPosition); primary.play()
            } else if (aligned && primary.isPlaying && kotlin.math.abs(primary.currentPosition - secondary.currentPosition) < 200) {
                cancel(); blocked = ""
            }
            return
        }
        if (primary.currentMediaItem?.mediaId != from || next == C.INDEX_UNSET || primary.getMediaItemAt(next).mediaId != target) { cancel(); return }
        if (!mixing) {
            val duration = durationMillis(preferences.getInt("crossfade_seconds", 5)).coerceAtMost(primary.duration / 2)
            val remaining = ((primary.duration - primary.currentPosition) / primary.playbackParameters.speed).toLong()
            if (remaining <= duration && remaining > 500 && primary.isPlaying && secondary.playbackState == Player.STATE_READY &&
                primary.bufferedPosition >= primary.duration - 200 && secondary.bufferedPosition > duration + 1000) {
                level = primary.volume; lastSet = level; fadeMs = remaining; mixing = true; secondary.play()
            }
            return
        }
        if (!primary.isPlaying || secondary.playbackState != Player.STATE_READY) { cancel(); return }
        if (kotlin.math.abs(primary.volume - lastSet) > .002f) level = primary.volume
        val progress = (secondary.currentPosition / primary.playbackParameters.speed / fadeMs).coerceIn(0f, 1f)
        lastSet = (level * cos(progress * Math.PI / 2)).toFloat()
        primary.volume = lastSet
        secondary.volume = (level * sin(progress * Math.PI / 2)).toFloat()
        if (progress >= .995f || primary.duration - primary.currentPosition < 150) {
            handoff = true; deadline = SystemClock.elapsedRealtime() + 8000
            primary.volume = 0f; lastSet = 0f; secondary.volume = level
            primary.seekTo(next, secondary.currentPosition); primary.play()
        }
    }
    fun close() { closed = true; handler.removeCallbacks(tick); cancel(); primary.removeListener(this); if (instance === this) instance = null }
}
