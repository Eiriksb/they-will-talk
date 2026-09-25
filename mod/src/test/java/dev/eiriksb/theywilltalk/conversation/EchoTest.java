package dev.eiriksb.theywilltalk.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoTest {
    @Test
    void recognisesAVillagerLineComingBackThroughAMicrophone() {
        var said = EchoFilter.words("Oh, apples! I have plenty of them. One emerald buys you four of these crisp apples.");
        // speech recognition rarely returns the exact words
        var heard = EchoFilter.words("oh apples I have plenty of them one emerald buys four crisp apples");
        assertTrue(EchoFilter.similarity(heard, said) >= 0.45);
    }

    @Test
    void doesNotConfuseAnAnswerWithAnEcho() {
        var said = EchoFilter.words("Oh, apples! I have plenty of them. One emerald buys you four of these crisp apples.");
        var reply = EchoFilter.words("No thanks, I'm looking for a blacksmith instead");
        assertTrue(EchoFilter.similarity(reply, said) < 0.45);
    }
}
