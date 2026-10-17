package com.eddyizm.tempus.util;

import android.content.Context;
import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.model.InternetRadioStationCache;
import java.util.*;

/** Piterfy: editable starter stations, seeded once without replacing user choices. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PiterfyRadioCatalog {
    private PiterfyRadioCatalog() {}
    private static final String[][] STATIONS = {
        {"rmf", "RMF FM", "https://rs101-krk-cyfronet.rmfstream.pl/rmf_fm", "https://www.rmf.fm/"},
        {"zet", "Radio ZET", "https://zt.cdn.eurozet.pl/zet-net.mp3", "https://www.radiozet.pl/"},
        {"eska", "Radio ESKA", "https://ic1.smcdn.pl/2380-1.aac", "https://www.eska.pl/"},
        {"maxx", "RMF MAXX", "https://rs202-krk-cyfronet.rmfstream.pl/rmf_maxxx", "https://www.rmfmaxx.pl/"},
        {"classic", "RMF Classic", "https://rs101-krk.rmfstream.pl/rmf_classic", "https://www.rmfclassic.pl/"},
        {"tok", "TOK FM", "https://radiostream.pl/tuba10-1.mp3", "https://www.tokfm.pl/"},
        {"antyradio", "Antyradio", "https://an.cdn.eurozet.pl/ant-waw.mp3", "https://www.antyradio.pl/"},
        {"pr1", "Jedynka", "https://stream11.polskieradio.pl/pr1/pr1.sdp/playlist.m3u8", "https://jedynka.polskieradio.pl/"},
        {"pr2", "Dwójka", "https://stream12.polskieradio.pl/pr2/pr2.sdp/playlist.m3u8", "https://dwojka.polskieradio.pl/"},
        {"pr3", "Trójka", "https://stream13.polskieradio.pl/pr3/pr3.sdp/playlist.m3u8", "https://trojka.polskieradio.pl/"},
        {"pr24", "Polskie Radio 24", "https://stream15.polskieradio.pl/pr24/pr24.sdp/playlist.m3u8", "https://polskieradio24.pl/"},
        {"357", "Radio 357", "https://stream.radio357.pl", "https://radio357.pl/"},
        {"nowyswiat", "Radio Nowy Świat", "https://stream.nowyswiat.online/mp3", "https://nowyswiat.online/"},
        {"bbc", "BBC World Service", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service", "https://www.bbc.com/audio"},
        {"lbc", "LBC", "https://media-ice.musicradio.com/LBCUKMP3", "https://www.lbc.co.uk/"},
        {"lbcnews", "LBC News", "https://media-ice.musicradio.com/LBCNewsUKMP3", "https://www.lbc.co.uk/"},
        {"ndr2", "NDR 2", "https://icecast.ndr.de/ndr/ndr2/niedersachsen/mp3/128/stream.mp3", "https://www.ndr.de/ndr2/"},
        {"ndrinfo", "NDR Info", "https://icecast.ndr.de/ndr/ndrinfo/live/mp3/128/stream.mp3", "https://www.ndr.de/nachrichten/info/"}
    };
    public static List<InternetRadioStationCache> defaults() {
        List<InternetRadioStationCache> result = new ArrayList<>();
        for (String[] row : STATIONS) {
            InternetRadioStationCache station = new InternetRadioStationCache();
            station.setId("local_piterfy_" + row[0]); station.setName(row[1]);
            station.setStreamUrl(row[2]); station.setHomePageUrl(row[3]);
            station.setSource(InternetRadioStationCache.SOURCE_LOCAL); result.add(station);
        }
        return result;
    }
    public static List<InternetRadioStationCache> missingDefaults(List<InternetRadioStationCache> existing) {
        Set<String> ids = new HashSet<>(), names = new HashSet<>(), urls = new HashSet<>();
        for (var station : existing) {
            ids.add(station.getId()); names.add(normalized(station.getName())); urls.add(normalized(station.getStreamUrl()));
        }
        List<InternetRadioStationCache> result = new ArrayList<>();
        for (var station : defaults())
            if (!ids.contains(station.getId()) && !names.contains(normalized(station.getName())) && !urls.contains(normalized(station.getStreamUrl()))) result.add(station);
        return result;
    }
    private static String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    /**
     * Logo stacji startowych sa wbudowane w aplikacje (res/raw/radio_<id>.png) i kopiowane do katalogu okladek
     * radia - tego samego, z ktorego korzystaja listy, powiadomienie i ekran blokady. Istniejacych plikow
     * (np. okladki wybranej przez uzytkownika) nie nadpisuje.
     */
    static void installCovers(Context context) {
        for (String[] row : STATIONS) {
            java.io.File target = RadioCoverArtDownloader.getLocalCoverFile("local_piterfy_" + row[0]);
            if (target.exists()) continue;
            int res = context.getResources().getIdentifier("radio_" + row[0], "raw", context.getPackageName());
            if (res == 0) continue;
            try (java.io.InputStream in = context.getResources().openRawResource(res);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            } catch (java.io.IOException e) {
                target.delete();  // niepelny plik zostalby wzięty za okladke
            }
        }
    }

    public static synchronized void seedIfNeeded(Context context) {
        installCovers(context);
        var prefs = context.getSharedPreferences("piterfy_radio_catalog", Context.MODE_PRIVATE);
        if (prefs.getBoolean("v1_added", false)) return;
        AppDatabase db = AppDatabase.getInstance();
        db.runInTransaction(() -> db.internetRadioStationDao().insertAll(missingDefaults(db.internetRadioStationDao().getAll())));
        // Persist even if the user later removes a starter station: never silently restore it.
        prefs.edit().putBoolean("v1_added", true).commit();
    }
    public static boolean isStarter(String id) { return id != null && id.startsWith("local_piterfy_"); }
}
