package dev.eiriksb.theywilltalk.villager;

public enum VillagerKind {
    VANILLA("vanilla", "Villager"),
    WANDERING_TRADER("wandering_trader", "Wandering Trader"),
    MCA("mca", "MCA Villager"),
    MINECOLONIES("minecolonies", "Colonist");

    public final String id;
    public final String label;

    VillagerKind(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static VillagerKind byId(String id) {
        for (VillagerKind k : values()) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        return VANILLA;
    }
}
