package dev.eiriksb.theywilltalk.voice;

import java.util.List;

public interface TtsEngine {
    String id();

    List<VoiceInfo> voices();

    /** @param lang espeak language code to phonemize with, or null for the voice default */
    Audio synthesize(String text, int sid, float speed, String lang);

    boolean supportsLanguage(String lang);

    /** Maps a requested language (e.g. "pt", "en-gb") to the engine's phonemizer code for the given voice. */
    default String espeakLang(String lang, VoiceInfo voice) {
        return null;
    }

    /** Best voice for a language, preferring the gender; {@code seed} spreads villagers over the candidates. */
    default VoiceInfo bestVoice(String lang, String gender, int seed) {
        String base = VoiceServer.baseLang(lang);
        List<VoiceInfo> sameLang = voices().stream()
                .filter(v -> "multi".equals(v.lang()) || VoiceServer.baseLang(v.lang()).equals(base)).toList();
        List<VoiceInfo> pool = sameLang.isEmpty() ? voices() : sameLang;
        List<VoiceInfo> sameGender = pool.stream().filter(v -> v.gender().equals(gender)).toList();
        List<VoiceInfo> finalPool = sameGender.isEmpty() ? pool : sameGender;
        return finalPool.get(Math.floorMod(seed, finalPool.size()));
    }
}
