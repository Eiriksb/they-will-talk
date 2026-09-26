package dev.eiriksb.theywilltalk.events;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsTest {
    @Test
    void jobsFromEveryModFindTheirNeeds() {
        assertTrue(Needs.forJob("farmer", false, false).needs().stream().anyMatch(n -> n.id().equals("wheat")));
        assertTrue(Needs.forJob("weaponsmith", false, false).needs().stream().anyMatch(n -> n.id().equals("iron_ingot")));
        assertTrue(Needs.forJob("librarian", false, false).needs().stream().anyMatch(n -> n.id().equals("paper")));
        assertSame(Needs.forJob("nitwit (no job, never will have one)", false, false), Needs.JOBS.getFirst());
        // MineColonies and MCA jobs
        assertTrue(Needs.forJob("builder", false, false).needs().stream().anyMatch(n -> n.id().equals("oak_planks")));
        assertTrue(Needs.forJob("knight", false, false).huntChance() > 0.5);
        assertTrue(Needs.forJob("guard", false, false).huntChance() > 0.5);
        assertTrue(Needs.forJob("chickenherder", false, false).needs().stream().anyMatch(n -> n.id().equals("wheat_seeds")));
        // no job, children and traders
        assertSame(Needs.DEFAULT, Needs.forJob("unemployed", false, false));
        assertSame(Needs.DEFAULT, Needs.forJob(null, false, false));
        assertSame(Needs.CHILD, Needs.forJob("farmer", true, false));
        assertSame(Needs.TRADER, Needs.forJob("wandering trader", false, true));
    }

    @Test
    void everyJobHasSensibleNeedsAndGifts() {
        Stream.concat(Needs.JOBS.stream(), Stream.of(Needs.DEFAULT, Needs.CHILD, Needs.TRADER)).forEach(job -> {
            assertFalse(job.needs().isEmpty(), job.keywords().toString());
            assertFalse(job.gifts().isEmpty(), job.keywords().toString());
            for (Needs.Item n : job.needs()) {
                assertTrue(n.min() >= 1 && n.min() <= n.max() && n.max() <= 64, n.id());
                assertTrue(n.value() > 0, n.id());
                assertTrue(n.id().matches("[a-z_]+") && !n.label().isBlank(), n.id());
            }
        });
    }

    @Test
    void rewardsBeatTradingButStaySmall() {
        Needs.Item wheat = new Needs.Item("wheat", 8, 16, 0.12, "wheat");
        assertEquals(3, Needs.reward(wheat, 8, false));
        assertEquals(4, Needs.reward(wheat, 16, false));
        Needs.Item compass = new Needs.Item("compass", 1, 1, 3, "compass");
        assertEquals(6, Needs.reward(compass, 1, false));
        assertEquals(16, Needs.reward(new Needs.Item("x", 64, 64, 5, "x"), 64, false));
        assertEquals(1, Needs.reward(new Needs.Item("poppy", 1, 3, 0.4, "poppies"), 1, true));
        assertTrue(Needs.reward(new Needs.Item("x", 64, 64, 5, "x"), 64, true) <= 3);
    }

    @Test
    void picksOnlyItemsTheWorldHas() {
        Random random = new Random(1);
        List<Needs.Item> items = Needs.forJob("farmer", false, false).needs();
        for (int i = 0; i < 20; i++) {
            Needs.Item n = Needs.pick(items, random, id -> id.equals("carrot"));
            assertEquals("carrot", n.id());
            int count = Needs.count(n, random);
            assertTrue(count >= n.min() && count <= n.max());
        }
        assertNull(Needs.pick(items, random, id -> false));
    }

    @Test
    void huntsCountTheRightMonsters() {
        Needs.Hunt zombies = Needs.hunt("zombies");
        assertTrue(zombies.entities().containsAll(List.of("zombie", "husk", "drowned")));
        assertFalse(zombies.entities().contains("skeleton"));
        assertEquals(4, Needs.huntReward(zombies, 3));
        assertEquals(7, Needs.huntReward(Needs.hunt("creepers"), 3));
        assertNull(Needs.hunt("dragons"));
    }
}
