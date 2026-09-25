package dev.eiriksb.theywilltalk.villager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Knows how to read (and nudge) one kind of villager. Implementations for optional mods live in their own classes
 * and are only loaded when that mod is present.
 */
public interface VillagerAdapter {
    VillagerKind kind();

    boolean matches(Entity entity);

    /** Fills {@code facts}; called on the server thread. {@code player} may be null (dashboard / ambient chat). */
    void collect(Entity entity, ServerPlayer player, VillagerFacts facts);

    /** Applies a conversation outcome (e.g. MCA hearts); server thread. */
    default void applyOutcome(Entity entity, ServerPlayer player, int affinityDelta) {}

    /** Stop wandering and look at the player while talking; server thread, every tick of a conversation. */
    default void holdAttention(Entity entity, Entity target) {}
}
