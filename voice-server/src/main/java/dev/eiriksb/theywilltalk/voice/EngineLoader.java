package dev.eiriksb.theywilltalk.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers TTS engines. Each sub directory of the models dir holds one model and a {@code twt-engine.json}
 * describing it:
 *
 * <pre>
 * { "id": "kokoro", "type": "kokoro", "priority": 10,
 *   "languages": ["en","es","fr","hi","it","pt","ja","zh"],
 *   "voices": [ {"name":"af_heart","sid":3,"gender":"f","lang":"en-us","traits":"warm"} ... ] }
 * </pre>
 *
 * Without that file the loader guesses the type from the files present and exposes speakers as {@code sN}.
 */
final class EngineLoader {
    private EngineLoader() {}

    static Map<String, TtsEngine> loadAll(Path modelsDir, Path cacheDir, int threads, String provider) throws IOException {
        List<EngineSpec> specs = new ArrayList<>();
        if (Files.isDirectory(modelsDir)) {
            try (Stream<Path> dirs = Files.list(modelsDir)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    EngineSpec spec = describe(dir);
                    if (spec != null) {
                        specs.add(spec);
                    }
                }
            }
        }
        specs.sort((a, b) -> Integer.compare(b.priority, a.priority));
        Map<String, TtsEngine> engines = new LinkedHashMap<>();
        for (EngineSpec spec : specs) {
            if (engines.containsKey(spec.id)) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            try {
                SherpaEngine engine = new SherpaEngine(spec, cacheDir.resolve(spec.id), threads, provider);
                engines.put(spec.id, engine);
                System.out.println("Loaded TTS engine " + spec.id + " (" + spec.type + ", " + engine.voices().size()
                        + " voices) in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable t) {
                System.err.println("Failed to load TTS engine in " + spec.dir + ": " + t);
                t.printStackTrace();
            }
        }
        return engines;
    }

    static EngineSpec describe(Path dir) throws IOException {
        EngineSpec spec = new EngineSpec();
        spec.dir = dir;
        spec.id = dir.getFileName().toString();
        Path meta = dir.resolve("twt-engine.json");
        if (Files.exists(meta)) {
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            spec.id = str(o, "id", spec.id);
            spec.type = str(o, "type", null);
            spec.priority = o.has("priority") ? o.get("priority").getAsInt() : 0;
            if (o.has("languages")) {
                for (JsonElement e : o.getAsJsonArray("languages")) {
                    spec.languages.add(e.getAsString().toLowerCase());
                }
            }
            if (o.has("blends")) {
                spec.blends = o.getAsJsonArray("blends");
            }
            if (o.has("voices")) {
                JsonArray arr = o.getAsJsonArray("voices");
                for (JsonElement e : arr) {
                    JsonObject v = e.getAsJsonObject();
                    spec.voices.add(new VoiceInfo(spec.id + "/" + str(v, "name", "s" + v.get("sid").getAsInt()), spec.id,
                            v.get("sid").getAsInt(), str(v, "name", "voice"), str(v, "gender", "n"),
                            str(v, "lang", spec.languages.isEmpty() ? "en" : spec.languages.getFirst()), str(v, "traits", "")));
                }
            }
        }
        if (spec.type == null) {
            if (Files.exists(dir.resolve("voices.bin"))) {
                spec.type = "kokoro";
            } else if (Files.exists(dir.resolve("tts.json"))) {
                spec.type = "supertonic";
            } else if (Files.exists(dir.resolve("tokens.txt")) && findOnnx(dir) != null) {
                spec.type = "vits";
            } else {
                return null;
            }
        }
        if (spec.languages.isEmpty()) {
            spec.languages.add("en");
        }
        return spec;
    }

    static Path findOnnx(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".onnx")).sorted().findFirst().orElse(null);
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    static final class EngineSpec {
        Path dir;
        String id;
        String type;
        int priority;
        final List<String> languages = new ArrayList<>();
        final List<VoiceInfo> voices = new ArrayList<>();
        JsonArray blends;
    }
}
