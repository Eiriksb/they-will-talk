package dev.eiriksb.theywilltalk.api;

import java.util.UUID;

/**
 * A memory to add. Start with {@link #of} and adjust with the {@code with...} methods:
 * {@code NewMemory.of(citizen, Subject.player(p), "Eirik promised to fix my roof").withKind("promise").withImportance(7)}.
 */
public record NewMemory(UUID villager, Subject subject, String kind, String text, int importance, String source) {
    public NewMemory {
        importance = Math.max(1, Math.min(10, importance));
        subject = subject == null ? Subject.NONE : subject;
        kind = kind == null || kind.isBlank() ? "event" : kind;
        source = source == null || source.isBlank() ? "witnessed" : source;
    }

    public static NewMemory of(UUID villager, Subject subject, String text) {
        return new NewMemory(villager, subject, "event", text, 3, "witnessed");
    }

    public NewMemory withKind(String k) {
        return new NewMemory(villager, subject, k, text, importance, source);
    }

    public NewMemory withImportance(int i) {
        return new NewMemory(villager, subject, kind, text, i, source);
    }

    /** Heard from another villager rather than seen first-hand. */
    public NewMemory toldBy(UUID teller) {
        return new NewMemory(villager, subject, kind, text, importance, "told_by:" + teller);
    }
}
