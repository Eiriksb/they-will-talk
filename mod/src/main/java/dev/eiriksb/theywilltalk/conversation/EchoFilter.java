package dev.eiriksb.theywilltalk.conversation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word-overlap test used to spot a villager's own voice coming back through a player's microphone (and being
 * transcribed as if the player said it). Speech recognition rarely returns identical text, so this compares
 * word sets rather than strings.
 */
final class EchoFilter {
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");
    static final double THRESHOLD = 0.45;

    private EchoFilter() {}

    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (m.group().length() > 1) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** Share of the shorter text's words that also appear in the other. */
    static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String w : a) {
            if (b.contains(w)) {
                common++;
            }
        }
        return (double) common / Math.min(a.size(), b.size());
    }

    static boolean isEcho(Set<String> heard, Set<String> spoken) {
        return similarity(heard, spoken) >= THRESHOLD;
    }
}
