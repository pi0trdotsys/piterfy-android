package com.eddyizm.tempus.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.PiterfyMedia;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.database.AppDatabase;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.*;
import java.util.concurrent.*;
import retrofit2.*;

@androidx.media3.common.util.UnstableApi
public class PiterfyPodcastViewModel extends AndroidViewModel {
    public final MutableLiveData<List<AlbumID3>> albums = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<List<AlbumID3>> downloaded = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    public final MutableLiveData<Boolean> failed = new MutableLiveData<>(false);
    public boolean downloadedOnly;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Call<ApiResponse> call;
    private String server;
    private int generation;
    private long loadedAt;
    public PiterfyPodcastViewModel(@NonNull Application app) { super(app); }

    public void refresh(boolean force) {
        String current = String.valueOf(Preferences.getServerId());
        if (!current.equals(server)) {
            server = current; generation++; loadedAt = 0;
            if (call != null) call.cancel();
            loading.setValue(false);
            albums.setValue(new ArrayList<>()); downloaded.setValue(new ArrayList<>());
            int token = generation;
            io.execute(() -> {
                try {
                    String json = getApplication().getSharedPreferences("piterfy_podcasts", 0).getString(current, "[]");
                    List<AlbumID3> cached = new Gson().fromJson(json, new TypeToken<List<AlbumID3>>(){}.getType());
                    main.post(() -> { if (token == generation && loadedAt == 0 && cached != null) albums.setValue(cached); });
                } catch (RuntimeException ignored) { }
            });
        }
        int token = generation;
        io.execute(() -> {
            Map<String, AlbumID3> local = new LinkedHashMap<>();
            for (var song : AppDatabase.getInstance().downloadDao().getAllSync()) {
                if (!PiterfyMedia.isPodcast(song.getGenre()) || song.getAlbumId() == null
                        || !DownloadUtil.getDownloadTracker(getApplication()).isDownloaded(song.getId())) continue;
                AlbumID3 album = local.get(song.getAlbumId());
                if (album == null) {
                    album = new AlbumID3();
                    album.setId(song.getAlbumId()); album.setName(song.getAlbum()); album.setArtist(song.getArtist());
                    album.setCoverArtId(song.getCoverArtId()); album.setGenre("Podcast"); album.setSongCount(0);
                    local.put(song.getAlbumId(), album);
                }
                album.setSongCount(album.getSongCount() + 1);
            }
            List<AlbumID3> result = new ArrayList<>(local.values());
            main.post(() -> { if (token == generation) downloaded.setValue(result); });
        });
        if (com.eddyizm.tempus.util.NetworkUtil.isOffline()) {
            if (call != null) call.cancel();
            loading.setValue(false); failed.setValue(false); return;
        }
        if (Boolean.TRUE.equals(loading.getValue()) || (!force && System.currentTimeMillis() - loadedAt < 300000)) return;
        loading.setValue(true); failed.setValue(false);
        page(0, generation, current, new ArrayList<>());
    }
    private void page(int offset, int token, String key, List<AlbumID3> all) {
        call = App.getSubsonicClientInstance(false).getAlbumSongListClient().getAlbumListByGenre("Podcast", 100, offset);
        call.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> request, @NonNull Response<ApiResponse> response) {
                if (request.isCanceled() || token != generation) return;
                var sr = response.body() == null ? null : response.body().getSubsonicResponse();
                if (!response.isSuccessful() || sr == null || sr.getAlbumList2() == null) { finishError(); return; }
                var batch = sr.getAlbumList2().getAlbums();
                if (batch != null) all.addAll(batch);
                if (batch != null && batch.size() == 100) { page(offset + 100, token, key, all); return; }
                all.sort(Comparator.comparing(a -> String.valueOf(a.getName()), String.CASE_INSENSITIVE_ORDER));
                loadedAt = System.currentTimeMillis(); loading.setValue(false); albums.setValue(all);
                io.execute(() -> getApplication().getSharedPreferences("piterfy_podcasts", 0).edit().putString(key, new Gson().toJson(all)).apply());
            }
            public void onFailure(@NonNull Call<ApiResponse> request, @NonNull Throwable error) {
                if (!request.isCanceled() && token == generation) finishError();
            }
        });
    }
    private void finishError() { loading.setValue(false); failed.setValue(true); }
    @Override protected void onCleared() { generation++; if (call != null) call.cancel(); io.shutdown(); }
}
