package dev.eiriksb.theywilltalk.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP text-to-speech server wrapping sherpa-onnx.
 *
 * <pre>
 *   GET  /health            -> {"ok":true,...}
 *   GET  /voices            -> [{"id":"kokoro/af_heart", ...}]
 *   POST /tts               -> body {"text","voice","speed","lang"}; returns raw s16le mono PCM
 *                              (headers X-Sample-Rate, X-Duration-Ms, X-Gen-Ms); add ?format=wav for a WAV file
 * </pre>
 *
 * Every engine lives in its own sub directory of {@code --models}; see {@link EngineLoader}.
 */
public final class VoiceServer {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, TtsEngine> engines;

    private VoiceServer(Map<String, TtsEngine> engines) {
        this.engines = engines;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String host = opts.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(opts.getOrDefault("port", "8786"));
        Path models = Paths.get(opts.getOrDefault("models", "models/tts"));
        int threads = Integer.parseInt(opts.getOrDefault("threads", "2"));
        String provider = opts.getOrDefault("provider", "cpu");

        long t0 = System.currentTimeMillis();
        Path cache = Paths.get(opts.getOrDefault("cache", Paths.get(System.getProperty("java.io.tmpdir"), "theywilltalk-voice").toString()));
        Map<String, TtsEngine> engines = EngineLoader.loadAll(models, cache, threads, provider);
        if (engines.isEmpty()) {
            System.err.println("No TTS engines found under " + models.toAbsolutePath());
            System.exit(2);
        }
        // Warm up each engine once so the first villager line isn't slow.
        for (TtsEngine engine : engines.values()) {
            try {
                VoiceInfo first = engine.voices().getFirst();
                engine.synthesize("Hello there.", first.sid(), 1.0f, engine.espeakLang(first.lang(), first));
            } catch (Exception e) {
                System.err.println("Warm-up failed for " + engine.id() + ": " + e);
            }
        }

        VoiceServer server = new VoiceServer(engines);
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 32);
        http.setExecutor(Executors.newFixedThreadPool(Math.max(2, engines.size() * 2)));
        http.createContext("/health", server::health);
        http.createContext("/voices", server::voices);
        http.createContext("/tts", server::tts);
        http.start();
        // The mod's process supervisor waits for this exact line.
        System.out.println("TWT-VOICE READY port=" + port + " engines=" + engines.keySet()
                + " loadMs=" + (System.currentTimeMillis() - t0));
        System.out.flush();
    }

    private void health(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        JsonObject e = new JsonObject();
        engines.forEach((id, engine) -> e.addProperty(id, engine.voices().size()));
        o.add("engines", e);
        send(ex, 200, "application/json", GSON.toJson(o).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private void voices(HttpExchange ex) throws IOException {
        List<VoiceInfo> all = new ArrayList<>();
        engines.values().forEach(engine -> all.addAll(engine.voices()));
        send(ex, 200, "application/json", GSON.toJson(all).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private void tts(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only".getBytes(StandardCharsets.UTF_8), Map.of());
            return;
        }
        try {
            TtsRequest req = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), TtsRequest.class);
            if (req == null || req.text == null || req.text.isBlank()) {
                send(ex, 400, "text/plain", "missing text".getBytes(StandardCharsets.UTF_8), Map.of());
                return;
            }
            Resolved r = resolve(req.voice, req.lang);
            float speed = req.speed <= 0 ? 1.0f : Math.clamp(req.speed, 0.5f, 2.0f);
            long t0 = System.nanoTime();
            Audio audio = r.engine.synthesize(req.text, r.voice.sid(), speed, r.lang);
            long genMs = (System.nanoTime() - t0) / 1_000_000;
            byte[] pcm = audio.toPcm16();
            boolean wav = ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("format=wav");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Sample-Rate", Integer.toString(audio.sampleRate()));
            headers.put("X-Duration-Ms", Long.toString(audio.durationMs()));
            headers.put("X-Gen-Ms", Long.toString(genMs));
            headers.put("X-Voice", r.voice.id());
            if (wav) {
                send(ex, 200, "audio/wav", Audio.wrapWav(pcm, audio.sampleRate()), headers);
            } else {
                send(ex, 200, "application/octet-stream", pcm, headers);
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8), Map.of());
        }
    }

    private record Resolved(TtsEngine engine, VoiceInfo voice, String lang) {}

    /**
     * Picks the engine + voice for a request. Voice ids look like {@code kokoro/af_heart}; when the requested
     * language isn't supported by that voice's engine, falls back to any engine that supports it.
     */
    private Resolved resolve(String voiceId, String lang) {
        String wantLang = lang == null || lang.isBlank() ? null : lang.toLowerCase();
        VoiceInfo voice = null;
        TtsEngine engine = null;
        if (voiceId != null && voiceId.contains("/")) {
            engine = engines.get(voiceId.substring(0, voiceId.indexOf('/')));
            if (engine != null) {
                voice = engine.voices().stream().filter(v -> v.id().equals(voiceId)).findFirst().orElse(null);
            }
        }
        if (engine == null) {
            engine = engines.values().iterator().next();
        }
        if (voice == null) {
            voice = engine.voices().getFirst();
        }
        if (wantLang != null && !engine.supportsLanguage(wantLang)) {
            for (TtsEngine other : engines.values()) {
                if (other.supportsLanguage(wantLang)) {
                    VoiceInfo pick = other.bestVoice(wantLang, voice.gender(), voice.id().hashCode());
                    return new Resolved(other, pick, other.espeakLang(wantLang, pick));
                }
            }
            wantLang = null; // unsupported everywhere: speak with the voice's native language
        }
        String espeak = engine.espeakLang(wantLang == null ? voice.lang() : wantLang, voice);
        if (wantLang != null && !"multi".equals(voice.lang()) && !voice.lang().startsWith(baseLang(wantLang))) {
            // Same engine supports the language but this voice has another accent; pick a matching voice.
            voice = engine.bestVoice(wantLang, voice.gender(), voice.id().hashCode());
            espeak = engine.espeakLang(wantLang, voice);
        }
        return new Resolved(engine, voice, espeak);
    }

    static String baseLang(String lang) {
        int i = lang.indexOf('-');
        return i < 0 ? lang : lang.substring(0, i);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, Map<String, String> headers) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        headers.forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String val = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
                m.put(key, val);
            }
        }
        return m;
    }

    static final class TtsRequest {
        String text;
        String voice;
        float speed = 1.0f;
        String lang;
    }

    static boolean exists(Path p) {
        return p != null && Files.exists(p);
    }
}
