package dev.eiriksb.theywilltalk.api;

import dev.eiriksb.theywilltalk.TwtApi;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Optional;
import java.util.UUID;

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
    /**
     * 1: kinds, adapters, prompt contributors, conversation hooks. 2: {@link #canTalk}. 3: {@link #memory}, reflection
     * fields. 4: {@link #llm}, {@link #speak}, {@link #profile}. 5: {@link #talk}, {@link #history}, and voice designs from
     * adapters ({@code VillagerFacts.voiceDesign}, {@code voicePitch}, {@code voiceSpeed}).
     */
    int VERSION = 5;

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

    /** Villagers' memories and relationships (with players and with each other). */
    MemoryApi memory();

    /** Adds a question to the reflection after each reply to a player. */
    void addReflectionField(ReflectionField field);

    /** The language model, for other mods' own calls. */
    LlmApi llm();

    /**
     * The villager says something to a player on their own initiative, in their own words and voice: {@code instruction}
     * tells them what's going on ("You just finished building the cottage; tell them proudly"). Server thread.
     *
     * @return false when they can't right now (already talking, the model isn't running)
     */
    boolean speak(Entity villager, ServerPlayer player, String instruction);

    /** Who a villager is (name, personality, quirk, backstory), once TWT has met them. */
    Optional<VillagerProfile> profile(UUID villager);

    /**
     * A player says something to a particular villager, as if typed in chat while looking at them (for a mod's own
     * chat screen). The reply comes as usual: in chat, spoken, and to the conversation hooks. Server thread.
     *
     * @return false when the villager can't talk right now (unknown, switched off, or the model isn't running)
     */
    boolean talk(Entity villager, ServerPlayer player, String text);

    /** The last {@code limit} lines between a villager and a player, oldest first. */
    java.util.concurrent.CompletableFuture<java.util.List<ChatLine>> history(UUID villager, UUID player, int limit);
}
