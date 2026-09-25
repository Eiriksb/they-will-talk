package dev.eiriksb.theywilltalk.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Client for the bundled voice server (see the voice-server project). */
public final class TtsClient {
    public record Voice(String id, String engine, String name, String gender, String lang, String traits) {}

    public record Speech(short[] pcm, int sampleRate, long genMs, String voiceId) {
        public long durationMs() {
            return pcm.length * 1000L / Math.max(1, sampleRate);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Supplier<String> baseUrl;
    private volatile List<Voice> voices = List.of();

    public TtsClient(Supplier<String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<Voice> voices() {
        if (voices.isEmpty()) {
            refreshVoices();
        }
        return voices;
    }

    public void refreshVoices() {
        String base = baseUrl.get();
        if (base == null) {
            return;
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/voices")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return;
            }
            List<Voice> list = new ArrayList<>();
            for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                list.add(new Voice(o.get("id").getAsString(), o.get("engine").getAsString(), o.get("name").getAsString(),
                        o.get("gender").getAsString(), o.get("lang").getAsString(), o.has("traits") ? o.get("traits").getAsString() : ""));
            }
            voices = List.copyOf(list);
        } catch (Exception ignored) {
            // voice server still starting
        }
    }

    public Speech synthesize(String text, String voice, float speed) throws IOException, InterruptedException {
        String base = baseUrl.get();
        if (base == null) {
            throw new IOException("voice server is not running");
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("voice", voice);
        body.addProperty("speed", speed);
        body.addProperty("lang", "en");
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/tts"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("TTS HTTP " + r.statusCode() + ": " + new String(r.body(), StandardCharsets.UTF_8));
        }
        int rate = Integer.parseInt(r.headers().firstValue("X-Sample-Rate").orElse("24000"));
        long gen = Long.parseLong(r.headers().firstValue("X-Gen-Ms").orElse("0"));
        String used = r.headers().firstValue("X-Voice").orElse(voice);
        short[] pcm = new short[r.body().length / 2];
        ByteBuffer.wrap(r.body()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return new Speech(pcm, rate, gen, used);
    }

    /** WAV bytes for the dashboard's "preview voice" button. */
    public byte[] previewWav(String text, String voice, float speed) throws IOException, InterruptedException {
        String base = baseUrl.get();
        if (base == null) {
            throw new IOException("voice server is not running");
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("voice", voice);
        body.addProperty("speed", speed);
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/tts?format=wav"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("TTS HTTP " + r.statusCode());
        }
        return r.body();
    }

    public static JsonArray toJson(List<Voice> voices) {
        JsonArray arr = new JsonArray();
        for (Voice v : voices) {
            JsonObject o = new JsonObject();
            o.addProperty("id", v.id());
            o.addProperty("engine", v.engine());
            o.addProperty("name", v.name());
            o.addProperty("gender", v.gender());
            o.addProperty("traits", v.traits());
            arr.add(o);
        }
        return arr;
    }
}
