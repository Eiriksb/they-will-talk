package dev.eiriksb.theywilltalk.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.audio.AudioDsp;
import dev.eiriksb.theywilltalk.audio.VoiceOutput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Villager voices through Simple Voice Chat entity audio channels (positional, follows the villager). */
final class SvcVoiceOutput implements VoiceOutput {
    static final String CATEGORY = "villagers";
    /** How long a stream keeps playing silence while waiting for the next sentence before giving up. */
    private static final int MAX_WAIT_FRAMES = 50 * 12;

    private final VoicechatServerApi api;

    SvcVoiceOutput(VoicechatServerApi api) {
        this.api = api;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean canHear(ServerPlayer player) {
        VoicechatConnection c = api.getConnectionOf(player.getUUID());
        return c != null && c.isInstalled() && c.isConnected() && !c.isDisabled();
    }

    @Override
    public SpeechStream open(Entity speaker, float distance) {
        // A stable per-villager channel id that can never collide with a player's UUID.
        UUID channelId = UUID.nameUUIDFromBytes(("theywilltalk:" + speaker.getUUID()).getBytes(StandardCharsets.UTF_8));
        EntityAudioChannel channel = api.createEntityAudioChannel(channelId, api.fromEntity(speaker));
        if (channel == null) {
            return SpeechStream.NOOP;
        }
        channel.setCategory(CATEGORY);
        channel.setDistance(distance);
        return new Stream(channel);
    }

    private final class Stream implements SpeechStream {
        private final ConcurrentLinkedQueue<short[]> frames = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queuedFrames = new AtomicInteger();
        private final AudioPlayer player;
        private volatile boolean finished;
        private volatile boolean done;
        private int waited;

        Stream(EntityAudioChannel channel) {
            this.player = api.createAudioPlayer(channel, api.createEncoder(OpusEncoderMode.VOIP), this::next);
            this.player.setOnStopped(() -> done = true);
        }

        private short[] next() {
            short[] f = frames.poll();
            if (f != null) {
                queuedFrames.decrementAndGet();
                waited = 0;
                return f;
            }
            if (finished || ++waited > MAX_WAIT_FRAMES) {
                return null;
            }
            return new short[AudioDsp.FRAME]; // keep the stream open while the next sentence renders
        }

        @Override
        public void push(short[] pcm48k) {
            if (done) {
                return;
            }
            for (short[] f : AudioDsp.frames(pcm48k)) {
                frames.add(f);
                queuedFrames.incrementAndGet();
            }
            if (!player.isStarted()) {
                try {
                    player.startPlaying();
                } catch (Exception e) {
                    TheyWillTalk.LOGGER.warn("Could not start villager audio", e);
                    done = true;
                }
            }
        }

        @Override
        public void finish() {
            finished = true;
            if (!player.isStarted()) {
                done = true;
            }
        }

        @Override
        public void cancel() {
            finished = true;
            frames.clear();
            queuedFrames.set(0);
            if (player.isStarted()) {
                player.stopPlaying();
            }
            done = true;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public long queuedMs() {
            return queuedFrames.get() * 20L;
        }
    }
}
