package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.ui.activity.base.BaseActivity
import com.eddyizm.tempus.ui.components.PiterfyStoryCovers
import com.eddyizm.tempus.ui.components.PiterfyStorySoundtrack
import com.eddyizm.tempus.ui.components.PiterfyStoryProgressView
import com.eddyizm.tempus.ui.components.PiterfyStoryRenderer
import com.eddyizm.tempus.ui.components.PiterfyStoryView
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStoryPlan
import com.eddyizm.tempus.util.PiterfyStoryPlan.Kind
import java.io.File

/**
 * Piterfy: „Wrapped story” na pełnym ekranie, czyli podsumowanie okresu jako przesuwane slajdy 9:16 (wstęp, czas
 * słuchania, top wykonawca, top utwory, gatunki, typ słuchacza, porównanie, finał). Muzyka dopasowuje się do
 * slajdu: każdy ma utwór o swoim temacie (hit okresu, utwór top wykonawcy, kolejne z listy; [PiterfyStorySoundtrack],
 * obok głośnika widać, co akurat leci). Slajd zmienia się
 * sam po 7 s (utwory 12 s, finał zostaje), dotknięcie lewej/prawej części albo przeciągnięcie przechodzi dalej lub
 * wstecz, przytrzymanie wstrzymuje (też muzykę), przeciągnięcie w dół zamyka. Przy otwarciu wstrzymuje główny
 * odtwarzacz i wznawia go po zamknięciu tylko wtedy, gdy to on go wstrzymał.
 */
class PiterfyStoryDialog : DialogFragment(), PiterfyStoryView.Listener {
    private var period: PiterfyStatsModel.Period? = null
    private var slides: List<PiterfyStoryPlan.Slide> = emptyList()
    private var index = 0
    private var slideStart = 0L
    private var heldAt = 0L          // != 0, gdy czas slajdu stoi (przytrzymanie albo ekran w tle)
    private var holding = false
    private var inBackground = false
    private var pausedMain = false
    private var started = false

    private lateinit var storyView: PiterfyStoryView
    private lateinit var progress: PiterfyStoryProgressView
    private lateinit var covers: PiterfyStoryCovers
    private lateinit var renderer: PiterfyStoryRenderer
    private lateinit var soundtrack: PiterfyStorySoundtrack
    private lateinit var mute: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var finaleBar: LinearLayout
    private lateinit var topBar: LinearLayout

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
    private fun now() = SystemClock.uptimeMillis()

    /** Liczba slajdów; używa tego test na urządzeniu. */
    val slideCount: Int get() = slides.size
    val currentIndex: Int get() = index

    // ── cykl życia ──
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val key = arguments?.getString(ARG_PERIOD)
        val stats = PiterfyStatsModel.parse(PiterfyLibrary.cachedStats())
        period = stats?.periods?.let { list -> list.firstOrNull { it.key == key } ?: list.firstOrNull() }
        val p = period
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        if (p == null) { dialog.setContentView(View(ctx)); dialog.setOnShowListener { dismissAllowingStateLoss() }; return dialog }

        slides = PiterfyStoryPlan.slides(p)
        val root = FrameLayout(ctx).apply { setBackgroundColor(PiterfyAppearance.effectiveTheme()?.background?.toInt() ?: Color.BLACK) }

        covers = PiterfyStoryCovers(ctx) { if (this::storyView.isInitialized) storyView.invalidate() }
        covers.preload(listOf(p.topArtists.firstOrNull()?.cover) + p.topArtists.take(5).map { it.cover } + p.topTracks.take(5).map { it.cover })
        renderer = PiterfyStoryRenderer(ctx, covers)
        soundtrack = PiterfyStorySoundtrack(ctx) { segment -> showNowPlaying(segment) }

        storyView = PiterfyStoryView(ctx).apply { renderer = this@PiterfyStoryDialog.renderer; period = p; listener = this@PiterfyStoryDialog }
        root.addView(storyView, FrameLayout.LayoutParams(-1, -1))

        // góra: pasek postępu, pod nim przyciski
        topBar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), 0) }
        progress = PiterfyStoryProgressView(ctx, slides.size)
        topBar.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mute = circleText("🔊").apply { setOnClickListener { toggleMute() } }
        row.addView(mute, LinearLayout.LayoutParams(dp(44), dp(44)))
        // co teraz gra: mały napis obok przycisku głośnika (poza obrazem slajdu, więc nie trafia do eksportu PNG)
        nowPlaying = TextView(ctx).apply {
            textSize = 12.5f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(6), dp(12), dp(6)); background = GradientDrawable().apply { setColor(0x55000000); cornerRadius = dp(16).toFloat() }
            visibility = View.GONE
        }
        val nowHolder = FrameLayout(ctx).apply { addView(nowPlaying, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START)) }
        row.addView(nowHolder, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row.addView(circleIcon(R.drawable.ic_share, getString(R.string.story_share_slide)).apply { setOnClickListener { exportSlide(save = false) } },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })
        row.addView(circleIcon(R.drawable.ic_close, getString(R.string.appearance_close)).apply { setOnClickListener { dismissAllowingStateLoss() } },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // finał: zapis i udostępnienie slajdu
        finaleBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(dp(20), 0, dp(20), dp(24)) }
        fun pill(label: Int, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
            text = getString(label); textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply { setColor(if (filled) 0xFFFFFFFF.toInt() else 0x44FFFFFF); cornerRadius = dp(28).toFloat() }
            minHeight = dp(52); isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        finaleBar.addView(pill(R.string.story_share_slide, true) { exportSlide(save = false) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })
        finaleBar.addView(pill(R.string.story_save, false) { exportSlide(save = true) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(8) })
        root.addView(finaleBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, 0)
            finaleBar.setPadding(dp(20) + bars.left, 0, dp(20) + bars.right, dp(24) + bars.bottom)
            insets
        }
        dialog.setContentView(root)
        pauseMainPlayer()
        return dialog
    }

    private fun circleBackground() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x55000000) }

    private fun circleText(value: String) = TextView(requireContext()).apply {
        text = value; textSize = 18f; gravity = Gravity.CENTER; background = circleBackground(); setTextColor(Color.WHITE)
    }

    private fun circleIcon(res: Int, description: String) = ImageView(requireContext()).apply {
        setImageResource(res); setColorFilter(Color.WHITE); background = circleBackground(); contentDescription = description
        setPadding(dp(11), dp(11), dp(11), dp(11))
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= 28) attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (period == null) return
        if (!started) {
            started = true; slideStart = now()
            period?.let { soundtrack.start(PiterfyStoryPlan.soundtrackPlan(it)) }
            enterSlide(0, first = true); storyView.postOnAnimation(frame)
            updateMuteIcon()
        }
        else if (inBackground) {
            // wracamy z tła: czas slajdu płynie dalej od miejsca, w którym stanął
            inBackground = false
            if (!holding) { resumeClock(); soundtrack.resume() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (period == null) return
        inBackground = true
        soundtrack.pause()
        if (heldAt == 0L) heldAt = now()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        teardown()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        teardown()
        super.onDismiss(dialog)
    }

    private var tornDown = false
    private fun teardown() {
        if (tornDown || period == null) return
        tornDown = true
        if (this::storyView.isInitialized) storyView.removeCallbacks(frame)
        if (this::soundtrack.isInitialized) soundtrack.release()
        if (this::covers.isInitialized) covers.release()
        resumeMainPlayer()
    }

    // ── czas slajdu ──
    private fun currentKind(): Kind = slides[index].kind

    private fun resumeClock() {
        if (heldAt != 0L) { slideStart += now() - heldAt; heldAt = 0L }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (tornDown || !this@PiterfyStoryDialog.isAdded) return
            if (!inBackground) tickFrame()
            storyView.postOnAnimation(this)
        }
    }

    private fun tickFrame() {
        val slide = slides[index]
        val clock = if (heldAt != 0L) heldAt else now()
        val t = (clock - slideStart).coerceAtLeast(0L)
        storyView.t = t
        progress.fraction = if (slide.durationMs > 0) t.toFloat() / slide.durationMs else 1f
        if (slide.durationMs > 0 && t >= slide.durationMs && index < slides.lastIndex && heldAt == 0L) { goTo(index + 1); return }
        val animating = storyView.animationsOn() && t < ANIMATION_MS
        if (animating) storyView.invalidate()
    }

    /** Przechodzi do slajdu [target] (poza zakresem: ignoruje); używa tego też test na urządzeniu. */
    fun goTo(target: Int) {
        if (period == null || slides.isEmpty() || target !in slides.indices) return
        enterSlide(target, first = false)
    }

    private fun enterSlide(target: Int, first: Boolean) {
        index = target
        val kind = currentKind()
        slideStart = now()
        heldAt = if (holding || inBackground) slideStart else 0L
        storyView.kind = kind
        storyView.t = 0L
        progress.index = index
        progress.fraction = 0f
        finaleBar.visibility = if (kind == Kind.FINALE) View.VISIBLE else View.GONE
        storyView.contentDescription = getString(R.string.story_slide_desc, index + 1, slides.size)
        storyView.invalidate()
        // muzyka idzie za slajdem: każdy ma swój utwór, a następny jest już przygotowywany
        if (this::soundtrack.isInitialized) soundtrack.onSlide(kind, slides.getOrNull(index + 1)?.kind)
    }

    private fun toggleMute() {
        soundtrack.muted = !soundtrack.muted
        updateMuteIcon()
    }

    private fun updateMuteIcon() {
        mute.text = if (soundtrack.muted) "🔇" else "🔊"
        mute.contentDescription = getString(if (soundtrack.muted) R.string.story_unmute else R.string.story_mute)
        mute.visibility = if (soundtrack.hasTracks || !started) View.VISIBLE else View.INVISIBLE
        if (soundtrack.muted) nowPlaying.visibility = View.GONE
    }

    private fun showNowPlaying(segment: PiterfyStoryPlan.Segment?) {
        if (!this::nowPlaying.isInitialized) return
        if (segment == null || soundtrack.muted) { nowPlaying.visibility = View.GONE; return }
        nowPlaying.text = getString(R.string.story_now_playing, segment.title, segment.artist)
        nowPlaying.visibility = View.VISIBLE
    }

    // ── dotyk ──
    override fun onTapBack() { if (index == 0) goTo(0) else goTo(index - 1) }
    override fun onTapForward() { if (index < slides.lastIndex) goTo(index + 1) }
    override fun onSwipe(forward: Boolean) { if (forward) onTapForward() else onTapBack() }
    override fun onDismissGesture() { dismissAllowingStateLoss() }

    override fun onHold(holding: Boolean) {
        this.holding = holding
        if (holding) { if (heldAt == 0L) heldAt = now(); soundtrack.pause() }
        else if (!inBackground) { resumeClock(); soundtrack.resume() }
    }

    // ── główny odtwarzacz ──
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun pauseMainPlayer() {
        runCatching {
            val future = (activity as? BaseActivity)?.mediaBrowserListenableFuture ?: return
            if (!future.isDone || future.isCancelled) return
            val browser = future.get()
            if (browser.isPlaying) { browser.pause(); pausedMain = true }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun resumeMainPlayer() {
        if (!pausedMain) return
        pausedMain = false
        runCatching {
            val future = (activity as? BaseActivity)?.mediaBrowserListenableFuture ?: return
            if (future.isDone && !future.isCancelled) future.get().play()
        }
    }

    // ── eksport slajdu ──
    private fun exportSlide(save: Boolean) {
        val p = period ?: return
        val ctx = requireContext()
        val kind = currentKind()
        val name = PiterfyStoryPlan.fileName(p, kind)
        try {
            val bitmap = renderer.export(p, kind)
            if (save && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Piterfy")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert")
                ctx.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                Toast.makeText(ctx, R.string.story_saved, Toast.LENGTH_SHORT).show()
            } else {
                // przed Androidem 10 zapis do galerii wymagałby uprawnienia, więc tam „zapisz” otwiera zwykłe udostępnianie
                val folder = File(ctx.cacheDir, "wrapped").apply { mkdirs() }
                folder.listFiles()?.forEach { it.delete() }
                val file = File(folder, name)
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.update.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "Piterfy Wrapped · ${p.label}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.story_share_slide)))
            }
            bitmap.recycle()
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.story_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "piterfy_story"
        private const val ARG_PERIOD = "period"
        private const val ANIMATION_MS = 3600L

        @JvmStatic fun newInstance(periodKey: String?) = PiterfyStoryDialog().apply { arguments = Bundle().apply { putString(ARG_PERIOD, periodKey) } }
    }
}
