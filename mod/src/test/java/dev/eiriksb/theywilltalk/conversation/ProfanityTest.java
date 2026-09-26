package dev.eiriksb.theywilltalk.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfanityTest {
    @Test
    void censorsSwearWordsKeepingTheFirstLetter() {
        assertEquals("Get off my f****** field, you b******!", Profanity.censor("Get off my fucking field, you bastard!", true));
        assertEquals("What a load of b*******.", Profanity.censor("What a load of bullshit.", true));
        assertEquals("F*** deg, din j****** d********!", Profanity.censor("Faen deg, din jævlige drittsekk!", true));
    }

    @Test
    void leavesInnocentWordsAndMildOnesAlone() {
        String clean = "The fickle assassin in Scunthorpe checked the cockpit, then passed the class. Slutt! Damn, bloody hell, what crap.";
        assertEquals(clean, Profanity.censor(clean, true));
        assertFalse(Profanity.contains(clean, true));
    }

    @Test
    void swearingIsAllowedWithoutBleepButSlursNeverAre() {
        assertEquals("Oh shit.", Profanity.censor("Oh shit.", false));
        assertTrue(Profanity.contains("You retard", false));
        assertEquals("You r*****", Profanity.censor("You retard", false));
    }

    @Test
    void splitsALineIntoSpeechAndBeeps() {
        List<Profanity.Part> parts = Profanity.split("Well, shit, the cow ate my wheat.", true);
        assertEquals(List.of(new Profanity.Part("Well, ", false), new Profanity.Part("shit", true),
                new Profanity.Part(", the cow ate my wheat.", false)), parts);
        assertEquals(List.of(new Profanity.Part("No swearing here.", false)), Profanity.split("No swearing here.", true));
        assertEquals(280, Profanity.beepMs("shit"));
    }
}
