package dev.eiriksb.theywilltalk.villager;

import java.util.UUID;

/**
 * The persistent identity of a talking villager (stored in the database). Name and gender come from MCA /
 * MineColonies when available and are generated otherwise; personality, quirk, backstory and voice are always ours
 * and can be edited by the admin in the dashboard. {@code voiceDesign} is an optional hand-written Qwen3-TTS voice
 * description; when empty one is generated from the personality ({@link VoiceDesign#base}).
 */
public record VillagerProfile(
        UUID uuid,
        VillagerKind kind,
        String name,
        String gender,
        String persona,
        String quirk,
        String backstory,
        String voice,
        double pitch,
        double speed,
        String customPrompt,
        long createdAt,
        String voiceDesign) {

    public Persona personaEnum() {
        return Persona.byKey(persona);
    }

    public String firstName() {
        int i = name.indexOf(' ');
        return i < 0 ? name : name.substring(0, i);
    }

    public VillagerProfile withName(String n) {
        return new VillagerProfile(uuid, kind, n, gender, persona, quirk, backstory, voice, pitch, speed, customPrompt, createdAt, voiceDesign);
    }

    public VillagerProfile withIdentity(String n, String g, VillagerKind k) {
        return new VillagerProfile(uuid, k, n, g, persona, quirk, backstory, voice, pitch, speed, customPrompt, createdAt, voiceDesign);
    }

    public VillagerProfile withPersona(String p) {
        return new VillagerProfile(uuid, kind, name, gender, p, quirk, backstory, voice, pitch, speed, customPrompt, createdAt, voiceDesign);
    }

    public VillagerProfile withVoice(String v, double p, double s) {
        return new VillagerProfile(uuid, kind, name, gender, persona, quirk, backstory, v, p, s, customPrompt, createdAt, voiceDesign);
    }

    public VillagerProfile withStory(String q, String b, String prompt) {
        return new VillagerProfile(uuid, kind, name, gender, persona, q, b, voice, pitch, speed, prompt, createdAt, voiceDesign);
    }

    public VillagerProfile withVoiceDesign(String d) {
        return new VillagerProfile(uuid, kind, name, gender, persona, quirk, backstory, voice, pitch, speed, customPrompt, createdAt, d);
    }
}
