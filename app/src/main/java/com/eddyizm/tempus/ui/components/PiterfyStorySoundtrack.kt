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
import com.eddyizm.tempus.util.PiterfyStoryPlan.Kind
import com.eddyizm.tempus.util.Preferences

/**
 * Piterfy: muzyka dopasowana do aktualnego slajdu. Plan układa [PiterfyStoryPlan.soundtrackPlan]: każdy slajd ma swój
 * utwór (hit okresu, utwór top wykonawcy, kolejne utwory z listy), a sąsiednie slajdy z tym samym utworem grają jeden
 * fragment bez restartu. Fragment to okno utworu (do 45 s od 35%). Zmiana slajdu ([onSlide], także wstecz) przełącza
 * fragment: poprzedni cichnie przez ok. 0,6 s, nowy wchodzi od początku okna przez 0,8 s; fragment, który skończy się
 * przed końcem slajdu, zostawia ciszę do następnej zmiany. Fragment następnego slajdu jest przygotowywany z
 * wyprzedzeniem. Osobny MediaPlayer (nie kolejka Media3), strumień z Navidrome w lekkim MP3 128 kb/s; początek okna
 * ustawia timeOffset w adresie (transkodowany MP3 nie obsługuje Range, więc seekTo nie działałoby). Czas fragmentu to
 * jeden zegar: płynie niezależnie od wyciszenia i staje razem z pauzą, więc po odciszeniu muzyka wraca w miejsce,
 * w którym „byłaby”. Każdy błąd (sieć, brak sesji, oszczędzanie danych, utrata fokusu audio) kończy się ciszą bez
 * komunikatów: muzyka jest dodatkiem.
 */
class PiterfyStorySoundtrack(context: Context, private val onNowPlaying: (PiterfyStoryPlan.Segment?) -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private class Voice(val index: Int, val player: MediaPlayer) {
        var prepared = false
        var started = false
        var dead = false
        var volume = 0f
        var fadeFrom = 0L      // dla cichnącego: uptimeMillis początku wyciszania i głośność w tym momencie
        var fadeVolume = 0f
    }

    private var plan = PiterfyStoryPlan.Soundtrack(emptyList(), emptyMap())
    private val segments: List<PiterfyStoryPlan.Segment> get() = plan.segments
    private var running = false
    private var silenced = false          // fokus audio przejął ktoś inny: do końca historyjki cisza
    private var segIndex = -1             // fragment, który teraz „należy” do slajdu
    private var nextIndex: Int? = null    // fragment następnego slajdu (do przygotowania z wyprzedzeniem)
    private var failedIndex = -1          // fragment, którego nie udało się nawet zacząć: bez ponownych prób do zmiany slajdu
    private var clockBase = 0L            // uptimeMillis, przy którym czas fragmentu wynosił 0 (przesuwany w pauzie)
    private var pausedAt = 0L             // != 0, gdy zegar stoi (przytrzymanie palcem, ekran w tle)
    private var current: Voice? = null
    private var next: Voice? = null
    private val fading = ArrayList<Voice>()
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

    /** Przygotowuje ścieżkę; gra dopiero po pierwszym [onSlide]. Nic nie gra bez utworów z id. */
    fun start(soundtrack: PiterfyStoryPlan.Soundtrack) {
        release()
        plan = soundtrack
        if (plan.isEmpty) return
        silenced = false; pausedAt = 0L; segIndex = -1; nextIndex = null; failedIndex = -1; clockBase = now(); running = true
        handler.post(tick)
    }

    /** Pokaz wszedł na slajd [kind] (następny w kolejności: [nextKind]): jeśli ma inny utwór, przełączamy fragment. */
    fun onSlide(kind: Kind, nextKind: Kind?) {
        if (!running) return
        nextIndex = plan.segmentOf(nextKind)
        val wanted = plan.segmentOf(kind) ?: return
        if (wanted == segIndex) return
        // poprzedni cichnie (w pauzie od razu cisza), przygotowany wcześniej fragment staje się bieżącym
        current?.let { old -> if (pausedAt != 0L || !old.started) releaseVoice(old) else fadeOut(old) }
        current = null
        segIndex = wanted; failedIndex = -1
        clockBase = if (pausedAt != 0L) pausedAt else now()
        val prepared = next?.takeIf { it.index == wanted }
        if (prepared != null) { next = null; current = prepared }
        onNowPlaying(null)
        if (pausedAt == 0L) step()
    }

    /** Pauza razem z historyjką (przytrzymanie palcem, ekran w tle): zegar staje, a po wznowieniu nic nie ucina. */
    fun pause() {
        if (!running || pausedAt != 0L) return
        pausedAt = now()
        current?.let { if (it.started) runCatching { it.player.pause() } }
        releaseFading()
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
        plan = PiterfyStoryPlan.Soundtrack(emptyList(), emptyMap())
        segIndex = -1; nextIndex = null
    }

    // ── jedna klatka zegara: głośność bieżącego fragmentu, wyciszanie poprzednich, przygotowanie następnego ──
    private fun step() {
        if (segIndex !in segments.indices) return
        if (muted || silenced || Preferences.isDataSavingMode()) {
            if (current != null || next != null || fading.isNotEmpty()) { stopVoices(); onNowPlaying(null) }
            return
        }
        val segment = segments[segIndex]
        val pos = elapsed()
        if (pos >= segment.preview.lengthMs) {
            // okno utworu się skończyło przed końcem slajdu: cisza do następnej zmiany
            current?.let { releaseVoice(it); current = null; onNowPlaying(null) }
        } else {
            if (current == null && failedIndex != segIndex) {
                current = begin(segIndex, pos.coerceAtLeast(0L))
                if (current == null) failedIndex = segIndex
            }
            current?.let { voice ->
                if (voice.prepared && !voice.started) startVoice(voice)
                if (voice.started && !voice.dead) {
                    voice.volume = PiterfyStoryPlan.volumeAt(pos, segment.preview)
                    runCatching { voice.player.setVolume(voice.volume, voice.volume) }
                }
            }
        }
        stepFading()
        val want = nextIndex?.takeIf { it != segIndex }
        if (next != null && next?.index != want) { releaseVoice(next); next = null }
        if (next == null && want != null) next = begin(want, 0L)
    }

    private fun fadeOut(voice: Voice) {
        voice.fadeFrom = now(); voice.fadeVolume = voice.volume
        fading += voice
    }

    private fun stepFading() {
        val iterator = fading.iterator()
        while (iterator.hasNext()) {
            val voice = iterator.next()
            val left = 1f - (now() - voice.fadeFrom).toFloat() / PiterfyStoryPlan.PREVIEW_FADE_OUT_MS
            if (left <= 0f || voice.dead) { iterator.remove(); releaseVoice(voice) }
            else runCatching { voice.player.setVolume(voice.fadeVolume * left, voice.fadeVolume * left) }
        }
    }

    private fun releaseFading() {
        fading.forEach { releaseVoice(it) }
        fading.clear()
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
        if (!voice.prepared || voice.started || voice.dead || pausedAt != 0L || !running || voice !== current) return
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
        releaseVoice(current); releaseVoice(next); releaseFading()
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
