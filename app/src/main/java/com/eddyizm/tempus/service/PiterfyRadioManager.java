package com.eddyizm.tempus.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.lan.LanRemoteSession;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.SubsonicResponse;
import com.eddyizm.tempus.util.MappingUtil;
import com.eddyizm.tempus.util.PiterfyRadioPlan;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Piterfy: „Radio z utworu / wykonawcy” — nieskończona kolejka podobnych utworów z biblioteki Navidrome.
 * (W aplikacji „radio” to także stacje internetowe — to jest radio z utworu; wszystkie napisy mówią „Radio z utworu”.)
 *
 * Start: Subsonic getSimilarSongs (dla utworu) albo getTopSongs + getSimilarSongs2 (dla wykonawcy), potem wspólna logika
 * z PiterfyRadioPlan (filtr podcastów i 1★, bez powtórek, lekkie tasowanie, ten sam wykonawca nie dwa razy pod rząd) i
 * zwykłe MediaManager.startQueue. Dociąganie: usługa odtwarzania woła onTrackStarted przy każdym nowym utworze; gdy po
 * bieżącym zostaje najwyżej 3, pytamy o podobne do bieżącego i dwóch poprzednich i dokładamy partię na koniec kolejki
 * bez przerywania gry. Radio wyłącza się samo, gdy zaczyna grać coś, czego nie dołożyło (użytkownik włączył inną muzykę).
 * Wszystko dzieje się na wątku głównym (słuchacz usługi i callbacki Retrofita).
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class PiterfyRadioManager {
    private static final String TAG = "PiterfyRadio";
    private static final int SIMILAR_LIMIT = 40;
    private static final int TOP_LIMIT = 20;
    private static final long FAIL_BACKOFF_MS = 15_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Etykieta „Radio z utworu: …” gdy radio gra, inaczej null — do wskaźnika w menu odtwarzacza. */
    private static final MutableLiveData<String> STATE = new MutableLiveData<>(null);
    private static final Set<String> known = new HashSet<>();
    private static List<String> memory = new ArrayList<>();
    private static boolean active;
    private static boolean fetching;
    private static long generation;
    private static long lastChangeAt;
    private static long lastFailAt;
    private static String label = "";

    private PiterfyRadioManager() {}

    public static boolean isActive() {
        return active;
    }

    /** Nazwa wskaźnika, np. „Radio z utworu: Intro”; pusta, gdy radio nie gra. */
    @NonNull
    public static String label() {
        return active ? label : "";
    }

    public static LiveData<String> state() {
        return STATE;
    }

    /** Radio z podanego utworu. */
    public static void startFromSong(@Nullable ListenableFuture<MediaBrowser> browser, @Nullable Child seed) {
        if (seed == null || browser == null || !canStart()) return;
        final long gen = begin(App.getContext().getString(R.string.piterfy_radio_label_song, title(seed)));
        List<Call<ApiResponse>> calls = new ArrayList<>();
        List<String> types = new ArrayList<>();
        calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getSimilarSongs(seed.getId(), SIMILAR_LIMIT));
        types.add("similarSongs");
        if (seed.getArtistId() != null && !seed.getArtistId().isEmpty()) {
            calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getSimilarSongs2(seed.getArtistId(), SIMILAR_LIMIT));
            types.add("similarSongs2");
        }
        fetch(calls, types, lists -> {
            if (gen != generation) return;
            List<Child> queue = PiterfyRadioPlan.INSTANCE.build(seed, PiterfyRadioPlan.INSTANCE.interleave(lists),
                    memory, Collections.emptySet(), kotlin.random.Random.Default, PiterfyRadioPlan.INSTANCE::podcastLike);
            launch(browser, queue);
        });
    }

    /** Radio wykonawcy: jego najpopularniejsze utwory przeplatane utworami podobnych wykonawców. */
    public static void startFromArtist(@Nullable ListenableFuture<MediaBrowser> browser, @Nullable String artistId,
                                       @Nullable String artistName) {
        boolean noId = artistId == null || artistId.isEmpty();
        boolean noName = artistName == null || artistName.isEmpty();
        if (browser == null || (noId && noName) || !canStart()) return;
        final long gen = begin(App.getContext().getString(R.string.piterfy_radio_label_artist, noName ? "" : artistName));
        List<Call<ApiResponse>> calls = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (!noName) {
            calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getTopSongs(artistName, TOP_LIMIT));
            types.add("topSongs");
        }
        if (!noId) {
            calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getSimilarSongs2(artistId, SIMILAR_LIMIT));
            types.add("similarSongs2");
        }
        fetch(calls, types, lists -> {
            if (gen != generation) return;
            PiterfyRadioPlan plan = PiterfyRadioPlan.INSTANCE;
            List<Child> all = plan.interleave(lists);
            List<Child> clean = plan.clean(all, Collections.emptySet(), memory, plan::podcastLike);
            Child seed = clean.isEmpty() ? null : clean.get(0);
            List<Child> queue = plan.build(seed, all, memory, Collections.emptySet(), kotlin.random.Random.Default, plan::podcastLike);
            launch(browser, queue);
        });
    }

    /** Radio z aktualnie granego utworu. */
    public static void startFromCurrent(@Nullable ListenableFuture<MediaBrowser> browser) {
        if (browser == null || !browser.isDone()) return;
        try {
            MediaItem current = browser.get().getCurrentMediaItem();
            if (current == null) {
                toast(R.string.piterfy_radio_nothing_playing);
                return;
            }
            startFromSong(browser, MappingUtil.mapToChild(current));
        } catch (Exception e) {
            Log.w(TAG, "startFromCurrent failed", e);
        }
    }

    /** Wyłącza radio; to, co już jest w kolejce, gra dalej. */
    public static void stop() {
        if (!active) return;
        active = false;
        generation++;
        fetching = false;
        known.clear();
        label = "";
        STATE.postValue(null);
        toast(R.string.piterfy_radio_stopped);
    }

    /**
     * Wołane przez usługę odtwarzania przy każdym nowym utworze (wątek główny): wyłącza radio, gdy gra obcy utwór,
     * a w przeciwnym razie zapamiętuje zagrany utwór i dociąga kolejne, gdy kolejka się kończy.
     */
    public static void onTrackStarted(@Nullable MediaItem item, @NonNull MediaManager.QueueTarget target) {
        if (!active || item == null) return;
        Player player = target.livePlayer();
        if (player == null) return;
        String id = item.mediaId;
        long since = System.currentTimeMillis() - lastChangeAt;
        if (PiterfyRadioPlan.INSTANCE.shouldStop(id, known, since, PiterfyRadioPlan.GRACE_MS)) {
            stop();
            return;
        }
        if (!known.contains(id)) {
            // coś innego zaczęło grać w trakcie karencji po naszej zmianie kolejki — sprawdzamy jeszcze raz po jej końcu
            final long gen = generation;
            MAIN.postDelayed(() -> {
                if (gen != generation || !active) return;
                Player live = target.livePlayer();
                MediaItem now = live == null ? null : live.getCurrentMediaItem();
                if (now != null) onTrackStarted(now, target);
            }, Math.max(0, PiterfyRadioPlan.GRACE_MS - since) + 200);
            return;
        }
        memory = PiterfyRadioPlan.INSTANCE.remember(memory, Collections.singletonList(id), PiterfyRadioPlan.MEMORY_SIZE);
        topUpIfNeeded(target, player);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static boolean canStart() {
        if (LanRemoteSession.isActive()) {
            toast(R.string.piterfy_radio_not_on_remote);
            return false;
        }
        return true;
    }

    private static long begin(String newLabel) {
        generation++;
        active = true;
        fetching = false;
        known.clear();
        label = newLabel;
        lastChangeAt = System.currentTimeMillis();
        lastFailAt = 0;
        STATE.postValue(newLabel);
        Toast.makeText(App.getContext(), newLabel, Toast.LENGTH_SHORT).show();
        return generation;
    }

    private static void launch(ListenableFuture<MediaBrowser> browser, List<Child> queue) {
        if (queue.isEmpty()) {
            active = false;
            known.clear();
            label = "";
            STATE.postValue(null);
            toast(R.string.piterfy_radio_empty);
            return;
        }
        for (Child song : queue) known.add(song.getId());
        lastChangeAt = System.currentTimeMillis();
        MediaManager.startQueue(browser, queue, 0);
    }

    private static void topUpIfNeeded(MediaManager.QueueTarget target, Player player) {
        if (fetching) return;
        int count = player.getMediaItemCount();
        int index = player.getCurrentMediaItemIndex();
        if (!PiterfyRadioPlan.INSTANCE.needMore(count, index, PiterfyRadioPlan.TOP_UP_AT)) return;
        if (System.currentTimeMillis() - lastFailAt < FAIL_BACKOFF_MS) return;

        List<String> queueIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) queueIds.add(player.getMediaItemAt(i).mediaId);
        List<String> seeds = PiterfyRadioPlan.INSTANCE.seedsForTopUp(queueIds, index);
        if (seeds.isEmpty()) return;
        String lastArtist = PiterfyRadioPlan.INSTANCE.artistKey(MappingUtil.mapToChild(player.getMediaItemAt(count - 1)));

        fetching = true;
        final long gen = generation;
        List<Call<ApiResponse>> calls = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (String seed : seeds) {
            calls.add(App.getSubsonicClientInstance(false).getBrowsingClient().getSimilarSongs(seed, 30));
            types.add("similarSongs");
        }
        fetch(calls, types, lists -> {
            fetching = false;
            if (gen != generation || !active) return;
            Player live = target.livePlayer();
            if (live == null) return;
            PiterfyRadioPlan plan = PiterfyRadioPlan.INSTANCE;
            // do kolejki wpadają też utwory dodane między zapytaniem a odpowiedzią — odfiltrowujemy aktualną zawartość
            Set<String> inQueue = new HashSet<>();
            for (int i = 0; i < live.getMediaItemCount(); i++) inQueue.add(live.getMediaItemAt(i).mediaId);
            List<Child> fresh = plan.clean(plan.interleave(lists), inQueue, memory, plan::podcastLike);
            List<Child> batch = plan.order(fresh.subList(0, Math.min(fresh.size(), PiterfyRadioPlan.BATCH * 2)),
                    kotlin.random.Random.Default, lastArtist);
            batch = new ArrayList<>(batch.subList(0, Math.min(batch.size(), PiterfyRadioPlan.BATCH)));
            if (batch.isEmpty()) {
                lastFailAt = System.currentTimeMillis();
                Log.d(TAG, "top-up: nothing new from the server");
                return;
            }
            for (Child song : batch) known.add(song.getId());
            lastChangeAt = System.currentTimeMillis();
            Log.d(TAG, "top-up: adding " + batch.size() + " songs");
            MediaManager.enqueue(target, batch, false);
        });
    }

    /** Wykonuje zapytania równolegle i oddaje po jednej liście utworów na zapytanie (w kolejności zapytań). */
    private static void fetch(List<Call<ApiResponse>> calls, List<String> types, Consumer<List<List<Child>>> done) {
        final List<List<Child>> lists = new ArrayList<>(Collections.nCopies(calls.size(), (List<Child>) new ArrayList<Child>()));
        if (calls.isEmpty()) {
            done.accept(lists);
            return;
        }
        final AtomicInteger pending = new AtomicInteger(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            final int slot = i;
            final String type = types.get(i);
            calls.get(i).enqueue(new Callback<ApiResponse>() {
                @Override
                public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                    synchronized (lists) {
                        lists.set(slot, extract(response, type));
                    }
                    finish();
                }

                @Override
                public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                    Log.w(TAG, "request failed: " + type, t);
                    finish();
                }

                private void finish() {
                    if (pending.decrementAndGet() == 0) MAIN.post(() -> done.accept(lists));
                }
            });
        }
    }

    private static List<Child> extract(Response<ApiResponse> response, String type) {
        List<Child> list = null;
        if (response.isSuccessful() && response.body() != null) {
            SubsonicResponse res = response.body().getSubsonicResponse();
            if (res != null) {
                if ("similarSongs".equals(type) && res.getSimilarSongs() != null) list = res.getSimilarSongs().getSongs();
                else if ("similarSongs2".equals(type) && res.getSimilarSongs2() != null) list = res.getSimilarSongs2().getSongs();
                else if ("topSongs".equals(type) && res.getTopSongs() != null) list = res.getTopSongs().getSongs();
            }
        }
        return list != null ? list : new ArrayList<>();
    }

    private static String title(Child song) {
        String t = song.getTitle();
        return t == null ? "" : t;
    }

    private static void toast(int resId) {
        Context context = App.getContext();
        MAIN.post(() -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show());
    }
}
