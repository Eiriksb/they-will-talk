package dev.eiriksb.theywilltalk.api;

import java.util.UUID;

/**
 * Something a villager remembers.
 *
 * @param created    when it was remembered (epoch ms)
 * @param kind       "conversation", "event", "trade", "gift", "errand", "gossip", or a mod's own
 * @param text       one sentence from the villager's point of view
 * @param importance 1 (trivia) to 10 (life-changing)
 * @param source     "witnessed", "told_by:&lt;villager uuid&gt;", "reflection" or "summary"
 * @param toldBy     the name of whoever told them, for gossip; else null
 */
public record Memory(long id, UUID villager, long created, String kind, String text, Subject subject, int importance,
                     String source, String toldBy) {
    public boolean hearsay() {
        return source != null && source.startsWith("told_by:");
    }
}
