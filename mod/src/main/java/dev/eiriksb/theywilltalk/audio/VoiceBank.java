package dev.eiriksb.theywilltalk.audio;

import dev.eiriksb.theywilltalk.ai.QwenTtsClient;
import dev.eiriksb.theywilltalk.villager.VoiceDesign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Keeps every villager's Qwen3-TTS voice the same from line to line.
 *
 * <p>VoiceDesign imagines a voice anew for every request, so a villager would sound like a slightly different person
 * each time. Instead their voice is designed once: VoiceDesign reads a fixed script in the villager's described voice,
 * and that clip is registered as a cloned voice on the Base model, which speaks every line. Each mood gets its own clip,
 * read by the Base model locked to the villager's voice (so the timbre stays) from a script written in that mood; lines
 * in that mood clone it and take on its delivery.
 *
 * <p>Clips are kept per villager in the world folder ({@code voices/<uuid>/}), so a villager keeps their voice across
 * restarts, and are made again when their voice description changes. Registrations live only in the Base server's
 * memory: they're redone when the server changes, or when it reports an unknown voice.
 *
 * <p>An admin can give a villager a real voice instead: an uploaded or recorded clip ({@code voices/custom/<uuid>.wav},
 * with an optional transcript) takes the place of the designed one, and the moods are read in that voice.
 */
public final class VoiceBank {
    /** Bump to redo every villager's clips (new scripts, new method). */
    private static final String VERSION = "1";
    public static final double MIN_CLIP_SECONDS = 2;
    public static final double MAX_CLIP_SECONDS = 30;

    /** An admin-provided voice clip. */
    public record CustomClip(Path wav, String transcript, double seconds) {}

    enum Mood {
        NEUTRAL("Well now, let me think. The harvest came in early this year, the well is full, and nobody has stolen my carrots since spring."),
        HAPPY("Oh, what a wonderful day! The sun is out, the bread is warm, and here you are at last! Ha, I could dance all the way to the market!"),
        ANGRY("That's it, I've had enough! You trampled my wheat, you scared my chickens, and now you want a discount? Get out of my sight!"),
        SAD("It's all gone... the rain took the whole harvest, and the house feels so empty now. I don't know what I'll do this winter."),
        SCARED("Did you hear that? Something is moving out there in the dark... please, stay close to me. I really don't want to be alone tonight.");

        final String script;

        Mood(String script) {
            this.script = script;
        }

        /** The LLM's emotion tags, grouped into the moods that get their own clip. */
        static Mood of(String emotion) {
            return switch (emotion == null ? "neutral" : emotion) {
                case "happy", "excited", "laugh", "flirty" -> HAPPY;
                case "angry", "annoyed" -> ANGRY;
                case "sad", "sleepy" -> SAD;
                case "scared", "surprised" -> SCARED;
                default -> NEUTRAL;
            };
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Path dir;
    private final QwenTtsClient designer;
    private final QwenTtsClient cloner;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    private volatile String registeredOn;

    /**
     * @param designer the VoiceDesign server, which invents voices from descriptions
     * @param cloner   the Base server, which speaks in registered (cloned) voices
     */
    public VoiceBank(Path dir, QwenTtsClient designer, QwenTtsClient cloner) {
        this.dir = dir;
        this.designer = designer;
        this.cloner = cloner;
    }

    /** Both Qwen3-TTS servers are running, so lines can be cloned. */
    public boolean available() {
        return designer.available() && cloner.available();
    }

    public QwenTtsClient cloner() {
        return cloner;
    }

    /**
     * The cloned voice to speak a line in: this villager in this mood. Makes and registers their clips first when
     * needed (about a second per clip, once).
     *
     * @param design the villager's voice description
     */
    public String voice(UUID villager, String design, long seed, String emotion) throws IOException, InterruptedException {
        String server = cloner.baseUrl();
        if (!Objects.equals(server, registeredOn)) {
            registered.clear();
            registeredOn = server;
        }
        Mood mood = Mood.of(emotion);
        synchronized (locks.computeIfAbsent(villager, id -> new Object())) {
            Path vdir = dir.resolve(villager.toString());
            CustomClip custom = customClip(villager).orElse(null);
            String source = custom == null ? design
                    : "clip " + Files.size(custom.wav()) + " " + Files.getLastModifiedTime(custom.wav()).toMillis() + "\n" + custom.transcript();
            String stamp = VERSION + "\n" + seed + "\n" + source;
            Path stampFile = vdir.resolve("voice.txt");
            if (!Files.isRegularFile(stampFile) || !Files.readString(stampFile, StandardCharsets.UTF_8).equals(stamp)) {
                // New villager, or their voice description changed: start over.
                deleteTree(vdir);
                Files.createDirectories(vdir);
                Files.writeString(stampFile, stamp, StandardCharsets.UTF_8);
                registered.removeIf(name -> name.startsWith(prefix(villager)));
            }
            Path neutral = vdir.resolve("neutral.wav");
            if (!Files.isRegularFile(neutral)) {
                if (custom != null) {
                    Files.copy(custom.wav(), neutral);
                } else {
                    write(neutral, designer.wav(Mood.NEUTRAL.script, VoiceDesign.forLine(design, "neutral"), null, seed));
                }
            }
            // Without a transcript an uploaded clip can only lend its timbre, not be continued from.
            String neutralText = custom == null ? Mood.NEUTRAL.script : custom.transcript().isBlank() ? null : custom.transcript();
            Path clip = vdir.resolve(mood.key() + ".wav");
            if (!Files.isRegularFile(clip)) {
                // Timbre only (no transcript): the Base model reads the mood's script in exactly this voice.
                String locked = prefix(villager) + "timbre";
                register(locked, neutral, null);
                write(clip, cloner.wav(mood.script, null, locked, seed));
            }
            String name = prefix(villager) + mood.key();
            register(name, clip, mood == Mood.NEUTRAL ? neutralText : mood.script);
            return name;
        }
    }

    public Optional<CustomClip> customClip(UUID villager) throws IOException {
        Path wav = customDir().resolve(villager + ".wav");
        if (!Files.isRegularFile(wav)) {
            return Optional.empty();
        }
        Path text = customDir().resolve(villager + ".txt");
        String transcript = Files.isRegularFile(text) ? Files.readString(text, StandardCharsets.UTF_8).trim() : "";
        return Optional.of(new CustomClip(wav, transcript, Wav.seconds(Files.readAllBytes(wav))));
    }

    /**
     * Gives a villager a real voice: a WAV clip of someone speaking ({@value #MIN_CLIP_SECONDS} to
     * {@value #MAX_CLIP_SECONDS} seconds, one speaker, little background noise), plus what they say in it if known.
     * Their mood clips are made again from it on the next line.
     */
    public void setCustomClip(UUID villager, byte[] wav, String transcript) throws IOException {
        double seconds = Wav.seconds(wav);
        if (seconds < MIN_CLIP_SECONDS || seconds > MAX_CLIP_SECONDS) {
            throw new IllegalArgumentException(String.format("the clip is %.1f s long; use %.0f to %.0f seconds of speech",
                    seconds, MIN_CLIP_SECONDS, MAX_CLIP_SECONDS));
        }
        synchronized (locks.computeIfAbsent(villager, id -> new Object())) {
            Files.createDirectories(customDir());
            write(customDir().resolve(villager + ".wav"), wav);
            Files.writeString(customDir().resolve(villager + ".txt"), transcript == null ? "" : transcript.trim(), StandardCharsets.UTF_8);
            registered.removeIf(name -> name.startsWith(prefix(villager)));
        }
    }

    /** Back to the designed voice. */
    public void removeCustomClip(UUID villager) throws IOException {
        synchronized (locks.computeIfAbsent(villager, id -> new Object())) {
            Files.deleteIfExists(customDir().resolve(villager + ".wav"));
            Files.deleteIfExists(customDir().resolve(villager + ".txt"));
            registered.removeIf(name -> name.startsWith(prefix(villager)));
        }
    }

    private Path customDir() {
        return dir.resolve("custom");
    }

    /** The Base server lost its registrations (it restarted on the same port): register again on next use. */
    public void forgetRegistrations() {
        registered.clear();
    }

    private void register(String name, Path clip, String refText) throws IOException, InterruptedException {
        if (registered.add(name)) {
            try {
                cloner.registerVoice(name, Files.readAllBytes(clip), refText);
            } catch (IOException | InterruptedException | RuntimeException e) {
                registered.remove(name);
                throw e;
            }
        }
    }

    private static String prefix(UUID villager) {
        return "twt-" + villager + "-";
    }

    private static void write(Path file, byte[] wav) throws IOException {
        if (wav.length < 1000) {
            throw new IOException("Qwen3-TTS returned an empty clip");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, wav);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
