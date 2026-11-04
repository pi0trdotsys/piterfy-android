package com.eddyizm.tempus.util;

public final class PiterfyMedia {
    private PiterfyMedia() {}
    public static boolean isPodcast(String genre) {
        if (genre == null) return false;
        for (String part : genre.split("[;,/]")) {
            if ("podcast".equalsIgnoreCase(part.trim()) || "podcasts".equalsIgnoreCase(part.trim()) || "podcasty".equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }
    public static boolean isPodcast(com.eddyizm.tempus.subsonic.models.Child song) {
        return song != null && isPodcast(song.getGenre(), song.getPath(), song.getType());
    }
    public static boolean isPodcast(String genre, String path, String type) {
        if ("podcast".equalsIgnoreCase(type) || isPodcast(genre)) return true;
        if (path == null) return false;
        for (String segment : path.replace('\\', '/').split("/"))
            if ("podcasty".equalsIgnoreCase(segment) || "podcasts".equalsIgnoreCase(segment) || "podcast".equalsIgnoreCase(segment)) return true;
        return false;
    }
}
