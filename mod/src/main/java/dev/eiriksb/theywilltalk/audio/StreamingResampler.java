package dev.eiriksb.theywilltalk.audio;

/**
 * Windowed-sinc resampler that accepts audio in chunks (e.g. while a TTS engine is still generating) and returns
 * every output sample whose filter window is already complete, so nothing clicks at chunk boundaries.
 * Pitch shifting works the same way as in {@link AudioDsp#resample}: pass {@code srcRate * pitch}.
 */
public final class StreamingResampler {
    private static final int HALF_TAPS = 12;
    private static final int RES = 256;

    private final double ratio;
    private final double[] table;
    private short[] in = new short[4096];
    private int inLen;
    private long produced;

    public StreamingResampler(double srcRate, double dstRate) {
        this.ratio = srcRate / dstRate;
        double cutoff = Math.min(1.0, 1.0 / ratio) * 0.97; // low-pass when downsampling
        table = new double[2 * HALF_TAPS * RES + 2];
        for (int j = 0; j < table.length; j++) {
            double x = (double) j / RES - HALF_TAPS;
            table[j] = sinc(x * cutoff) * blackman(x / HALF_TAPS);
        }
    }

    /** Adds input and returns the output that can be produced so far. {@code end} flushes everything. */
    public short[] push(short[] chunk, boolean end) {
        if (chunk != null && chunk.length > 0) {
            if (inLen + chunk.length > in.length) {
                short[] grown = new short[Math.max(in.length * 2, inLen + chunk.length)];
                System.arraycopy(in, 0, grown, 0, inLen);
                in = grown;
            }
            System.arraycopy(chunk, 0, in, inLen, chunk.length);
            inLen += chunk.length;
        }
        long available = end ? (long) Math.floor(inLen / ratio) : (long) Math.floor((inLen - HALF_TAPS - 1) / ratio);
        if (available <= produced) {
            return new short[0];
        }
        short[] out = new short[(int) (available - produced)];
        for (int o = 0; o < out.length; o++) {
            out[o] = sample(produced + o);
        }
        produced = available;
        return out;
    }

    private short sample(long outIndex) {
        double t = outIndex * ratio;
        int center = (int) Math.floor(t);
        double acc = 0;
        double norm = 0;
        for (int k = center - HALF_TAPS + 1; k <= center + HALF_TAPS; k++) {
            if (k < 0 || k >= inLen) {
                continue;
            }
            double pos = (t - k + HALF_TAPS) * RES;
            int idx = (int) pos;
            if (idx < 0 || idx >= table.length - 1) {
                continue;
            }
            double frac = pos - idx;
            double w = table[idx] + (table[idx + 1] - table[idx]) * frac;
            acc += in[k] * w;
            norm += w;
        }
        return AudioDsp.clampShort(norm > 1e-9 ? acc / norm : 0);
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-9) {
            return 1.0;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double blackman(double x) {
        if (x <= -1 || x >= 1) {
            return 0;
        }
        double n = (x + 1) / 2;
        return 0.42 - 0.5 * Math.cos(2 * Math.PI * n) + 0.08 * Math.cos(4 * Math.PI * n);
    }
}
