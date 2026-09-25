package dev.eiriksb.theywilltalk.villager;

import java.util.List;
import java.util.Random;

/**
 * Natural-language voice descriptions for Qwen3-TTS VoiceDesign. Every villager gets a stable description built from
 * who they are (gender, age, personality, the pitch/speed of their profile) plus a seeded timbre, and each line adds
 * how they sound right now (the emotion the LLM tagged the reply with).
 */
public final class VoiceDesign {
    private static final List<String> TIMBRES = List.of(
            "raspy", "gravelly", "slightly nasal", "breathy", "warm", "bright", "husky", "smooth", "crackly", "reedy",
            "hoarse", "velvety", "twangy", "croaky", "clear");

    private VoiceDesign() {}

    /** The villager's permanent voice: same text every time, so the voice stays recognisable. */
    public static String base(VillagerProfile p, VillagerFacts f) {
        Random r = new Random(p.uuid().getMostSignificantBits() * 31 + p.uuid().getLeastSignificantBits());
        Persona persona = p.personaEnum();
        boolean female = "f".equals(p.gender());
        String age = switch (f.ageGroup == null ? "adult" : f.ageGroup) {
            case "baby", "child" -> female ? "A little girl of about eight" : "A little boy of about eight";
            case "teen" -> female ? "A teenage girl" : "A teenage boy";
            default -> {
                boolean elder = persona == Persona.WISE || persona == Persona.GRUMPY && r.nextFloat() < 0.5f || r.nextFloat() < 0.2f;
                if (elder) {
                    yield female ? "An elderly woman" : "An elderly man";
                }
                yield r.nextFloat() < 0.5f ? (female ? "A middle-aged woman" : "A middle-aged man")
                        : (female ? "A young woman" : "A young man");
            }
        };
        String pitch = p.pitch() < 0.95 ? "low-pitched" : p.pitch() > 1.06 ? "high-pitched" : "medium-pitched";
        String pace = p.speed() < 0.95 ? "speaks slowly" : p.speed() > 1.06 ? "speaks quickly" : "speaks at a natural pace";
        String timbre = TIMBRES.get(r.nextInt(TIMBRES.size()));
        String role = switch (f.kind == null ? VillagerKind.VANILLA : f.kind) {
            case WANDERING_TRADER -> "a mysterious travelling merchant";
            case MINECOLONIES -> "a hard-working colonist" + (f.job == null ? "" : " (" + f.job + ")");
            default -> "a medieval village " + (f.job == null || f.job.startsWith("unemployed") ? "commoner" : f.job.split(" ")[0]);
        };
        return age + ", " + role + ", with a " + pitch + ", " + timbre + " voice; " + pace + ". Personality: "
                + persona.description + ". Expressive, characterful storybook delivery.";
    }

    /** The whole instruction for one line: the permanent voice plus the current emotion. */
    public static String forLine(String base, String emotion) {
        String now = switch (emotion == null ? "neutral" : emotion) {
            case "happy" -> "Right now they are happy: warm, smiling tone.";
            case "excited" -> "Right now they are very excited: fast, energetic, rising pitch.";
            case "laugh" -> "Right now they are amused and laughing while they speak.";
            case "angry" -> "Right now they are angry: sharp, loud, forceful.";
            case "annoyed" -> "Right now they are annoyed: curt, irritated, with a sigh.";
            case "sad" -> "Right now they are sad: quiet, slow, a little shaky.";
            case "scared" -> "Right now they are scared: trembling, hushed, hurried.";
            case "surprised" -> "Right now they are surprised: gasping, higher pitch.";
            case "confused" -> "Right now they are confused: hesitant and questioning.";
            case "sleepy" -> "Right now they are sleepy: drowsy, mumbling, yawning.";
            case "flirty" -> "Right now they are flirty: playful and teasing.";
            default -> "Right now they sound calm and conversational.";
        };
        return base + " " + now;
    }
}
