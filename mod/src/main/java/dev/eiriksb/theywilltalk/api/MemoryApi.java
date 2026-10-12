package dev.eiriksb.theywilltalk.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Villagers' memories and relationships, stored in TWT's database. Every method may be called from any thread; reads
 * and writes run on the database thread. While no world is loaded the futures fail with {@link IllegalStateException}.
 */
public interface MemoryApi {
    /** Adds a memory; completes with its id. */
    CompletableFuture<Long> remember(NewMemory memory);

    /** Their latest memories about a subject, newest first. */
    CompletableFuture<List<Memory>> about(UUID villager, Subject subject, int limit);

    /** Their latest memories about anything, newest first. */
    CompletableFuture<List<Memory>> recent(UUID villager, int limit);

    /**
     * The memories that best fit some text (what a player just said, a topic): matched by words, then ranked by
     * relevance, importance and how recent they are.
     */
    CompletableFuture<List<Memory>> recall(UUID villager, String text, int limit);

    /**
     * {@code from} tells {@code to} what they remember about a subject: up to {@code limit} of their most important
     * memories of at least {@code minImportance} that {@code to} doesn't know yet, copied as hearsay (one point less
     * important). {@link Subject#NONE} for any subject. Completes with how many were passed on.
     */
    CompletableFuture<Integer> tell(UUID from, UUID to, Subject subject, int minImportance, int limit);

    /** How {@code a} sees {@code b} ({@link Relation#none} if they've never met). */
    CompletableFuture<Relation> relation(UUID a, UUID b);

    /** Everyone {@code villager} knows, best known first. */
    CompletableFuture<List<Relation>> relations(UUID villager, int limit);

    /** Changes how {@code a} sees {@code b}; affinity is kept within -100..100. */
    void adjustRelation(UUID a, UUID b, int affinityDelta, int familiarityDelta);

    /** Names what {@code b} is to {@code a} ("friend", "spouse"...); null clears it. */
    void setRelationKind(UUID a, UUID b, String kind);

    /** How a villager feels about a player, -100..100. */
    CompletableFuture<Integer> affinity(UUID villager, UUID player);

    /** Changes how a villager feels about a player. */
    void adjustAffinity(UUID villager, UUID player, int delta);
}
