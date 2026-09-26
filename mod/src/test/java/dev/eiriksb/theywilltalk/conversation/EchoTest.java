package dev.eiriksb.theywilltalk.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoTest {
    private static boolean echo(String heard, String spoken) {
        return EchoFilter.isEcho(EchoFilter.words(heard), EchoFilter.words(spoken));
    }

    @Test
    void recognisesAVillagerLineComingBackThroughAMicrophone() {
        String said = "Oh, apples! I have plenty of them. One emerald buys you four of these crisp apples.";
        // speech recognition rarely returns the exact words
        assertTrue(echo("oh apples I have plenty of them one emerald buys four crisp apples", said));
        // or only part of a long line
        assertTrue(echo("working the forge all morning", "I've been working the forge all morning and my arms are tired."));
        // short lines come back whole
        assertTrue(echo("yes", "Yes!"));
        assertTrue(echo("hello there", "Hello there!"));
        assertTrue(echo("of course dear", "Of course, dear!"));
    }

    @Test
    void answersThatReuseTheVillagersWordsAreNotEchoes() {
        assertFalse(echo("No thanks, I'm looking for a blacksmith instead",
                "Oh, apples! I have plenty of them. One emerald buys you four of these crisp apples."));
        assertFalse(echo("Hello, David.", "Hello there! I'm David, the village records keeper!"));
        assertFalse(echo("Hello!", "Hello there, Dev!"));
        assertFalse(echo("Hello there, David!", "Hello there, Dev!"));
        assertFalse(echo("Sure, I'll bring you the flint.", "Could you bring me eight pieces of flint for four emeralds?"));
        assertFalse(echo("Yes, of course.", "Of course, dear!"));
        assertFalse(echo("What kind of sparkly ones?", "I just want sparkly ones for my dear husband!"));
    }

    @Test
    void anAnswerMixedWithTheVillagersVoiceStillCounts() {
        // Open speakers: the microphone caught the end of the villager's line and then the player's answer.
        assertFalse(echo("hello there dev yes I'd love to help you with that", "Hello there, Dev!"));
    }
}
