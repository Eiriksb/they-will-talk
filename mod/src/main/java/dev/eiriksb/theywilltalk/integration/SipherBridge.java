package dev.eiriksb.theywilltalk.integration;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import io.github.eiriksb.sipher.api.PlayerCaptionEvent;
import io.github.eiriksb.sipher.api.SipherCaptions;
import io.github.eiriksb.sipher.net.CaptionUpdatePayload;
import io.github.eiriksb.sipher.server.CaptionRelay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voice input: Sipher transcribes each player's microphone on their own client, translates it to English and sends
 * the caption to the server, which posts it as a {@link PlayerCaptionEvent}. Finished lines go to the villagers, in
 * English (what villagers speak) whenever Sipher could translate them. Only loaded when Sipher is installed.
 */
public final class SipherBridge {
    private static volatile long linesHeard;
    private static int relayLine;
    private static final AtomicInteger captionLines = new AtomicInteger();
    private static volatile boolean captionsUnavailable;

    private SipherBridge() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SipherBridge::onCaption);
    }

    public static long linesHeard() {
        return linesHeard;
    }

    /** Server thread. */
    private static void onCaption(PlayerCaptionEvent event) {
        if (event.isPartial()) {
            return;
        }
        // Sipher leaves the English empty when the player spoke English (or no translation was available).
        String line = event.getEnglish().isEmpty() ? event.getText() : event.getEnglish();
        ConversationManager conversations = TheyWillTalk.conversations();
        if (line.isEmpty() || conversations == null) {
            return;
        }
        linesHeard++;
        ServerPlayer player = event.getPlayer();
        if (TwtConfig.IGNORE_GROUP_SPEECH.get() && TheyWillTalk.inVoiceGroup(player)) {
            return;
        }
        conversations.hear(player, line, ConversationManager.Channel.VOICE, event.getText(), event.getLanguage());
    }

    /**
     * Shows a villager's line as a Sipher bubble above them to the players in earshot; each player's Sipher translates it
     * into their reading language when they have the pack. Server thread.
     */
    public static void caption(Entity speaker, String language, String text, double range) {
        if (captionsUnavailable) {
            return;
        }
        List<ServerPlayer> listeners = ((ServerLevel) speaker.level()).players().stream()
                .filter(p -> p.distanceTo(speaker) <= range).map(p -> (ServerPlayer) p).toList();
        if (listeners.isEmpty()) {
            return;
        }
        try {
            SipherCaptions.show(speaker, captionLines.incrementAndGet(), false, language, text, "", listeners);
        } catch (LinkageError e) {
            captionsUnavailable = true;
            TheyWillTalk.LOGGER.info("This Sipher version can't show captions for villagers; update Sipher to get speech bubbles");
        }
    }

    /**
     * Testing aid without a microphone: a live and a final caption through Sipher's own server relay, exactly as if
     * the player's client had sent them, so nearby players' captions and the villager reply all run for real.
     */
    public static void relay(ServerPlayer player, String language, String text, String english) {
        int line = 900_000 + ++relayLine;
        CaptionRelay.handle(player, new CaptionUpdatePayload(line, true, language, text, english));
        CaptionRelay.handle(player, new CaptionUpdatePayload(line, false, language, text, english));
    }
}
