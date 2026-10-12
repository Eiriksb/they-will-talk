package dev.eiriksb.theywilltalk.api;

import java.util.UUID;

/**
 * How villager {@code a} sees villager {@code b}. Relations go one way: {@code b} may feel differently about {@code a}.
 *
 * @param otherName   b's name, when known
 * @param affinity    -100 (hates) to 100 (adores)
 * @param familiarity how well a knows b: grows every time they talk
 * @param kind        "friend", "rival", "partner", "spouse", "parent", ..., or null when nobody has said
 */
public record Relation(UUID a, UUID b, String otherName, int affinity, int familiarity, String kind, long lastInteraction) {
    public static Relation none(UUID a, UUID b) {
        return new Relation(a, b, null, 0, 0, null, 0);
    }
}
