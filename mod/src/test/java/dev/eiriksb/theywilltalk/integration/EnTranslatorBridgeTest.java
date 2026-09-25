package dev.eiriksb.theywilltalk.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnTranslatorBridgeTest {
    @Test
    void prefersTheEnglishTranslation() {
        assertEquals("Do you have apples?", EnTranslatorBridge.pickEnglish("Har du epler?", "Do you have apples?", "no", "en"));
    }

    @Test
    void usesTheSourceWhenThePlayerAlreadySpokeEnglish() {
        assertEquals("Hello Stella", EnTranslatorBridge.pickEnglish("Hello Stella", "", "en", "en"));
    }

    @Test
    void fallsBackToWhateverTextExists() {
        assertEquals("Hei", EnTranslatorBridge.pickEnglish("Hei", "", "no", "de"));
    }
}
