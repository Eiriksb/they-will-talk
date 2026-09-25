package dev.eiriksb.theywilltalk.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Just enough WAV (RIFF) reading to check an uploaded voice clip. */
public final class Wav {
    private Wav() {}

    /** Duration of an uncompressed WAV file. */
    public static double seconds(byte[] wav) {
        if (wav.length < 12 || !ascii(wav, 0).equals("RIFF") || !ascii(wav, 8).equals("WAVE")) {
            throw new IllegalArgumentException("not a WAV file");
        }
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int format = 0;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        long data = -1;
        long pos = 12;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, (int) pos);
            long size = b.getInt((int) pos + 4) & 0xffffffffL;
            if (id.equals("fmt ") && pos + 24 <= wav.length) {
                format = b.getShort((int) pos + 8) & 0xffff;
                channels = b.getShort((int) pos + 10) & 0xffff;
                rate = b.getInt((int) pos + 12);
                bits = b.getShort((int) pos + 22) & 0xffff;
            } else if (id.equals("data")) {
                data = Math.min(size, wav.length - pos - 8);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (data < 0 || rate <= 0 || channels <= 0 || bits < 8) {
            throw new IllegalArgumentException("not a readable WAV file");
        }
        if (format != 1 && format != 3 && format != 0xFFFE) {
            throw new IllegalArgumentException("only uncompressed (PCM) WAV files work");
        }
        return data / (double) ((long) rate * channels * (bits / 8));
    }

    /** A 16-bit mono WAV file. */
    public static byte[] encode(short[] pcm, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short s : pcm) {
            b.putShort(s);
        }
        return b.array();
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, StandardCharsets.US_ASCII);
    }
}
