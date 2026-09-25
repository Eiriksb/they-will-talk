package dev.eiriksb.theywilltalk.voice;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsCallback;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A sherpa-onnx offline TTS model (Kokoro, Piper/VITS or Supertonic). */
final class SherpaEngine implements TtsEngine {
    /** Kokoro's lexicons cover English (and Chinese); everything else goes through espeak-ng. */
    private static final Map<String, String> KOKORO_ESPEAK = Map.of(
            "es", "es", "fr", "fr-fr", "hi", "hi", "it", "it", "pt", "pt-br", "ja", "ja");

    private final EngineLoader.EngineSpec spec;
    private final OfflineTts tts;
    private final List<VoiceInfo> voices;
    // espeak-ng keeps global state, so one generation at a time per engine.
    private final ReentrantLock lock = new ReentrantLock();

    SherpaEngine(EngineLoader.EngineSpec spec, Path cacheDir, int threads, String provider) throws IOException {
        this.spec = spec;
        Path dir = spec.dir;
        List<VoiceInfo> blended = new ArrayList<>();
        OfflineTtsModelConfig.Builder model = OfflineTtsModelConfig.builder()
                .setNumThreads(threads)
                .setProvider(provider)
                .setDebug(false);
        switch (spec.type) {
            case "kokoro" -> {
                String lexicons = Stream.of("lexicon-us-en.txt", "lexicon-zh.txt")
                        .map(dir::resolve).filter(Files::exists).map(Path::toString).collect(Collectors.joining(","));
                OfflineTtsKokoroModelConfig.Builder k = OfflineTtsKokoroModelConfig.builder()
                        .setModel(pick(dir, "model.int8.onnx", "model.onnx"))
                        .setVoices(KokoroVoiceBlender.blend(dir.resolve("voices.bin"), spec.blends,
                                spec.voices.stream().collect(Collectors.toMap(VoiceInfo::name, VoiceInfo::sid)),
                                cacheDir, blended, spec.id).toString())
                        .setTokens(dir.resolve("tokens.txt").toString())
                        .setDataDir(dir.resolve("espeak-ng-data").toString())
                        .setLexicon(lexicons);
                if (Files.isDirectory(dir.resolve("dict"))) {
                    k.setDictDir(dir.resolve("dict").toString());
                }
                if (lexicons.isEmpty()) {
                    k.setLang("en-us");
                }
                model.setKokoro(k.build());
            }
            case "vits" -> model.setVits(OfflineTtsVitsModelConfig.builder()
                    .setModel(EngineLoader.findOnnx(dir).toString())
                    .setTokens(dir.resolve("tokens.txt").toString())
                    .setDataDir(dir.resolve("espeak-ng-data").toString())
                    .setNoiseScale(0.667f)
                    .setNoiseScaleW(0.8f)
                    .setLengthScale(1.0f)
                    .build());
            case "supertonic" -> model.setSupertonic(OfflineTtsSupertonicModelConfig.builder()
                    .setDurationPredictor(pick(dir, "duration_predictor.int8.onnx", "duration_predictor.onnx"))
                    .setTextEncoder(pick(dir, "text_encoder.int8.onnx", "text_encoder.onnx"))
                    .setVectorEstimator(pick(dir, "vector_estimator.int8.onnx", "vector_estimator.onnx"))
                    .setVocoder(pick(dir, "vocoder.int8.onnx", "vocoder.onnx"))
                    .setTtsJson(dir.resolve("tts.json").toString())
                    .setUnicodeIndexer(dir.resolve("unicode_indexer.bin").toString())
                    .setVoiceStyle(pick(dir, "voice.bin", "voices.bin"))
                    .build());
            default -> throw new IllegalArgumentException("Unknown engine type " + spec.type);
        }
        OfflineTtsConfig config = OfflineTtsConfig.builder().setModel(model.build()).setMaxNumSentences(1).build();
        this.tts = new OfflineTts(config);

        List<VoiceInfo> v = new ArrayList<>(spec.voices);
        v.addAll(blended);
        if (v.isEmpty()) {
            int n = Math.max(1, tts.getNumSpeakers());
            for (int i = 0; i < n; i++) {
                v.add(new VoiceInfo(spec.id + "/s" + i, spec.id, i, "s" + i, i % 2 == 0 ? "f" : "m", spec.languages.getFirst(), ""));
            }
        }
        this.voices = List.copyOf(v);
    }

    private static String pick(Path dir, String... names) {
        for (String n : names) {
            if (Files.exists(dir.resolve(n))) {
                return dir.resolve(n).toString();
            }
        }
        return dir.resolve(names[names.length - 1]).toString();
    }

    @Override
    public String id() {
        return spec.id;
    }

    @Override
    public List<VoiceInfo> voices() {
        return voices;
    }

    @Override
    public boolean supportsLanguage(String lang) {
        String base = VoiceServer.baseLang(lang);
        return spec.languages.stream().anyMatch(l -> VoiceServer.baseLang(l).equals(base));
    }

    @Override
    public String espeakLang(String lang, VoiceInfo voice) {
        String base = lang == null ? "en" : VoiceServer.baseLang(lang);
        return switch (spec.type) {
            case "kokoro" -> KOKORO_ESPEAK.get(base);
            // Supertonic always needs a language; it handles its own text processing.
            case "supertonic" -> supportsLanguage(base) ? base : "en";
            default -> null;
        };
    }

    @Override
    public Audio synthesize(String text, int sid, float speed, String lang) {
        GenerationConfig gen = new GenerationConfig();
        gen.setSid(sid);
        gen.setSpeed(speed);
        gen.setSilenceScale(0.2f);
        if ("supertonic".equals(spec.type)) {
            gen.setExtra(Map.of("lang", lang == null ? "en" : lang, "num_steps", "5"));
        } else if (lang != null) {
            gen.setExtra(Map.of("lang", lang));
        }
        lock.lock();
        try {
            GeneratedAudio audio = tts.generateWithConfigAndCallback(text, gen, (OfflineTtsCallback) samples -> 1);
            return new Audio(audio.getSamples(), audio.getSampleRate());
        } finally {
            lock.unlock();
        }
    }
}
