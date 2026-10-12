package dev.eiriksb.theywilltalk.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * An extra question in the reflection TWT runs after each reply to a player (it already asks how the villager's
 * opinion changed, their mood, and what to remember). Adding a field costs no extra LLM call.
 *
 * <p>Example: a {@code promise} field (string, "" for none) with the instruction "a promise {player} made to
 * {villager} in this exchange, in a few words, or """. Placeholders: {@code {villager}} and {@code {player}} are
 * replaced with first names.
 */
public interface ReflectionField {
    /** The JSON key: lowercase letters, digits and _, unique across mods (prefix it: {@code stad_promise}). */
    String name();

    /** JSON schema for the value; keep it small and constrained (enums, min/max, maxLength). */
    JsonObject schema();

    /** One line telling the model what to put there. */
    String instruction();

    /** Whether to ask at all for this turn (e.g. only for your own kind of villager). */
    default boolean appliesTo(TurnInfo turn) {
        return true;
    }

    /** Called with the answer, on a background thread. */
    void apply(TurnInfo turn, JsonElement value);
}
