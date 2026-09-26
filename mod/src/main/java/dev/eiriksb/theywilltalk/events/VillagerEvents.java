package dev.eiriksb.theywilltalk.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.LlmClient;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import dev.eiriksb.theywilltalk.conversation.Gestures;
import dev.eiriksb.theywilltalk.conversation.LiveFeed;
import dev.eiriksb.theywilltalk.conversation.PromptBuilder;
import dev.eiriksb.theywilltalk.data.Store;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import dev.eiriksb.theywilltalk.villager.VillagerRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;

import java.lang.ref.WeakReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Villagers taking the initiative. They walk up to players they want to talk to - to greet a friend, give a gift, ask
 * a favour or collect a finished one - and hand out errands: bring them something their job needs, deal with some
 * monsters, or take a letter to someone in the village. Errands are checked against the game (inventories, kills),
 * paid in emeralds and goodwill, and kept in the world's database. Server thread unless noted.
 */
public final class VillagerEvents implements ConversationManager.Hooks {
    /** How close a player has to be to hand things over. */
    private static final double HAND_OVER = 3.5;
    /** How far away a villager notices a player. */
    private static final double SPOT_RANGE = 20;
    /** Close enough to call out when they can't get any closer. */
    private static final double CALL_OUT = 9;
    private static final double ARRIVED = 2.8;
    private static final long WALK_TIMEOUT_MS = 30_000;
    private static final long OFFER_TTL_MS = 4 * 60_000L;
    private static final long MINUTE = 60_000L;
    private static final Pattern ASKS_FOR_WORK = Pattern.compile("(?iu)\\b(quests?|tasks?|errands?|missions?|jobs? for me|work for me|"
            + "any work|anything (i|we) can do|need (any |some )?help|can (i|we) help|help you|favou?rs?|oppdrag|aufgaben?|auftrag|"
            + "tareas?|misi[oó]n|qu[eê]tes?|travail|lavoro|incarichi)\\b");

    enum Reason { GREET, GIFT, OFFER, COLLECT }

    private final MinecraftServer server;
    private final VillagerRegistry villagers;
    private final Store store;
    private final ConversationManager conversations;
    private final LlmClient llm;
    private final LiveFeed feed;
    private final Supplier<Boolean> llmReady;
    private final Random random = new Random();
    private final AtomicLong nextId = new AtomicLong(1);

    /** Offered and active errands, by id. */
    private final Map<Long, Errand> open = new ConcurrentHashMap<>();
    /** Villagers walking up to a player, by player. */
    private final Map<UUID, Approach> approaches = new HashMap<>();
    /** When a villager last walked up to each player. */
    private final Map<UUID, Long> lastApproach = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** Errands offered while a villager answers a player; their chat card follows the answer. By villager. */
    private final Map<UUID, Errand> offeredInTurn = new HashMap<>();
    private long ticks;

    public VillagerEvents(MinecraftServer server, VillagerRegistry villagers, Store store, ConversationManager conversations,
                          LlmClient llm, LiveFeed feed, Supplier<Boolean> llmReady) {
        this.server = server;
        this.villagers = villagers;
        this.store = store;
        this.conversations = conversations;
        this.llm = llm;
        this.feed = feed;
        this.llmReady = llmReady;
        load();
    }

    private void load() {
        try {
            long now = System.currentTimeMillis();
            nextId.set(store.db.query("SELECT COALESCE(MAX(id), 0) FROM errands", rs -> rs.getLong(1)).get(10, TimeUnit.SECONDS).getFirst() + 1);
            // Offers wait for an answer in the moment; after a restart they're gone.
            store.db.exec("UPDATE errands SET status='expired', updated=? WHERE status='offered'", now);
            for (Errand e : store.db.query("SELECT * FROM errands WHERE status='active'", VillagerEvents::row).get(10, TimeUnit.SECONDS)) {
                open.put(e.id, e);
            }
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Could not load errands: {}", e.toString());
        }
    }

    private static Errand row(ResultSet rs) throws SQLException {
        String target = rs.getString("target_uuid");
        Errand e = new Errand(Errand.Kind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)), UUID.fromString(rs.getString("villager_uuid")),
                rs.getString("villager_name"), UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"), rs.getString("item"),
                rs.getString("label"), rs.getInt("count"), target == null ? null : UUID.fromString(target), rs.getString("target_name"),
                rs.getInt("reward"), rs.getLong("created"));
        e.id = rs.getLong("id");
        e.progress = rs.getInt("progress");
        e.status = Errand.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT));
        e.updated = rs.getLong("updated");
        e.deadline = rs.getLong("deadline");
        e.request = rs.getString("request") == null ? "" : rs.getString("request");
        return e;
    }

    private void save(Errand e) {
        e.updated = System.currentTimeMillis();
        store.db.exec("""
                INSERT INTO errands (id, villager_uuid, villager_name, player_uuid, player_name, kind, item, label, count, progress,
                  target_uuid, target_name, reward, status, created, updated, deadline, request)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET progress=excluded.progress, status=excluded.status, updated=excluded.updated,
                  deadline=excluded.deadline, request=excluded.request""",
                e.id, e.villager, e.villagerName, e.player, e.playerName, e.kind.name().toLowerCase(Locale.ROOT), e.item, e.label,
                e.count, e.progress, e.target, e.targetName, e.reward, e.status.id(), e.created, e.updated, e.deadline, e.request);
    }

    /** Gives a planned errand its id and starts tracking it as an offer. */
    private void register(Errand e) {
        e.id = nextId.getAndIncrement();
        e.status = Errand.Status.OFFERED;
        e.deadline = System.currentTimeMillis() + OFFER_TTL_MS;
        open.put(e.id, e);
        save(e);
        publish(e, "asked " + e.playerName + " to " + e.forMe());
    }

    private void close(Errand e, Errand.Status status) {
        e.status = status;
        open.remove(e.id);
        save(e);
    }

    // =========================================================================================================
    // Ticking
    // =========================================================================================================

    public void tick() {
        ticks++;
        if (!approaches.isEmpty()) {
            stepApproaches();
        }
        if (ticks % 20 == 0) {
            checkErrands();
        }
        if (ticks % 40 == 0 && llmReady.get()) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                maybeApproach(p);
            }
        }
    }

    private void checkErrands() {
        long now = System.currentTimeMillis();
        for (Errand e : List.copyOf(open.values())) {
            if (e.status == Errand.Status.OFFERED) {
                if (now > e.deadline) {
                    close(e, Errand.Status.EXPIRED);
                }
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(e.player);
            if (now > e.deadline) {
                expire(e, p);
                continue;
            }
            if (p == null) {
                continue;
            }
            boolean ready = ready(e, p);
            if (ready && !e.readyNotified && e.kind == Errand.Kind.FETCH) {
                p.sendSystemMessage(prefix().append(Component.literal("You have the " + e.count + " " + e.label + " " + e.villagerName
                        + " wants. Take them over!").withStyle(ChatFormatting.WHITE)));
                ding(p, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f);
            }
            e.readyNotified = ready;
            if (ready && !collecting(e)) {
                Entity receiver = p.serverLevel().getEntity(e.receiver());
                if (receiver != null && receiver.isAlive() && receiver.distanceTo(p) <= HAND_OVER && !conversations.talking(receiver.getUUID())) {
                    complete(e, receiver, p, true);
                }
            }
        }
    }

    // =========================================================================================================
    // Walking up to players
    // =========================================================================================================

    private final class Approach {
        final WeakReference<Entity> villager;
        final UUID villagerId;
        final UUID player;
        final Reason reason;
        final Errand errand;
        final long started = System.currentTimeMillis();
        boolean canWalk = true;

        Approach(Entity villager, ServerPlayer player, Reason reason, Errand errand) {
            this.villager = new WeakReference<>(villager);
            this.villagerId = villager.getUUID();
            this.player = player.getUUID();
            this.reason = reason;
            this.errand = errand;
        }
    }

    private void maybeApproach(ServerPlayer p) {
        if (p.isSpectator() || p.isSleeping() || !p.isAlive() || approaches.containsKey(p.getUUID())) {
            return;
        }
        long now = System.currentTimeMillis();
        long sinceTalk = now - conversations.lastTalk(p.getUUID());
        if (sinceTalk < 8_000) {
            return; // they're talking with someone
        }
        ServerLevel level = p.serverLevel();
        // A finished errand: its villager comes to collect it, whatever the time. (A letter's recipient doesn't know it's coming.)
        for (Errand e : errands(p.getUUID())) {
            if (e.status != Errand.Status.ACTIVE || e.kind == Errand.Kind.DELIVER || now - e.lastCollectAttempt < MINUTE || !ready(e, p)) {
                continue;
            }
            Entity receiver = level.getEntity(e.receiver());
            if (receiver != null && available(receiver) && receiver.distanceTo(p) <= SPOT_RANGE) {
                e.lastCollectAttempt = now;
                start(new Approach(receiver, p, Reason.COLLECT, e), receiver, p);
                return;
            }
        }
        if (sinceTalk < 30_000 || now - lastApproach.getOrDefault(p.getUUID(), 0L) < TwtConfig.APPROACH_COOLDOWN_SECONDS.get() * 1000L
                || !(level.dimensionType().hasFixedTime() || level.isDay())) {
            return; // nobody goes out to chat at night
        }
        List<Entity> near = level.getEntities(p, p.getBoundingBox().inflate(SPOT_RANGE), e -> villagers.canTalk(e) && available(e));
        if (near.isEmpty()) {
            return;
        }
        Entity v = near.get(random.nextInt(near.size()));
        int affinity = conversations.affinity(v.getUUID(), p.getUUID());
        if (TwtConfig.GREETINGS.get() && affinity >= 25 && !cooling("greet", v.getUUID(), p.getUUID()) && random.nextFloat() < 0.35f) {
            cool("greet", v.getUUID(), p.getUUID(), 20 * MINUTE);
            start(new Approach(v, p, Reason.GREET, null), v, p);
        } else if (TwtConfig.GIFTS.get() && affinity >= 55 && !cooling("gift", v.getUUID(), p.getUUID()) && random.nextFloat() < 0.06f) {
            cool("gift", v.getUUID(), p.getUUID(), 8 * 60 * MINUTE);
            start(new Approach(v, p, Reason.GIFT, null), v, p);
        } else if (TwtConfig.ERRANDS.get() && affinity >= -10 && !cooling("offer", v.getUUID(), p.getUUID()) && canTakeErrand(p)
                && errandBetween(v.getUUID(), p.getUUID()) == null && random.nextFloat() < 0.03f) {
            cool("offer", v.getUUID(), p.getUUID(), 30 * MINUTE);
            Errand planned = plan(v, p);
            if (planned != null) {
                start(new Approach(v, p, Reason.OFFER, planned), v, p);
            }
        }
    }

    private void start(Approach a, Entity v, ServerPlayer p) {
        lastApproach.put(p.getUUID(), System.currentTimeMillis());
        if (!TwtConfig.APPROACH_PLAYERS.get()) {
            // Not walking over: only speak up when they're close anyway.
            if (v.distanceTo(p) <= CALL_OUT) {
                arrive(a, v, p);
            }
            return;
        }
        approaches.put(p.getUUID(), a);
        TheyWillTalk.LOGGER.debug("[events] {} walks up to {} ({}, {} blocks)", name(v), p.getGameProfile().getName(), a.reason, (int) v.distanceTo(p));
    }

    private void stepApproaches() {
        long now = System.currentTimeMillis();
        Iterator<Approach> it = approaches.values().iterator();
        List<Runnable> arrivals = new ArrayList<>();
        while (it.hasNext()) {
            Approach a = it.next();
            Entity v = a.villager.get();
            ServerPlayer p = server.getPlayerList().getPlayer(a.player);
            VillagerAdapter adapter = v == null ? null : villagers.adapterFor(v);
            if (v == null || p == null || adapter == null || !v.isAlive() || v.level() != p.level() || p.isSpectator()
                    || conversations.talking(v.getUUID())) {
                if (v != null && adapter != null) {
                    adapter.stopWalking(v);
                }
                it.remove();
                continue;
            }
            double d = v.distanceTo(p);
            boolean timedOut = now - a.started > WALK_TIMEOUT_MS;
            if (d <= ARRIVED || ((timedOut || !a.canWalk) && d <= CALL_OUT && p.hasLineOfSight(v))) {
                it.remove();
                arrivals.add(() -> arrive(a, v, p));
            } else if (timedOut || d > SPOT_RANGE * 2) {
                TheyWillTalk.LOGGER.debug("[events] {} gave up walking to {} ({} blocks left)", name(v), p.getGameProfile().getName(), (int) d);
                adapter.stopWalking(v);
                it.remove();
            } else if (ticks % 5 == 0) {
                a.canWalk = adapter.walkTo(v, p);
            }
        }
        arrivals.forEach(Runnable::run);
    }

    private void arrive(Approach a, Entity v, ServerPlayer p) {
        TheyWillTalk.LOGGER.debug("[events] {} reached {} ({}, {} blocks, {} s)", name(v), p.getGameProfile().getName(), a.reason,
                (int) v.distanceTo(p), (System.currentTimeMillis() - a.started) / 1000);
        VillagerAdapter adapter = villagers.adapterFor(v);
        if (adapter != null) {
            adapter.stopWalking(v);
            adapter.holdAttention(v, p);
        }
        String playerName = p.getGameProfile().getName();
        switch (a.reason) {
            case GREET -> conversations.event(v, p, PromptBuilder.greetingRequest(playerName), null);
            case GIFT -> gift(v, p);
            case OFFER -> offer(v, p, a.errand);
            case COLLECT -> {
                if (a.errand.status == Errand.Status.ACTIVE && ready(a.errand, p)) {
                    complete(a.errand, v, p, true);
                }
            }
        }
    }

    /** Free to walk up to someone: not talking, sleeping, trading or already on their way to another player. */
    private boolean available(Entity e) {
        if (!e.isAlive() || conversations.talking(e.getUUID()) || busy(e.getUUID())) {
            return false;
        }
        if (e instanceof LivingEntity le && le.isSleeping()) {
            return false;
        }
        return !(e instanceof AbstractVillager av && av.isTrading());
    }

    @Override
    public boolean busy(UUID villager) {
        for (Approach a : approaches.values()) {
            if (a.villagerId.equals(villager)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================================================
    // Gifts
    // =========================================================================================================

    private void gift(Entity v, ServerPlayer p) {
        VillagerFacts f = villagers.facts(v, p);
        VillagerProfile prof = villagers.profile(v, f);
        Needs.Item g = Needs.pick(job(f).gifts(), random, ErrandItems::exists);
        if (g == null) {
            return;
        }
        int n = Needs.count(g, random);
        ErrandItems.give(p, g.id(), n);
        String playerName = p.getGameProfile().getName();
        p.sendSystemMessage(tag("Gift", ChatFormatting.LIGHT_PURPLE).append(Component.literal(prof.name() + " gave you " + n + " × "
                + ErrandItems.name(g.id())).withStyle(ChatFormatting.WHITE)));
        ding(p, SoundEvents.ITEM_PICKUP, 1.0f);
        Gestures.react(v, f.kind, "happy");
        conversations.remember(prof.uuid(), prof.name(), p.getUUID(), playerName, "gift",
                "I gave " + playerName + " " + n + " " + g.label() + " as a present", 1);
        conversations.event(v, p, PromptBuilder.giftRequest(playerName, n + " " + g.label()), null);
    }

    // =========================================================================================================
    // Errands
    // =========================================================================================================

    /** Picks a favour this villager could ask of the player: something their job needs, monsters, or a letter. */
    private Errand plan(Entity v, ServerPlayer p) {
        return plan(v, p, null);
    }

    /** @param kind the kind of errand wanted, or null for whatever fits */
    private Errand plan(Entity v, ServerPlayer p, Errand.Kind kind) {
        VillagerFacts f = villagers.facts(v, p);
        VillagerProfile prof = villagers.profile(v, f);
        boolean child = child(f);
        boolean trader = f.kind == VillagerKind.WANDERING_TRADER;
        Needs.Job job = job(f);
        long now = System.currentTimeMillis();
        String playerName = p.getGameProfile().getName();
        Errand e = null;
        if (kind == Errand.Kind.DELIVER || kind == null && !trader && random.nextFloat() < (child ? 0.1f : 0.22f)) {
            Entity to = recipient(v, f);
            VillagerProfile tp = to == null ? null : villagers.cached(to.getUUID());
            if (tp != null) {
                e = new Errand(Errand.Kind.DELIVER, prof.uuid(), prof.name(), p.getUUID(), playerName, "paper", "letter", 1, tp.uuid(),
                        tp.name(), 2 + random.nextInt(3), now);
            }
        }
        if (e == null && (kind == Errand.Kind.HUNT || kind == null && random.nextDouble() < job.huntChance())) {
            Needs.Hunt hunt = Needs.pickHunt(random);
            int n = Needs.huntCount(hunt, random);
            int reward = Needs.huntReward(hunt, n);
            e = new Errand(Errand.Kind.HUNT, prof.uuid(), prof.name(), p.getUUID(), playerName, hunt.id(), hunt.label(), n, null, null,
                    child ? Math.max(1, reward / 2) : reward, now);
        }
        if (e == null && kind != Errand.Kind.DELIVER) {
            Needs.Item need = Needs.pick(job.needs(), random, ErrandItems::exists);
            if (need == null) {
                return null;
            }
            int n = Needs.count(need, random);
            e = new Errand(Errand.Kind.FETCH, prof.uuid(), prof.name(), p.getUUID(), playerName, need.id(), need.label(), n, null, null,
                    Needs.reward(need, n, child), now);
        }
        if (e != null) {
            e.village = f.village == null ? null : f.village.name();
        }
        return e;
    }

    /**
     * An admin makes something happen now (/twt event): the villager walks up to the player to greet them, give a gift,
     * or ask a favour ({@code errand}, or {@code fetch}, {@code hunt}, {@code deliver} for one kind).
     *
     * @return what happened, for the admin
     */
    public String trigger(Entity v, ServerPlayer p, String what) {
        if (v.level() != p.level()) {
            return "They're in different dimensions.";
        }
        if (conversations.talking(v.getUUID()) || busy(v.getUUID())) {
            return name(v) + " is busy.";
        }
        if (TwtConfig.APPROACH_PLAYERS.get() ? v.distanceTo(p) > SPOT_RANGE * 2 : v.distanceTo(p) > CALL_OUT) {
            return name(v) + " is too far away (" + (int) v.distanceTo(p) + " blocks).";
        }
        Approach a;
        switch (what) {
            case "greet" -> a = new Approach(v, p, Reason.GREET, null);
            case "gift" -> a = new Approach(v, p, Reason.GIFT, null);
            case "errand", "fetch", "hunt", "deliver" -> {
                if (errandBetween(v.getUUID(), p.getUUID()) != null) {
                    return name(v) + " already has an errand going with " + p.getGameProfile().getName() + ".";
                }
                Errand e = plan(v, p, what.equals("errand") ? null : Errand.Kind.valueOf(what.toUpperCase(Locale.ROOT)));
                if (e == null) {
                    return what.equals("deliver") ? "Nobody else in " + name(v) + "'s village to write to." : "Nothing to ask for.";
                }
                a = new Approach(v, p, Reason.OFFER, e);
            }
            default -> {
                return "Unknown event " + what;
            }
        }
        Approach old = approaches.remove(p.getUUID());
        if (old != null && old.villager.get() != null) {
            villagers.adapterFor(old.villager.get()).stopWalking(old.villager.get());
        }
        if (!TwtConfig.APPROACH_PLAYERS.get() || v.distanceTo(p) <= ARRIVED) {
            arrive(a, v, p);
            return name(v) + " speaks to " + p.getGameProfile().getName() + ".";
        }
        lastApproach.put(p.getUUID(), System.currentTimeMillis());
        approaches.put(p.getUUID(), a);
        return name(v) + " walks over to " + p.getGameProfile().getName() + ".";
    }

    /** Someone else from the same village a letter could go to, not right next door. */
    private Entity recipient(Entity v, VillagerFacts f) {
        List<Entity> others = v.level().getEntities(v, v.getBoundingBox().inflate(96), e -> villagers.canTalk(e)
                && !(e instanceof WanderingTrader) && villagers.cached(e.getUUID()) != null && e.distanceTo(v) >= 12);
        Collections.shuffle(others, random);
        for (Entity o : others.subList(0, Math.min(6, others.size()))) {
            VillagerFacts of = villagers.facts(o, null);
            if (f.village == null ? of.village == null : of.village != null && of.village.key().equals(f.village.key())) {
                return o;
            }
        }
        return null;
    }

    private static boolean child(VillagerFacts f) {
        return "child".equals(f.ageGroup) || "baby".equals(f.ageGroup);
    }

    private static Needs.Job job(VillagerFacts f) {
        return Needs.forJob(f.job, child(f), f.kind == VillagerKind.WANDERING_TRADER);
    }

    private void offer(Entity v, ServerPlayer p, Errand e) {
        if (!canTakeErrand(p) || errandBetween(e.villager, e.player) != null) {
            return; // things changed on the way over
        }
        register(e);
        boolean asking = conversations.event(v, p, PromptBuilder.errandRequest(p.getGameProfile().getName(), e.objective(), e.rewardText()),
                said -> {
                    e.request = said;
                    save(e);
                    offerCard(e, p);
                });
        if (!asking) {
            close(e, Errand.Status.EXPIRED);
        }
    }

    private void offerCard(Errand e, ServerPlayer p) {
        if (e.status != Errand.Status.OFFERED) {
            return;
        }
        p.sendSystemMessage(prefix()
                .append(Component.literal(e.villagerName + " asks: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(e.task()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" · " + e.rewardText() + "  ").withStyle(ChatFormatting.GREEN))
                .append(button("Accept", ChatFormatting.GREEN, "/twt errand accept " + e.id, "Or just tell " + e.villagerName + " yes"))
                .append(Component.literal(" "))
                .append(button("No thanks", ChatFormatting.RED, "/twt errand decline " + e.id, "Or just tell " + e.villagerName + " no")));
    }

    /** Did the player just say yes or no to an offer? Asks the LLM (any language); acts on the server thread. */
    private void classify(Errand e, ServerPlayer p, String playerText) {
        llm.chat(LlmClient.Priority.CONVERSATION, PromptBuilder.answer(e.villagerName, e.playerName, e.request, e.task(), playerText),
                0.0, 24, PromptBuilder.answerSchema(), null).thenAccept(r -> {
            String answer;
            try {
                answer = JsonParser.parseString(r.text()).getAsJsonObject().get("answer").getAsString();
            } catch (Exception ex) {
                return;
            }
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(e.player);
                if (player == null || e.status != Errand.Status.OFFERED) {
                    return;
                }
                if (answer.equals("yes")) {
                    accept(player, e.id);
                } else if (answer.equals("no")) {
                    decline(player, e.id);
                }
            });
        });
    }

    public boolean accept(ServerPlayer p, long id) {
        Errand e = open.get(id);
        if (e == null || e.status != Errand.Status.OFFERED || !e.player.equals(p.getUUID())) {
            p.sendSystemMessage(prefix().append(Component.literal("That errand isn't on offer any more.").withStyle(ChatFormatting.GRAY)));
            return false;
        }
        if (active(p.getUUID()) >= TwtConfig.MAX_ERRANDS.get()) {
            p.sendSystemMessage(prefix().append(Component.literal("You already have " + active(p.getUUID())
                    + " errands going. Finish or give one up first (/twt errands).").withStyle(ChatFormatting.GRAY)));
            return false;
        }
        e.status = Errand.Status.ACTIVE;
        e.deadline = System.currentTimeMillis() + TwtConfig.ERRAND_MINUTES.get() * MINUTE;
        save(e);
        if (e.kind == Errand.Kind.DELIVER) {
            ErrandItems.give(p, ErrandItems.letter(e, e.village));
        }
        MutableComponent line = prefix().append(Component.literal("Accepted: " + e.task()).withStyle(ChatFormatting.WHITE));
        if (e.kind == Errand.Kind.FETCH) {
            line.append(Component.literal(" (you have " + Math.min(e.count, ErrandItems.count(p, e.item)) + ")").withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal(" · " + e.rewardText() + " · " + TwtConfig.ERRAND_MINUTES.get() + " minutes. ").withStyle(ChatFormatting.GRAY))
                .append(button("Errands", ChatFormatting.AQUA, "/twt errands", "Show your errands"));
        p.sendSystemMessage(line);
        ding(p, SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f);
        Entity v = p.serverLevel().getEntity(e.villager);
        if (v != null) {
            Gestures.react(v, kindOf(v), "happy");
        }
        conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand", e.playerName + " agreed to " + e.forMe(), 1);
        return true;
    }

    public boolean decline(ServerPlayer p, long id) {
        Errand e = open.get(id);
        if (e == null || e.status != Errand.Status.OFFERED || !e.player.equals(p.getUUID())) {
            p.sendSystemMessage(prefix().append(Component.literal("That errand isn't on offer any more.").withStyle(ChatFormatting.GRAY)));
            return false;
        }
        close(e, Errand.Status.DECLINED);
        cool("offer", e.villager, e.player, 60 * MINUTE);
        p.sendSystemMessage(prefix().append(Component.literal("You turned down " + e.villagerName + "'s request.").withStyle(ChatFormatting.GRAY)));
        conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand",
                e.playerName + " said no when I asked them to " + e.forMe(), 0);
        return true;
    }

    public boolean abandon(ServerPlayer p, long id) {
        Errand e = open.get(id);
        if (e == null || e.status != Errand.Status.ACTIVE || !e.player.equals(p.getUUID())) {
            p.sendSystemMessage(prefix().append(Component.literal("You don't have that errand.").withStyle(ChatFormatting.GRAY)));
            return false;
        }
        close(e, Errand.Status.ABANDONED);
        p.sendSystemMessage(prefix().append(Component.literal("You gave up on: " + e.task()).withStyle(ChatFormatting.GRAY)));
        conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand",
                e.playerName + " promised to " + e.forMe() + " but gave up", -3);
        return true;
    }

    private void expire(Errand e, ServerPlayer p) {
        close(e, Errand.Status.EXPIRED);
        if (p != null) {
            p.sendSystemMessage(prefix().append(Component.literal(e.villagerName + " stopped waiting: " + e.task()).withStyle(ChatFormatting.GRAY)));
        }
        conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand",
                e.playerName + " promised to " + e.forMe() + " but never did", -3);
    }

    /** Whether the player can finish the errand right now. */
    private static boolean ready(Errand e, ServerPlayer p) {
        return switch (e.kind) {
            case FETCH -> ErrandItems.count(p, e.item) >= e.count;
            case HUNT -> e.progress >= e.count;
            case DELIVER -> ErrandItems.hasLetter(p, e.id);
        };
    }

    private boolean collecting(Errand e) {
        for (Approach a : approaches.values()) {
            if (a.errand == e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands a finished errand over: takes the items or letter, pays the emeralds, and the villager remembers.
     *
     * @param speak whether the villager says thank you (not when they're already answering the player)
     */
    private boolean complete(Errand e, Entity receiver, ServerPlayer p, boolean speak) {
        boolean handedOver = switch (e.kind) {
            case FETCH -> ErrandItems.take(p, e.item, e.count);
            case DELIVER -> ErrandItems.takeLetter(p, e.id);
            case HUNT -> true;
        };
        if (!handedOver) {
            return false;
        }
        e.progress = e.count;
        close(e, Errand.Status.DONE);
        ErrandItems.giveEmeralds(p, e.reward);
        String playerName = p.getGameProfile().getName();
        VillagerKind kind = kindOf(receiver);
        p.sendSystemMessage(prefix().append(Component.literal("Done! " + e.receiverName() + " gave you " + e.rewardText() + ".")
                .withStyle(ChatFormatting.GREEN)));
        ding(p, SoundEvents.PLAYER_LEVELUP, 1.4f);
        Gestures.react(receiver, kind, "happy");
        if (receiver instanceof Villager villager) {
            villager.getGossips().add(p.getUUID(), GossipType.MINOR_POSITIVE, 15); // cheaper trades, like curing a zombie villager (a little)
        }
        VillagerAdapter adapter = villagers.adapterFor(receiver);
        if (adapter != null) {
            adapter.applyOutcome(receiver, p, 3); // MCA hearts
        }
        if (e.kind == Errand.Kind.DELIVER) {
            conversations.remember(e.target, e.targetName, e.player, e.playerName, "errand", playerName + " brought me a letter from " + e.villagerName, 3);
            conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand", playerName + " delivered my letter to " + e.targetName, 5);
        } else {
            conversations.remember(e.villager, e.villagerName, e.player, e.playerName, "errand", playerName + " " + e.doneForMe(), 6);
        }
        if (speak) {
            conversations.event(receiver, p, e.kind == Errand.Kind.DELIVER
                    ? PromptBuilder.letterRequest(playerName, e.villagerName, e.rewardText())
                    : PromptBuilder.thanksRequest(playerName, e.doneForYou(), e.rewardText()), null);
        }
        return true;
    }

    // =========================================================================================================
    // Game events
    // =========================================================================================================

    /** A player killed something: counts for their monster errands. */
    public void killed(ServerPlayer killer, Entity dead) {
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType());
        if (!type.getNamespace().equals("minecraft")) {
            return;
        }
        for (Errand e : errands(killer.getUUID())) {
            Needs.Hunt hunt = e.kind == Errand.Kind.HUNT && e.status == Errand.Status.ACTIVE ? Needs.hunt(e.item) : null;
            if (hunt == null || e.progress >= e.count || !hunt.entities().contains(type.getPath())) {
                continue;
            }
            e.progress++;
            save(e);
            if (e.progress >= e.count) {
                killer.sendSystemMessage(prefix().append(Component.literal("That's " + e.count + " " + e.label + ". Go tell " + e.villagerName + "!")
                        .withStyle(ChatFormatting.WHITE)));
                ding(killer, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f);
            } else {
                killer.displayClientMessage(Component.literal(cap(e.label) + " for " + e.villagerName + ": " + e.progress + "/" + e.count)
                        .withStyle(ChatFormatting.GOLD), true);
            }
            return; // one kill counts for one errand
        }
    }

    /** A villager died: errands from or to them are off. */
    public void villagerDied(UUID villager) {
        for (Errand e : List.copyOf(open.values())) {
            if (!villager.equals(e.villager) && !villager.equals(e.target)) {
                continue;
            }
            close(e, Errand.Status.FAILED);
            ServerPlayer p = server.getPlayerList().getPlayer(e.player);
            if (p != null) {
                String who = villager.equals(e.villager) ? e.villagerName : e.targetName;
                p.sendSystemMessage(prefix().append(Component.literal(who + " died. The errand is off: " + e.task()).withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    // =========================================================================================================
    // Conversations
    // =========================================================================================================

    @Override
    public List<String> favours(Entity villager, ServerPlayer p, String playerText) {
        List<String> lines = new ArrayList<>();
        UUID vid = villager.getUUID();
        UUID pid = p.getUUID();
        // Talking to someone with a finished errand for them hands it over (they hear you from further than arm's length).
        if (playerText != null) {
            for (Errand e : errands(pid)) {
                if (e.status == Errand.Status.ACTIVE && vid.equals(e.receiver()) && ready(e, p) && !collecting(e)
                        && complete(e, villager, p, false)) {
                    lines.add(e.kind == Errand.Kind.DELIVER
                            ? "They just handed you a letter from " + e.villagerName + " and you gave them " + e.rewardText()
                            + " for their trouble. React to the letter; you can guess what " + e.villagerName + " wrote."
                            : "They just " + e.doneForYou() + ", and you gave them " + e.rewardText() + " as promised. Thank them.");
                }
            }
        }
        for (Errand e : errands(pid)) {
            if (vid.equals(e.villager)) {
                if (e.status == Errand.Status.OFFERED) {
                    if (playerText != null && offeredInTurn.get(vid) != e) {
                        classify(e, p, playerText);
                        lines.add("You just asked them a favour: " + e.objective() + ", for " + e.rewardText()
                                + ". This is their answer: thank them if they agree, react in character if they refuse.");
                    } else {
                        lines.add("You asked them a favour: " + e.objective() + ", for " + e.rewardText() + ", and they haven't answered yet.");
                    }
                } else {
                    lines.add(switch (e.kind) {
                        case FETCH -> "They promised to " + e.objective() + " (for " + e.rewardText() + "); they're carrying "
                                + ErrandItems.count(p, e.item) + " right now.";
                        case HUNT -> "They promised to " + e.objective() + " (for " + e.rewardText() + "); " + e.progress + " so far.";
                        case DELIVER -> "They promised to " + e.objective().replace("from you", "of yours") + " (for " + e.rewardText() + ").";
                    });
                }
            } else if (e.kind == Errand.Kind.DELIVER && e.status == Errand.Status.ACTIVE && vid.equals(e.target)) {
                lines.add(e.villagerName + " gave them a letter for you, but they don't have it on them.");
            }
        }
        if (playerText != null && TwtConfig.ERRANDS.get() && ASKS_FOR_WORK.matcher(playerText).find() && errandBetween(vid, pid) == null) {
            if (!canTakeErrand(p)) {
                lines.add("They asked if you have work for them, but they're already busy with enough favours: tell them to finish those first.");
            } else {
                Errand e = plan(villager, p);
                if (e == null) {
                    lines.add("They asked if you have work for them, but you can't think of anything you need right now.");
                } else {
                    register(e);
                    offeredInTurn.put(vid, e);
                    lines.add("They asked if you have any work for them. You do: ask them to " + e.objective() + ", and offer "
                            + e.rewardText() + ". Say exactly what you need.");
                }
            }
        }
        return lines;
    }

    @Override
    public void answered(Entity villager, ServerPlayer player, String reply) {
        Errand e = offeredInTurn.remove(villager.getUUID());
        if (e != null && e.status == Errand.Status.OFFERED && e.player.equals(player.getUUID())) {
            e.request = reply;
            save(e);
            offerCard(e, player);
        }
    }

    // =========================================================================================================
    // Players' view: /twt errands
    // =========================================================================================================

    /** Sends the player their errands, with where to find the villagers and buttons. */
    public void list(ServerPlayer p) {
        List<Errand> mine = errands(p.getUUID());
        if (mine.isEmpty()) {
            p.sendSystemMessage(prefix().append(Component.literal("No errands. Ask a villager if they need help!").withStyle(ChatFormatting.GRAY)));
            return;
        }
        long now = System.currentTimeMillis();
        for (Errand e : mine) {
            MutableComponent line = prefix().append(Component.literal(e.task()).withStyle(ChatFormatting.WHITE));
            if (e.status == Errand.Status.OFFERED) {
                line.append(Component.literal(" · " + e.rewardText() + "  ").withStyle(ChatFormatting.GREEN))
                        .append(button("Accept", ChatFormatting.GREEN, "/twt errand accept " + e.id, "Take on this errand"))
                        .append(Component.literal(" "))
                        .append(button("No thanks", ChatFormatting.RED, "/twt errand decline " + e.id, "Turn it down"));
            } else {
                String progress = switch (e.kind) {
                    case FETCH -> Math.min(e.count, ErrandItems.count(p, e.item)) + "/" + e.count;
                    case HUNT -> e.progress + "/" + e.count;
                    case DELIVER -> ErrandItems.hasLetter(p, e.id) ? "letter in your bags" : "letter lost!";
                };
                long left = Math.max(0, (e.deadline - now) / MINUTE);
                line.append(Component.literal(" · " + progress + " · " + e.rewardText() + " · " + left + " min left").withStyle(ChatFormatting.GRAY));
                String where = where(p, e.receiver());
                if (where != null) {
                    line.append(Component.literal(" · " + e.receiverName() + " is " + where).withStyle(ChatFormatting.DARK_AQUA));
                }
                line.append(Component.literal("  ")).append(button("Give up", ChatFormatting.RED, "/twt errand abandon " + e.id,
                        e.villagerName + " will be disappointed"));
            }
            p.sendSystemMessage(line);
        }
    }

    /** "24 blocks north-east", when the villager is loaded in the player's world. */
    private static String where(ServerPlayer p, UUID villager) {
        Entity v = p.serverLevel().getEntity(villager);
        if (v == null) {
            return null;
        }
        double dx = v.getX() - p.getX();
        double dz = v.getZ() - p.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 4) {
            return "right here";
        }
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-z), 90 = east (+x)
        return dist + " blocks " + dirs[(int) Math.floorMod(Math.round(angle / 45), 8)];
    }

    // =========================================================================================================
    // Helpers
    // =========================================================================================================

    /** The player's offered and active errands, oldest first. */
    private List<Errand> errands(UUID player) {
        List<Errand> list = new ArrayList<>();
        for (Errand e : open.values()) {
            if (e.player.equals(player)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparingLong(e -> e.id));
        return list;
    }

    private int active(UUID player) {
        return (int) errands(player).stream().filter(e -> e.status == Errand.Status.ACTIVE).count();
    }

    private boolean canTakeErrand(ServerPlayer p) {
        return errands(p.getUUID()).size() < TwtConfig.MAX_ERRANDS.get();
    }

    private Errand errandBetween(UUID villager, UUID player) {
        for (Errand e : open.values()) {
            if (e.villager.equals(villager) && e.player.equals(player)) {
                return e;
            }
        }
        return null;
    }

    private boolean cooling(String what, UUID villager, UUID player) {
        Long until = cooldowns.get(what + "|" + villager + "|" + player);
        return until != null && until > System.currentTimeMillis();
    }

    private void cool(String what, UUID villager, UUID player, long ms) {
        cooldowns.put(what + "|" + villager + "|" + player, System.currentTimeMillis() + ms);
        if (cooldowns.size() > 5000) {
            long now = System.currentTimeMillis();
            cooldowns.values().removeIf(t -> t < now);
        }
    }

    private VillagerKind kindOf(Entity v) {
        VillagerAdapter a = villagers.adapterFor(v);
        return a == null ? VillagerKind.VANILLA : a.kind();
    }

    private String name(Entity v) {
        VillagerProfile p = villagers.cached(v.getUUID());
        return p != null ? p.name() : v.getName().getString();
    }

    private void publish(Errand e, String text) {
        JsonObject ev = new JsonObject();
        ev.addProperty("villager", e.villager.toString());
        ev.addProperty("villagerName", e.villagerName);
        ev.addProperty("player", e.playerName);
        ev.addProperty("text", text);
        feed.publish("errand", ev);
    }

    private static MutableComponent prefix() {
        return tag("Errand", ChatFormatting.GOLD);
    }

    private static MutableComponent tag(String label, ChatFormatting color) {
        return Component.literal("[" + label + "] ").withStyle(color);
    }

    private static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static void ding(ServerPlayer p, SoundEvent sound, float pitch) {
        p.playNotifySound(sound, SoundSource.PLAYERS, 0.6f, pitch);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
