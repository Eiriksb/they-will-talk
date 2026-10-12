package dev.eiriksb.theywilltalk.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which memories come to mind: words in common with what's being said (SQLite FTS5), how important the memory is and
 * how recent, weighted like the "Generative Agents" retrieval score. No game classes, so it's unit tested.
 */
public final class MemoryRanking {
    /** A memory loses half its recency weight every week (real time: worlds are played on and off). */
    static final double HALF_LIFE_MS = 7.0 * 24 * 3600 * 1000;
    static final double W_RELEVANCE = 1.0;
    static final double W_IMPORTANCE = 0.8;
    static final double W_RECENCY = 0.5;
    private static final int MAX_TERMS = 12;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}']+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "but", "not", "you", "your", "yours", "all", "any", "can", "had", "has", "have", "her",
            "him", "his", "how", "its", "let", "may", "our", "out", "own", "she", "too", "use", "was", "way", "who", "why",
            "what", "when", "where", "which", "with", "would", "could", "should", "this", "that", "these", "those", "there",
            "their", "them", "then", "than", "they", "been", "being", "were", "will", "just", "about", "into", "from", "some",
            "very", "much", "more", "most", "also", "here", "hey", "hello", "please", "thanks", "thank", "yes", "yeah", "okay",
            "did", "does", "doing", "done", "get", "got", "going", "know", "like", "really", "think", "want", "tell", "said");

    private MemoryRanking() {}

    /**
     * An FTS5 query matching any of the meaningful words in {@code text} ({@code "roof" OR "bakery"}), or null when there
     * are none.
     */
    public static String ftsQuery(String text) {
        if (text == null) {
            return null;
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find() && terms.size() < MAX_TERMS) {
            String w = m.group().replace("'", "");
            if (w.length() >= 3 && !STOPWORDS.contains(w)) {
                terms.add(w);
            }
        }
        if (terms.isEmpty()) {
            return null;
        }
        List<String> quoted = new ArrayList<>();
        for (String t : terms) {
            quoted.add('"' + t.replace("\"", "\"\"") + '"');
        }
        return String.join(" OR ", quoted);
    }

    /** 0..1: 1 for a brand-new memory, halving every {@link #HALF_LIFE_MS}. */
    static double recency(long ageMs) {
        return Math.pow(0.5, Math.max(0, ageMs) / HALF_LIFE_MS);
    }

    /**
     * The best {@code limit} items, by relevance (FTS5 {@code bm25}: lower is better, scaled so the best match is 1),
     * importance (1-10) and recency.
     */
    public static <T> List<T> rank(List<T> items, ToDoubleFunction<T> bm25, ToIntFunction<T> importance, ToLongFunction<T> created,
                                   long now, int limit) {
        double best = 0;
        for (T t : items) {
            best = Math.min(best, bm25.applyAsDouble(t));
        }
        double bestScore = best;
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble((T t) -> -score(bm25.applyAsDouble(t), bestScore, importance.applyAsInt(t),
                now - created.applyAsLong(t))));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    static double score(double bm25, double bestBm25, int importance, long ageMs) {
        double relevance = bestBm25 < 0 ? Math.max(0, bm25 / bestBm25) : 0;
        double imp = Math.max(1, Math.min(10, importance)) / 10.0;
        return W_RELEVANCE * relevance + W_IMPORTANCE * imp + W_RECENCY * recency(ageMs);
    }
}
