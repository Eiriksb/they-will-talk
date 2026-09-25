package dev.eiriksb.theywilltalk.villager;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** Name generation for villagers and villages, flavored by the villager's biome type. */
public final class Names {
    private static final Map<String, List<String>> FEMALE = Map.of(
            "plains", List.of("Rosie", "Marigold", "Hazel", "Clover", "Poppy", "Tilly", "Maud", "Edith", "Wren", "Bess", "Dorothea", "Fern", "Ivy", "Mabel", "Nell", "Primrose"),
            "desert", List.of("Samira", "Zahra", "Amira", "Layla", "Nadia", "Yasmin", "Farah", "Soraya", "Leila", "Dunya", "Imani", "Selma"),
            "savanna", List.of("Amara", "Zuri", "Nia", "Adaeze", "Kaya", "Imara", "Talia", "Nala", "Ayo", "Makena", "Sade", "Eshe"),
            "snow", List.of("Sigrid", "Astrid", "Ingrid", "Freya", "Solveig", "Liv", "Kari", "Ylva", "Runa", "Tove", "Hedda", "Embla"),
            "taiga", List.of("Katya", "Vera", "Mila", "Anya", "Olga", "Dasha", "Lena", "Yana", "Zlata", "Irina", "Nadya", "Sonya"),
            "jungle", List.of("Luana", "Maya", "Inti", "Yara", "Iara", "Naira", "Tainá", "Luz", "Ixchel", "Ceiba", "Aru", "Kiana"),
            "swamp", List.of("Moira", "Agatha", "Morwen", "Isolde", "Griselda", "Bryony", "Nettle", "Elspeth", "Hester", "Brambla", "Ottilie", "Sabine"));
    private static final Map<String, List<String>> MALE = Map.of(
            "plains", List.of("Bramble", "Tobias", "Alfred", "Barnaby", "Cedric", "Ned", "Oswald", "Percy", "Rufus", "Silas", "Walter", "Jasper", "Hugo", "Otto", "Gideon", "Milo"),
            "desert", List.of("Tariq", "Omar", "Karim", "Hassan", "Rami", "Sami", "Zayd", "Idris", "Faris", "Nabil", "Bashir", "Malik"),
            "savanna", List.of("Kofi", "Jabari", "Tendai", "Obi", "Sefu", "Kwame", "Dayo", "Baraka", "Juma", "Tau", "Zane", "Chike"),
            "snow", List.of("Bjørn", "Leif", "Sven", "Ragnar", "Einar", "Halvard", "Torvald", "Ulrik", "Knut", "Arne", "Eirik", "Stig"),
            "taiga", List.of("Ivan", "Dmitri", "Pavel", "Yuri", "Boris", "Oleg", "Mikhail", "Fyodor", "Lev", "Anton", "Sasha", "Grisha"),
            "jungle", List.of("Tupac", "Kai", "Ruma", "Mateo", "Inca", "Tayo", "Chaska", "Rafa", "Tenoch", "Joaquim", "Coyo", "Arawak"),
            "swamp", List.of("Mortimer", "Bartholomew", "Ezekiel", "Cornelius", "Thaddeus", "Fenwick", "Grimsby", "Horace", "Ambrose", "Lemuel", "Obadiah", "Silvanus"));
    private static final List<String> SURNAMES = List.of(
            "Oakes", "Thistlewood", "Carrotfield", "Ironhand", "Stonebrook", "Wheatley", "Brewer", "Fletcher", "Mossbottom",
            "Emeraldson", "Hayward", "Pumpkinpatch", "Cobble", "Birchwood", "Goldleaf", "Honeycomb", "Millstone", "Quill",
            "Bellweather", "Lanternjaw", "Beetroot", "Woolsey", "Fishburn", "Copperpot", "Mudfoot", "Barrowby", "Hearthstone");
    private static final List<String> VILLAGE_PREFIX = List.of(
            "Moss", "Oak", "Willow", "Stone", "Clover", "Amber", "Thorn", "Maple", "Honey", "Ember", "Frost", "Sun", "Bramble",
            "Copper", "Mist", "Fern", "Raven", "Pebble", "Hollow", "Wheat", "Birch", "Lantern", "Ash", "Pine", "Sand", "Reed");
    private static final List<String> VILLAGE_SUFFIX = List.of(
            "brook", "vale", "ford", "wick", "stead", "haven", "field", "mere", "dale", "bury", "hollow", "well", "ridge", "moor",
            "glen", "wood", "cross", "stone", "by", "hill");

    private Names() {}

    public static String first(String biomeType, boolean female, Random r) {
        Map<String, List<String>> pool = female ? FEMALE : MALE;
        List<String> list = pool.getOrDefault(biomeType, pool.get("plains"));
        return list.get(r.nextInt(list.size()));
    }

    public static String surname(Random r) {
        return SURNAMES.get(r.nextInt(SURNAMES.size()));
    }

    public static String village(long seed) {
        Random r = new Random(seed * 31 + 17);
        return VILLAGE_PREFIX.get(r.nextInt(VILLAGE_PREFIX.size())) + VILLAGE_SUFFIX.get(r.nextInt(VILLAGE_SUFFIX.size()));
    }
}
