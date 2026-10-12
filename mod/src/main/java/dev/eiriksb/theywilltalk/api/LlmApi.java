package dev.eiriksb.theywilltalk.api;

import com.google.gson.JsonObject;

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

    /**
     * Asks for JSON matching {@code schema} (enforced by the model's grammar). Completes with the JSON text; fails when
     * the model isn't running or the request fails.
     */
    CompletableFuture<String> json(String system, String user, JsonObject schema, int maxTokens, Priority priority);

    /** Asks for plain text. */
    CompletableFuture<String> text(String system, String user, int maxTokens, Priority priority);
}
