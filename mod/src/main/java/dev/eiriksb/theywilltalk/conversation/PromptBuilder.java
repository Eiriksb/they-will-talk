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
import java.util.UUID;

/** Builds the LLM prompts: the villager persona, what it knows and remembers, and the conversation so far. */
public final class PromptBuilder {
    private PromptBuilder() {}

    /**
     * A line of a conversation.
     *
     * @param role    "player" or "villager"
     * @param speaker who said it, by name, in conversations with several villagers
     * @param villager the villager who said it (null for the player, and in older one-to-one history)
     */
    public record Turn(String role, String text, String emotion, String speaker, UUID villager) {
        public Turn(String role, String text, String emotion) {
            this(role, text, emotion, null, null);
        }
    }

    /**
     * Another villager standing with the one who's talking, who hears everything and may chime in.
     *
     * @param about "the village archer, cheerful and upbeat; your wife; she likes Eirik"
     */
    public record Bystander(UUID uuid, String name, String about) {}

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
                                             List<Turn> history, String playerText, int maxWords, String language,
                                             List<String> favours, List<Bystander> others) {
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
        if (!favours.isEmpty()) {
            sys.append("Favours between you:\n");
            favours.forEach(l -> sys.append("- ").append(l).append('\n'));
        }
        if (!others.isEmpty()) {
            sys.append("\n# Who else is here\n");
            for (Bystander b : others) {
                sys.append("- ").append(b.name()).append(": ").append(b.about()).append('\n');
            }
            sys.append("They're standing with you and ").append(playerName).append(", hear everything and may chime in. ")
                    .append("You can talk to them too, and react to what they say.\n");
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
                - Don't make up tasks, quests or favours for %s: the only favours between you are the ones listed above (if any). If they ask for work and none is listed, you don't need anything right now.
                - %s
                """.formatted(playerName, p.firstName(), language, maxWords, emotionTags(), playerName, languageRule()));

        // With other villagers around, everyone else's lines are "Name: text" so the model knows who said what.
        boolean group = !others.isEmpty() || history.stream().anyMatch(t -> t.villager() != null && !t.villager().equals(p.uuid()));
        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system(sys.toString()));
        for (Turn t : history) {
            boolean mine = t.role().equals("villager") && (t.villager() == null || t.villager().equals(p.uuid()));
            if (mine) {
                add(msgs, Message.assistant("[" + (t.emotion() == null ? "neutral" : t.emotion()) + "] " + t.text()));
            } else {
                String who = t.role().equals("player") ? playerName : t.speaker();
                add(msgs, Message.user(group && who != null ? who + ": " + t.text() : t.text()));
            }
        }
        add(msgs, Message.user(group ? playerName + ": " + playerText : playerText));
        return msgs;
    }

    /** Some chat templates insist on user and assistant taking turns: lines in a row from the same side are joined. */
    private static void add(List<Message> msgs, Message m) {
        Message last = msgs.getLast();
        if (msgs.size() > 1 && last.role().equals(m.role())) {
            msgs.set(msgs.size() - 1, new Message(m.role(), last.content() + "\n" + m.content()));
        } else {
            msgs.add(m);
        }
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
            sys.append("- The player ").append(nearbyPlayer).append(" is nearby: they may gossip about them, or turn to ").append(nearbyPlayer)
                    .append(" and ask what they think.\n");
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

    // ---- group conversations: the others chime in ----------------------------------------------------------------

    /** Someone taking part in a group conversation, for the chime-in prompt. */
    public record Member(String name, String persona) {}

    /** The others' lines, after rating how natural it is for them to speak up at all (the rating comes first). */
    public static JsonObject chimeInSchema(List<String> names, int maxLines) {
        JsonObject schema = ambientSchema();
        JsonObject lines = schema.getAsJsonObject("properties").getAsJsonObject("lines");
        lines.addProperty("minItems", 0);
        lines.addProperty("maxItems", maxLines);
        JsonObject speaker = lines.getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("speaker");
        JsonArray allowed = new JsonArray();
        names.forEach(allowed::add);
        speaker.add("enum", allowed);
        JsonObject natural = new JsonObject();
        natural.addProperty("type", "integer");
        natural.addProperty("minimum", 0);
        natural.addProperty("maximum", 10);
        JsonObject props = new JsonObject();
        props.add("natural", natural);
        props.add("lines", lines);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("natural");
        req.add("lines");
        schema.add("required", req);
        return schema;
    }

    /**
     * What the chosen others say after {@code speaker} answered the player, if it's natural for them to speak up.
     *
     * @param speaking   who may speak (usually one villager)
     * @param answerBack whether {@code speaker} may answer them back once
     * @param toEveryone the player spoke to all of them ("what do you two think?")
     * @param recent     the conversation so far, the villager's answer last
     */
    public static List<Message> chimeIn(Member speaker, List<Member> speaking, List<Member> listening, String playerName,
                                        List<Turn> recent, boolean toEveryone, boolean answerBack, String language) {
        StringBuilder sys = new StringBuilder("You write what happens next in a conversation in a Minecraft village. ")
                .append(playerName).append(" (a player) is talking with ").append(speaker.name()).append(", and others are standing with them.\n\n")
                .append(speaker.name()).append(": ").append(speaker.persona()).append('\n');
        for (Member m : speaking) {
            sys.append(m.name()).append(": ").append(m.persona()).append('\n');
        }
        for (Member m : listening) {
            sys.append(m.name()).append(": ").append(m.persona()).append(" (just listening)\n");
        }
        sys.append("\nConversation so far:\n");
        for (Turn t : recent) {
            sys.append(t.role().equals("player") ? playerName : t.speaker() == null ? speaker.name() : t.speaker())
                    .append(": ").append(t.text()).append('\n');
        }
        String names = String.join(" and ", speaking.stream().map(Member::name).toList());
        if (toEveryone) {
            sys.append('\n').append(playerName).append(" spoke to all of them, so ").append(names)
                    .append(" each answer briefly, in character. Rate \"natural\" 10.\n");
        } else {
            sys.append("""

                    Would %s naturally say something right now? In a group, people let whoever was asked do the talking.                     Only speak up with a real reason: being mentioned, it concerns them or their family, or it's so like them                     they can't help it. React to the last thing said, not to earlier topics. Don't just agree or repeat what                     was said.
                    First rate "natural": how natural it would be for %s to speak up now (0 = nobody would, 10 = they clearly would).                     If it's natural, write their one short line (at most 15 words); otherwise write no lines.
                    """.formatted(names, names));
            if (answerBack) {
                sys.append(speaker.name()).append(" may answer back once, briefly, but only if the line calls for it.\n");
            }
        }
        sys.append("Spoken words only, no narration. Speak ").append(language).append(". ").append(languageRule()).append('\n');
        return List.of(Message.system(sys.toString()), Message.user("What happens next?"));
    }

    // ---- villagers taking the initiative ------------------------------------------------------------------------

    public static String greetingRequest(String playerName) {
        return "(You spotted " + playerName + ", whom you like, and walked over to them. Greet them in one short sentence.)";
    }

    /** @param objective "bring you 12 wheat" */
    public static String errandRequest(String playerName, String objective, String reward) {
        return "(You walked over to " + playerName + " because you need a favour: " + objective + ". You'll give them " + reward
                + " for it. Ask them in one or two short sentences, in character, with a reason that fits your life, and say exactly what you need.)";
    }

    /** @param gift "3 bread" */
    public static String giftRequest(String playerName, String gift) {
        return "(You walked over to " + playerName + ", whom you're very fond of, and just handed them a small gift: " + gift
                + ". Say something short and warm as you give it.)";
    }

    /** @param done "brought you the 12 wheat you asked for" */
    public static String thanksRequest(String playerName, String done, String reward) {
        return "(" + playerName + " just " + done + ", and you gave them " + reward + " as promised. Thank them in one or two sentences, in character.)";
    }

    public static String letterRequest(String playerName, String from, String reward) {
        return "(" + playerName + " just handed you a letter from " + from + ", and you gave them " + reward
                + " for their trouble. React to the letter in one or two sentences; you can guess what " + from + " wrote.)";
    }

    // ---- did the player agree to do the favour? ----------------------------------------------------------------

    public static JsonObject answerSchema() {
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("yes");
        values.add("no");
        values.add("unclear");
        answer.add("enum", values);
        JsonObject props = new JsonObject();
        props.add("answer", answer);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add("answer");
        schema.add("required", req);
        return schema;
    }

    /** @param request what the villager said when asking; {@code task} is the plain version */
    public static List<Message> answer(String villagerName, String playerName, String request, String task, String playerText) {
        String sys = """
                In a Minecraft game, the villager %s asked the player %s for a favour: "%s" (%s).
                This is what %s answered. It may be in any language and comes from speech recognition, so it can contain small mistakes.
                Did they agree to do it? Output JSON: answer is "yes" if they agreed (even reluctantly or with a joke), "no" if they refused or put it off, "unclear" if they didn't answer the request.
                """.formatted(villagerName, playerName, request.isBlank() ? task : request, task, playerName);
        return List.of(Message.system(sys), Message.user(playerText));
    }
}
