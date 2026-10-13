package dev.eiriksb.theywilltalk.api;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The language model TWT runs, for other mods' own calls (a citizen planning their day, a builder drafting a house).
 * Requests share TWT's queue: a player waiting for an answer always goes first.
 */
public interface LlmApi {
    enum Priority {
        /** someone is waiting for it */
        CONVERSATION,
        /** needed soon, nobody is waiting (day plans, decisions) */
        PLANNING,
        /** whenever there's time */
        BACKGROUND
    }

    /** Whether the model is running. */
    boolean ready();

    /** Whether requests about {@code player} can run: on the server's model, or on the GPU the player lends (API v6). */
    default boolean readyFor(UUID player) {
        return ready();
    }

    /**
     * The same model, for requests about {@code player} (something they're waiting for in their own conversation).
     * These may run on the GPU that player lends, so put nothing in them about other players (API v6).
     */
    default LlmApi forPlayer(UUID player) {
        return this;
    }

    /**
     * The planner model: a bigger one the admin picked for planning (building designs), started when needed. The chat
     * model when none is set (API v6).
     */
    default LlmApi planner() {
        return this;
    }

    /** Requests waiting for this model: background work can hold off while it's long (API v6). */
    default int backlog() {
        return 0;
    }

    /**
     * Asks for JSON matching {@code schema} (enforced by the model's grammar). Completes with the JSON text; fails when
     * the model isn't running or the request fails.
     */
    CompletableFuture<String> json(String system, String user, JsonObject schema, int maxTokens, Priority priority);

    /** Asks for plain text. */
    CompletableFuture<String> text(String system, String user, int maxTokens, Priority priority);
}
