package dev.eiriksb.theywilltalk.conversation;

import java.util.Locale;
import java.util.Map;

/**
 * The languages villagers can answer in: the ones the Qwen3-TTS voices speak. Players speaking anything else get English
 * replies (and, with Sipher, captions translated into their language).
 */
public final class Languages {
    public static final String ENGLISH = "en";

    /** code -> {English name, Qwen3-TTS language label} */
    private static final Map<String, String[]> SPOKEN = Map.of(
            "en", new String[]{"English", "english"},
            "de", new String[]{"German", "german"},
            "fr", new String[]{"French", "french"},
            "es", new String[]{"Spanish", "spanish"},
            "it", new String[]{"Italian", "italian"},
            "pt", new String[]{"Portuguese", "portuguese"},
            "ru", new String[]{"Russian", "russian"},
            "ja", new String[]{"Japanese", "japanese"},
            "ko", new String[]{"Korean", "korean"},
            "zh", new String[]{"Chinese", "chinese"});

    private Languages() {}

    /** "pt-br" -> "pt"; null/unknown -> "". */
    public static String base(String code) {
        if (code == null) {
            return "";
        }
        String c = code.toLowerCase(Locale.ROOT).trim();
        int dash = c.indexOf('-');
        return dash > 0 ? c.substring(0, dash) : c;
    }

    /** Whether villagers' voices can speak this language. */
    public static boolean speakable(String code) {
        return SPOKEN.containsKey(base(code));
    }

    /** English name for the prompt ("German"), English when unknown. */
    public static String name(String code) {
        String[] l = SPOKEN.get(base(code));
        return l == null ? "English" : l[0];
    }

    /** Qwen3-TTS's label for the language ("german"), English when unknown. */
    public static String qwenLabel(String code) {
        String[] l = SPOKEN.get(base(code));
        return l == null ? "english" : l[1];
    }
}
