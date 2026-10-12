package dev.eiriksb.theywilltalk.api;

import java.util.List;

/**
 * What a {@link ConversationHook} wants done with a turn.
 *
 * @param handled true when the hook took the turn over completely: TWT neither replies nor reflects on it (the
 *                player's line is still logged)
 * @param context lines added to this turn's prompt, under "Right now". Use them to tell the villager what it has
 *                decided, e.g. "You refuse this order: they let Ingrid starve last winter. Say so in your own words."
 */
public record BeforeReply(boolean handled, List<String> context) {
    public static final BeforeReply CONTINUE = new BeforeReply(false, List.of());
    public static final BeforeReply HANDLED = new BeforeReply(true, List.of());

    public BeforeReply {
        context = List.copyOf(context);
    }

    public static BeforeReply context(String... lines) {
        return new BeforeReply(false, List.of(lines));
    }

    public static BeforeReply context(List<String> lines) {
        return new BeforeReply(false, lines);
    }
}
