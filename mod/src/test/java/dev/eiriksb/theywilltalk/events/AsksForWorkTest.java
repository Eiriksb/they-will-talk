package dev.eiriksb.theywilltalk.events;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsksForWorkTest {
    @Test
    void recognisesPlayersAskingForSomethingToDo() {
        for (String s : List.of("You have any requests for me by chance?", "Do you have any work for me?", "Need anything?",
                "Is there anything I can do for you?", "Got any quests?", "Can I help you with something?", "Any errands today?",
                "What can I do for you?", "Do you need some help?", "Hast du eine Aufgabe für mich?", "Har du et oppdrag til meg?")) {
            assertTrue(AsksForWork.test(s), s);
        }
    }

    @Test
    void leavesOrdinaryTalkAlone() {
        for (String s : List.of("Hello, David.", "What kind of sparkly ones?", "I want something to eat.", "Nice weather today.",
                "Where is the blacksmith?", "Sure, I'll bring you the flint.")) {
            assertFalse(AsksForWork.test(s), s);
        }
    }
}
