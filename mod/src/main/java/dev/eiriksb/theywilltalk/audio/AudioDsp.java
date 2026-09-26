package dev.eiriksb.theywilltalk.audio;

/**
 * Small, dependency-free DSP helpers: a windowed-sinc resampler (also used as a pitch shifter), gain with a soft
 * limiter, short fades and splitting into Simple Voice Chat's 20 ms / 960 sample frames.
 */
public final class AudioDsp {
    public static final int SVC_RATE = 48_000;
    public static final int FRAME = 960;

    private AudioDsp() {}

    /**
     * Resamples {@code in} (treated as {@code srcRate} Hz) to {@code dstRate}. Pitch shifting is done by lying about
     * the source rate: {@code resample(pcm, 24000 * 1.2, 48000)} plays 20 % higher (and faster).
     */
    public static short[] resample(short[] in, double srcRate, double dstRate) {
        if (in.length == 0) {
            return in;
        }
        return new StreamingResampler(srcRate, dstRate).push(in, true);
    }

    /** Gain + gentle tanh limiter so boosted lines never clip harshly. */
    public static void gain(short[] pcm, double gain) {
        if (Math.abs(gain - 1.0) < 1e-3) {
            return;
        }
        for (int i = 0; i < pcm.length; i++) {
            double v = pcm[i] / 32768.0 * gain;
            if (Math.abs(v) > 0.8) {
                v = Math.signum(v) * (0.8 + 0.2 * Math.tanh((Math.abs(v) - 0.8) / 0.2));
            }
            pcm[i] = clampShort(v * 32767.0);
        }
    }

    public static void fade(short[] pcm, int samples) {
        int n = Math.min(samples, pcm.length / 2);
        for (int i = 0; i < n; i++) {
            double f = (double) i / n;
            pcm[i] = (short) (pcm[i] * f);
            pcm[pcm.length - 1 - i] = (short) (pcm[pcm.length - 1 - i] * f);
        }
    }

    /** A censor beep: a 1 kHz tone with soft edges, like a TV bleep. */
    public static short[] beep(int ms) {
        short[] pcm = new short[SVC_RATE * ms / 1000];
        int fade = SVC_RATE * 6 / 1000;
        for (int i = 0; i < pcm.length; i++) {
            double env = Math.min(1.0, Math.min(i, pcm.length - 1 - i) / (double) fade);
            pcm[i] = (short) (Math.sin(2 * Math.PI * 1000 * i / SVC_RATE) * 0.28 * 32767 * env);
        }
        return pcm;
    }

    public static short[] silence(int ms) {
        return new short[SVC_RATE * ms / 1000];
    }

    public static short[][] frames(short[] pcm) {
        int n = (pcm.length + FRAME - 1) / FRAME;
        short[][] out = new short[n][FRAME];
        for (int f = 0; f < n; f++) {
            int from = f * FRAME;
            System.arraycopy(pcm, from, out[f], 0, Math.min(FRAME, pcm.length - from));
        }
        return out;
    }

    public static int rms(short[] pcm) {
        if (pcm.length == 0) {
            return 0;
        }
        double sum = 0;
        for (short s : pcm) {
            sum += (double) s * s;
        }
        return (int) Math.sqrt(sum / pcm.length);
    }

    static short clampShort(double v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }
}
