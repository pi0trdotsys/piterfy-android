package com.eddyizm.tempus.ui.fragment;

import android.content.ComponentName;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.*;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.*;
import com.eddyizm.tempus.databinding.InnerFragmentPlayerLyricsBinding;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.*;
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
    @Override public void onResume() { super.onResume(); resumed = true; main.removeCallbacks(tick); main.post(tick); requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    @Override public void onPause() { resumed = false; main.removeCallbacks(tick); if (!Preferences.isDisplayAlwaysOn()) requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause(); }
    @Override public void onStop() { browser = null; if (future != null) MediaBrowser.releaseFuture(future); future = null; super.onStop(); }
    @Override public void onDestroyView() { main.removeCallbacks(tick); bind = null; lines = Collections.emptyList(); plain = null; description = null; super.onDestroyView(); }
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
        if (synced) { paint(true); return; }
        bind.nowPlayingSongLyricsTextView.setMovementMethod(null);
        bind.nowPlayingSongLyricsTextView.setTextColor(Color.WHITE);
        StringBuilder text = new StringBuilder();
        for (Line line : lines) text.append(line.getValue().trim()).append("\n\n");
        if (text.length() == 0) text.append(plain != null ? MusicUtil.getReadableLyrics(plain) : description != null ? MusicUtil.getReadableLyrics(description) : "♪");
        bind.nowPlayingSongLyricsTextView.setText(text);
    }
    private void paint(boolean force) {
        if (bind == null || !synced) return;
        int active = LyricsTiming.active(lines, position(), sourceOffset + correction);
        if (!force && active == highlighted) return;
        highlighted = active;
        SpannableStringBuilder text = new SpannableStringBuilder();
        int activeStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            final int index = i;
            final long seek = Math.max(0L, (long) lines.get(i).getStart() - sourceOffset - correction);
            int from = text.length();
            String value = lines.get(i).getValue().trim();
            text.append(value.isEmpty() ? "♪" : value).append("\n\n");
            if (i == active) activeStart = from;
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(@NonNull View view) {
                    if (LanRemoteSession.isActive()) LanRemoteSession.command("seek", seek);
                    else if (browser != null) browser.seekTo(seek);
                    model.setSyncLyricsState(true); highlighted = -2; controls();
                }
                @Override public void updateDrawState(@NonNull TextPaint paint) {
                    paint.setUnderlineText(false); paint.setColor(index == active ? Color.WHITE : index < active ? 0xFF9AC8AA : 0xFF6C947C);
                }
            }, from, text.length() - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        bind.nowPlayingSongLyricsTextView.setMovementMethod(LinkMovementMethod.getInstance());
        bind.nowPlayingSongLyricsTextView.setText(text);
        final int start = activeStart;
        if (start >= 0 && model.getSyncLyricsState()) bind.nowPlayingSongLyricsTextView.post(() -> {
            if (bind == null || !model.getSyncLyricsState()) return;
            Layout layout = bind.nowPlayingSongLyricsTextView.getLayout();
            if (layout != null && start < bind.nowPlayingSongLyricsTextView.length()) {
                int y = layout.getLineTop(layout.getLineForOffset(start)) + bind.nowPlayingSongLyricsTextView.getPaddingTop() - bind.nowPlayingSongLyricsSrollView.getHeight() / 3;
                bind.nowPlayingSongLyricsSrollView.smoothScrollTo(0, Math.max(0, y));
            }
        });
    }
}
