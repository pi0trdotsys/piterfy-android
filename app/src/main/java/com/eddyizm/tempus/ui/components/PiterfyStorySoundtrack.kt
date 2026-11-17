package com.eddyizm.tempus.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eddyizm.tempus.util.MusicUtil
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.Preferences

/**
 * Piterfy: muzyka przez cały czas trwania historyjki. Ścieżkę układa [PiterfyStoryPlan.soundtrack]: pierwsze pięć
 * najczęściej słuchanych utworów, każdy jako fragment (do 45 s od 35% utworu), jeden po drugim od chwili otwarcia aż
 * do zamknięcia (potem cisza). Osobny MediaPlayer (nie kolejka Media3), strumień z Navidrome w lekkim MP3 128 kb/s;
 * początek fragmentu ustawia timeOffset w adresie (transkodowany MP3 nie obsługuje Range, więc seekTo nie działałoby).
 * Następny fragment przygotowuje się na [PiterfyStoryPlan.PREFETCH_MS] przed końcem poprzedniego, żeby nie było przerwy.
 * Czas ścieżki to jeden zegar (jak czas pokazu): płynie niezależnie od wyciszenia i staje razem z pauzą, więc po
 * odciszeniu muzyka wraca w miejsce, w którym „byłaby”. Każdy błąd (sieć, brak sesji, oszczędzanie danych, utrata
 * fokusu audio) kończy się ciszą bez komunikatów: muzyka jest dodatkiem.
 */
class PiterfyStorySoundtrack(context: Context, private val onNowPlaying: (PiterfyStoryPlan.Segment?) -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private class Voice(val index: Int, val player: MediaPlayer) {
        var prepared = false
        var started = false
        var dead = false
    }

    private var segments: List<PiterfyStoryPlan.Segment> = emptyList()
    private var running = false
    private var silenced = false          // fokus audio przejął ktoś inny: do końca historyjki cisza
    private var clockBase = 0L            // uptimeMillis, przy którym czas ścieżki wynosił 0 (przesuwany w pauzie)
    private var pausedAt = 0L             // != 0, gdy zegar stoi (przytrzymanie palcem, ekran w tle)
    private var current: Voice? = null
    private var next: Voice? = null
    private var focus: AudioFocusRequest? = null
    private var hasFocus = false

    /** Wyciszone przez użytkownika (pamiętane w preferencjach); wyciszenie kończy strumieniowanie. */
    var muted: Boolean
        get() = app.getSharedPreferences(PREFS, 0).getBoolean(KEY_MUTED, false)
        set(value) {
            app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_MUTED, value).apply()
            if (value) { stopVoices(); onNowPlaying(null) }
        }

    /** Czy w ogóle jest co grać (jakikolwiek utwór z id). */
    val hasTracks: Boolean get() = segments.isNotEmpty()

    private fun now() = SystemClock.uptimeMillis()
    private fun elapsed() = (if (pausedAt != 0L) pausedAt else now()) - clockBase

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            handler.postDelayed(this, 40)
            if (pausedAt == 0L) step()
        }
    }

    /** Zaczyna ścieżkę od zera; nic nie gra bez utworów z id. */
    fun start(tracks: List<PiterfyStatsModel.Track>) {
        release()
        segments = PiterfyStoryPlan.soundtrack(tracks)
        if (segments.isEmpty()) return
        silenced = false; pausedAt = 0L; clockBase = now(); running = true
        handler.post(tick)
    }

    /** Pauza razem z historyjką (przytrzymanie palcem, ekran w tle): zegar staje, a po wznowieniu nic nie ucina. */
    fun pause() {
        if (!running || pausedAt != 0L) return
        pausedAt = now()
        current?.let { if (it.started) runCatching { it.player.pause() } }
    }

    fun resume() {
        if (!running || pausedAt == 0L) return
        clockBase += now() - pausedAt
        pausedAt = 0L
        current?.let { if (it.started && !it.dead) runCatching { it.player.start() } else startVoice(it) }
    }

    /** Kończy wszystko i zapomina utwory; wołane przy zamknięciu historyjki. */
    fun release() {
        running = false
        handler.removeCallbacks(tick)
        stopVoices()
        abandonFocus()
        onNowPlaying(null)
        segments = emptyList()
    }

    // ── jedna klatka zegara: który fragment, jaka głośność, czy przygotować następny ──
    private fun step() {
        val position = PiterfyStoryPlan.positionAt(segments, elapsed())
        if (position == null) { stopVoices(); onNowPlaying(null); running = false; return }
        if (muted || silenced || Preferences.isDataSavingMode()) { if (current != null) { stopVoices(); onNowPlaying(null) }; return }

        var voice = current
        if (voice == null || voice.index != position.index) {
            releaseVoice(voice)
            val prepared = next?.takeIf { it.index == position.index }
            if (prepared != null) { next = null; voice = prepared } else { releaseVoice(next); next = null; voice = begin(position.index, position.intoMs) }
            current = voice
            if (voice != null) startVoice(voice) else onNowPlaying(null)
        }
        voice = current ?: return
        if (voice.started && !voice.dead) {
            val v = PiterfyStoryPlan.volumeAt(position.intoMs, segments[position.index].preview)
            runCatching { voice.player.setVolume(v, v) }
        }
        if (next == null && PiterfyStoryPlan.prefetchDue(segments, position)) next = begin(position.index + 1, 0L)
    }

    /** Tworzy odtwarzacz fragmentu [index] od chwili [intoMs] jego okna i zaczyna go przygotowywać. */
    private fun begin(index: Int, intoMs: Long): Voice? {
        val segment = segments.getOrNull(index) ?: return null
        return runCatching {
            val offset = ((segment.preview.startMs + intoMs) / 1000L).toInt()
            val url = PiterfyStoryPlan.previewUrl(MusicUtil.getStreamUri(segment.id, offset).toString())
            val mp = MediaPlayer()
            val voice = Voice(index, mp)
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (voice.dead) return@setOnPreparedListener
                voice.prepared = true
                if (voice === current) startVoice(voice)
            }
            mp.setOnErrorListener { _, _, _ -> kill(voice); true }
            mp.setDataSource(url)
            mp.prepareAsync()
            voice
        }.getOrNull()
    }

    private fun startVoice(voice: Voice) {
        if (!voice.prepared || voice.started || voice.dead || pausedAt != 0L || !running) return
        if (!requestFocus()) { kill(voice); silenced = true; return }
        voice.started = true
        runCatching { voice.player.setVolume(0f, 0f); voice.player.start() }.onFailure { kill(voice); return }
        segments.getOrNull(voice.index)?.let { onNowPlaying(it) }
    }

    /** Odtwarzacz padł (sieć, błąd serwera): do końca fragmentu cisza, następny zagra normalnie. */
    private fun kill(voice: Voice) {
        voice.dead = true
        runCatching { voice.player.reset() }; runCatching { voice.player.release() }
        if (voice === current) onNowPlaying(null)
    }

    private fun releaseVoice(voice: Voice?) {
        if (voice == null) return
        voice.dead = true
        runCatching { voice.player.stop() }
        runCatching { voice.player.reset() }; runCatching { voice.player.release() }
    }

    private fun stopVoices() {
        releaseVoice(current); releaseVoice(next)
        current = null; next = null
    }

    // ── fokus audio ──
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) handler.post {
            silenced = true; stopVoices(); onNowPlaying(null)
        }
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener, handler).build()
                focus = request
                audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
        return hasFocus
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
        }
        focus = null; hasFocus = false
    }

    companion object {
        private const val PREFS = "piterfy_story"
        private const val KEY_MUTED = "muted"
    }
}
