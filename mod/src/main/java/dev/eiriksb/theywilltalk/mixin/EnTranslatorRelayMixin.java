package dev.eiriksb.theywilltalk.mixin;

import dev.eiriksb.theywilltalk.integration.EnTranslatorBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * EN Translator transcribes (and translates to English) each player's voice on their own client and sends the text
 * to the server, which relays it to everyone as chat bubbles. We listen in on that relay. {@code @Pseudo}: when EN
 * Translator isn't installed this mixin is silently skipped.
 */
@Pseudo
@Mixin(targets = "com.example.voicetranslator.translation.TranslationService", remap = false)
public abstract class EnTranslatorRelayMixin {
    @Inject(method = "relayClientTranslation", at = @At("HEAD"), remap = false, require = 0)
    private void theywilltalk$onRelay(UUID speakerId, String sourceText, String translatedText, String sourceLanguage,
                                      String translationLanguage, int displayDurationMs, int lineId, boolean partial,
                                      CallbackInfo ci) {
        EnTranslatorBridge.onTranscript(speakerId, sourceText, translatedText, sourceLanguage, translationLanguage, lineId, partial);
    }
}
