package dev.eiriksb.theywilltalk.audio;

import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.QwenTtsClient;
import dev.eiriksb.theywilltalk.ai.TtsClient;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import dev.eiriksb.theywilltalk.villager.VoiceDesign;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Turns a villager line into 48 kHz voice-chat audio with the best engine available:
 * <ul>
 *   <li><b>Qwen3-TTS</b> (GPU): the villager's designed voice acts out the emotion itself; streamed, so the villager
 *   starts talking after the first few frames.</li>
 *   <li><b>Kokoro / Supertonic</b> (CPU voice server): a cast voice with pitch/speed/volume mood shaping.</li>
 * </ul>
 */
public final class SpeechRenderer {
    private final TtsClient voiceServer;
    private final QwenTtsClient qwen;

    public SpeechRenderer(TtsClient voiceServer, QwenTtsClient qwen) {
        this.voiceServer = voiceServer;
        this.qwen = qwen;
    }

    /** Qwen3-TTS is running and not switched off in the config. */
    public boolean expressive() {
        String engine = TwtConfig.TTS_ENGINE.get();
        return qwen.available() && !engine.equalsIgnoreCase("kokoro") && !engine.equalsIgnoreCase("supertonic");
    }

    public static String designOf(VillagerProfile p, VillagerFacts f) {
        return p.voiceDesign() != null && !p.voiceDesign().isBlank() ? p.voiceDesign() : VoiceDesign.base(p, f);
    }

    public static long seedOf(VillagerProfile p) {
        return p.uuid().getMostSignificantBits() & 0x7fff_ffffL;
    }

    /**
     * Speaks {@code text} into {@code stream} (streaming when the engine allows it).
     *
     * @return milliseconds of audio pushed
     */
    public long speak(VillagerProfile p, VillagerFacts f, String text, String emotion, boolean firstLine,
                      VoiceOutput.SpeechStream stream, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (expressive()) {
            long[] pushed = {0};
            try {
                StreamingResampler rs = new StreamingResampler(QwenTtsClient.SAMPLE_RATE, AudioDsp.SVC_RATE);
                boolean[] started = {false};
                qwen.stream(text, VoiceDesign.forLine(designOf(p, f), emotion), null, seedOf(p), pcm -> {
                    if (cancelled.getAsBoolean()) {
                        return false;
                    }
                    short[] out = rs.push(pcm, false);
                    if (!started[0] && out.length > 0) {
                        fadeIn(out);
                        started[0] = true;
                    }
                    stream.push(out);
                    pushed[0] += out.length;
                    return true;
                });
                short[] tail = rs.push(null, true);
                stream.push(tail);
                pushed[0] += tail.length;
                return pushed[0] * 1000L / AudioDsp.SVC_RATE;
            } catch (IOException e) {
                if (pushed[0] > 0) {
                    return pushed[0] * 1000L / AudioDsp.SVC_RATE; // broke off mid-sentence: don't start it over
                }
                TheyWillTalk.LOGGER.debug("Qwen3-TTS failed, falling back to the voice server: {}", e.toString());
            }
        }
        Prosody prosody = Prosody.forLine(p.pitch(), p.speed(), emotion, f.moodLevel);
        TtsClient.Speech speech = voiceServer.synthesize(text, p.voice(), prosody.ttsSpeed());
        short[] pcm = prosody.render(speech.pcm(), speech.sampleRate());
        if (firstLine && prosody.leadInMs() > 0) {
            stream.push(AudioDsp.silence(prosody.leadInMs()));
        }
        stream.push(pcm);
        return pcm.length * 1000L / AudioDsp.SVC_RATE;
    }

    /** A whole line as 48 kHz PCM (ambient chatter, dashboard previews). */
    public short[] render(VillagerProfile p, VillagerFacts f, String text, String emotion) throws IOException, InterruptedException {
        Collector c = new Collector();
        speak(p, f, text, emotion, false, c, () -> false);
        return c.pcm();
    }

    /** Dashboard voice lab: try a voice description directly. */
    public short[] renderDesign(String design, String emotion, String text, long seed) throws IOException, InterruptedException {
        if (!qwen.available()) {
            throw new IOException("Qwen3-TTS is not running");
        }
        StreamingResampler rs = new StreamingResampler(QwenTtsClient.SAMPLE_RATE, AudioDsp.SVC_RATE);
        Collector c = new Collector();
        qwen.stream(text, VoiceDesign.forLine(design, emotion), null, seed, pcm -> {
            c.push(rs.push(pcm, false));
            return true;
        });
        c.push(rs.push(null, true));
        return c.pcm();
    }

    private static void fadeIn(short[] pcm) {
        int n = Math.min(96, pcm.length);
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) (pcm[i] * (i / (double) n));
        }
    }

    /** A SpeechStream that just collects the audio. */
    private static final class Collector implements VoiceOutput.SpeechStream {
        private short[] buf = new short[48_000];
        private int len;

        @Override
        public void push(short[] pcm) {
            if (len + pcm.length > buf.length) {
                buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, len + pcm.length));
            }
            System.arraycopy(pcm, 0, buf, len, pcm.length);
            len += pcm.length;
        }

        short[] pcm() {
            return java.util.Arrays.copyOf(buf, len);
        }

        @Override public void finish() { }
        @Override public void cancel() { }
        @Override public boolean isDone() { return true; }
        @Override public long queuedMs() { return 0; }
    }
}
