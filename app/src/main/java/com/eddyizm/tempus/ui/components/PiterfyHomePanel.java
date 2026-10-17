package com.eddyizm.tempus.ui.components;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.navigation.Navigation;

import com.bumptech.glide.Glide;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.Preferences;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Piterfy: strona glowna w stylu Spotify - kafelki szybkiego dostepu (playlisty "Odkrywaj", ulubione,
 * ostatnio sluchane albumy) i polka "Odkryte dla Ciebie" z kwadratowymi okladkami.
 * Zapytania sieciowe koncza sie razem z widokiem.
 */
@androidx.media3.common.util.UnstableApi
public final class PiterfyHomePanel extends LinearLayout implements DefaultLifecycleObserver {
    private static final int TILE_COLOR = 0xFF2A2A2A;
    private static final int GREEN = 0xFF1ED760;

    private final Fragment host;
    private final Consumer<List<Child>> play;
    private final List<Call<ApiResponse>> calls = new ArrayList<>();
    private final LinearLayout tiles;
    private final LinearLayout shelf;
    private final Typeface regular;
    private final Typeface bold;
    private boolean destroyed;
    private final String server = String.valueOf(Preferences.getServerId());

    private List<Playlist> discover = new ArrayList<>();
    private List<AlbumID3> recent = new ArrayList<>();

    public PiterfyHomePanel(Fragment host, Consumer<List<Child>> play) {
        super(host.requireContext());
        this.host = host;
        this.play = play;
        regular = ResourcesCompat.getFont(getContext(), R.font.figtree_family);
        bold = android.os.Build.VERSION.SDK_INT >= 28 ? Typeface.create(regular, 700, false) : Typeface.create(regular, Typeface.BOLD);
        setOrientation(VERTICAL);
        setPadding(dp(12), dp(4), dp(12), dp(16));
        host.getViewLifecycleOwner().getLifecycle().addObserver(this);

        MaterialButton taste = new MaterialButton(getContext());
        taste.setText(R.string.piterfy_taste);
        taste.setAllCaps(false);
        taste.setTypeface(bold);
        taste.setTextSize(13);
        taste.setTextColor(Color.WHITE);
        taste.setCornerRadius(dp(18));
        taste.setMinHeight(0);
        taste.setMinimumHeight(0);
        taste.setInsetTop(0);
        taste.setInsetBottom(0);
        taste.setPadding(dp(16), dp(8), dp(16), dp(8));
        taste.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TILE_COLOR));
        taste.setOnClickListener(v -> {
            String base = Preferences.getServer();
            if (base == null) return;
            Uri uri = Uri.parse(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return;
            Uri target = uri.buildUpon().encodedPath("/gust/").clearQuery().fragment(null).build();
            try {
                host.startActivity(new Intent(Intent.ACTION_VIEW, target));
            } catch (ActivityNotFoundException ignored) {
            }
        });
        LayoutParams tasteParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tasteParams.leftMargin = dp(4);
        tasteParams.bottomMargin = dp(12);
        addView(taste, tasteParams);

        tiles = new LinearLayout(getContext());
        tiles.setOrientation(VERTICAL);
        addView(tiles);

        TextView shelfHeading = text(getResources().getString(R.string.piterfy_discover), 22, Color.WHITE, true);
        shelfHeading.setPadding(dp(4), dp(28), 0, dp(12));
        addView(shelfHeading);
        shelf = new LinearLayout(getContext());
        shelf.setOrientation(HORIZONTAL);
        HorizontalScrollView shelfScroll = new HorizontalScrollView(getContext());
        shelfScroll.setHorizontalScrollBarEnabled(false);
        shelfScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        shelfScroll.addView(shelf);
        addView(shelfScroll);

        load();
        loadRecentAlbums();
    }

    // ── pomocnicze ──

    private boolean alive() {
        return !destroyed && server.equals(String.valueOf(Preferences.getServerId()));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(CharSequence value, int sizeSp, int color, boolean strong) {
        TextView t = new TextView(getContext());
        t.setText(value);
        t.setTypeface(strong ? bold : regular);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void clipRounded(View v) {
        v.setClipToOutline(true);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    private void art(ImageView target, String coverId, int px) {
        if (coverId == null) {  // bez okladki - zwykly zastepnik, bez zapytania "id=null" do serwera
            target.setImageResource(R.drawable.ic_placeholder_album);
            return;
        }
        Glide.with(host).load(CustomGlideRequest.createUrl(coverId, px))
                .placeholder(R.drawable.ic_placeholder_playlist).centerCrop().into(target);
    }

    private View playButton(int sizeDp, View.OnClickListener click) {
        FrameLayout button = new FrameLayout(getContext());
        button.setBackground(rounded(GREEN, sizeDp / 2));
        button.setElevation(dp(4));
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_play);
        icon.setColorFilter(Color.BLACK);
        int inner = dp(sizeDp * 6 / 10);
        button.addView(icon, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        button.setOnClickListener(click);
        button.setContentDescription(getResources().getString(R.string.piterfy_play));
        return button;
    }

    // ── dane ──

    private void load() {
        shelf.removeAllViews();
        ProgressBar progress = new ProgressBar(getContext());
        shelf.addView(progress, new LayoutParams(dp(32), dp(32)));
        Call<ApiResponse> call = App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylists();
        calls.add(call);
        call.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> c, @NonNull Response<ApiResponse> r) {
                if (!alive() || c.isCanceled()) return;
                var sr = r.body() == null ? null : r.body().getSubsonicResponse();
                if (!r.isSuccessful() || sr == null || sr.getPlaylists() == null) {
                    error();
                    return;
                }
                List<Playlist> list = new ArrayList<>();
                if (sr.getPlaylists().getPlaylists() != null) for (Playlist p : sr.getPlaylists().getPlaylists())
                    if (p.getName() != null && (p.getName().equalsIgnoreCase("Odkrywaj") || p.getName().startsWith("Odkrywaj ")))
                        list.add(p);
                list.sort(Comparator.comparing((Playlist p) -> p.getCreated() == null ? new Date(0) : p.getCreated()).reversed());
                discover = list;
                shelf.removeAllViews();
                if (list.isEmpty()) {
                    shelf.addView(text(getResources().getString(R.string.piterfy_discover_empty), 14, 0xFFB3B3B3, false));
                }
                for (Playlist p : list.subList(0, Math.min(7, list.size()))) shelf.addView(card(p));
                buildTiles();
            }

            public void onFailure(@NonNull Call<ApiResponse> c, @NonNull Throwable t) {
                if (alive() && !c.isCanceled()) error();
            }
        });
    }

    private void loadRecentAlbums() {
        new AlbumRepository().getAlbums("recent", 8, null, null).observe(host.getViewLifecycleOwner(), albums -> {
            if (albums == null || !alive()) return;
            recent = albums;
            buildTiles();
        });
    }

    private void error() {
        shelf.removeAllViews();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.addView(text(getResources().getString(R.string.piterfy_network_error), 14, 0xFFB3B3B3, false));
        MaterialButton retry = new MaterialButton(getContext());
        retry.setText(R.string.piterfy_retry);
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> load());
        box.addView(retry);
        shelf.addView(box);
    }

    // ── kafelki szybkiego dostepu ──

    private void buildTiles() {
        tiles.removeAllViews();
        List<View> items = new ArrayList<>();
        if (!discover.isEmpty()) {
            Playlist newest = discover.get(0);
            items.add(tile(newest.getName(), newest.getCoverArtId(), null, v -> openPlaylist(newest)));
        }
        Playlist mix = null;
        for (Playlist p : discover) if ("Odkrywaj".equalsIgnoreCase(p.getName())) mix = p;
        if (mix != null && (discover.isEmpty() || mix != discover.get(0))) {
            Playlist all = mix;
            items.add(tile(all.getName(), all.getCoverArtId(), null, v -> openPlaylist(all)));
        }
        items.add(tile(getResources().getString(R.string.piterfy_favorites), null, 0xFF5B2EEA, v -> {
            Bundle b = new Bundle();
            b.putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED);
            Navigation.findNavController(host.requireView()).navigate(R.id.action_homeFragment_to_songListPageFragment, b);
        }));
        for (AlbumID3 a : recent) {
            if (items.size() >= 8) break;
            items.add(tile(a.getName(), a.getCoverArtId(), null, v -> {
                Bundle b = new Bundle();
                b.putParcelable(Constants.ALBUM_OBJECT, a.strippedForNav());
                Navigation.findNavController(host.requireView()).navigate(R.id.albumPageFragment, b);
            }));
        }
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(getContext());
            LayoutParams rowParams = new LayoutParams(-1, dp(56));
            rowParams.bottomMargin = dp(8);
            for (int j = 0; j < 2; j++) {
                View cell = i + j < items.size() ? items.get(i + j) : new View(getContext());
                LayoutParams p = new LayoutParams(0, -1, 1);
                if (j == 1) p.leftMargin = dp(8);
                row.addView(cell, p);
            }
            tiles.addView(row, rowParams);
        }
    }

    /** Kafelek jak w Spotify: kwadratowa okladka po lewej, pogrubiony tytul, zaokraglone rogi. */
    private View tile(String title, String coverId, Integer plainColor, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setBackground(rounded(TILE_COLOR, 6));
        clipRounded(tile);
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (plainColor == null) {
            art(image, coverId, 160);
        } else {
            image.setBackgroundColor(plainColor);
            image.setImageResource(R.drawable.ic_favorite);
            image.setScaleType(ImageView.ScaleType.CENTER);
            image.setColorFilter(Color.WHITE);
        }
        tile.addView(image, new LayoutParams(dp(56), dp(56)));
        TextView label = text(title == null ? "" : title, 13, Color.WHITE, true);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(dp(12), 0, dp(8), 0);
        tile.addView(label, new LayoutParams(0, -2, 1));
        tile.setOnClickListener(click);
        tile.setContentDescription(title);
        return tile;
    }

    // ── polka z playlistami ──

    private void openPlaylist(Playlist p) {
        Bundle b = new Bundle();
        b.putParcelable(Constants.PLAYLIST_OBJECT, p);
        Navigation.findNavController(host.requireView()).navigate(R.id.playlistPageFragment, b);
    }

    private View card(Playlist p) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        LayoutParams params = new LayoutParams(dp(148), -2);
        params.rightMargin = dp(12);
        card.setLayoutParams(params);

        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackground(rounded(TILE_COLOR, 8));
        clipRounded(frame);
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art(image, p.getCoverArtId(), 320);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        card.addView(frame, new LayoutParams(dp(148), dp(148)));

        // zielony przycisk play w prawym dolnym rogu okladki
        View play = playButton(40, v -> tracks(p, v, false));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.BOTTOM);
        pp.rightMargin = dp(8);
        pp.bottomMargin = dp(8);
        frame.addView(play, pp);

        TextView name = text(p.getName(), 14, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(8), 0, 0);
        card.addView(name);
        TextView count = text(getResources().getString(R.string.piterfy_discover_count, p.getSongCount()), 12, 0xFFB3B3B3, false);
        count.setMaxLines(2);
        card.addView(count);

        card.setOnClickListener(v -> openPlaylist(p));
        card.setOnLongClickListener(v -> {
            tracks(p, card, true);  // dlugie przytrzymanie pobiera playliste offline
            return true;
        });
        card.setContentDescription(p.getName());
        return card;
    }

    private void tracks(Playlist p, View button, boolean download) {
        button.setEnabled(false);
        Call<ApiResponse> call = App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylist(p.getId());
        calls.add(call);
        call.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> c, @NonNull Response<ApiResponse> r) {
                if (!alive() || c.isCanceled()) return;
                button.setEnabled(true);
                var sr = r.body() == null ? null : r.body().getSubsonicResponse();
                if (!r.isSuccessful() || sr == null || sr.getPlaylist() == null || sr.getPlaylist().getEntries() == null) {
                    fail();
                    return;
                }
                List<Child> songs = sr.getPlaylist().getEntries();
                if (download) DownloadUtil.downloadMissing(getContext(), songs, p.getId(), p.getName(), queued -> {
                    if (alive()) Snackbar.make(PiterfyHomePanel.this, queued < 0
                            ? getResources().getString(R.string.piterfy_network_error)
                            : getResources().getString(R.string.piterfy_queued, queued), Snackbar.LENGTH_LONG).show();
                });
                else if (!songs.isEmpty()) play.accept(songs);
            }

            public void onFailure(@NonNull Call<ApiResponse> c, @NonNull Throwable t) {
                if (alive() && !c.isCanceled()) {
                    button.setEnabled(true);
                    fail();
                }
            }

            private void fail() {
                Snackbar.make(PiterfyHomePanel.this, R.string.piterfy_network_error, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        destroyed = true;
        for (var c : calls) c.cancel();
        calls.clear();
    }
}
