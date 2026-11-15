package com.eddyizm.tempus.ui.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.adapter.TvRowsAdapter;
import com.eddyizm.tempus.ui.adapter.TvRowsAdapter.Card;
import com.eddyizm.tempus.ui.adapter.TvRowsAdapter.Row;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.util.PiterfyAmbient;
import com.eddyizm.tempus.util.TvTheme;
import com.eddyizm.tempus.viewmodel.HomeViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Piterfy TV: dedykowany ekran glowny. Zamiast mobilnej strony z karuzelami - powitanie, szybkie akcje
 * i rzedy duzych kart, po ktorych porusza sie pilotem. Tlo plynnie przyjmuje kolor okladki aktualnie
 * fokusowanej karty.
 */
@OptIn(markerClass = UnstableApi.class)
public class TvHomeFragment extends Fragment {
    public static final String ARG_GRAB_FOCUS = "grabFocus";
    private static final int MAX_CARDS = 20;

    private MainActivity activity;
    private HomeViewModel homeViewModel;
    private RecyclerView rowsView;
    private View empty;
    private TvRowsAdapter adapter;

    private ListenableFuture<MediaBrowser> mediaBrowserFuture;
    private View nowPlayingAction;

    private final Row recent = new Row("recent", "");
    private final Row playlists = new Row("playlists", "");
    private final Row mostPlayed = new Row("most", "");
    private final Row added = new Row("added", "");
    private final Row releases = new Row("releases", "");
    private final Row starredAlbums = new Row("starredAlbums", "");
    private final Row starredArtists = new Row("starredArtists", "");
    private final Row discover = new Row("discover", "");
    private final Row starredTracks = new Row("starredTracks", "");
    private List<Row> allRows;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingBackdrop;
    private int lastRowIndex = -1;
    private int lastCardIndex;
    private boolean focusRestored;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) requireActivity();
        View root = inflater.inflate(R.layout.piterfy_tv_home, container, false);
        rowsView = root.findViewById(R.id.tv_home_rows);
        empty = root.findViewById(R.id.tv_home_empty);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Po powrocie z albumu widok powstaje od nowa: fokus ma wrocic na ostatnia karte, nie na poczatek.
        Bundle args = getArguments();
        focusRestored = !((args == null || args.getBoolean(ARG_GRAB_FOCUS, true)) || lastRowIndex > 0);
        homeViewModel = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);

        recent.title = getString(R.string.home_title_last_played);
        playlists.title = getString(R.string.home_title_pinned_playlists);
        mostPlayed.title = getString(R.string.home_title_most_played);
        added.title = getString(R.string.home_title_recently_added);
        releases.title = getString(R.string.home_title_new_releases);
        starredAlbums.title = getString(R.string.home_title_starred_albums);
        starredArtists.title = getString(R.string.home_title_starred_artists);
        discover.title = getString(R.string.home_title_discovery);
        starredTracks.title = getString(R.string.home_title_starred_tracks);
        allRows = List.of(recent, playlists, mostPlayed, added, releases, starredAlbums, starredArtists, discover, starredTracks);

        adapter = new TvRowsAdapter(this::bindHeader, this::onCardFocused);
        rowsView.setLayoutManager(new LinearLayoutManager(requireContext()));
        rowsView.setAdapter(adapter);
        rowsView.setItemViewCacheSize(3);

        observeData();
        applyBackdrop(PiterfyAmbient.COLOR.getValue() == null ? PiterfyAmbient.DEFAULT : PiterfyAmbient.COLOR.getValue());
        refreshRows();
    }

    @Override
    public void onStart() {
        super.onStart();
        mediaBrowserFuture = new MediaBrowser.Builder(requireContext(),
                new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
        mediaBrowserFuture.addListener(() -> {
            try {
                MediaBrowser browser = mediaBrowserFuture.get();
                updateNowPlayingAction(browser);
                browser.addListener(new Player.Listener() {
                    @Override
                    public void onTimelineChanged(@NonNull androidx.media3.common.Timeline timeline, int reason) {
                        updateNowPlayingAction(browser);
                    }
                });
            } catch (Exception ignored) {
            }
        }, MoreExecutors.directExecutor());
    }

    @Override
    public void onStop() {
        MediaBrowser.releaseFuture(mediaBrowserFuture);
        mediaBrowserFuture = null;
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (activity != null) activity.releaseTvBackdrop(this); // inne ekrany wracaja do zwyklej czerni
        nowPlayingAction = null;
        rowsView = null;
        super.onDestroyView();
    }

    // ---- Dane

    private void observeData() {
        observeAlbums(Constants.HOME_SECTOR_LAST_PLAYED, recent, homeViewModel.getRecentlyPlayedAlbumList(getViewLifecycleOwner()));
        observeAlbums(Constants.HOME_SECTOR_MOST_PLAYED, mostPlayed, homeViewModel.getMostPlayedAlbums(getViewLifecycleOwner()));
        observeAlbums(Constants.HOME_SECTOR_RECENTLY_ADDED, added, homeViewModel.getMostRecentlyAddedAlbums(getViewLifecycleOwner()));
        observeAlbums(Constants.HOME_SECTOR_NEW_RELEASES, releases, homeViewModel.getRecentlyReleasedAlbums(getViewLifecycleOwner()));
        observeAlbums(Constants.HOME_SECTOR_STARRED_ALBUMS, starredAlbums, homeViewModel.getStarredAlbums(getViewLifecycleOwner()));

        if (!homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_PINNED_PLAYLISTS)) {
            homeViewModel.getPinnedPlaylists(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), list -> {
                List<Card> cards = new ArrayList<>();
                if (list != null) for (Playlist playlist : list.subList(0, Math.min(MAX_CARDS, list.size()))) {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlist);
                    cards.add(new Card(playlist.getName(), playlist.getSongCount() + " ♪", playlist.getCoverArtId(),
                            CustomGlideRequest.ResourceType.Playlist, false,
                            () -> navigate(R.id.playlistPageFragment, bundle),
                            () -> navigate(R.id.playlistRowBottomSheetDialog, bundle)));
                }
                playlists.cards = cards;
                refreshRows();
            });
        }

        if (!homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_STARRED_ARTISTS)) {
            homeViewModel.getStarredArtists(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), list -> {
                List<Card> cards = new ArrayList<>();
                if (list != null) for (ArtistID3 artist : list.subList(0, Math.min(MAX_CARDS, list.size()))) {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ARTIST_OBJECT, artist.strippedForNav());
                    cards.add(new Card(artist.getName(), "", artist.getCoverArtId(),
                            CustomGlideRequest.ResourceType.Artist, true,
                            () -> navigate(R.id.artistPageFragment, bundle),
                            () -> navigate(R.id.artistBottomSheetDialog, bundle)));
                }
                starredArtists.cards = cards;
                refreshRows();
            });
        }

        if (!homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_DISCOVERY)) {
            observeTracks(discover, homeViewModel.getDiscoverSongSample(getViewLifecycleOwner()));
        }
        if (!homeViewModel.checkHomeSectorVisibility(Constants.HOME_SECTOR_STARRED_TRACKS)) {
            observeTracks(starredTracks, homeViewModel.getStarredTracksSample(getViewLifecycleOwner()));
        }
    }

    private void observeAlbums(String sector, Row row, androidx.lifecycle.LiveData<List<AlbumID3>> data) {
        if (homeViewModel.checkHomeSectorVisibility(sector)) return;
        data.observe(getViewLifecycleOwner(), list -> {
            List<Card> cards = new ArrayList<>();
            if (list != null) for (AlbumID3 album : list.subList(0, Math.min(MAX_CARDS, list.size()))) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.ALBUM_OBJECT, album.strippedForNav());
                cards.add(new Card(album.getName(), album.getArtist(), album.getCoverArtId(),
                        CustomGlideRequest.ResourceType.Album, false,
                        () -> navigate(R.id.albumPageFragment, bundle),
                        () -> navigate(R.id.albumBottomSheetDialog, bundle)));
            }
            row.cards = cards;
            refreshRows();
        });
    }

    private void observeTracks(Row row, androidx.lifecycle.LiveData<List<Child>> data) {
        data.observe(getViewLifecycleOwner(), list -> {
            List<Card> cards = new ArrayList<>();
            if (list != null) {
                List<Child> tracks = new ArrayList<>(list.subList(0, Math.min(MAX_CARDS, list.size())));
                for (int i = 0; i < tracks.size(); i++) {
                    Child track = tracks.get(i);
                    int index = i;
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.TRACK_OBJECT, track);
                    cards.add(new Card(track.getTitle(), track.getArtist(), track.getCoverArtId(),
                            CustomGlideRequest.ResourceType.Song, false,
                            () -> playTracks(tracks, index),
                            () -> navigate(R.id.songBottomSheetDialog, bundle)));
                }
            }
            row.cards = cards;
            refreshRows();
        });
    }

    private void refreshRows() {
        if (adapter == null) return;
        List<Row> visible = new ArrayList<>();
        for (Row row : allRows) if (!row.cards.isEmpty()) visible.add(row);
        adapter.setRows(visible);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (!focusRestored) rowsView.post(this::restoreFocus);
    }

    // ---- Naglowek i akcje

    private void bindHeader(View header) {
        ((TextView) header.findViewById(R.id.tv_home_greeting)).setText(greeting());
        header.findViewById(R.id.tv_home_action_play).setOnClickListener(v ->
                homeViewModel.getRandomShuffleSample().observe(getViewLifecycleOwner(), songs -> {
                    if (songs == null) return;
                    MusicUtil.ratingFilter(songs);
                    if (!songs.isEmpty()) {
                        MediaManager.startQueue(mediaBrowserFuture, songs, 0);
                        activity.setBottomSheetInPeek(true);
                        activity.expandBottomSheet();
                    }
                }));
        nowPlayingAction = header.findViewById(R.id.tv_home_action_now_playing);
        nowPlayingAction.setOnClickListener(v -> activity.expandBottomSheet());
        header.findViewById(R.id.tv_home_action_favorites).setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED);
            navigate(R.id.action_homeFragment_to_songListPageFragment, bundle);
        });
        header.findViewById(R.id.tv_home_action_search).setOnClickListener(v ->
                navigate(R.id.action_homeFragment_to_searchFragment, null));
        updateNowPlayingActionFromFuture();
    }

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return getString(R.string.piterfy_tv_greeting_night);
        if (hour < 12) return getString(R.string.piterfy_tv_greeting_morning);
        if (hour < 18) return getString(R.string.piterfy_tv_greeting_afternoon);
        return getString(R.string.piterfy_tv_greeting_evening);
    }

    private void updateNowPlayingAction(MediaBrowser browser) {
        if (nowPlayingAction != null) {
            nowPlayingAction.setVisibility(browser.getMediaItemCount() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateNowPlayingActionFromFuture() {
        if (mediaBrowserFuture != null && mediaBrowserFuture.isDone()) {
            try {
                updateNowPlayingAction(mediaBrowserFuture.get());
            } catch (Exception ignored) {
            }
        }
    }

    // ---- Nawigacja i odtwarzanie

    private void navigate(int destination, @Nullable Bundle args) {
        if (!isAdded() || getView() == null) return;
        NavHostFragment.findNavController(this).navigate(destination, args);
    }

    private void playTracks(List<Child> tracks, int index) {
        MediaManager.startQueue(mediaBrowserFuture, tracks, index);
        activity.setBottomSheetInPeek(true);
    }

    // ---- Fokus i tlo

    /** Zapamietuje, gdzie bylismy, zeby po powrocie z albumu fokus wrocil na te sama karte, a nie na poczatek. */
    private void onCardFocused(Card card) {
        if (rowsView != null) {
            View focus = rowsView.findFocus();
            RecyclerView.ViewHolder rowHolder = focus == null ? null : rowsView.findContainingViewHolder(focus);
            if (rowHolder != null && rowHolder.getBindingAdapterPosition() > 0) {
                lastRowIndex = rowHolder.getBindingAdapterPosition();
                RecyclerView cards = rowHolder.itemView.findViewById(R.id.tv_row_cards);
                RecyclerView.ViewHolder cardHolder = cards == null ? null : cards.findContainingViewHolder(focus);
                if (cardHolder != null) lastCardIndex = cardHolder.getBindingAdapterPosition();
            }
        }
        if (pendingBackdrop != null) handler.removeCallbacks(pendingBackdrop);
        // Krotkie opoznienie: przy szybkim przewijaniu nie pobieramy koloru dla kazdej mijanej karty.
        pendingBackdrop = () -> TvTheme.forCover(requireContext(), card.coverId, color -> {
            if (isAdded() && rowsView != null) animateBackdrop(color);
        });
        handler.postDelayed(pendingBackdrop, 140L);
    }

    private void restoreFocus() {
        if (rowsView == null || focusRestored || rowsView.getAdapter() == null) return;
        if (activity.isBottomSheetExpanded()) return;
        focusRestored = true;
        if (lastRowIndex > 0 && lastRowIndex < adapter.getItemCount()) {
            rowsView.scrollToPosition(lastRowIndex);
            rowsView.post(() -> {
                if (rowsView == null) return;
                RecyclerView.ViewHolder row = rowsView.findViewHolderForAdapterPosition(lastRowIndex);
                RecyclerView cards = row == null ? null : row.itemView.findViewById(R.id.tv_row_cards);
                if (cards == null) { focusHero(); return; }
                cards.scrollToPosition(lastCardIndex);
                cards.post(() -> {
                    RecyclerView.ViewHolder card = cards.findViewHolderForAdapterPosition(lastCardIndex);
                    if (card != null) card.itemView.requestFocus(); else focusHero();
                });
            });
        } else {
            focusHero();
        }
    }

    private void focusHero() {
        if (rowsView == null) return;
        RecyclerView.ViewHolder header = rowsView.findViewHolderForAdapterPosition(0);
        View play = header == null ? null : header.itemView.findViewById(R.id.tv_home_action_play);
        if (play != null) play.requestFocus();
    }

    private void animateBackdrop(int target) {
        activity.setTvBackdropColor(this, target);
    }

    private void applyBackdrop(int color) {
        if (activity != null) activity.setTvBackdropColor(this, color);
    }
}
