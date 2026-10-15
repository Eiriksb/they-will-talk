package dev.eiriksb.theywilltalk.api;

/**
 * Runs around a villager's reply to something a player said (typed, spoken or by command): {@link #beforeReply} and
 * {@link #afterReply} aren't called when the villager speaks first or chats with other villagers, and run on a
 * background thread, never the server thread (use {@code server.execute} to touch the world). {@link #spoke} is called
 * for every line a villager says, on the server thread.
 */
public interface ConversationHook {
    /**
     * Before the reply is written. May block for a moment, e.g. for a quick LLM call that works out whether the player
     * gave an order, but the player is waiting: hooks get {@value #BEFORE_REPLY_TIMEOUT_MS} ms, then TWT goes on
     * without them.
     */
    default BeforeReply beforeReply(TurnInfo turn) {
        return BeforeReply.CONTINUE;
    }

    /** After the villager has answered (the reply is complete; the voice may still be playing). */
    default void afterReply(TurnInfo turn, String reply, String emotion) {}

    /**
     * A villager says something (a reply, chiming in, chatting with another villager, speaking up), as it's shown to the
     * players near them: what they said and how they felt saying it ("happy", "angry"...). Server thread. Since 0.3.0-alpha.9.
     */
    default void spoke(java.util.UUID villager, String text, String emotion) {}

    long BEFORE_REPLY_TIMEOUT_MS = 8_000;
}
