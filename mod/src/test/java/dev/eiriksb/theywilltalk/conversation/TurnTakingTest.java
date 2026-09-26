package dev.eiriksb.theywilltalk.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnTakingTest {
    private static final TurnTaking.Candidate MARICA = new TurnTaking.Candidate("Marica", "cheerful", false, 0);
    private static final TurnTaking.Candidate SMAJO = new TurnTaking.Candidate("Smajo", "laid_back", false, 0);

    /** How often anyone chimes in, over many replies. */
    private static double rate(List<TurnTaking.Candidate> others, String player, String reply, int turnsSinceChime) {
        Random random = new Random(7);
        int spoke = 0;
        for (int i = 0; i < 10_000; i++) {
            if (!TurnTaking.decide(others, player, reply, false, turnsSinceChime, random).speakers().isEmpty()) {
                spoke++;
            }
        }
        return spoke / 10_000.0;
    }

    @Test
    void mostlyNobodyChimesIn() {
        double rate = rate(List.of(MARICA, SMAJO), "How is the forge today?", "Busy as ever, the smelter never sleeps.", 5);
        assertTrue(rate > 0.1 && rate < 0.3, "rate " + rate);
    }

    @Test
    void theGroupHoldsBackRightAfterItHadItsSay() {
        double fresh = rate(List.of(MARICA, SMAJO), "How is the forge?", "Busy as ever.", 5);
        double justSpoke = rate(List.of(MARICA, SMAJO), "How is the forge?", "Busy as ever.", 0);
        assertTrue(justSpoke < fresh / 2, fresh + " vs " + justSpoke);
    }

    @Test
    void someoneWhoIsMentionedUsuallyHasAWord() {
        double rate = rate(List.of(MARICA, SMAJO), "Is it true that Smajo burned the bread?", "Oh, he always does!", 0);
        assertTrue(rate > 0.6, "rate " + rate);
    }

    @Test
    void nobodyCutsInWhenThePlayerWasAskedSomething() {
        assertEquals(0, rate(List.of(MARICA, SMAJO), "Hello!", "Hello there! What brings you here today?", 5));
    }

    @Test
    void talkativeVillagersSpeakUpMoreThanQuietOnes() {
        assertTrue(TurnTaking.chance(MARICA, false, false, 5) > 2 * TurnTaking.chance(SMAJO, false, false, 5));
        var wife = new TurnTaking.Candidate("Vera", "friendly", true, 0);
        var stranger = new TurnTaking.Candidate("Ante", "friendly", false, 0);
        assertTrue(TurnTaking.chance(wife, false, false, 5) > TurnTaking.chance(stranger, false, false, 5));
    }

    @Test
    void atMostOneChimesInUnlessEveryoneWasAsked() {
        Random random = new Random(3);
        for (int i = 0; i < 1000; i++) {
            assertTrue(TurnTaking.decide(List.of(MARICA, SMAJO), "Marica and Smajo, hello", "Hi!", false, 5, random).speakers().size() <= 1);
        }
        assertEquals(List.of("Marica", "Smajo"),
                TurnTaking.decide(List.of(MARICA, SMAJO), "What do you all think?", "I love it!", true, 0, random).speakers());
    }
}
