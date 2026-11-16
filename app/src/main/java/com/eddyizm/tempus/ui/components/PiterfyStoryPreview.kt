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
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.Preferences

/**
 * Piterfy: krótki podgląd utworu nr 1 na slajdzie z utworami. Osobny MediaPlayer (nie kolejka Media3), strumień
 * z Navidrome w lekkim MP3 128 kb/s. Okno odtwarzania liczy [PiterfyStoryPlan.preview]: start przesuwamy parametrem
 * timeOffset w adresie (transkodowany MP3 nie obsługuje Range, więc seekTo nie działałoby) i gramy od zera tego
 * strumienia, przez najwyżej 25 s, z narastaniem i wyciszaniem. Każdy błąd (sieć, brak sesji, oszczędzanie danych)
 * kończy się ciszą, bez komunikatów: podgląd jest dodatkiem.
 */
class PiterfyStoryPreview(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var preview = PiterfyStoryPlan.preview(0)
    private var startedAt = 0L
    private var pausedAt = 0L
    private var playing = false
    private var prepared = false
    private var generation = 0

    /** Wyciszone przez użytkownika (pamiętane w preferencjach). */
    var muted: Boolean
        get() = app.getSharedPreferences(PREFS, 0).getBoolean(KEY_MUTED, false)
        set(value) { app.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_MUTED, value).apply(); if (value) fadeOutAndStop() else resumeIfWanted() }

    private var wantedTrack: String? = null
    private var wantedSeconds = 0

    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            if (!playing || !prepared) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            if (elapsed >= preview.lengthMs) { stopNow(); return }
            runCatching { val v = PiterfyStoryPlan.volumeAt(elapsed, preview); mp.setVolume(v, v) }
            handler.postDelayed(this, 40)
        }
    }

    /** Zaczyna podgląd utworu [trackId] (długość [seconds] z serwera); nic nie robi bez id, przy wyciszeniu lub oszczędzaniu danych. */
    fun start(trackId: String, seconds: Int) {
        wantedTrack = trackId; wantedSeconds = seconds
        if (muted) return
        begin()
    }

    private fun resumeIfWanted() {
        if (!muted && wantedTrack != null && player == null) begin()
    }

    private fun begin() {
        val id = wantedTrack ?: return
        stopNow()
        if (id.isBlank() || Preferences.isDataSavingMode()) return
        val token = ++generation
        runCatching {
            preview = PiterfyStoryPlan.preview(wantedSeconds)
            val offset = (preview.startMs / 1000L).toInt()
            val url = PiterfyStoryPlan.previewUrl(MusicUtil.getStreamUri(id, offset).toString())
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (token != generation || player !== it) return@setOnPreparedListener
                if (!requestFocus()) { stopNow(); return@setOnPreparedListener }
                prepared = true; playing = true
                startedAt = SystemClock.uptimeMillis()
                runCatching { it.start() }
                handler.post(tick)
            }
            mp.setOnErrorListener { _, _, _ -> if (token == generation) stopNow(); true }
            mp.setOnCompletionListener { if (token == generation) stopNow() }
            mp.setDataSource(url)
            mp.prepareAsync()
        }.onFailure { stopNow() }
    }

    /** Pauza razem z historyjką (przytrzymanie palcem); czas okna stoi, więc po wznowieniu nie ucina fragmentu. */
    fun pause() {
        val mp = player ?: return
        if (!playing || !prepared) return
        playing = false; pausedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        runCatching { mp.pause() }
    }

    fun resume() {
        val mp = player ?: return
        if (playing || !prepared) return
        startedAt += SystemClock.uptimeMillis() - pausedAt
        playing = true
        runCatching { mp.start() }
        handler.post(tick)
    }

    /** Wycisza w 600 ms i kończy (wyjście ze slajdu, zamknięcie, uśpienie aplikacji). */
    fun fadeOutAndStop() {
        val mp = player ?: return
        if (!playing || !prepared) { stopNow(); return }
        handler.removeCallbacks(tick)
        val token = generation
        val from = SystemClock.uptimeMillis()
        val startVolume = runCatching { PiterfyStoryPlan.volumeAt(from - startedAt, preview) }.getOrDefault(1f)
        val fade = object : Runnable {
            override fun run() {
                if (token != generation || player !== mp) return
                val k = (SystemClock.uptimeMillis() - from).toFloat() / PiterfyStoryPlan.PREVIEW_FADE_OUT_MS
                if (k >= 1f) { stopNow(); return }
                runCatching { val v = startVolume * (1f - k); mp.setVolume(v, v) }
                handler.postDelayed(this, 30)
            }
        }
        handler.post(fade)
    }

    private fun stopNow() {
        generation++
        handler.removeCallbacksAndMessages(null)
        val mp = player
        player = null; playing = false; prepared = false
        if (mp != null) runCatching { mp.stop() }.also { runCatching { mp.reset() }; runCatching { mp.release() } }
        abandonFocus()
    }

    /** Kończy wszystko i zapomina utwór; wołane przy zamknięciu historyjki. */
    fun release() {
        wantedTrack = null
        stopNow()
    }

    /** Opuszczono slajd z utworami: wyciszamy i kończymy; powrót na slajd woła [start] od nowa. */
    fun leave() {
        wantedTrack = null
        fadeOutAndStop()
    }

    // ── fokus audio ──
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) handler.post { stopNow() }
    }

    private fun requestFocus(): Boolean = runCatching {
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

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
        }
        focus = null
    }

    companion object {
        private const val PREFS = "piterfy_story"
        private const val KEY_MUTED = "muted"
    }
}
