package com.eddyizm.tempus.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.model.RecentSearch;
import com.eddyizm.tempus.repository.SearchingRepository;
import com.eddyizm.tempus.subsonic.models.SearchResult2;
import com.eddyizm.tempus.subsonic.models.SearchResult3;
import com.eddyizm.tempus.ui.fragment.SearchFragment;

import java.util.ArrayList;
import java.util.List;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class SearchViewModel extends AndroidViewModel {
    private static final String TAG = "SearchViewModel";

    private String query = "";

    private final SearchingRepository searchingRepository;

    public SearchViewModel(@NonNull Application application) {
        super(application);

        searchingRepository = new SearchingRepository();
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;

        if (!query.isEmpty()) {
            insertNewSearch(query);
        }
    }

    public LiveData<SearchResult2> search2(String title) {
        return searchingRepository.search2(title);
    }

    @UnstableApi
    public LiveData<SearchResult3> search3(String title) {
        return searchingRepository.search3(title);
    }

    public void insertNewSearch(String search) {
        searchingRepository.insert(new RecentSearch(search, System.currentTimeMillis() / 1000L));
    }

    public void deleteRecentSearch(String search) {
        searchingRepository.delete(new RecentSearch(search, 0));
    }

    public LiveData<List<String>> getSearchSuggestion(String query) {
        return searchingRepository.getSuggestions(query);
    }

    public LiveData<List<String>> getRecentSearchSuggestion() { return searchingRepository.getRecentSearchSuggestion(); }
    public LiveData<Boolean> getError() { return searchingRepository.error; }
    public LiveData<Boolean> getLoadingAll() { return searchingRepository.loadingAll; }
    public LiveData<List<com.eddyizm.tempus.subsonic.models.Child>> loadAll() { return searchingRepository.loadAll(query); }
    public void cancel() { searchingRepository.cancelSearch(); searchingRepository.cancelSuggestions(); }
    public void cancelSuggestions() { searchingRepository.cancelSuggestions(); }
    @Override protected void onCleared() { searchingRepository.close(); }
}
