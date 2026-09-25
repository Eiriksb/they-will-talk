package dev.eiriksb.theywilltalk.audio;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Where villager speech audio goes. The real implementation streams through a Simple Voice Chat entity channel
 * so the voice is positional and follows the villager; without Simple Voice Chat the {@link #NONE} output is used
 * and villagers only speak through subtitles.
 */
public interface VoiceOutput {
    VoiceOutput NONE = new VoiceOutput() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public SpeechStream open(Entity speaker, float distance) {
            return SpeechStream.NOOP;
        }

        @Override
        public boolean canHear(ServerPlayer player) {
            return false;
        }
    };

    boolean available();

    /** Opens a stream of 48 kHz mono audio coming out of {@code speaker}. */
    SpeechStream open(Entity speaker, float distance);

    /** Whether this player has voice chat installed and connected. */
    boolean canHear(ServerPlayer player);

    interface SpeechStream {
        SpeechStream NOOP = new SpeechStream() {
            @Override public void push(short[] pcm48k) { }
            @Override public void finish() { }
            @Override public void cancel() { }
            @Override public boolean isDone() { return true; }
            @Override public long queuedMs() { return 0; }
        };

        /** Queues audio; playback starts immediately and continues seamlessly as more is pushed. */
        void push(short[] pcm48k);

        /** No more audio is coming; the stream ends once the queue has played out. */
        void finish();

        /** Stops immediately (villager was interrupted). */
        void cancel();

        boolean isDone();

        /** Audio queued but not yet played. */
        long queuedMs();
    }
}
