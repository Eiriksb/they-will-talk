package dev.eiriksb.theywilltalk.villager;

import dev.eiriksb.theywilltalk.TwtConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What sort of villager an entity is. The built-in kinds are constants; other mods add their own through
 * {@link dev.eiriksb.theywilltalk.api.TheyWillTalkApi#registerKind}. Kinds are singletons, so compare them with
 * {@code ==}. The id is what the database stores.
 */
public final class VillagerKind {
    private static final Map<String, VillagerKind> KINDS = new LinkedHashMap<>();

    public static final VillagerKind VANILLA = register("vanilla", "Villager", () -> TwtConfig.VANILLA_VILLAGERS.get());
    public static final VillagerKind WANDERING_TRADER = register("wandering_trader", "Wandering Trader", () -> TwtConfig.WANDERING_TRADERS.get());
    public static final VillagerKind MCA = register("mca", "MCA Villager", () -> TwtConfig.MCA_VILLAGERS.get());
    public static final VillagerKind MINECOLONIES = register("minecolonies", "Colonist", () -> TwtConfig.MINECOLONIES_CITIZENS.get());

    public final String id;
    public final String label;
    private final BooleanSupplier enabled;

    private VillagerKind(String id, String label, BooleanSupplier enabled) {
        this.id = id;
        this.label = label;
        this.enabled = enabled;
    }

    /**
     * Adds a kind, or returns the existing one with that id.
     *
     * @param enabled whether villagers of this kind may talk right now (usually a config option)
     */
    public static VillagerKind register(String id, String label, BooleanSupplier enabled) {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Villager kind ids are lowercase letters, digits and _: " + id);
        }
        synchronized (KINDS) {
            return KINDS.computeIfAbsent(id, k -> new VillagerKind(id, label, enabled));
        }
    }

    /** The kind with this id; unknown ids (a mod that has since been removed) count as vanilla. */
    public static VillagerKind byId(String id) {
        synchronized (KINDS) {
            return KINDS.getOrDefault(id, VANILLA);
        }
    }

    public static List<VillagerKind> all() {
        synchronized (KINDS) {
            return new ArrayList<>(KINDS.values());
        }
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    @Override
    public String toString() {
        return id;
    }
}
