package dev.eiriksb.theywilltalk.api;

import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;

import java.util.UUID;

/**
 * One thing a player said to a villager.
 *
 * @param text         what was said, in English (translated by Sipher when spoken in another language)
 * @param originalText the player's own words when they spoke another language, else null
 * @param language     the player's language code ("en", "de", ...)
 * @param channel      "voice", "text", "command" or "dashboard"
 * @param facts        what the villager knew as the turn started; read it, don't keep it
 */
public record TurnInfo(UUID villager, String villagerName, VillagerKind kind, UUID player, String playerName, String text,
                       String originalText, String language, String channel, VillagerFacts facts) {}
