package dev.eiriksb.theywilltalk.web;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.runtime.RuntimeManager;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** The dashboard's Settings page: every option in config/theywilltalk-common.toml, read from the config spec. */
final class Settings {
    /** Options the dashboard only shows: changing them could lock the admin out or point the server at other folders or hosts. */
    private static final Set<String> READ_ONLY = Set.of("runtime.runtimeDir", "runtime.externalLlmUrl", "dashboard.enabled",
            "dashboard.bindAddress", "dashboard.port", "dashboard.bluemapUrl");
    /** Options the AI programs read when they start. */
    private static final Set<String> AI_RESTART = Set.of("runtime.llmModel", "runtime.gpuLayers", "runtime.contextSize",
            "runtime.parallelSlots", "runtime.qwenTtsModel", "runtime.ttsThreads", "runtime.processNice");
    /** Words written differently from plain lower case in labels. */
    private static final Map<String, String> WORDS = Map.of("llm", "LLM", "tts", "TTS", "gpu", "GPU", "url", "URL", "mca", "MCA",
            "bluemap", "BlueMap", "minecolonies", "MineColonies", "sipher", "Sipher", "qwen", "Qwen");
    private static final Map<String, List<String>> CHOICES = Map.of("runtime.ttsEngine", List.of("auto", "qwen3", "kokoro", "supertonic"));

    private Settings() {}

    static JsonObject json() {
        JsonArray sections = new JsonArray();
        for (UnmodifiableConfig.Entry section : TwtConfig.SPEC.getValues().entrySet()) {
            if (!(section.getRawValue() instanceof UnmodifiableConfig values)) {
                continue;
            }
            JsonObject s = new JsonObject();
            s.addProperty("id", section.getKey());
            s.addProperty("comment", TwtConfig.SPEC.getLevelComment(List.of(section.getKey())));
            JsonArray options = new JsonArray();
            for (UnmodifiableConfig.Entry option : values.entrySet()) {
                if (option.getRawValue() instanceof ModConfigSpec.ConfigValue<?> value) {
                    options.add(option(value));
                }
            }
            s.add("options", options);
            sections.add(s);
        }
        JsonObject o = new JsonObject();
        o.add("sections", sections);
        return o;
    }

    private static JsonObject option(ModConfigSpec.ConfigValue<?> value) {
        String key = String.join(".", value.getPath());
        ModConfigSpec.ValueSpec spec = value.getSpec();
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("label", label(value.getPath().getLast()));
        o.addProperty("comment", comment(spec.getComment()));
        Object current = value.get();
        o.addProperty("type", current instanceof Boolean ? "boolean" : current instanceof Integer ? "int"
                : current instanceof Number ? "double" : "string");
        o.add("value", element(current));
        o.add("default", element(value.getDefault()));
        ModConfigSpec.Range<?> range = spec.getRange();
        if (range != null) {
            o.add("min", element(range.getMin()));
            o.add("max", element(range.getMax()));
        }
        List<String> choices = CHOICES.get(key);
        if (choices != null) {
            JsonArray a = new JsonArray();
            choices.forEach(a::add);
            o.add("choices", a);
        }
        o.addProperty("readOnly", READ_ONLY.contains(key));
        o.addProperty("restartAi", AI_RESTART.contains(key));
        return o;
    }

    /**
     * Changes one option ({@code {"key": "conversation.listenRadius", "value": 10}}) and saves the config file.
     *
     * @return whether the AI programs need a restart for it to take effect
     */
    static boolean update(JsonObject body, RuntimeManager runtime) {
        String key = body.has("key") ? body.get("key").getAsString() : "";
        ModConfigSpec.ConfigValue<?> value = find(key);
        if (value == null) {
            throw new IllegalArgumentException("unknown setting " + key);
        }
        if (READ_ONLY.contains(key)) {
            throw new IllegalArgumentException(key + " can only be changed in config/theywilltalk-common.toml");
        }
        JsonElement raw = body.get("value");
        if (raw == null || !raw.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing value");
        }
        Object current = value.get();
        if (current instanceof Boolean != raw.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("not a valid value for " + key);
        }
        Object parsed;
        try {
            parsed = current instanceof Boolean ? (Object) raw.getAsBoolean()
                    : current instanceof Integer ? (Object) Math.toIntExact(raw.getAsLong())
                    : current instanceof Number ? (Object) raw.getAsDouble()
                    : raw.getAsString().trim();
        } catch (NumberFormatException | ArithmeticException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("not a valid value for " + key);
        }
        if (!value.getSpec().test(parsed)) {
            ModConfigSpec.Range<?> range = value.getSpec().getRange();
            throw new IllegalArgumentException(range != null ? label(value.getPath().getLast()) + " must be between "
                    + range.getMin() + " and " + range.getMax() : "not a valid value for " + key);
        }
        if (key.equals("runtime.ttsEngine")) {
            runtime.setTtsEngine((String) parsed);
            return false;
        }
        set(value, parsed);
        value.save();
        TheyWillTalk.LOGGER.info("Dashboard: {} = {}", key, parsed);
        return AI_RESTART.contains(key);
    }

    /** The option's comment as one paragraph, without the default and range lines NeoForge adds (the page shows those). */
    private static String comment(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("Default:") && !l.startsWith("Range:")
                && !l.startsWith("Allowed Values:")).map(l -> l.matches(".*[.!?:)]$") ? l : l + ".").collect(Collectors.joining(" "));
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(ModConfigSpec.ConfigValue<T> value, Object v) {
        value.set((T) v);
    }

    private static ModConfigSpec.ConfigValue<?> find(String key) {
        Object v = TwtConfig.SPEC.getValues().get(key);
        return v instanceof ModConfigSpec.ConfigValue<?> value ? value : null;
    }

    /** "maxRepliesPerMinute" -> "Max replies per minute", "gpuLayers" -> "GPU layers". */
    static String label(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.split("(?<=[a-z0-9])(?=[A-Z])")) {
            String lower = w.toLowerCase(Locale.ROOT);
            sb.append(sb.isEmpty() ? "" : " ").append(WORDS.getOrDefault(lower, sb.isEmpty() ? w : lower));
        }
        return Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
    }

    private static JsonElement element(Object v) {
        return v instanceof Boolean b ? new JsonPrimitive(b) : v instanceof Number n ? new JsonPrimitive(n)
                : v == null ? JsonNull.INSTANCE : new JsonPrimitive(v.toString());
    }
}
