package dev.eiriksb.theywilltalk.ai;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Client for qwentts.cpp's OpenAI-compatible {@code tts-server} running Qwen3-TTS on the GPU. Audio streams back as
 * 24 kHz s16le PCM while it's being generated, so a villager starts talking after the first few frames.
 *
 * The villager's voice and mood travel in {@code instructions} (VoiceDesign model): "An elderly man ... Right now
 * they are angry: sharp, loud, forceful."
 */
public final class QwenTtsClient {
    public static final int SAMPLE_RATE = 24_000;

    public interface PcmSink {
        /** @return false to stop (villager interrupted) */
        boolean accept(short[] pcm24k);
    }

    public record Result(long firstAudioMs, long totalMs, long samples) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Supplier<String> baseUrl;

    public QwenTtsClient(Supplier<String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean available() {
        return baseUrl.get() != null;
    }

    public Result stream(String text, String instructions, String voice, long seed, PcmSink sink) throws IOException, InterruptedException {
        String base = baseUrl.get();
        if (base == null) {
            throw new IOException("Qwen3-TTS server is not running");
        }
        JsonObject body = new JsonObject();
        body.addProperty("input", text);
        body.addProperty("response_format", "pcm");
        body.addProperty("language", "english");
        if (instructions != null && !instructions.isBlank()) {
            body.addProperty("instructions", instructions);
        }
        if (voice != null && !voice.isBlank()) {
            body.addProperty("voice", voice);
        }
        if (seed >= 0) {
            body.addProperty("seed", seed); // same seed per villager keeps their designed voice consistent
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/v1/audio/speech"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        long t0 = System.nanoTime();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("Qwen3-TTS HTTP " + resp.statusCode() + ": " + new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        long first = -1;
        long samples = 0;
        byte[] buf = new byte[4800]; // 100 ms
        int carry = -1;              // odd trailing byte from the previous read
        try (InputStream in = resp.body()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (first < 0) {
                    first = (System.nanoTime() - t0) / 1_000_000;
                }
                // Reads can split a 16-bit sample; carry the odd byte into the next read.
                byte[] data = buf;
                int len = n;
                if (carry >= 0) {
                    data = new byte[n + 1];
                    data[0] = (byte) carry;
                    System.arraycopy(buf, 0, data, 1, n);
                    len = n + 1;
                    carry = -1;
                }
                short[] pcm = new short[len / 2];
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) ((data[2 * i] & 0xFF) | (data[2 * i + 1] << 8));
                }
                if ((len & 1) == 1) {
                    carry = data[len - 1] & 0xFF;
                }
                samples += pcm.length;
                if (pcm.length > 0 && !sink.accept(pcm)) {
                    break;
                }
            }
        }
        return new Result(first, (System.nanoTime() - t0) / 1_000_000, samples);
    }
}
