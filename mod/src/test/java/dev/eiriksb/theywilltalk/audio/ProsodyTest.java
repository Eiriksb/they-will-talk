package dev.eiriksb.theywilltalk.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProsodyTest {
    @Test
    void emotionsShapeDelivery() {
        Prosody neutral = Prosody.forLine(1.0, 1.0, "neutral", 0);
        Prosody angry = Prosody.forLine(1.0, 1.0, "angry", 0);
        Prosody sad = Prosody.forLine(1.0, 1.0, "sad", 0);
        assertTrue(angry.speed() > neutral.speed() && angry.pitch() < neutral.pitch() && angry.gain() > 1);
        assertTrue(sad.speed() < neutral.speed() && sad.gain() < 1 && sad.leadInMs() > 0);
    }

    @Test
    void ttsSpeedCompensatesForThePitchShift() {
        Prosody p = Prosody.forLine(1.2, 1.0, "neutral", 0);
        // engine speed x resample speed-up (pitch) = requested tempo
        assertEquals(p.speed(), p.ttsSpeed() * p.pitch(), 1e-6);
    }

    @Test
    void lastingMoodBiasesTheVoice() {
        assertTrue(Prosody.forLine(1, 1, "neutral", 15).speed() > Prosody.forLine(1, 1, "neutral", -15).speed());
    }
}
