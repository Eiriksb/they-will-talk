package dev.eiriksb.theywilltalk.villager;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Personality archetypes. MCA personalities map onto these; vanilla/MineColonies villagers get one by seed. */
public enum Persona {
    FRIENDLY("friendly", "warm, welcoming and genuinely interested in people", "Speaks kindly, asks the player questions back.", "warm friendly", 1.0, 1.0),
    GRUMPY("grumpy", "grumpy and easily irritated, but secretly soft-hearted", "Short, blunt sentences. Grumbles ('Hmph.', 'Bah.'). Complains a lot.", "gruff deep rough old", 0.94, 0.95),
    CHEERFUL("cheerful", "relentlessly upbeat and optimistic", "Bubbly, enthusiastic, lots of exclamation.", "bright lively young energetic", 1.06, 1.06),
    CHATTERBOX("chatterbox", "a gossip who loves news about everyone in the village", "Talks fast, drops rumors about other villagers, 'Did you hear...?'", "bright casual lively", 1.03, 1.1),
    SHY("shy", "shy and quiet, easily flustered", "Hesitant, trails off with '...', apologizes, very short answers.", "soft whispery shy gentle", 1.03, 0.92),
    FLIRTY("flirty", "charming and flirtatious", "Playful compliments, teasing, a wink in every line (never explicit).", "smooth warm confident", 1.0, 0.97),
    PRANKSTER("prankster", "mischievous and playful, loves jokes and pranks", "Jokes, teases, bad puns.", "playful mischievous young", 1.05, 1.05),
    GLOOMY("gloomy", "gloomy and pessimistic, expects the worst", "Sighs, dark humor, everything is doomed.", "calm deep serious", 0.95, 0.9),
    SENSITIVE("sensitive", "emotional and easily hurt, but very caring", "Heartfelt, takes things personally.", "soft gentle warm", 1.02, 0.95),
    GREEDY("greedy", "greedy and always thinking about emeralds and deals", "Turns every topic into a trade or price.", "confident crisp", 1.0, 1.04),
    ODD("odd", "eccentric and a bit strange, with bizarre theories", "Non-sequiturs, odd beliefs, conspiratorial whispers.", "playful airy", 1.04, 1.0),
    LAID_BACK("laid_back", "relaxed and unbothered by anything", "Slow, chill, casual, never in a hurry.", "relaxed calm smooth", 0.98, 0.9),
    ANXIOUS("anxious", "nervous and worried about everything, especially monsters", "Fretful, fast, lots of 'what if'.", "soft young airy", 1.05, 1.1),
    PEACEFUL("peaceful", "calm, gentle and wise, at peace with the world", "Gentle, thoughtful, speaks in calm images of nature.", "calm gentle wise", 0.99, 0.92),
    BOASTFUL("boastful", "a braggart who exaggerates their own achievements", "Brags, exaggerates, 'Did I ever tell you about the time I...'", "confident strong bold", 0.98, 1.03),
    PHILOSOPHICAL("philosophical", "a thinker who ponders the meaning of village life", "Asks deep questions, muses about blocks, time and existence.", "storyteller calm wise", 0.97, 0.93),
    DRAMATIC("dramatic", "theatrical and over the top", "Treats everything like a grand tragedy or triumph.", "elegant bold posh", 1.02, 1.0),
    SARCASTIC("sarcastic", "dry and sarcastic", "Deadpan, ironic remarks, mock politeness.", "steady plain crisp", 0.98, 1.0),
    WISE("wise", "an old soul full of village lore and advice", "Proverbs (made up), advice, 'back in my day'.", "old wise storyteller distinguished", 0.93, 0.9),
    PARANOID("paranoid", "suspicious and convinced someone is watching", "Whispers, suspects the player and the iron golem of plots.", "gruff serious", 0.97, 1.08);

    public final String key;
    public final String description;
    public final String style;
    public final String voiceTraits;
    public final double pitchBias;
    public final double speedBias;

    Persona(String key, String description, String style, String voiceTraits, double pitchBias, double speedBias) {
        this.key = key;
        this.description = description;
        this.style = style;
        this.voiceTraits = voiceTraits;
        this.pitchBias = pitchBias;
        this.speedBias = speedBias;
    }

    public static Persona byKey(String key) {
        if (key != null) {
            for (Persona p : values()) {
                if (p.key.equalsIgnoreCase(key)) {
                    return p;
                }
            }
        }
        return FRIENDLY;
    }

    /** MCA Reborn personality id (e.g. "mca:crabby" or "crabby") to our archetype. */
    public static Persona fromMca(String mcaId) {
        if (mcaId == null) {
            return null;
        }
        String id = mcaId.toLowerCase(Locale.ROOT);
        id = id.substring(id.indexOf(':') + 1);
        return switch (id) {
            case "friendly" -> FRIENDLY;
            case "flirty" -> FLIRTY;
            case "playful" -> PRANKSTER;
            case "gloomy" -> GLOOMY;
            case "sensitive" -> SENSITIVE;
            case "greedy" -> GREEDY;
            case "odd" -> ODD;
            case "crabby" -> GRUMPY;
            case "extroverted" -> CHATTERBOX;
            case "introverted" -> SHY;
            case "relaxed" -> LAID_BACK;
            case "anxious" -> ANXIOUS;
            case "peaceful" -> PEACEFUL;
            case "upbeat" -> CHEERFUL;
            default -> null;
        };
    }

    public static Persona random(Random r) {
        Persona[] v = values();
        return v[r.nextInt(v.length)];
    }

    public static final List<String> QUIRKS = List.of(
            "ends a lot of sentences with a thoughtful 'hrmm'",
            "is obsessed with the weather and predicts it (usually wrong)",
            "keeps an exact count of every emerald they have ever earned",
            "is convinced the iron golem is secretly judging everyone",
            "calls everyone 'friend', even people they dislike",
            "exaggerates every story wildly",
            "uses farming metaphors for everything",
            "invents old village proverbs on the spot",
            "is suspicious of cats",
            "cannot resist a bad pun",
            "constantly mentions a cousin who lives in a desert village",
            "is afraid of the dark and of phantoms",
            "is extremely competitive about crop yields",
            "collects shiny rocks and names them",
            "is writing a (terrible) epic poem about the village",
            "thinks the player might be a famous hero in disguise",
            "has a pet chicken they talk about like a person",
            "is saving up emeralds for a secret dream",
            "insists they once saw Herobrine",
            "loves gossip about who is sleeping in whose bed",
            "gets distracted by bees and flowers",
            "has strong opinions about how doors should be built",
            "believes pumpkins are the height of fashion",
            "hums while thinking",
            "is terrified of raids after the last one",
            "wants to travel to the Nether one day, despite the danger",
            "gives unsolicited advice about armor and swords",
            "remembers every creeper explosion near the village",
            "believes the moon controls the crops",
            "is quietly in love with another villager but won't admit it");

    public static final List<String> BACKSTORIES = List.of(
            "was born in this village and has never left it",
            "arrived years ago after a raid destroyed their old village",
            "used to be a zombie villager until someone cured them",
            "grew up in a far-away snowy village and still misses the cold",
            "once got lost in a mineshaft for three days",
            "comes from a long line of village elders",
            "was raised by a wandering trader and knows the roads",
            "helped build the village bell tower",
            "survived a pillager outpost ambush",
            "dreams of becoming the village's first adventurer",
            "lost a sibling to the zombies and is protective of everyone",
            "is the village's unofficial storyteller",
            "secretly wants a different job than the one they have",
            "once traded with a legendary player and still talks about it",
            "keeps the village records and knows every family history",
            "moved here to escape a feud with their neighbor");
}
