package com.eddyizm.tempus.ui.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.FragmentHomeTabPiterfyPodcastBinding;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.ui.adapter.AlbumCatalogueAdapter;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Piterfy: zakladka "Podcasty". Navidrome nie obsluguje podcastow z API Subsonic, wiec serwer Piterfy
 * (podcasts.py) zapisuje odcinki jako pliki audio z gatunkiem "Podcast" - kazda audycja to album.
 * Zakladka pokazuje te albumy; wejscie w audycje otwiera zwykla strone albumu, a pobieranie offline
 * dziala tak jak dla muzyki.
 */
@UnstableApi
public class HomeTabPiterfyPodcastFragment extends Fragment implements ClickCallback {
    private static final String TAG = "HomeTabPiterfyPodcast";
    private static final String GENRE = "Podcast";
    private static final int SPAN_COUNT = 2;

    private FragmentHomeTabPiterfyPodcastBinding bind;
    private com.eddyizm.tempus.ui.adapter.ContinueListeningAdapter continueAdapter;
    private com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser> browser;
    private AlbumCatalogueAdapter adapter;
    private com.eddyizm.tempus.viewmodel.PiterfyPodcastViewModel model;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        bind = FragmentHomeTabPiterfyPodcastBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bind.piterfyPodcastRecyclerView.setLayoutManager(new GridLayoutManager(requireContext(), Math.max(1, Math.min(4, (int)(getResources().getConfiguration().screenWidthDp / (160 * getResources().getConfiguration().fontScale))))));
        adapter = new AlbumCatalogueAdapter(this, false);
        bind.piterfyPodcastRecyclerView.setAdapter(adapter);
        continueAdapter = new com.eddyizm.tempus.ui.adapter.ContinueListeningAdapter(null, true);
        bind.piterfyPodcastContinue.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        bind.piterfyPodcastContinue.setAdapter(continueAdapter);
        model = new androidx.lifecycle.ViewModelProvider(this).get(com.eddyizm.tempus.viewmodel.PiterfyPodcastViewModel.class);
        model.albums.observe(getViewLifecycleOwner(), ignored -> render());
        model.downloaded.observe(getViewLifecycleOwner(), ignored -> render());
        model.loading.observe(getViewLifecycleOwner(), loading -> { if (bind != null) { bind.piterfyPodcastRefresh.setRefreshing(Boolean.TRUE.equals(loading)); render(); } });
        model.failed.observe(getViewLifecycleOwner(), failed -> render());
        bind.piterfyPodcastRefresh.setOnRefreshListener(() -> model.refresh(true));
        bind.piterfyPodcastRetry.setOnClickListener(v -> model.refresh(true));
        bind.piterfyPodcastFilter.setChecked(model.downloadedOnly);
        bind.piterfyPodcastFilter.setOnCheckedChangeListener((button, checked) -> { model.downloadedOnly = checked; render(); });
    }

    @Override
    public void onResume() {
        super.onResume();
        model.refresh(false);
        List<com.eddyizm.tempus.util.Preferences.ResumePoint> points = new ArrayList<>();
        for (var point : com.eddyizm.tempus.util.Preferences.getContinueListening())
            if (com.eddyizm.tempus.util.Constants.MEDIA_TYPE_PODCAST.equals(point.getType())) points.add(point);
        continueAdapter.setItems(points);
        bind.piterfyPodcastContinue.setVisibility(points.isEmpty() ? View.GONE : View.VISIBLE);
        bind.piterfyPodcastContinueTitle.setVisibility(points.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public void onStart() {
        super.onStart();
        browser = new androidx.media3.session.MediaBrowser.Builder(requireContext(),
            new androidx.media3.session.SessionToken(requireContext(), new android.content.ComponentName(requireContext(), com.eddyizm.tempus.service.MediaService.class))).buildAsync();
        continueAdapter.setMediaBrowserFuture(browser);
    }
    @Override public void onStop() {
        continueAdapter.setMediaBrowserFuture(null);
        androidx.media3.session.MediaBrowser.releaseFuture(browser);
        browser = null;
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void render() {
        if (bind == null || model == null) return;
        List<AlbumID3> albums = (model.downloadedOnly ? model.downloaded : model.albums).getValue();
        if (albums == null) albums = new ArrayList<>();
        adapter.setItemsWithoutFilter(albums);
        boolean failed = Boolean.TRUE.equals(model.failed.getValue());
        boolean loading = Boolean.TRUE.equals(model.loading.getValue());
        bind.piterfyPodcastEmpty.setText(model.downloadedOnly ? R.string.piterfy_no_downloaded_podcasts : R.string.piterfy_podcasts_empty);
        bind.piterfyPodcastEmpty.setVisibility(albums.isEmpty() && !loading && (!failed || model.downloadedOnly) ? View.VISIBLE : View.GONE);
        bind.piterfyPodcastError.setVisibility(failed && !model.downloadedOnly ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onAlbumClick(Bundle bundle) {
        bundle.putBoolean("piterfy_downloaded_only", model.downloadedOnly);
        Navigation.findNavController(requireView()).navigate(R.id.albumPageFragment, bundle);
    }

    @Override
    public void onAlbumLongClick(Bundle bundle) {
        Navigation.findNavController(requireView()).navigate(R.id.albumBottomSheetDialog, bundle);
    }
}
