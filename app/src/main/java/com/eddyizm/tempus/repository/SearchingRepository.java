package com.eddyizm.tempus.repository;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.database.dao.RecentSearchDao;
import com.eddyizm.tempus.model.RecentSearch;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.subsonic.models.PlaylistWithSongs;
import com.eddyizm.tempus.subsonic.models.SearchResult2;
import com.eddyizm.tempus.subsonic.models.SearchResult3;
import com.eddyizm.tempus.util.Preferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SearchingRepository {
    private final RecentSearchDao recentSearchDao = AppDatabase.getInstance().recentSearchDao();

    public MutableLiveData<SearchResult2> search2(String query) {
        MutableLiveData<SearchResult2> result = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getSearchingClient()
                .search3(query, 20, 0, 20, 0, 20, 0)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            result.setValue(response.body().getSubsonicResponse().getSearchResult2());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return result;
    }

    private Call<ApiResponse> searchCall, suggestionCall, allCall;
    private int searchGeneration, suggestionGeneration;
    private final java.util.concurrent.ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<String>> recent = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<Boolean> error = new MutableLiveData<>(false);
    public final MutableLiveData<Boolean> loadingAll = new MutableLiveData<>(false);

    public void cancelSearch() {
        searchGeneration++;
        if (searchCall != null) searchCall.cancel();
        if (allCall != null) allCall.cancel();
        loadingAll.setValue(false);
    }

    public void cancelSuggestions() {
        suggestionGeneration++;
        if (suggestionCall != null) suggestionCall.cancel();
    }

    public void close() {
        cancelSearch();
        cancelSuggestions();
        databaseExecutor.shutdown();
    }

    @UnstableApi
    public MutableLiveData<SearchResult3> search3(String query) {
        cancelSearch();
        error.setValue(false);
        final int generation = searchGeneration;
        MutableLiveData<SearchResult3> result = new MutableLiveData<>();
        searchCall = App.getSubsonicClientInstance(false).getSearchingClient()
                .search3(query, 50, 0, 20, 0, 20, 0);
        searchCall.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                if (generation != searchGeneration || call.isCanceled()) return;
                SearchResult3 value = response.isSuccessful() && response.body() != null
                        && response.body().getSubsonicResponse() != null
                        ? response.body().getSubsonicResponse().getSearchResult3() : null;
                error.setValue(value == null);
                result.setValue(value);
            }
            public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                if (generation == searchGeneration && !call.isCanceled()) error.setValue(true);
            }
        });
        return result;
    }

    // Only fetch the full collection when the user explicitly asks to play it.
    @UnstableApi
    public MutableLiveData<List<Child>> loadAll(String query) {
        if (allCall != null) allCall.cancel();
        final int generation = ++searchGeneration;
        MutableLiveData<List<Child>> result = new MutableLiveData<>();
        loadingAll.setValue(true);
        error.setValue(false);
        loadPage(query, 0, generation, new ArrayList<>(), result);
        return result;
    }

    @UnstableApi
    private void loadPage(String query, int offset, int generation, List<Child> songs, MutableLiveData<List<Child>> result) {
        allCall = App.getSubsonicClientInstance(false).getSearchingClient().search3(query, 200, offset, 0, 0, 0, 0);
        allCall.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                if (generation != searchGeneration || call.isCanceled()) return;
                SearchResult3 value = response.isSuccessful() && response.body() != null
                        && response.body().getSubsonicResponse() != null
                        ? response.body().getSubsonicResponse().getSearchResult3() : null;
                if (value == null) { error.setValue(true); loadingAll.setValue(false); return; }
                List<Child> page = value.getSongs();
                if (page != null) songs.addAll(page);
                if (page != null && page.size() == 200) {
                    loadPage(query, offset + page.size(), generation, songs, result);
                } else {
                    loadingAll.setValue(false);
                    result.setValue(songs);
                }
            }
            public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                if (generation == searchGeneration && !call.isCanceled()) {
                    loadingAll.setValue(false); error.setValue(true);
                }
            }
        });
    }

    public MutableLiveData<List<String>> getSuggestions(String query) {
        MutableLiveData<List<String>> suggestions = new MutableLiveData<>();

        cancelSuggestions();
        final int generation = suggestionGeneration;
        suggestionCall = App.getSubsonicClientInstance(false).getSearchingClient().search3(query, 5, 0, 5, 0, 5, 0);
        suggestionCall.enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (generation != suggestionGeneration || call.isCanceled()) return;
                        List<String> newSuggestions = new ArrayList();

                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse() != null && response.body().getSubsonicResponse().getSearchResult3() != null) {
                            if (response.body().getSubsonicResponse().getSearchResult3().getArtists() != null) {
                                for (ArtistID3 artistID3 : response.body().getSubsonicResponse().getSearchResult3().getArtists()) {
                                    newSuggestions.add(artistID3.getName());
                                }
                            }

                            if (response.body().getSubsonicResponse().getSearchResult3().getAlbums() != null) {
                                for (AlbumID3 albumID3 : response.body().getSubsonicResponse().getSearchResult3().getAlbums()) {
                                    newSuggestions.add(albumID3.getName());
                                }
                            }

                            if (response.body().getSubsonicResponse().getSearchResult3().getSongs() != null) {
                                for (Child song : response.body().getSubsonicResponse().getSearchResult3().getSongs()) {
                                    newSuggestions.add(song.getTitle());
                                }
                            }

                            LinkedHashSet<String> hashSet = new LinkedHashSet<>(newSuggestions);
                            ArrayList<String> suggestionsWithoutDuplicates = new ArrayList<>(hashSet);

                            suggestions.setValue(suggestionsWithoutDuplicates);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return suggestions;
    }

    public void insert(RecentSearch search) {
        databaseExecutor.execute(() -> { recentSearchDao.insert(search); refreshRecent(); });
    }
    public void delete(RecentSearch search) {
        databaseExecutor.execute(() -> { recentSearchDao.delete(search); refreshRecent(); });
    }
    public MutableLiveData<List<String>> getRecentSearchSuggestion() {
        databaseExecutor.execute(this::refreshRecent);
        return recent;
    }
    private void refreshRecent() {
        recent.postValue(Preferences.isSearchSortingChronologicallyEnabled() ? recentSearchDao.getRecent() : recentSearchDao.getAlpha());
    }
}
