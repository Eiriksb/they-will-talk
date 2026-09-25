package dev.eiriksb.theywilltalk.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mono float audio in [-1, 1]. */
public record Audio(float[] samples, int sampleRate) {

    public long durationMs() {
        return sampleRate == 0 ? 0 : samples.length * 1000L / sampleRate;
    }

    /** Little-endian signed 16 bit PCM. */
    public byte[] toPcm16() {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            buf.putShort((short) Math.round(Math.clamp(s, -1f, 1f) * 32767f));
        }
        return buf.array();
    }

    public static byte[] wrapWav(byte[] pcm16, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm16.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm16.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm16.length).put(pcm16);
        return b.array();
    }
}
