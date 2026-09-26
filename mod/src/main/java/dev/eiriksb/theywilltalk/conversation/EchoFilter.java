package dev.eiriksb.theywilltalk.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spots a villager's own voice coming back through a player's microphone (and being transcribed as if the player said
 * it). An echo repeats the villager's words in order, or all of a short line; an answer may reuse a few of their words
 * ("Hello, David!", "Sure, I'll bring you the flint") but not whole stretches of what they said. Speech recognition
 * rarely returns identical text, so this compares word pairs rather than strings.
 */
final class EchoFilter {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");

    private EchoFilter() {}

    /** The words of a line in order, lower case, without one-letter words. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (m.group().length() > 1) {
                out.add(m.group());
            }
        }
        return out;
    }

    static boolean isEcho(List<String> heard, List<String> spoken) {
        if (heard.isEmpty() || spoken.isEmpty()) {
            return false;
        }
        // A stretch of the villager's line: most of what was heard are word pairs they said, in that order.
        Set<String> spokenPairs = pairs(spoken);
        List<String> heardPairs = new ArrayList<>();
        for (int i = 1; i < heard.size(); i++) {
            heardPairs.add(heard.get(i - 1) + ' ' + heard.get(i));
        }
        long matching = heardPairs.stream().filter(spokenPairs::contains).count();
        if (matching >= 2 && matching >= heardPairs.size() * 0.5) {
            return true;
        }
        // A short line coming back whole ("Yes!", "Hello there!"): nearly the same words both ways.
        Set<String> h = new HashSet<>(heard);
        Set<String> s = new HashSet<>(spoken);
        long common = h.stream().filter(s::contains).count();
        return common >= h.size() * 0.75 && common >= s.size() * 0.6;
    }

    private static Set<String> pairs(List<String> words) {
        Set<String> out = new HashSet<>();
        for (int i = 1; i < words.size(); i++) {
            out.add(words.get(i - 1) + ' ' + words.get(i));
        }
        return out;
    }
}
