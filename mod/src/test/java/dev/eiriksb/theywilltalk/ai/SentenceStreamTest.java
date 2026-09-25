package dev.eiriksb.theywilltalk.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceStreamTest {
    @Test
    void extractsEmotionTagAndSplitsSentencesWhileStreaming() {
        List<String> out = new ArrayList<>();
        SentenceStream ss = new SentenceStream(out::add);
        for (String piece : List.of("[ang", "ry] Hmph", ". Who trampled ", "my carrots? ", "I bet it was ", "that golem!")) {
            ss.accept(piece);
        }
        // the first sentence is emitted before the stream ends
        assertEquals(List.of("Hmph. Who trampled my carrots?"), out);
        ss.finish();
        assertEquals("angry", ss.emotion());
        assertEquals(List.of("Hmph. Who trampled my carrots?", "I bet it was that golem!"), out);
    }

    @Test
    void mapsLooseEmotionNamesAndDefaultsToNeutral() {
        SentenceStream a = new SentenceStream(s -> { });
        a.accept("(Cheerful) Hello there, friend!");
        assertEquals("happy", a.emotion());
        SentenceStream b = new SentenceStream(s -> { });
        b.accept("Hello there, friend!");
        assertEquals("neutral", b.emotion());
    }

    @Test
    void stripsStageDirectionsMarkdownAndEmoji() {
        assertEquals("Welcome to Mossbrook!", SentenceStream.clean("*waves* **Welcome** to Mossbrook! 😀"));
        assertEquals("Oh my.", SentenceStream.clean("(sighs deeply) Oh my."));
    }

    @Test
    void doesNotSplitDecimalsOrEllipses() {
        List<String> out = SentenceStream.split("[neutral] That costs 3.5 emeralds... maybe. Or not!");
        assertEquals(List.of("That costs 3.5 emeralds... maybe.", "Or not!"), out);
        assertTrue(out.stream().noneMatch(s -> s.contains("[")));
    }
}
