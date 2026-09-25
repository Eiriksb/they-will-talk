package dev.eiriksb.theywilltalk.villager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything we know about a villager right now, gathered on the server thread by the matching
 * {@link VillagerAdapter}. Mutable builder-style bag; fields left null are simply not mentioned in the prompt.
 */
public final class VillagerFacts {
    public UUID uuid;
    public VillagerKind kind = VillagerKind.VANILLA;
    public String name;
    /** "m", "f" or null when the mod doesn't know */
    public String gender;
    /** "baby", "child", "teen" or "adult" */
    public String ageGroup = "adult";
    public String job;
    public int jobLevel;
    public String biomeType = "plains";
    /** an MCA personality id or similar hint, mapped through {@link Persona#fromMca} */
    public String personalityHint;
    public String mood;
    /** -15 .. 15 */
    public int moodLevel;
    public final List<String> traits = new ArrayList<>();
    public Village village;
    public final List<FamilyLink> family = new ArrayList<>();
    /** how this villager is related to the player they're talking to ("your spouse", "your child") */
    public String relationToPlayer;
    public Integer hearts;
    public Integer reputation;
    public final List<String> offers = new ArrayList<>();
    public final Map<String, String> extra = new LinkedHashMap<>();
    public boolean sleeping;
    public String customPrompt;
    public String dimension;
    public double x, y, z;
    public boolean alive = true;
    /** set by the prompt builder: include the trade list only when the conversation is about trading */
    public boolean talkingAboutTrade;

    public record Village(String key, String source, String name, String dimension, int x, int y, int z, int population) {}

    /**
     * @param relation "father", "mother", "spouse", "child", "sibling", "partner" ...
     */
    public record FamilyLink(String relation, UUID uuid, String name, boolean player, boolean deceased) {}
}
