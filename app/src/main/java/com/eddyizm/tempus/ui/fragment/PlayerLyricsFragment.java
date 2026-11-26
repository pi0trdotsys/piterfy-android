package com.eddyizm.tempus.ui.fragment;

import android.content.ComponentName;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.core.widget.NestedScrollView;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.*;
import com.eddyizm.tempus.appearance.PiterfyAppearance;
import com.eddyizm.tempus.databinding.InnerFragmentPlayerLyricsBinding;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.*;
import com.eddyizm.tempus.ui.components.PiterfyGlass;
import com.eddyizm.tempus.ui.components.PiterfyLyricLineView;
import com.eddyizm.tempus.util.*;
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel;
import com.eddyizm.tempus.lan.LanRemoteSession;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

@OptIn(markerClass = UnstableApi.class)
public class PlayerLyricsFragment extends Fragment {
    private InnerFragmentPlayerLyricsBinding bind;
    private PlayerBottomSheetViewModel model;
    private ListenableFuture<MediaBrowser> future;
    private MediaBrowser browser;
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Line> lines = Collections.emptyList();
    private boolean synced, resumed;
    private int sourceOffset, correction, highlighted = -2;
    private String key = "", plain, description;
    // Piterfy: styl Apple Music — po jednej PiterfyLyricLineView na linijkę, karaoke i sprężyste przewijanie
    private final List<PiterfyLyricLineView> lineViews = new ArrayList<>();
    private List<Line> builtLines;
    private SpringAnimation scrollSpring;
    private final Runnable frame = new Runnable() {
        public void run() {
            if (!resumed || bind == null || !synced) return;
            int active = highlighted;
            if (active >= 0 && active < lineViews.size() && active < lines.size()) {
                Integer start = lines.get(active).getStart();
                Integer next = active + 1 < lines.size() ? lines.get(active + 1).getStart() : null;
                float progress = start == null ? 1f : PiterfyMotion.enabled()
                    ? PiterfyLyricsKaraoke.progress(start, next, position(), sourceOffset + correction) : 1f;
                lineViews.get(active).setProgress(progress);
            }
            main.postDelayed(this, 33);
        }
    };
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!resumed || bind == null) return;
            if (synced) paint(false);
            main.postDelayed(this, 200);
        }
    };
    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        bind = InnerFragmentPlayerLyricsBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);
        return bind.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, Bundle state) {
        model.getLiveLyricsList().observe(getViewLifecycleOwner(), list -> {
            StructuredLyrics selected = LyricsTiming.preferred(list);
            lines = new ArrayList<>(); synced = selected != null && selected.getSynced();
            sourceOffset = selected == null ? 0 : selected.getOffset();
            if (selected != null) for (Line line : selected.getLine())
                if (line != null && line.getValue() != null && (!synced || line.getStart() != null)) lines.add(line);
            if (synced) lines.sort(Comparator.comparingInt(Line::getStart));
            highlighted = -2; render();
        });
        model.getLiveLyrics().observe(getViewLifecycleOwner(), text -> { plain = text; render(); });
        model.getLiveDescription().observe(getViewLifecycleOwner(), text -> { description = text; render(); });
        model.getLyricsStatus().observe(getViewLifecycleOwner(), text -> bind.lyricsStatus.setText(text));
        model.getLiveMedia().observe(getViewLifecycleOwner(), song -> {
            String next = Preferences.getServer() + "|" + Preferences.getUser() + "|" + (song == null ? "" : song.getId());
            if (!next.equals(key)) {
                key = next; correction = requireContext().getSharedPreferences("lyrics-offset", 0).getInt(key, 0);
                model.setSyncLyricsState(true); highlighted = -2; controls();
                if (scrollSpring != null) scrollSpring.cancel();
                bind.nowPlayingSongLyricsSrollView.scrollTo(0, 0);
            }
        });
        model.getLyricsCachedState().observe(getViewLifecycleOwner(), saved -> {
            bind.downloadLyricsButton.setText(Boolean.TRUE.equals(saved) ? "Zapisano" : "Offline");
            bind.downloadLyricsButton.setEnabled(!Boolean.TRUE.equals(saved));
        });
        bind.downloadLyricsButton.setOnClickListener(v -> Toast.makeText(requireContext(), model.downloadCurrentLyrics() ? "Tekst zapisany offline" : "Brak tekstu do zapisania", Toast.LENGTH_SHORT).show());
        bind.syncLyricsTapButton.setOnClickListener(v -> { model.setSyncLyricsState(true); highlighted = -2; controls(); paint(true); });
        bind.lyricsTimingButton.setOnClickListener(v -> {
            final String adjustmentKey = key;
            new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Dopasuj tekst · " + String.format(Locale.getDefault(), "%+.1f s", correction / 1000.0))
            .setItems(new String[]{"Pokaż 0,5 s wcześniej", "Pokaż 0,5 s później", "Wyzeruj korektę"}, (dialog, which) -> {
                if (bind == null || !key.equals(adjustmentKey)) return;
                correction = which == 2 ? 0 : Math.max(-10000, Math.min(10000, correction + (which == 0 ? 500 : -500)));
                requireContext().getSharedPreferences("lyrics-offset", 0).edit().putInt(key, correction).apply();
                highlighted = -2; controls(); paint(true);
            }).show();
        });
        bind.lyricsStatus.setOnClickListener(v -> model.refreshMediaInfo(getViewLifecycleOwner(), model.getLiveMedia().getValue()));
        bind.nowPlayingSongLyricsSrollView.setOnTouchListener((v, event) -> {
            if (scrollSpring != null && (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE)) scrollSpring.cancel();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && synced) { model.setSyncLyricsState(false); controls(); }
            return false;
        });
    }
    @Override public void onStart() {
        super.onStart();
        future = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
        final var pending = future;
        future.addListener(() -> { if (future == pending && bind != null) try { browser = pending.get(); } catch (Exception ignored) {} }, main::post);
    }
    @Override public void onResume() { super.onResume(); resumed = true; main.removeCallbacks(tick); main.post(tick); main.removeCallbacks(frame); main.post(frame); requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    @Override public void onPause() { resumed = false; main.removeCallbacks(tick); main.removeCallbacks(frame); if (!Preferences.isDisplayAlwaysOn()) requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause(); }
    @Override public void onStop() { browser = null; if (future != null) MediaBrowser.releaseFuture(future); future = null; super.onStop(); }
    @Override public void onDestroyView() { main.removeCallbacks(tick); main.removeCallbacks(frame); if (scrollSpring != null) scrollSpring.cancel(); scrollSpring = null; lineViews.clear(); builtLines = null; bind = null; lines = Collections.emptyList(); plain = null; description = null; super.onDestroyView(); }
    private long position() { return LanRemoteSession.isActive() ? LanRemoteSession.current().positionAt(SystemClock.elapsedRealtime()) : browser == null ? 0 : browser.getCurrentPosition(); }
    private void controls() {
        if (bind == null) return;
        bind.syncLyricsTapButton.setVisibility(synced ? View.VISIBLE : View.GONE);
        bind.lyricsTimingButton.setVisibility(synced ? View.VISIBLE : View.GONE);
        bind.syncLyricsTapButton.setText(model.getSyncLyricsState() ? "Śledzenie" : "Wróć do tekstu");
        bind.syncLyricsTapButton.setContentDescription(model.getSyncLyricsState() ? "Automatyczne śledzenie tekstu włączone" : "Wróć do bieżącej linijki tekstu");
        bind.lyricsTimingButton.setText(String.format(Locale.getDefault(), "%+.1f s", correction / 1000.0));
    }
    private void render() {
        if (bind == null) return;
        controls();
        boolean content = !lines.isEmpty() || (plain != null && !plain.isBlank());
        bind.downloadLyricsButton.setVisibility(content ? View.VISIBLE : View.GONE);
        if (synced) {
            bind.nowPlayingSongLyricsTextView.setVisibility(View.GONE);
            bind.lyricsLinesContainer.setVisibility(View.VISIBLE);
            paint(true); return;
        }
        bind.lyricsLinesContainer.setVisibility(View.GONE);
        bind.nowPlayingSongLyricsTextView.setVisibility(View.VISIBLE);
        bind.nowPlayingSongLyricsTextView.setMovementMethod(null);
        bind.nowPlayingSongLyricsTextView.setTextColor(Color.WHITE);
        StringBuilder text = new StringBuilder();
        for (Line line : lines) text.append(line.getValue().trim()).append("\n\n");
        if (text.length() == 0) text.append(plain != null ? MusicUtil.getReadableLyrics(plain) : description != null ? MusicUtil.getReadableLyrics(description) : "♪");
        bind.nowPlayingSongLyricsTextView.setText(text);
    }
    private void paint(boolean force) {
        if (bind == null || !synced) return;
        ensureLineViews();
        int active = LyricsTiming.active(lines, position(), sourceOffset + correction);
        if (!force && active == highlighted) return;
        highlighted = active;
        applyStyles(active, force);
        if (active >= 0 && model.getSyncLyricsState()) scrollToLine(active);
    }
    /** Buduje widoki linijek (po jednym na wiersz tekstu); dotknięcie linii przewija utwór jak dotąd. */
    private void ensureLineViews() {
        if (builtLines == lines && lineViews.size() == lines.size()) return;
        bind.lyricsLinesContainer.removeAllViews(); lineViews.clear(); builtLines = lines;
        final float margin = 10 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < lines.size(); i++) {
            final long seek = Math.max(0L, (long) lines.get(i).getStart() - sourceOffset - correction);
            String value = lines.get(i).getValue().trim();
            PiterfyLyricLineView view = new PiterfyLyricLineView(requireContext());
            view.setText(value.isEmpty() ? "♪" : value);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = (int) margin;
            view.setOnClickListener(v -> {
                if (LanRemoteSession.isActive()) LanRemoteSession.command("seek", seek);
                else if (browser != null) browser.seekTo(seek);
                model.setSyncLyricsState(true); highlighted = -2; controls();
            });
            bind.lyricsLinesContainer.addView(view, params);
            lineViews.add(view);
        }
    }
    /** Aktywna linia duża z wypełnieniem, dalsze mniejsze, przygaszone i (API 31+) rozmyte tym mocniej, im dalej. */
    private void applyStyles(int active, boolean force) {
        final boolean animate = PiterfyMotion.enabled();
        final var glass = PiterfyAppearance.glassEnabled() ? PiterfyGlass.plan(requireContext()) : null;
        final boolean pill = glass != null && glass.getEnabled();
        for (int i = 0; i < lineViews.size(); i++) {
            PiterfyLyricLineView view = lineViews.get(i);
            final int distance = active < 0 ? i + 1 : i - active;
            final int cls = Math.min(Math.abs(distance), 5);
            final boolean isActive = i == active;
            if (!force && view.getStyledFor() == cls && view.isActiveLine() == isActive) continue;
            view.setStyledFor(cls);
            view.setActiveLine(isActive);
            view.setProgress(i < active ? 1f : 0f);
            view.setBlurDp(PiterfyLyricsKaraoke.blurDp(distance));
            view.setGlassPill(pill && isActive,
                pill ? Math.min(110, (int) (46 + glass.getSheenAlpha() * 120)) : 0, pill ? (int) (glass.getEdgeAlpha() * 255 * 0.7f) : 0);
            PiterfyLyricsKaraoke.LineStyle style = PiterfyLyricsKaraoke.style(distance);
            if (animate && !force) {
                view.animate().alpha(style.getAlpha()).scaleX(style.getScale()).scaleY(style.getScale())
                    .setDuration(380).setInterpolator(new DecelerateInterpolator()).start();
            } else {
                view.animate().cancel();
                view.setAlpha(style.getAlpha()); view.setScaleX(style.getScale()); view.setScaleY(style.getScale());
            }
        }
    }
    /** Przewija aktywną linię w okolice 40% wysokości: sprężyście (androidx.dynamicanimation), a bez animacji od razu. */
    private void scrollToLine(int index) {
        if (index < 0 || index >= lineViews.size()) return;
        final NestedScrollView scroll = bind.nowPlayingSongLyricsSrollView;
        scroll.post(() -> {
            if (bind == null || index >= lineViews.size() || !model.getSyncLyricsState()) return;
            View line = lineViews.get(index);
            int top = 0;
            for (View v = line; v != null && v != scroll; v = v.getParent() instanceof View ? (View) v.getParent() : null) top += v.getTop();
            View child = scroll.getChildAt(0);
            int max = child == null ? 0 : Math.max(0, child.getHeight() - scroll.getHeight());
            int target = Math.max(0, Math.min(max, top + line.getHeight() / 2 - (int) (scroll.getHeight() * 0.4f)));
            if (!PiterfyMotion.enabled()) { scroll.scrollTo(0, target); return; }
            if (scrollSpring == null) {
                scrollSpring = new SpringAnimation(scroll, DynamicAnimation.SCROLL_Y);
                scrollSpring.setSpring(new SpringForce().setStiffness(SpringForce.STIFFNESS_LOW).setDampingRatio(0.85f));
            }
            scrollSpring.setMinValue(0f); scrollSpring.setMaxValue(max);
            scrollSpring.animateToFinalPosition(target);
        });
    }
}
