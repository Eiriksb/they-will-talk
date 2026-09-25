package dev.eiriksb.theywilltalk.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kokoro stores one style embedding per speaker in {@code voices.bin} (float32, {@code [speakers][510][256]}) and the
 * model metadata fixes the number of speakers. We don't need the non-English speakers, so their slots are overwritten
 * with weighted blends of English voices, giving every villager a larger pool of distinct voices without touching
 * the model.
 *
 * <pre>
 * "blends": [ {"sid": 28, "name": "blend_michael_onyx", "gender": "m", "lang": "en-us", "traits": "deep warm",
 *              "mix": {"am_michael": 0.6, "am_onyx": 0.4}} ]
 * </pre>
 */
final class KokoroVoiceBlender {
    private static final int FLOATS_PER_VOICE = 510 * 256;

    private KokoroVoiceBlender() {}

    /** Writes a blended copy of voices.bin into {@code cacheDir} and returns its path, or the original if no blends. */
    static Path blend(Path voicesBin, JsonArray blends, Map<String, Integer> nameToSid, Path cacheDir,
                      List<VoiceInfo> voicesOut, String engineId) throws IOException {
        if (blends == null || blends.isEmpty()) {
            return voicesBin;
        }
        byte[] raw = Files.readAllBytes(voicesBin);
        int speakers = raw.length / 4 / FLOATS_PER_VOICE;
        float[] all = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(all);

        Map<Integer, float[]> originals = new HashMap<>();
        for (JsonElement e : blends) {
            JsonObject b = e.getAsJsonObject();
            int sid = b.get("sid").getAsInt();
            if (sid < 0 || sid >= speakers) {
                continue;
            }
            float[] mixed = new float[FLOATS_PER_VOICE];
            float total = 0;
            for (Map.Entry<String, JsonElement> m : b.getAsJsonObject("mix").entrySet()) {
                Integer src = nameToSid.get(m.getKey());
                if (src == null) {
                    continue;
                }
                float w = m.getValue().getAsFloat();
                // Read from the untouched original even if that slot was already replaced by an earlier blend.
                float[] srcVec = originals.computeIfAbsent(src, s -> slice(all, s));
                for (int i = 0; i < FLOATS_PER_VOICE; i++) {
                    mixed[i] += w * srcVec[i];
                }
                total += w;
            }
            if (total <= 0) {
                continue;
            }
            originals.computeIfAbsent(sid, s -> slice(all, s));
            for (int i = 0; i < FLOATS_PER_VOICE; i++) {
                all[sid * FLOATS_PER_VOICE + i] = mixed[i] / total;
            }
            String name = b.get("name").getAsString();
            voicesOut.add(new VoiceInfo(engineId + "/" + name, engineId, sid, name, b.get("gender").getAsString(),
                    b.has("lang") ? b.get("lang").getAsString() : "en-us", b.has("traits") ? b.get("traits").getAsString() : ""));
        }
        ByteBuffer out = ByteBuffer.allocate(raw.length).order(ByteOrder.LITTLE_ENDIAN);
        out.asFloatBuffer().put(all);
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve("voices-blended.bin");
        Files.write(target, out.array());
        return target;
    }

    private static float[] slice(float[] all, int sid) {
        float[] v = new float[FLOATS_PER_VOICE];
        System.arraycopy(all, sid * FLOATS_PER_VOICE, v, 0, FLOATS_PER_VOICE);
        return v;
    }
}
