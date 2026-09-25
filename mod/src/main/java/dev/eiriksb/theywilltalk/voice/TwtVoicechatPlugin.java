package dev.eiriksb.theywilltalk.voice;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.audio.VoiceOutput;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat plugin. Found by SVC through the {@link ForgeVoicechatPlugin} annotation, only when SVC is
 * installed, so the rest of the mod never touches SVC classes directly.
 */
@ForgeVoicechatPlugin
public final class TwtVoicechatPlugin implements VoicechatPlugin {
    /** Players currently talking in a voice chat group (their speech isn't meant for villagers). */
    static final Map<UUID, Long> GROUP_SPEAKERS = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return TheyWillTalk.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        TheyWillTalk.LOGGER.info("Simple Voice Chat detected: villagers will speak out loud");
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, this::onStarted);
        reg.registerEvent(VoicechatServerStoppedEvent.class, e -> TheyWillTalk.setVoiceOutput(VoiceOutput.NONE));
        reg.registerEvent(MicrophonePacketEvent.class, this::onMic, -100);
    }

    private void onStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();
        VolumeCategory category = api.volumeCategoryBuilder()
                .setId(SvcVoiceOutput.CATEGORY)
                .setName("Villagers")
                .setDescription("Talking villagers (They Will Talk)")
                .setIcon(VillagerIcon.pixels())
                .build();
        api.registerVolumeCategory(category);
        // SVC loads and validates its native Opus library on the first encoder; do that once here, because several
        // villagers starting to talk at the same moment would otherwise race on extracting the library.
        try {
            api.createEncoder(de.maxhenkel.voicechat.api.opus.OpusEncoderMode.VOIP).close();
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Opus warm-up failed: {}", e.toString());
        }
        TheyWillTalk.setVoiceOutput(new SvcVoiceOutput(api));
    }

    /** Only used to know who's speaking into a group; transcription itself happens in EN Translator. */
    private void onMic(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender != null && sender.isInGroup()) {
            GROUP_SPEAKERS.put(sender.getPlayer().getUuid(), System.currentTimeMillis());
        }
    }

    public static boolean recentlySpokeInGroup(UUID player) {
        Long t = GROUP_SPEAKERS.get(player);
        return t != null && System.currentTimeMillis() - t < 15_000;
    }
}
