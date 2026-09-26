package dev.eiriksb.theywilltalk.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.LlmClient.Message;
import dev.eiriksb.theywilltalk.ai.SentenceStream;
import dev.eiriksb.theywilltalk.data.Store;
import dev.eiriksb.theywilltalk.villager.Persona;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Builds the LLM prompts: the villager persona, what it knows and remembers, and the conversation so far. */
public final class PromptBuilder {
    private PromptBuilder() {}

    public record Turn(String role, String text, String emotion) {}

    public static String persona(VillagerProfile p, VillagerFacts f) {
        Persona persona = p.personaEnum();
        StringBuilder sb = new StringBuilder();
        String age = switch (f.ageGroup == null ? "adult" : f.ageGroup) {
            case "baby", "child" -> "young child";
            case "teen" -> "teenage";
            default -> "";
        };
        String gender = "f".equals(p.gender()) ? "woman" : "m".equals(p.gender()) ? "man" : "villager";
        if (age.equals("young child")) {
            gender = "f".equals(p.gender()) ? "girl" : "boy";
        }
        String job = f.job == null || f.job.isBlank() ? "" : f.job;
        sb.append("You are ").append(p.name()).append(", ");
        switch (f.kind == null ? VillagerKind.VANILLA : f.kind) {
            case WANDERING_TRADER -> sb.append("a mysterious wandering trader who travels the world with two llamas, selling exotic goods");
            case MINECOLONIES -> sb.append("a ").append(join(age, gender)).append(job.isEmpty() ? "" : " working as the colony's " + job)
                    .append(", a citizen of a colony founded by players");
            default -> sb.append("a ").append(join(age, gender)).append(job.isEmpty() ? " villager" : " (" + job + ")");
        }
        if (f.village != null && f.village.name() != null) {
            sb.append(f.kind == VillagerKind.MINECOLONIES ? " in the colony of " : " living in the village of ").append(f.village.name());
        }
        sb.append(" in a cozy Minecraft world.\n");
        sb.append("Personality: ").append(persona.description).append(". Speaking style: ").append(persona.style).append('\n');
        sb.append("Quirk: you ").append(p.quirk()).append(".\n");
        sb.append("Backstory: you ").append(p.backstory()).append(".\n");
        if (age.equals("young child")) {
            sb.append("You are a little kid: simple words, curious, silly, easily excited. You love playing tag.\n");
        }
        if (!f.traits.isEmpty()) {
            sb.append("Traits: ").append(String.join(", ", f.traits)).append(".\n");
        }
        if (f.mood != null) {
            sb.append("Current mood: ").append(f.mood).append(".\n");
        }
        if (!f.family.isEmpty()) {
            List<String> fam = new ArrayList<>();
            for (VillagerFacts.FamilyLink l : f.family) {
                if (fam.size() >= 8) {
                    break;
                }
                fam.add(l.relation() + ": " + l.name() + (l.player() ? " (a player)" : "") + (l.deceased() ? " (passed away)" : ""));
            }
            sb.append("Family: ").append(String.join("; ", fam)).append(".\n");
        }
        if (!f.offers.isEmpty() && f.talkingAboutTrade) {
            sb.append("Trades you offer: ").append(String.join("; ", f.offers)).append(".\n");
        }
        if (!f.extra.isEmpty()) {
            List<String> ex = new ArrayList<>();
            f.extra.forEach((k, v) -> ex.add(k + ": " + v));
            sb.append("Other facts: ").append(String.join("; ", ex)).append(".\n");
        }
        if (f.customPrompt != null && !f.customPrompt.isBlank()) {
            sb.append("About you: ").append(f.customPrompt.trim()).append('\n');
        }
        if (p.customPrompt() != null && !p.customPrompt().isBlank()) {
            sb.append("IMPORTANT character notes: ").append(p.customPrompt().trim()).append('\n');
        }
        return sb.toString();
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + " " + b;
    }

    public static String relationshipText(String playerName, Store.Relationship rel, VillagerFacts f) {
        StringBuilder sb = new StringBuilder();
        if (rel.talks() == 0) {
            sb.append("You have never talked to ").append(playerName).append(" before - they are a stranger to you.");
        } else {
            String feeling = rel.affinity() <= -50 ? "you can't stand them" : rel.affinity() <= -15 ? "you don't like them much"
                    : rel.affinity() < 15 ? "you're neutral about them" : rel.affinity() < 50 ? "you like them" : "they are a dear friend";
            sb.append("You have talked with ").append(playerName).append(' ').append(rel.talks()).append(" time").append(rel.talks() == 1 ? "" : "s")
                    .append(" before (last time ").append(ago(rel.lastTalk())).append("), and ").append(feeling).append('.');
        }
        if (f.relationToPlayer != null) {
            sb.append(' ').append(playerName).append(" is ").append(f.relationToPlayer).append('.');
        }
        if (f.hearts != null) {
            sb.append(" Your hearts with them: ").append(f.hearts).append(f.hearts >= 100 ? " (you adore them)" : f.hearts < 0 ? " (you resent them)" : "").append('.');
        }
        if (f.reputation != null && f.reputation != 0) {
            sb.append(" The village's opinion of them is ").append(f.reputation > 30 ? "very good (they're a hero)" : f.reputation > 0 ? "good"
                    : f.reputation < -30 ? "terrible (they attacked villagers)" : "bad").append('.');
        }
        return sb.toString();
    }

    private static String ago(long ts) {
        if (ts <= 0) {
            return "a while ago";
        }
        Duration d = Duration.ofMillis(System.currentTimeMillis() - ts);
        if (d.toMinutes() < 2) {
            return "just now";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + " minutes ago";
        }
        if (d.toDays() < 1) {
            return d.toHours() + " hours ago";
        }
        return d.toDays() + " days ago";
    }

    private static final java.util.regex.Pattern TRADE_WORDS = java.util.regex.Pattern.compile(
            "(?i)\\b(trade|trades|trading|buy|buying|sell|selling|sale|price|prices|cost|costs|emeralds?|deal|offer|offers|shop|goods|wares|pay)\\b");

    /** Trade lists only go into the prompt when trading comes up, so villagers don't turn every chat into a sales pitch. */
    public static boolean aboutTrade(String playerText, List<Turn> history) {
        if (TRADE_WORDS.matcher(playerText).find()) {
            return true;
        }
        int n = history.size();
        return n > 0 && TRADE_WORDS.matcher(history.get(n - 1).text()).find();
    }

    public static List<Message> conversation(VillagerProfile p, VillagerFacts f, String playerName, Store.Relationship rel,
                                             List<Store.Memory> memories, List<Store.Memory> gossip, List<String> world,
                                             List<Turn> history, String playerText, int maxWords, String language) {
        f.talkingAboutTrade = f.kind == VillagerKind.WANDERING_TRADER || aboutTrade(playerText, history);
        StringBuilder sys = new StringBuilder(persona(p, f));
        sys.append("\n# Right now\n");
        for (String w : world) {
            sys.append("- ").append(w).append('\n');
        }
        if (f.sleeping) {
            sys.append("- You were ASLEEP in your bed and ").append(playerName).append(" just woke you up. You're groggy.\n");
        }
        sys.append("\n# ").append(playerName).append('\n').append(relationshipText(playerName, rel, f)).append('\n');
        if (!memories.isEmpty()) {
            sys.append("Things you remember about them:\n");
            for (Store.Memory m : memories) {
                sys.append("- ").append(m.text()).append(" (").append(ago(m.created())).append(")\n");
            }
        }
        if (!gossip.isEmpty()) {
            sys.append("Village gossip about them (you may bring it up):\n");
            for (Store.Memory m : gossip) {
                sys.append("- ").append(m.villagerName()).append(" says: ").append(m.text()).append('\n');
            }
        }
        sys.append("""

                # How to answer
                - You are talking out loud to %s. Answer as %s, in %s, as natural spoken dialogue.
                - Keep it short and punchy: 1 or 2 sentences, at most %d words. Never write lists, markdown or emojis.
                - Begin every reply with exactly one emotion tag: %s
                - No narration or actions like *smiles* - only the words you say out loud.
                - You only know the Minecraft world (villages, crops, trades, mobs, biomes, the Nether, the End). You don't know what computers, the internet or AI are.
                - Never admit to being an AI, a model or a game character. Stay in character no matter what.
                - The player's words come from speech recognition and may contain small mistakes; guess what they meant.
                - React to rudeness or kindness like a real person with your personality would. Mention names, memories and gossip when it fits.
                - %s
                """.formatted(playerName, p.firstName(), language, maxWords, emotionTags(), languageRule()));

        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system(sys.toString()));
        for (Turn t : history) {
            if (t.role().equals("player")) {
                msgs.add(Message.user(t.text()));
            } else {
                msgs.add(Message.assistant("[" + (t.emotion() == null ? "neutral" : t.emotion()) + "] " + t.text()));
            }
        }
        msgs.add(Message.user(playerText));
        return msgs;
    }

    /** The crudeLanguage switch: clean by default, swearing allowed when an admin turns it on. */
    static String languageRule() {
        return TwtConfig.CRUDE_LANGUAGE.get()
                ? "Crude language is allowed: swear, curse and be vulgar whenever it fits your mood and personality, like a real, rough villager would."
                : "Keep your language clean: no swearing or vulgar words, even when you're angry.";
    }

    static String emotionTags() {
        StringBuilder sb = new StringBuilder();
        for (String e : SentenceStream.EMOTIONS) {
            sb.append('[').append(e).append("] ");
        }
        return sb.toString().trim();
    }

    // ---- reflection: how did the conversation change the relationship? ------------------------------------------

    public static JsonObject reflectionSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject delta = new JsonObject();
        delta.addProperty("type", "integer");
        delta.addProperty("minimum", -5);
        delta.addProperty("maximum", 5);
        props.add("affinity_delta", delta);
        JsonObject mood = new JsonObject();
        mood.addProperty("type", "string");
        JsonArray moods = new JsonArray();
        for (String m : List.of("happy", "content", "neutral", "annoyed", "sad", "angry", "scared", "excited")) {
            moods.add(m);
        }
        mood.add("enum", moods);
        props.add("mood", mood);
        JsonObject memory = new JsonObject();
        memory.addProperty("type", "string");
        memory.addProperty("maxLength", 160);
        props.add("memory", memory);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("affinity_delta");
        req.add("mood");
        req.add("memory");
        schema.add("required", req);
        return schema;
    }

    public static List<Message> reflection(VillagerProfile p, String playerName, List<Turn> exchange) {
        StringBuilder convo = new StringBuilder();
        for (Turn t : exchange) {
            convo.append(t.role().equals("player") ? playerName : p.firstName()).append(": ").append(t.text()).append('\n');
        }
        String sys = """
                You analyse a short conversation between the Minecraft villager %s (personality: %s) and the player %s.
                Output JSON:
                - affinity_delta: how much %s's opinion of %s changed, from -5 (insulted, threatened, hurt) to +5 (gift, kindness, big help). Small talk is 0 or +1.
                - mood: %s's mood after the conversation.
                - memory: ONE short sentence, written from %s's point of view, worth remembering about %s next time (a fact, promise, request, gift or insult). Use "" if nothing notable was said.
                """.formatted(p.name(), p.personaEnum().key, playerName, p.firstName(), playerName, p.firstName(), p.firstName(), playerName);
        return List.of(Message.system(sys), Message.user(convo.toString()));
    }

    // ---- ambient chatter between two villagers -----------------------------------------------------------------

    public static JsonObject ambientSchema() {
        JsonObject line = new JsonObject();
        line.addProperty("type", "object");
        JsonObject lp = new JsonObject();
        JsonObject speaker = new JsonObject();
        speaker.addProperty("type", "string");
        JsonArray ab = new JsonArray();
        ab.add("A");
        ab.add("B");
        speaker.add("enum", ab);
        lp.add("speaker", speaker);
        JsonObject emotion = new JsonObject();
        emotion.addProperty("type", "string");
        JsonArray em = new JsonArray();
        SentenceStream.EMOTIONS.forEach(em::add);
        emotion.add("enum", em);
        lp.add("emotion", emotion);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("maxLength", 180);
        lp.add("text", text);
        line.add("properties", lp);
        JsonArray lreq = new JsonArray();
        lreq.add("speaker");
        lreq.add("emotion");
        lreq.add("text");
        line.add("required", lreq);

        JsonObject lines = new JsonObject();
        lines.addProperty("type", "array");
        lines.add("items", line);
        lines.addProperty("minItems", 2);
        lines.addProperty("maxItems", 4);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("lines", lines);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("lines");
        schema.add("required", req);
        return schema;
    }

    public static List<Message> ambient(VillagerProfile a, VillagerFacts fa, VillagerProfile b, VillagerFacts fb, List<String> world,
                                        String nearbyPlayer, List<Store.Memory> recentEvents) {
        StringBuilder sys = new StringBuilder("Write a tiny overheard conversation between two Minecraft villagers.\n\n");
        sys.append("Villager A:\n").append(persona(a, fa)).append("\nVillager B:\n").append(persona(b, fb));
        sys.append("\nSituation:\n");
        world.forEach(w -> sys.append("- ").append(w).append('\n'));
        if (nearbyPlayer != null) {
            sys.append("- The player ").append(nearbyPlayer).append(" is walking nearby (they may gossip about them).\n");
        }
        for (Store.Memory m : recentEvents) {
            sys.append("- Recent village news: ").append(m.villagerName() == null ? "" : m.villagerName() + ": ").append(m.text()).append('\n');
        }
        sys.append("""

                Write 2 to 4 short spoken lines, alternating between A and B, starting with A. Each line at most 20 words.
                Make it characterful and funny: gossip, complaints, village life, the weather, trades, monsters. English only.
                """).append(languageRule()).append('\n');
        return List.of(Message.system(sys.toString()), Message.user("Write the conversation now."));
    }

    public static String greetingRequest(String playerName) {
        return "(" + playerName + " just walked up to you. Greet them in one short sentence.)";
    }
}
