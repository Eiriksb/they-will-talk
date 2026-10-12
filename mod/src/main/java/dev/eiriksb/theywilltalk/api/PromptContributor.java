package dev.eiriksb.theywilltalk.api;

import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Adds what's going on to a villager's prompt for one conversation turn: "You're building the bakery; the roof is
 * half done", "Bjørn's town is at war with yours". Lines go under "Right now" in the system prompt.
 */
@FunctionalInterface
public interface PromptContributor {
    /**
     * Called on the server thread as a turn starts, so the world may be read. Keep it quick: the player is waiting.
     *
     * @param player null when nobody in particular is being talked to
     * @return lines for the prompt, or an empty list
     */
    List<String> contribute(Entity villager, ServerPlayer player, VillagerFacts facts);
}
