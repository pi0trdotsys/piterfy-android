package com.eddyizm.tempus.util;

import android.os.Handler;
import android.os.Looper;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.model.LyricsCache;
import com.eddyizm.tempus.repository.LyricsRepository;
import com.eddyizm.tempus.subsonic.models.*;
import com.google.gson.*;
import okhttp3.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Cancellable, account-scoped lookup: downloaded text, library, then exact LRCLIB match. */
public final class PiterfyLyricsLoader {
    public record Result(String text, LyricsList lines, String status, boolean cached) {}
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build();
    private volatile int generation;
    private volatile retrofit2.Call<?> serverCall;
    private volatile Call publicCall;
    public void cancel() { generation++; if (serverCall != null) serverCall.cancel(); if (publicCall != null) publicCall.cancel(); }
    public void close() { cancel(); io.shutdownNow(); }
    public void load(Child song, String cacheKey, boolean offline, Consumer<Result> callback) {
        cancel(); final int request = generation;
        final String identity = Preferences.getServer() + "|" + Preferences.getUser();
        io.execute(() -> {
            Result result;
            try { result = lookup(song, cacheKey, offline, request); }
            catch (Exception e) { result = new Result(null, null, "Nie udało się pobrać tekstu. Dotknij, aby ponowić.", false); }
            final Result answer = result;
            main.post(() -> { if (request == generation && identity.equals(Preferences.getServer() + "|" + Preferences.getUser())) callback.accept(answer); });
        });
    }
    private Result lookup(Child song, String key, boolean offline, int expected) throws Exception {
        LyricsCache cached = new LyricsRepository().getLyrics(key);
        if (cached != null) {
            LyricsList list = null;
            try { list = cached.getStructuredLyrics() == null ? null : new Gson().fromJson(cached.getStructuredLyrics(), LyricsList.class); }
            catch (JsonParseException ignored) { /* A damaged cache must not prevent a fresh lookup. */ }
            if (LyricsTiming.hasLines(list) || (cached.getLyrics() != null && !cached.getLyrics().isBlank()))
                return new Result(cached.getLyrics(), list, "Zapisany tekst · dostępny offline", true);
        }
        if (offline) return new Result(null, null, "Brak zapisanego tekstu. Połącz się z internetem.", false);
        if (song == null) return new Result(null, null, "", false);
        if (PiterfyMedia.isPodcast(song.getGenre()) || Constants.MEDIA_TYPE_PODCAST.equals(song.getType()))
            return new Result(null, null, "Opis odcinka", false);
        if (expected != generation) throw new InterruptedException();
        try {
            var call = App.getSubsonicClientInstance(false).getOpenClient().getLyricsBySongId(song.getId());
            serverCall = call; call.timeout().timeout(8, TimeUnit.SECONDS);
            if (expected != generation) { call.cancel(); throw new InterruptedException(); }
            var response = call.execute();
            LyricsList list = response.body() == null ? null : response.body().getSubsonicResponse().getLyricsList();
            if (LyricsTiming.hasLines(list)) return new Result(null, list, "Tekst z Twojej biblioteki", false);
        } catch (Exception ignored) { /* Older servers may only support legacy lyrics. */ }
        if (expected != generation) throw new InterruptedException();
        if (!OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            try {
                var call = App.getSubsonicClientInstance(false).getMediaRetrievalClient().getLyrics(song.getArtist(), song.getTitle());
                serverCall = call; call.timeout().timeout(8, TimeUnit.SECONDS);
                var response = call.execute();
                var lyrics = response.body() == null ? null : response.body().getSubsonicResponse().getLyrics();
                if (lyrics != null && lyrics.getValue() != null && !lyrics.getValue().isBlank()) return new Result(lyrics.getValue(), null, "Tekst z Twojej biblioteki", false);
            } catch (Exception ignored) {}
        }
        if (expected != generation) throw new InterruptedException();
        if (song.getTitle() == null || song.getArtist() == null || song.getDuration() == null || song.getDuration() < 1 || song.getDuration() > 3600)
            return new Result(null, null, "Brak tekstu dla tego nagrania.", false);
        HttpUrl url = HttpUrl.parse("https://lrclib.net/api/get").newBuilder()
            .addQueryParameter("track_name", song.getTitle()).addQueryParameter("artist_name", song.getArtist())
            .addQueryParameter("album_name", song.getAlbum() == null ? "" : song.getAlbum())
            .addQueryParameter("duration", song.getDuration().toString()).build();
        Call call = http.newCall(new Request.Builder().url(url).header("User-Agent", "Piterfy/1.0 (https://github.com/pi0trdotsys/piterfy-android)").build());
        publicCall = call;
        if (expected != generation) { call.cancel(); throw new InterruptedException(); }
        try (Response response = call.execute()) {
            if (response.code() == 404) return new Result(null, null, "Nie znaleziono tekstu dla tej wersji utworu.", false);
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException();
            String body = response.body().string();
            if (body.length() > 500000) throw new IllegalStateException();
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            if (!data.has("duration") || data.get("duration").isJsonNull() || !Double.isFinite(data.get("duration").getAsDouble()) || Math.abs(data.get("duration").getAsDouble() - song.getDuration()) > 2)
                return new Result(null, null, "Brak tekstu pasującego do tej wersji nagrania.", false);
            if (data.has("instrumental") && data.get("instrumental").getAsBoolean()) return new Result(null, null, "Utwór instrumentalny · LRCLIB", false);
            String synced = string(data, "syncedLyrics");
            if (synced != null) {
                LyricsList list = LyricsTiming.parse(synced);
                if (LyricsTiming.hasLines(list)) return new Result(null, list, "Tekst synchronizowany · LRCLIB", false);
            }
            String plain = string(data, "plainLyrics");
            return new Result(plain, null, plain == null ? "Brak tekstu · LRCLIB" : "Tekst bez synchronizacji · LRCLIB", false);
        }
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}
