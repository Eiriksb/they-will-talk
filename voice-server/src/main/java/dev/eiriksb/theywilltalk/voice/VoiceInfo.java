package dev.eiriksb.theywilltalk.voice;

/**
 * One selectable voice.
 *
 * @param id     globally unique id, {@code engine/name}
 * @param engine engine id
 * @param sid    speaker id inside the engine
 * @param name   short display name
 * @param gender "f", "m" or "n"
 * @param lang   BCP-47-ish language of the voice, e.g. "en-us", "en-gb", "es", "nb"
 * @param traits free-form description used when matching voices to villager personalities
 */
public record VoiceInfo(String id, String engine, int sid, String name, String gender, String lang, String traits) {}
