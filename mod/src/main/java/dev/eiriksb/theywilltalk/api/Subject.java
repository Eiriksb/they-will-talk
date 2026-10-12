package dev.eiriksb.theywilltalk.api;

import java.util.UUID;

/**
 * What a memory is about. Types TWT uses: {@code player}, {@code villager}. Mods may add their own, e.g.
 * {@code building}, {@code place} or {@code colony}, with whatever id they use for those.
 *
 * @param type lowercase letters, digits and _
 */
public record Subject(String type, String id) {
    /** Not about anyone in particular ("The harvest was good this year"). */
    public static final Subject NONE = new Subject("none", "");

    public Subject {
        if (type == null || !type.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Subject types are lowercase letters, digits and _: " + type);
        }
        id = id == null ? "" : id;
    }

    public static Subject player(UUID player) {
        return new Subject("player", player.toString());
    }

    public static Subject villager(UUID villager) {
        return new Subject("villager", villager.toString());
    }

    public static Subject of(String type, String id) {
        return new Subject(type, id);
    }

    public boolean isNone() {
        return "none".equals(type);
    }
}
