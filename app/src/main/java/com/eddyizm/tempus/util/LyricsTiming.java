package com.eddyizm.tempus.util;

import com.eddyizm.tempus.subsonic.models.*;
import java.util.*;
import java.util.regex.*;

/** LRC/OpenSubsonic times are milliseconds. Positive offset displays a line sooner. */
public final class LyricsTiming {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    public static LyricsList parse(String lrc) {
        StructuredLyrics lyrics = new StructuredLyrics();
        ArrayList<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher offset = OFFSET.matcher(raw);
            if (offset.find()) {
                try { lyrics.setOffset(Integer.parseInt(offset.group(1))); } catch (NumberFormatException ignored) {}
            }
            Matcher times = TIME.matcher(raw);
            ArrayList<Integer> starts = new ArrayList<>();
            int end = 0;
            while (times.find()) {
                String fraction = times.group(3);
                int ms = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
                starts.add(Integer.parseInt(times.group(1)) * 60000 + Integer.parseInt(times.group(2)) * 1000 + ms);
                end = times.end();
            }
            final String value = raw.substring(end).trim();
            for (int start : starts) { Line line = new Line(); line.setStart(start); line.setValue(value); lines.add(line); }
        }
        lines.sort(Comparator.comparingInt(Line::getStart));
        lyrics.setSynced(!lines.isEmpty()); lyrics.setLine(lines); lyrics.setLang("und");
        LyricsList result = new LyricsList(); result.setStructuredLyrics(Collections.singletonList(lyrics)); return result;
    }
    public static int active(List<Line> lines, long position, int offset) {
        int active = -1;
        for (int i = 0; i < lines.size(); i++) {
            Integer start = lines.get(i).getStart();
            if (start != null && start <= position + offset) active = i;
        }
        return active;
    }
    public static boolean hasLines(LyricsList list) {
        return list != null && list.getStructuredLyrics() != null && list.getStructuredLyrics().stream()
            .anyMatch(LyricsTiming::usable);
    }
    private static boolean usable(StructuredLyrics lyrics) {
        return lyrics != null && lyrics.getLine() != null && lyrics.getLine().stream()
            .anyMatch(line -> line != null && line.getValue() != null && (!lyrics.getSynced() || line.getStart() != null));
    }
    public static StructuredLyrics preferred(LyricsList list) {
        if (!hasLines(list)) return null;
        return list.getStructuredLyrics().stream().filter(LyricsTiming::usable)
            .sorted(Comparator.comparing(x -> !x.getSynced())).findFirst().orElse(null);
    }
}
