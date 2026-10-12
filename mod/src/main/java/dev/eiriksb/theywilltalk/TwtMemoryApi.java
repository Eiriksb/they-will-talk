package dev.eiriksb.theywilltalk;

import dev.eiriksb.theywilltalk.api.Memory;
import dev.eiriksb.theywilltalk.api.MemoryApi;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.data.Store;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** {@link MemoryApi} on top of the current world's {@link Store}. */
final class TwtMemoryApi implements MemoryApi {
    private static Store store() {
        TheyWillTalk mod = TheyWillTalk.get();
        Store s = mod == null ? null : mod.store();
        if (s == null) {
            throw new IllegalStateException("They Will Talk isn't running (no world loaded)");
        }
        return s;
    }

    private static <T> CompletableFuture<T> read(Function<Store, CompletableFuture<T>> f) {
        try {
            return f.apply(store());
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static void write(Consumer<Store> f) {
        f.accept(store());
    }

    @Override
    public CompletableFuture<Long> remember(NewMemory memory) {
        return read(s -> s.remember(memory));
    }

    @Override
    public CompletableFuture<List<Memory>> about(UUID villager, Subject subject, int limit) {
        return read(s -> s.memoriesAbout(villager, subject, limit));
    }

    @Override
    public CompletableFuture<List<Memory>> recent(UUID villager, int limit) {
        return read(s -> s.recentMemories(villager, limit));
    }

    @Override
    public CompletableFuture<List<Memory>> recall(UUID villager, String text, int limit) {
        return read(s -> s.recall(villager, text, limit));
    }

    @Override
    public CompletableFuture<Integer> tell(UUID from, UUID to, Subject subject, int minImportance, int limit) {
        return read(s -> s.tell(from, to, subject, minImportance, limit));
    }

    @Override
    public CompletableFuture<Relation> relation(UUID a, UUID b) {
        return read(s -> s.villagerRelation(a, b));
    }

    @Override
    public CompletableFuture<List<Relation>> relations(UUID villager, int limit) {
        return read(s -> s.villagerRelations(villager, limit));
    }

    @Override
    public void adjustRelation(UUID a, UUID b, int affinityDelta, int familiarityDelta) {
        write(s -> s.adjustVillagerRelation(a, b, affinityDelta, familiarityDelta));
    }

    @Override
    public void setRelationKind(UUID a, UUID b, String kind) {
        write(s -> s.setVillagerRelationKind(a, b, kind));
    }

    @Override
    public CompletableFuture<Integer> affinity(UUID villager, UUID player) {
        return read(s -> s.relationship(villager, player).thenApply(Store.Relationship::affinity));
    }

    @Override
    public void adjustAffinity(UUID villager, UUID player, int delta) {
        TheyWillTalk mod = TheyWillTalk.get();
        if (mod != null && mod.conversationManager() != null) {
            mod.conversationManager().adjustAffinity(villager, player, delta);
        } else {
            write(s -> s.adjustAffinity(villager, player, delta));
        }
    }
}
