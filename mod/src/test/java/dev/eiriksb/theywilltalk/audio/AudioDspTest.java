package dev.eiriksb.theywilltalk.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDspTest {
    private static short[] sine(int rate, double hz, double seconds, double amp) {
        short[] s = new short[(int) (rate * seconds)];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) (Math.sin(2 * Math.PI * hz * i / rate) * amp * 32767);
        }
        return s;
    }

    /** Dominant frequency by counting zero crossings. */
    private static double frequency(short[] s, int rate) {
        int crossings = 0;
        for (int i = 1; i < s.length; i++) {
            if ((s[i - 1] < 0) != (s[i] < 0)) {
                crossings++;
            }
        }
        return crossings / 2.0 / (s.length / (double) rate);
    }

    @Test
    void upsamplesKokoroRateToVoiceChatRateKeepingPitchAndLength() {
        short[] in = sine(24_000, 440, 1.0, 0.5);
        short[] out = AudioDsp.resample(in, 24_000, AudioDsp.SVC_RATE);
        assertEquals(48_000, out.length, 2);
        assertEquals(440, frequency(out, 48_000), 3);
        assertEquals(AudioDsp.rms(in), AudioDsp.rms(out), AudioDsp.rms(in) * 0.03);
    }

    @Test
    void pitchShiftRaisesFrequencyAndShortensAudio() {
        short[] in = sine(24_000, 300, 1.0, 0.5);
        short[] out = AudioDsp.resample(in, 24_000 * 1.25, AudioDsp.SVC_RATE);
        assertEquals(375, frequency(out, 48_000), 4);
        assertEquals(48_000 / 1.25, out.length, 2);
    }

    @Test
    void downsamplingSuppressesFrequenciesAboveNewNyquist() {
        short[] in = sine(48_000, 12_000, 0.5, 0.8); // above 8 kHz Nyquist of 16 kHz
        short[] out = AudioDsp.resample(in, 48_000, 16_000);
        assertTrue(AudioDsp.rms(out) < AudioDsp.rms(in) * 0.1, "aliasing tone should be filtered out");
    }

    @Test
    void framesAreExactlyWhatSimpleVoiceChatExpects() {
        short[][] frames = AudioDsp.frames(new short[2500]);
        assertEquals(3, frames.length);
        for (short[] f : frames) {
            assertEquals(960, f.length);
        }
    }

    @Test
    void gainLimiterNeverClips() {
        short[] loud = sine(48_000, 200, 0.2, 0.95);
        AudioDsp.gain(loud, 1.6);
        for (short s : loud) {
            assertTrue(Math.abs(s) < 32767);
        }
    }

    @Test
    void chunkedResamplingMatchesOneShot() {
        short[] in = sine(24_000, 523, 0.8, 0.6);
        short[] whole = AudioDsp.resample(in, 24_000, AudioDsp.SVC_RATE);
        StreamingResampler r = new StreamingResampler(24_000, AudioDsp.SVC_RATE);
        short[] joined = new short[0];
        for (int i = 0; i < in.length; i += 1777) {
            short[] part = java.util.Arrays.copyOfRange(in, i, Math.min(in.length, i + 1777));
            short[] out = r.push(part, i + 1777 >= in.length);
            short[] n = java.util.Arrays.copyOf(joined, joined.length + out.length);
            System.arraycopy(out, 0, n, joined.length, out.length);
            joined = n;
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole, joined);
    }
}
