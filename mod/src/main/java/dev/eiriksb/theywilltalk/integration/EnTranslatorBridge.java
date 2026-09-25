package dev.eiriksb.theywilltalk.integration;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives finished voice lines from EN Translator (via {@code EnTranslatorRelayMixin}) and hands the English text to
 * the conversation manager. Everything reaching the server is (translated to) English, which is what villagers speak.
 */
public final class EnTranslatorBridge {
    /** The same final line can be relayed more than once; remember the last (line id, text) per player. */
    private static final Map<UUID, String> LAST_LINE = new ConcurrentHashMap<>();
    private static volatile long transcriptsSeen;

    private EnTranslatorBridge() {}

    public static long transcriptsSeen() {
        return transcriptsSeen;
    }

    public static void onTranscript(UUID speaker, String sourceText, String translatedText, String sourceLanguage,
                                    String translationLanguage, int lineId, boolean partial) {
        if (partial || speaker == null) {
            return;
        }
        String english = pickEnglish(sourceText, translatedText, sourceLanguage, translationLanguage);
        String key = lineId + "|" + english;
        if (key.equals(LAST_LINE.put(speaker, key))) {
            return;
        }
        if (english.isBlank()) {
            return;
        }
        transcriptsSeen++;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ConversationManager conversations = TheyWillTalk.conversations();
        if (server == null || conversations == null) {
            return;
        }
        String original = sourceText == null ? "" : sourceText.trim();
        String lang = sourceLanguage == null ? "auto" : sourceLanguage;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(speaker);
            if (player == null) {
                return;
            }
            if (TwtConfig.IGNORE_GROUP_SPEECH.get() && TheyWillTalk.inVoiceGroup(player)) {
                return;
            }
            conversations.hear(player, english, ConversationManager.Channel.VOICE, original, lang);
        });
    }

    static String pickEnglish(String source, String translated, String sourceLang, String translationLang) {
        String s = source == null ? "" : source.trim();
        String t = translated == null ? "" : translated.trim();
        if (isEnglish(translationLang) && !t.isEmpty()) {
            return t;
        }
        if (isEnglish(sourceLang) && !s.isEmpty()) {
            return s;
        }
        // Unknown languages: prefer the translation, the LLM copes with the rest.
        return !t.isEmpty() ? t : s;
    }

    private static boolean isEnglish(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
    }
}
