package dev.eiriksb.theywilltalk.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRankingTest {
    private record M(String name, double bm25, int importance, long created) {}

    private static final long NOW = 1_000_000_000_000L;
    private static final long DAY = 24L * 3600 * 1000;

    @Test
    void queryKeepsMeaningfulWordsQuoted() {
        assertEquals("\"fix\" OR \"roof\" OR \"bakery\"", MemoryRanking.ftsQuery("Hey, did you fix the roof of the bakery? Fix it!"));
        assertNull(MemoryRanking.ftsQuery("Hi! How are you?"));
        assertNull(MemoryRanking.ftsQuery(null));
    }

    @Test
    void queryCannotBreakOutOfQuotes() {
        String q = MemoryRanking.ftsQuery("roof\" OR villager_uuid:*");
        assertTrue(q.startsWith("\"roof\""), q);
        assertTrue(!q.contains("*"), q);
    }

    @Test
    void recencyHalvesEveryWeek() {
        assertEquals(1.0, MemoryRanking.recency(0), 1e-9);
        assertEquals(0.5, MemoryRanking.recency(7 * DAY), 1e-9);
        assertEquals(0.25, MemoryRanking.recency(14 * DAY), 1e-9);
    }

    @Test
    void relevantBeatsRecentTrivia() {
        M promise = new M("promise to fix the roof", -6.0, 7, NOW - 10 * DAY);
        M weather = new M("talked about the weather", -1.0, 2, NOW - DAY / 2);
        M raid = new M("the raid", -2.0, 9, NOW - 30 * DAY);
        List<M> ranked = MemoryRanking.rank(List.of(weather, raid, promise), M::bm25, M::importance, M::created, NOW, 2);
        assertEquals(List.of(promise, raid), ranked);
    }

    @Test
    void importanceBreaksTiesBetweenEquallyRelevantMemories() {
        M small = new M("small", -3.0, 2, NOW - DAY);
        M big = new M("big", -3.0, 9, NOW - DAY);
        assertEquals(big, MemoryRanking.rank(List.of(small, big), M::bm25, M::importance, M::created, NOW, 1).getFirst());
    }
}
