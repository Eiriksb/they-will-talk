package dev.eiriksb.theywilltalk;

import dev.eiriksb.theywilltalk.api.BeforeReply;
import dev.eiriksb.theywilltalk.api.ConversationHook;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.ReflectionField;
import dev.eiriksb.theywilltalk.api.PromptContributor;
import dev.eiriksb.theywilltalk.api.TheyWillTalkApi;
import dev.eiriksb.theywilltalk.api.TurnInfo;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** The implementation behind {@link TheyWillTalkApi}; TWT's own code calls the dispatch methods. */
public final class TwtApi implements TheyWillTalkApi {
    public static final TwtApi INSTANCE = new TwtApi();

    private final List<VillagerAdapter> adapters = new CopyOnWriteArrayList<>();
    private final List<PromptContributor> contributors = new CopyOnWriteArrayList<>();
    private final List<ConversationHook> hooks = new CopyOnWriteArrayList<>();
    private final List<ReflectionField> reflectionFields = new CopyOnWriteArrayList<>();
    private final MemoryApi memory = new TwtMemoryApi();

    private TwtApi() {}

    @Override
    public VillagerKind registerKind(String id, String label, BooleanSupplier enabled) {
        return VillagerKind.register(id, label, enabled);
    }

    @Override
    public void registerAdapter(VillagerAdapter adapter) {
        adapters.add(adapter);
        TheyWillTalk mod = TheyWillTalk.get();
        VillagerRegistry registry = mod == null ? null : mod.villagers();
        if (registry != null) {
            registry.addAdapterFirst(adapter);
        }
        TheyWillTalk.LOGGER.info("Villager adapter registered: {} ({})", adapter.kind().label, adapter.getClass().getName());
    }

    @Override
    public void addPromptContributor(PromptContributor contributor) {
        contributors.add(contributor);
    }

    @Override
    public void addConversationHook(ConversationHook hook) {
        hooks.add(hook);
    }

    @Override
    public boolean running() {
        TheyWillTalk mod = TheyWillTalk.get();
        return mod != null && mod.store() != null && mod.server() != null;
    }

    @Override
    public boolean canTalk(Entity entity) {
        TheyWillTalk mod = TheyWillTalk.get();
        VillagerRegistry registry = mod == null ? null : mod.villagers();
        return registry != null && registry.canTalk(entity);
    }

    @Override
    public MemoryApi memory() {
        return memory;
    }

    @Override
    public void addReflectionField(ReflectionField field) {
        if (field.name() == null || !field.name().matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Reflection field names are lowercase letters, digits and _: " + field.name());
        }
        reflectionFields.add(field);
    }

    /** The extra reflection fields that apply to this turn. */
    public List<ReflectionField> reflectionFields(TurnInfo turn) {
        return reflectionFields.stream().filter(f -> {
            try {
                return f.appliesTo(turn);
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("Reflection field {} failed: {}", f.name(), t.toString());
                return false;
            }
        }).toList();
    }

    // ---- used by TWT ---------------------------------------------------------------------------------------------

    /** Adds the adapters registered so far to a freshly made registry, ahead of the built-in ones. */
    void installAdapters(VillagerRegistry registry) {
        for (VillagerAdapter a : adapters.reversed()) {
            registry.addAdapterFirst(a);
        }
    }

    /** Server thread. */
    public void contribute(Entity villager, ServerPlayer player, VillagerFacts facts) {
        for (PromptContributor c : contributors) {
            try {
                List<String> lines = c.contribute(villager, player, facts);
                if (lines != null) {
                    lines.stream().filter(l -> l != null && !l.isBlank()).forEach(facts.context::add);
                }
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("Prompt contributor {} failed: {}", c.getClass().getName(), t.toString());
            }
        }
    }

    public boolean hasHooks() {
        return !hooks.isEmpty();
    }

    /**
     * Runs every hook's {@link ConversationHook#beforeReply} (side by side, each within the timeout). Background thread.
     *
     * @return whether a hook took the turn over; context lines are added to {@code turn.facts()}
     */
    public boolean beforeReply(TurnInfo turn) {
        List<CompletableFuture<BeforeReply>> calls = new ArrayList<>();
        for (ConversationHook h : hooks) {
            calls.add(CompletableFuture.supplyAsync(() -> h.beforeReply(turn), r -> Thread.ofVirtual().start(r)));
        }
        long deadline = System.currentTimeMillis() + ConversationHook.BEFORE_REPLY_TIMEOUT_MS;
        boolean handled = false;
        for (int i = 0; i < calls.size(); i++) {
            ConversationHook h = hooks.get(i);
            try {
                BeforeReply r = calls.get(i).get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (r != null) {
                    handled |= r.handled();
                    r.context().stream().filter(l -> l != null && !l.isBlank()).forEach(turn.facts().context::add);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                calls.get(i).cancel(true);
                TheyWillTalk.LOGGER.warn("Conversation hook {} took too long; answering without it", h.getClass().getName());
            } catch (Exception e) {
                TheyWillTalk.LOGGER.warn("Conversation hook {} failed: {}", h.getClass().getName(), e.toString());
            }
        }
        return handled;
    }

    /** Background thread. */
    public void afterReply(TurnInfo turn, String reply, String emotion) {
        for (ConversationHook h : hooks) {
            try {
                h.afterReply(turn, reply, emotion);
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("Conversation hook {} failed: {}", h.getClass().getName(), t.toString());
            }
        }
    }
}
