package dev.eiriksb.theywilltalk.villager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

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

    /**
     * Hurry towards {@code target}; server thread, called every few ticks while walking up to a player.
     *
     * @return false when this kind of villager can't be steered (it calls out instead)
     */
    default boolean walkTo(Entity entity, Entity target) {
        if (entity instanceof Mob mob) {
            mob.getNavigation().moveTo(target, 1.0);
            mob.getLookControl().setLookAt(target, 30f, 30f);
            return true;
        }
        return false;
    }

    /** Stop walking towards whoever {@link #walkTo} was heading for. */
    default void stopWalking(Entity entity) {
        if (entity instanceof Mob mob) {
            mob.getNavigation().stop();
        }
    }
}
