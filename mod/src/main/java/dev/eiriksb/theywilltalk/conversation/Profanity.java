package dev.eiriksb.theywilltalk.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds swear words, YouTube style: censored in text ({@code f***}) and replaced by a beep in speech. Covers English and
 * the other languages villagers may answer in, plus Norwegian. Mild words (damn, hell, crap, bloody) are left alone.
 * Slurs are a separate list: they are always censored, even when swearing is allowed.
 */
public final class Profanity {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // Word stems: "\w*" lets a stem take endings (fucking, shitty, wanker), a leading "\w*" allows compounds (bullshit).
    private static final String SWEARS = String.join("|",
            // English
            "\\w*fuck\\w*", "\\w*shit\\w*", "bitch\\w*", "bastard\\w*", "ass(es|hole|holes|hat|hats)?", "jackass\\w*", "arse(hole|holes)?",
            "cunt\\w*", "dick(s|head|heads)?", "cock(s|sucker|suckers)?", "twat\\w*", "wank\\w*", "bollock\\w*", "piss(ed|ing|es)?",
            "prick(s)?", "slut(s|ty)?", "whore\\w*", "douchebag\\w*", "motherfuck\\w*",
            // Norwegian
            "faen\\w*", "fanden", "jævl\\w*", "helvete\\w*", "dritt\\w*", "fitte\\w*", "kuk(en|er)?", "pikk\\w*", "hore\\w*", "rævhøl\\w*",
            // German
            "schei(ß|ss)\\w*", "fick(en|t|st|er|e)?", "arschloch\\w*", "fotze\\w*", "hurensohn\\w*", "wichser\\w*",
            // Spanish
            "mierda", "put[ao]s?", "joder", "coño", "cabr[oó]n\\w*", "pendej\\w*", "gilipollas",
            // French
            "merde\\w*", "putain\\w*", "connard\\w*", "connasse\\w*", "salope\\w*", "encul\\w*",
            // Italian
            "cazz\\w*", "stronz\\w*", "vaffanculo", "puttan\\w*", "minchia",
            // Portuguese
            "porra", "caralho\\w*", "foda\\w*", "merda");

    private static final String SLURS = String.join("|",
            "nigg\\w*", "fag(s|got|gots)?", "retard(s|ed)?", "kike(s)?", "spic(s)?", "chink(s)?", "tranny", "trannies");

    private static final Pattern SWEAR = Pattern.compile("\\b(" + SWEARS + ")\\b", FLAGS);
    private static final Pattern SLUR = Pattern.compile("\\b(" + SLURS + ")\\b", FLAGS);
    private static final Pattern ANY = Pattern.compile("\\b(" + SWEARS + "|" + SLURS + ")\\b", FLAGS);

    /** A piece of a line: words to speak, or a word to beep. */
    public record Part(String text, boolean bleep) {}

    private Profanity() {}

    /**
     * Censors a line for players: slurs always, swear words when {@code bleep} is on ({@code fucking} becomes
     * {@code f******}).
     */
    public static String censor(String text, boolean bleep) {
        Matcher m = (bleep ? ANY : SLUR).matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(word.charAt(0) + "*".repeat(word.length() - 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Splits a line into what to say and what to beep (only the beeped words are {@code bleep}). */
    public static List<Part> split(String text, boolean bleep) {
        Matcher m = (bleep ? ANY : SLUR).matcher(text);
        List<Part> parts = new ArrayList<>();
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                parts.add(new Part(text.substring(at, m.start()), false));
            }
            parts.add(new Part(m.group(), true));
            at = m.end();
        }
        if (at < text.length()) {
            parts.add(new Part(text.substring(at), false));
        }
        return parts;
    }

    public static boolean contains(String text, boolean bleep) {
        return (bleep ? ANY : SLUR).matcher(text).find();
    }

    /** Roughly how long the beeped word would have taken to say. */
    public static int beepMs(String word) {
        return Math.clamp(word.length() * 70L, 220, 800);
    }
}
