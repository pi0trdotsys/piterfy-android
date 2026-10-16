package com.eddyizm.tempus.util;

public final class PiterfyMedia {
    private PiterfyMedia() {}
    public static boolean isPodcast(String genre) {
        if (genre == null) return false;
        for (String part : genre.split("[;,/]")) {
            if ("podcast".equalsIgnoreCase(part.trim()) || "podcasts".equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }
}
