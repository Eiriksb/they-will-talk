package dev.eiriksb.theywilltalk.api;

/**
 * One line of a conversation between a player and a villager, as stored.
 *
 * @param time       when it was said (epoch milliseconds)
 * @param fromPlayer whether the player said it (else the villager did)
 * @param speaker    the name of whoever said it
 * @param emotion    the villager's mood as they said it ("happy", "angry"...), or null for the player's lines
 */
public record ChatLine(long time, boolean fromPlayer, String speaker, String text, String emotion) {}
