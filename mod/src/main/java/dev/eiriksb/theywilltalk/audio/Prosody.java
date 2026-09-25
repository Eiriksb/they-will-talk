package dev.eiriksb.theywilltalk.audio;

/**
 * How a line is delivered. The villager's voice has a base pitch/speed; every line then gets an emotion-driven
 * adjustment (angry lines come out faster and lower, sad ones slower and quieter, and so on) plus a mood bias from
 * the villager's lasting mood.
 */
public record Prosody(double pitch, double speed, double gain, int leadInMs) {

    public static Prosody forLine(double basePitch, double baseSpeed, String emotion, int moodLevel) {
        double p = basePitch;
        double s = baseSpeed;
        double g = 1.0;
        int lead = 0;
        switch (emotion == null ? "neutral" : emotion) {
            case "happy" -> { p *= 1.04; s *= 1.04; g = 1.05; }
            case "excited" -> { p *= 1.08; s *= 1.10; g = 1.12; }
            case "laugh" -> { p *= 1.06; s *= 1.06; g = 1.08; }
            case "flirty" -> { p *= 1.02; s *= 0.95; }
            case "angry" -> { p *= 0.93; s *= 1.08; g = 1.18; }
            case "annoyed" -> { p *= 0.96; s *= 1.03; g = 1.08; }
            case "sad" -> { p *= 0.95; s *= 0.88; g = 0.82; lead = 250; }
            case "scared" -> { p *= 1.07; s *= 1.12; g = 0.92; }
            case "surprised" -> { p *= 1.07; s *= 1.02; g = 1.08; }
            case "confused" -> { p *= 1.01; s *= 0.95; lead = 150; }
            case "sleepy" -> { p *= 0.94; s *= 0.82; g = 0.8; lead = 300; }
            default -> { }
        }
        // Lasting mood: -15 (miserable) .. +15 (overjoyed), MCA's scale; 0 = neutral.
        double m = Math.max(-15, Math.min(15, moodLevel)) / 15.0;
        p *= 1 + 0.03 * m;
        s *= 1 + 0.05 * m;
        return new Prosody(clamp(p, 0.7, 1.5), clamp(s, 0.6, 1.6), clamp(g, 0.5, 1.6), lead);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * The TTS engine is asked for {@code speed / pitch} so that after the pitch-shifting resample (which also speeds
     * the audio up by {@code pitch}) the final tempo is exactly {@code speed}.
     */
    public float ttsSpeed() {
        return (float) clamp(speed / pitch, 0.5, 2.0);
    }

    /** Converts engine output to 48 kHz Simple Voice Chat audio with this prosody applied. */
    public short[] render(short[] pcm, int sampleRate) {
        short[] out = AudioDsp.resample(pcm, sampleRate * pitch, AudioDsp.SVC_RATE);
        AudioDsp.gain(out, gain);
        AudioDsp.fade(out, 96);
        return out;
    }
}
