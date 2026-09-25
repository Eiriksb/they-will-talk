package dev.eiriksb.theywilltalk.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns streamed LLM text into speakable sentences as early as possible, so the villager starts talking while the
 * rest of the reply is still being generated. Also extracts the leading emotion tag ({@code [happy] ...}) and
 * strips things that shouldn't be read aloud (stage directions, markdown, emoji).
 */
public final class SentenceStream {
    public static final List<String> EMOTIONS = List.of(
            "happy", "neutral", "sad", "angry", "surprised", "laugh", "confused", "scared", "excited", "annoyed", "sleepy", "flirty");

    private static final Pattern LEADING_TAG = Pattern.compile("^\\s*[\\[(<*]+\\s*([a-zA-Z_ -]{2,20})\\s*[\\])>*]+\\s*");
    private static final Pattern ANY_TAG = Pattern.compile("\\[[^\\]]{0,30}\\]");
    private static final Pattern STAGE_DIRECTION = Pattern.compile("\\*[^*]{1,80}\\*|\\([^)]{0,60}(sighs?|laughs?|chuckles?|grumbles?|pauses?|smiles?|shrugs?)[^)]{0,40}\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_SPEAKABLE = Pattern.compile("[\\p{So}\\p{Cn}#_~`|>]");
    private static final int MIN_FIRST_CHARS = 12;

    private final Consumer<String> onSentence;
    private final StringBuilder buf = new StringBuilder();
    private String emotion;
    private boolean tagResolved;
    private int emitted;

    public SentenceStream(Consumer<String> onSentence) {
        this.onSentence = onSentence;
    }

    /** Emotion from the leading tag, "neutral" when the model forgot it. */
    public String emotion() {
        return emotion == null ? "neutral" : emotion;
    }

    public boolean emotionKnown() {
        return tagResolved;
    }

    public void accept(String delta) {
        buf.append(delta);
        if (!tagResolved) {
            String s = buf.toString();
            String trimmed = s.stripLeading();
            if (trimmed.isEmpty()) {
                return;
            }
            char c = trimmed.charAt(0);
            if (c == '[' || c == '(' || c == '<' || c == '*') {
                Matcher m = LEADING_TAG.matcher(s);
                if (m.find()) {
                    emotion = normalizeEmotion(m.group(1));
                    buf.delete(0, m.end());
                    tagResolved = true;
                } else if (trimmed.length() < 24) {
                    return; // tag not complete yet
                } else {
                    tagResolved = true;
                }
            } else {
                tagResolved = true;
            }
        }
        drain(false);
    }

    public void finish() {
        drain(true);
    }

    private void drain(boolean all) {
        while (true) {
            int cut = findBoundary();
            if (cut < 0) {
                break;
            }
            emit(buf.substring(0, cut));
            buf.delete(0, cut);
        }
        if (all && !buf.isEmpty()) {
            emit(buf.toString());
            buf.setLength(0);
        }
    }

    /** Index just after a sentence end, or -1. The first sentence may be cut at a comma to start speaking sooner. */
    private int findBoundary() {
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?' || c == '\n' || c == '…';
            boolean earlyComma = emitted == 0 && (c == ',' || c == ';' || c == ':' || c == '—') && i >= 40;
            if (!end && !earlyComma) {
                continue;
            }
            // Need to see the next char to know the sentence really ended ("3.5", "Mr.", "...").
            if (i + 1 >= buf.length()) {
                return -1;
            }
            char next = buf.charAt(i + 1);
            if (end && (next == '.' || next == '!' || next == '?' || next == '"' || next == '\'' || next == ')')) {
                continue;
            }
            if (!Character.isWhitespace(next)) {
                continue;
            }
            // "3.5 emeralds... maybe" - an ellipsis followed by a lowercase word is a pause, not a sentence end.
            if (c == '.' && i > 0 && buf.charAt(i - 1) == '.') {
                int j = i + 1;
                while (j < buf.length() && Character.isWhitespace(buf.charAt(j))) {
                    j++;
                }
                if (j >= buf.length()) {
                    return -1; // can't tell yet
                }
                if (Character.isLowerCase(buf.charAt(j))) {
                    continue;
                }
            }
            if (i + 1 < MIN_FIRST_CHARS && emitted == 0 && c != '\n') {
                continue;
            }
            return i + 1;
        }
        return -1;
    }

    private void emit(String raw) {
        String s = clean(raw);
        if (s.length() < 2 || !s.chars().anyMatch(Character::isLetter)) {
            return;
        }
        emitted++;
        onSentence.accept(s);
    }

    public static String clean(String raw) {
        String s = ANY_TAG.matcher(raw).replaceAll(" ");
        s = s.replace("**", "");                          // bold is emphasis, keep the words
        s = STAGE_DIRECTION.matcher(s).replaceAll(" ");   // *waves*, (sighs)
        s = s.replace("*", "");
        s = NON_SPEAKABLE.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** Whole reply at once (used for tests and non-streaming paths). */
    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        SentenceStream ss = new SentenceStream(out::add);
        ss.accept(text);
        ss.finish();
        return out;
    }

    static String normalizeEmotion(String raw) {
        String e = raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        if (EMOTIONS.contains(e)) {
            return e;
        }
        return switch (e) {
            case "joy", "joyful", "cheerful", "glad", "pleased", "warm", "friendly" -> "happy";
            case "mad", "furious", "grumpy", "irritated" -> "annoyed";
            case "laughing", "amused", "chuckle", "chuckles" -> "laugh";
            case "afraid", "fear", "fearful", "nervous", "worried", "anxious" -> "scared";
            case "shocked", "astonished", "amazed" -> "surprised";
            case "unhappy", "gloomy", "melancholy" -> "sad";
            case "tired", "yawn", "drowsy" -> "sleepy";
            case "curious", "puzzled", "thinking", "thoughtful" -> "confused";
            case "enthusiastic", "eager", "thrilled" -> "excited";
            default -> "neutral";
        };
    }
}
