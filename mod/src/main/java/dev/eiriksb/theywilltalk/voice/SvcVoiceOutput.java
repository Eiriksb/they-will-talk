package dev.eiriksb.theywilltalk.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.audio.AudioDsp;
import dev.eiriksb.theywilltalk.audio.FrameQueue;
import dev.eiriksb.theywilltalk.audio.VoiceOutput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Villager voices through Simple Voice Chat entity audio channels (positional, follows the villager). */
final class SvcVoiceOutput implements VoiceOutput {
    static final String CATEGORY = "villagers";
    /** How long a stream keeps playing silence while waiting for the next sentence before giving up. */
    private static final int MAX_WAIT_FRAMES = 50 * 12;

    private static final java.nio.file.Path DUMP = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs").resolve("theywilltalk").resolve("voice-dump");

    private static short[] toShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        }
        return s;
    }

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
        private final FrameQueue frames = new FrameQueue();
        private final AudioPlayer player;
        private volatile boolean done;
        private int waited;

        Stream(EntityAudioChannel channel) {
            // AUDIO, not VOIP: the voices are clean full-band speech, which VOIP mode filters and thins out for noisy mics.
            this.player = api.createAudioPlayer(channel, api.createEncoder(OpusEncoderMode.AUDIO), this::next);
            this.player.setOnStopped(() -> done = true);
        }

        /** with a {@code logs/theywilltalk/voice-dump} folder (diagnosing sound): everything handed to Simple Voice Chat */
        private final java.io.ByteArrayOutputStream dump = java.nio.file.Files.isDirectory(DUMP) ? new java.io.ByteArrayOutputStream() : null;

        private short[] next() {
            short[] f = frames.poll();
            if (f == null && !frames.ended() && ++waited <= MAX_WAIT_FRAMES) {
                f = new short[AudioDsp.FRAME]; // keep the stream open while the next sentence renders
            } else if (f != null) {
                waited = 0;
            }
            if (dump != null) {
                if (f != null) {
                    for (short v : f) {
                        dump.write(v & 0xFF);
                        dump.write((v >> 8) & 0xFF);
                    }
                } else if (dump.size() > 0) {
                    try {
                        java.nio.file.Files.write(DUMP.resolve(System.currentTimeMillis() + ".wav"),
                                dev.eiriksb.theywilltalk.audio.Wav.encode(toShorts(dump.toByteArray()), AudioDsp.SVC_RATE));
                    } catch (java.io.IOException e) {
                        TheyWillTalk.LOGGER.warn("Couldn't save the voice dump", e);
                    }
                    dump.reset();
                }
            }
            return f;
        }

        @Override
        public void push(short[] pcm48k) {
            if (done) {
                return;
            }
            frames.push(pcm48k);
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
            frames.finish();
            if (!player.isStarted()) {
                done = true;
            }
        }

        @Override
        public void cancel() {
            frames.clear();
            frames.finish();
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
            return frames.queued() * 20L;
        }
    }
}
