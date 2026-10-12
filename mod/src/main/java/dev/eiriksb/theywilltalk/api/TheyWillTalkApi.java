package dev.eiriksb.theywilltalk.api;

import dev.eiriksb.theywilltalk.TwtApi;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import net.minecraft.world.entity.Entity;

import java.util.function.BooleanSupplier;

/**
 * The public API for mods built on They Will Talk. Get it with {@link #get()} at any time, also while mods are being
 * constructed. Register kinds and adapters early (mod constructor or common setup); everything else may be added any
 * time. {@link TwtReadyEvent} is posted on the game bus once a world is loaded and TWT is running.
 *
 * <p>The API is this package plus {@link VillagerAdapter}, {@link dev.eiriksb.theywilltalk.villager.VillagerFacts},
 * {@link VillagerKind} and {@link dev.eiriksb.theywilltalk.villager.VillagerProfile}. Everything else in TWT is
 * internal and may change without notice. {@link #VERSION} goes up whenever something is added.
 */
public interface TheyWillTalkApi {
    /** 1: kinds, adapters, prompt contributors, conversation hooks. 2: {@link #canTalk}. */
    int VERSION = 2;

    static TheyWillTalkApi get() {
        return TwtApi.INSTANCE;
    }

    /**
     * Adds a kind of villager (or returns the existing one with this id). The id is stored in the database: keep it
     * stable.
     *
     * @param enabled whether villagers of this kind may talk right now, usually backed by the mod's own config
     */
    VillagerKind registerKind(String id, String label, BooleanSupplier enabled);

    /** Lets TWT talk to another mod's entities. Checked before TWT's built-in adapters. */
    void registerAdapter(VillagerAdapter adapter);

    /** Adds lines to the prompt of every conversation turn (with any kind of villager). */
    void addPromptContributor(PromptContributor contributor);

    /** Called around each reply to something a player said. */
    void addConversationHook(ConversationHook hook);

    /** Whether a world is loaded and TWT is running (its database is open). */
    boolean running();

    /** Whether TWT can talk to this entity right now: an adapter knows it and its kind is enabled. Server thread. */
    boolean canTalk(Entity entity);
}
