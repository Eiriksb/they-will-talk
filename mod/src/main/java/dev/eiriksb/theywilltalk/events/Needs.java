package dev.eiriksb.theywilltalk.events;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * What villagers ask players for and give them, by job. Vanilla professions, MCA's and MineColonies' jobs are matched
 * by keywords in the job name. Item ids are vanilla ones; ids a world doesn't have are skipped.
 */
public final class Needs {
    /**
     * Something a villager needs or gives.
     *
     * @param label how the villager says it, fitting any count ("wheat", "carrots")
     * @param value emeralds a single one is worth to them
     */
    public record Item(String id, int min, int max, double value, String label) {}

    /** A kind of monster a villager wants gone; {@code entities} are the entity type ids that count. */
    public record Hunt(String id, String label, List<String> entities, int min, int max, int emeraldsEach) {}

    /**
     * @param huntChance how often this job asks for monsters to be dealt with rather than for things
     */
    record Job(List<String> keywords, List<Item> needs, List<Item> gifts, double huntChance) {}

    private static Item i(String id, int min, int max, double value, String label) {
        return new Item(id, min, max, value, label);
    }

    private static Job job(List<String> keywords, List<Item> needs, List<Item> gifts, double huntChance) {
        return new Job(keywords, needs, gifts, huntChance);
    }

    static final List<Hunt> HUNTS = List.of(
            new Hunt("zombies", "zombies", List.of("zombie", "husk", "drowned", "zombie_villager"), 3, 6, 1),
            new Hunt("skeletons", "skeletons", List.of("skeleton", "stray", "bogged"), 2, 5, 1),
            new Hunt("spiders", "spiders", List.of("spider", "cave_spider"), 2, 5, 1),
            new Hunt("creepers", "creepers", List.of("creeper"), 1, 3, 2));

    private static final List<Item> FOOD_GIFTS = List.of(i("bread", 2, 4, 0, "bread"), i("apple", 2, 4, 0, "apples"),
            i("cookie", 3, 6, 0, "cookies"));

    /** First match wins, so more specific keywords come first. */
    static final List<Job> JOBS = List.of(
            job(List.of("nitwit"), List.of(i("cake", 1, 1, 3, "cake"), i("cookie", 4, 8, 0.3, "cookies"),
                    i("pumpkin_pie", 1, 2, 1, "pumpkin pies")), List.of(i("dandelion", 1, 3, 0, "dandelions")), 0.1),
            job(List.of("farmer", "planter", "composter"), List.of(i("wheat", 8, 16, 0.12, "wheat"), i("carrot", 6, 12, 0.15, "carrots"),
                    i("potato", 6, 12, 0.15, "potatoes"), i("beetroot", 6, 12, 0.15, "beetroots"), i("bone_meal", 4, 8, 0.3, "bone meal")),
                    List.of(i("bread", 2, 4, 0, "bread"), i("baked_potato", 3, 5, 0, "baked potatoes"), i("pumpkin_pie", 1, 2, 0, "pumpkin pie")), 0.1),
            job(List.of("fisher"), List.of(i("string", 4, 8, 0.4, "string"), i("stick", 8, 16, 0.08, "sticks"), i("coal", 6, 12, 0.12, "coal")),
                    List.of(i("cooked_cod", 2, 4, 0, "cooked cod"), i("cooked_salmon", 2, 3, 0, "cooked salmon")), 0.1),
            job(List.of("shepherd"), List.of(i("shears", 1, 1, 2, "shears"), i("white_dye", 3, 6, 0.4, "white dye"), i("wheat", 8, 16, 0.12, "wheat")),
                    List.of(i("white_wool", 4, 8, 0, "wool")), 0.1),
            job(List.of("butcher", "cow", "chicken", "swine", "rabbit", "herder"), List.of(i("wheat", 8, 16, 0.12, "wheat"),
                    i("carrot", 6, 12, 0.15, "carrots"), i("wheat_seeds", 8, 16, 0.08, "seeds"), i("coal", 6, 12, 0.12, "coal")),
                    List.of(i("cooked_porkchop", 2, 4, 0, "cooked porkchops"), i("cooked_beef", 2, 4, 0, "steaks")), 0.2),
            job(List.of("fletcher"), List.of(i("stick", 16, 32, 0.05, "sticks"), i("flint", 6, 12, 0.2, "flint"),
                    i("feather", 6, 12, 0.2, "feathers"), i("string", 4, 8, 0.4, "string")), List.of(i("arrow", 8, 16, 0, "arrows")), 0.3),
            job(List.of("librarian", "teacher", "researcher", "pupil", "student", "library"), List.of(i("paper", 8, 16, 0.12, "paper"),
                    i("book", 2, 4, 0.6, "books"), i("ink_sac", 3, 6, 0.4, "ink sacs"), i("feather", 3, 6, 0.2, "feathers")),
                    List.of(i("book", 1, 3, 0, "books")), 0.05),
            job(List.of("cartographer"), List.of(i("paper", 8, 16, 0.12, "paper"), i("glass_pane", 6, 12, 0.15, "glass panes"),
                    i("compass", 1, 1, 3, "compass")), List.of(i("map", 1, 1, 0, "empty map")), 0.05),
            job(List.of("enchanter"), List.of(i("lapis_lazuli", 6, 12, 0.3, "lapis lazuli"), i("book", 2, 4, 0.6, "books")),
                    List.of(i("experience_bottle", 2, 4, 0, "bottles o' enchanting")), 0.1),
            job(List.of("cleric", "healer", "alchemist", "druid"), List.of(i("rotten_flesh", 8, 16, 0.08, "rotten flesh"),
                    i("glass_bottle", 4, 8, 0.25, "glass bottles"), i("redstone", 6, 12, 0.2, "redstone"),
                    i("nether_wart", 3, 6, 0.5, "nether wart")), List.of(i("redstone", 4, 8, 0, "redstone"),
                    i("lapis_lazuli", 4, 8, 0, "lapis lazuli"), i("experience_bottle", 1, 3, 0, "bottles o' enchanting")), 0.2),
            job(List.of("leather"), List.of(i("leather", 4, 8, 0.5, "leather"), i("rabbit_hide", 3, 6, 0.4, "rabbit hides")),
                    List.of(i("leather", 3, 6, 0, "leather")), 0.15),
            job(List.of("armorer", "weaponsmith", "toolsmith", "blacksmith", "smelter", "mechanic", "smith"),
                    List.of(i("iron_ingot", 3, 6, 0.6, "iron ingots"), i("coal", 8, 16, 0.12, "coal"), i("flint", 4, 8, 0.2, "flint")),
                    List.of(i("iron_ingot", 2, 4, 0, "iron ingots")), 0.3),
            job(List.of("mason", "stone", "quarr", "crusher", "sifter", "concrete"), List.of(i("clay_ball", 8, 16, 0.1, "clay"),
                    i("stone", 16, 32, 0.04, "stone"), i("andesite", 8, 16, 0.06, "andesite"), i("sand", 16, 32, 0.04, "sand")),
                    List.of(i("brick", 8, 16, 0, "bricks")), 0.1),
            job(List.of("builder", "sawmill", "carpenter"), List.of(i("oak_planks", 16, 32, 0.04, "oak planks"),
                    i("cobblestone", 16, 32, 0.03, "cobblestone"), i("oak_log", 8, 16, 0.1, "oak logs")), FOOD_GIFTS, 0.1),
            job(List.of("lumberjack", "forester"), List.of(i("oak_sapling", 4, 8, 0.2, "oak saplings"), i("bone_meal", 4, 8, 0.3, "bone meal")),
                    List.of(i("apple", 2, 4, 0, "apples")), 0.15),
            job(List.of("miner"), List.of(i("torch", 16, 32, 0.05, "torches"), i("coal", 8, 16, 0.12, "coal"), i("bread", 3, 6, 0.3, "bread")),
                    List.of(i("coal", 4, 8, 0, "coal")), 0.3),
            job(List.of("baker", "cook"), List.of(i("wheat", 8, 16, 0.12, "wheat"), i("sugar", 4, 8, 0.2, "sugar"),
                    i("egg", 3, 6, 0.3, "eggs"), i("coal", 6, 12, 0.12, "coal")), List.of(i("bread", 2, 4, 0, "bread"),
                    i("cookie", 4, 8, 0, "cookies"), i("cake", 1, 1, 0, "cake")), 0.05),
            job(List.of("guard", "knight", "ranger", "archer", "warrior", "mercenary", "combat", "adventurer"),
                    List.of(i("arrow", 16, 32, 0.04, "arrows"), i("bread", 4, 8, 0.25, "bread"), i("iron_ingot", 2, 4, 0.6, "iron ingots")),
                    List.of(i("arrow", 8, 16, 0, "arrows"), i("cooked_beef", 2, 4, 0, "steaks")), 0.6),
            job(List.of("florist", "beekeeper"), List.of(i("poppy", 4, 8, 0.15, "poppies"), i("dandelion", 4, 8, 0.15, "dandelions"),
                    i("glass_bottle", 4, 8, 0.25, "glass bottles")), List.of(i("honey_bottle", 1, 2, 0, "honey")), 0.05),
            job(List.of("glass"), List.of(i("sand", 16, 32, 0.04, "sand"), i("coal", 8, 16, 0.12, "coal")),
                    List.of(i("glass", 4, 8, 0, "glass")), 0.1),
            job(List.of("dyer"), List.of(i("red_dye", 3, 6, 0.3, "red dye"), i("yellow_dye", 3, 6, 0.3, "yellow dye"),
                    i("ink_sac", 3, 6, 0.4, "ink sacs")), FOOD_GIFTS, 0.1),
            job(List.of("deliveryman", "courier"), List.of(i("bread", 3, 6, 0.3, "bread"), i("leather", 3, 6, 0.5, "leather")), FOOD_GIFTS, 0.15),
            job(List.of("undertaker", "graveyard"), List.of(i("bone", 6, 12, 0.1, "bones"), i("rotten_flesh", 8, 16, 0.08, "rotten flesh")),
                    FOOD_GIFTS, 0.3),
            job(List.of("nether"), List.of(i("gold_nugget", 8, 16, 0.08, "gold nuggets"), i("obsidian", 2, 4, 0.5, "obsidian")), FOOD_GIFTS, 0.2));

    static final Job DEFAULT = job(List.of(), List.of(i("bread", 3, 6, 0.3, "bread"), i("apple", 3, 6, 0.3, "apples"),
            i("torch", 8, 16, 0.08, "torches"), i("oak_log", 8, 16, 0.1, "oak logs"), i("cooked_beef", 2, 4, 0.4, "steaks"),
            i("poppy", 3, 6, 0.2, "poppies")), FOOD_GIFTS, 0.15);

    static final Job CHILD = job(List.of(), List.of(i("poppy", 1, 3, 0.4, "poppies"), i("dandelion", 1, 3, 0.4, "dandelions"),
            i("cookie", 2, 4, 0.4, "cookies"), i("sweet_berries", 4, 8, 0.15, "sweet berries"), i("apple", 2, 3, 0.4, "apples")),
            List.of(i("poppy", 1, 1, 0, "poppy"), i("dandelion", 1, 1, 0, "dandelion"), i("cornflower", 1, 1, 0, "cornflower")), 0.05);

    static final Job TRADER = job(List.of(), List.of(i("amethyst_shard", 4, 8, 0.4, "amethyst shards"), i("honeycomb", 3, 6, 0.4, "honeycomb"),
            i("leather", 4, 8, 0.5, "leather"), i("lead", 1, 2, 1, "leads")), List.of(i("emerald", 1, 2, 0, "emeralds")), 0.1);

    private Needs() {}

    static Job forJob(String job, boolean child, boolean trader) {
        if (trader) {
            return TRADER;
        }
        if (child) {
            return CHILD;
        }
        String j = job == null ? "" : job.toLowerCase(Locale.ROOT);
        for (Job candidate : JOBS) {
            if (candidate.keywords().stream().anyMatch(j::contains)) {
                return candidate;
            }
        }
        return DEFAULT;
    }

    /** One of the job's needs that this world has, or null. */
    static Item pick(List<Item> items, RandomGenerator random, Predicate<String> exists) {
        List<Item> available = items.stream().filter(n -> exists.test(n.id())).toList();
        return available.isEmpty() ? null : available.get(random.nextInt(available.size()));
    }

    static int count(Item item, RandomGenerator random) {
        return item.min() >= item.max() ? item.min() : item.min() + random.nextInt(item.max() - item.min() + 1);
    }

    /** Emeralds for bringing {@code count}: better than trading them, at least 2 (children: 1 to 3). */
    static int reward(Item item, int count, boolean child) {
        long r = Math.round(1.5 + count * item.value() * 1.5);
        return (int) (child ? Math.clamp(r / 2, 1, 3) : Math.clamp(r, 2, 16));
    }

    static Hunt pickHunt(RandomGenerator random) {
        return HUNTS.get(random.nextInt(HUNTS.size()));
    }

    static Hunt hunt(String id) {
        return HUNTS.stream().filter(h -> h.id().equals(id)).findFirst().orElse(null);
    }

    static int huntCount(Hunt hunt, RandomGenerator random) {
        return hunt.min() + random.nextInt(hunt.max() - hunt.min() + 1);
    }

    static int huntReward(Hunt hunt, int count) {
        return Math.clamp((long) count * hunt.emeraldsEach() + 1, 2, 16);
    }
}
