package dev.eiriksb.theywilltalk;

import dev.eiriksb.theywilltalk.ai.LlmClient;
import dev.eiriksb.theywilltalk.ai.QwenTtsClient;
import dev.eiriksb.theywilltalk.ai.TtsClient;
import dev.eiriksb.theywilltalk.audio.SpeechRenderer;
import dev.eiriksb.theywilltalk.audio.VoiceBank;
import dev.eiriksb.theywilltalk.audio.VoiceOutput;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import dev.eiriksb.theywilltalk.conversation.LiveFeed;
import dev.eiriksb.theywilltalk.data.Database;
import dev.eiriksb.theywilltalk.data.Store;
import dev.eiriksb.theywilltalk.integration.Integrations;
import dev.eiriksb.theywilltalk.integration.SipherBridge;
import dev.eiriksb.theywilltalk.faces.VillagerFaces;
import dev.eiriksb.theywilltalk.runtime.RuntimeManager;
import dev.eiriksb.theywilltalk.villager.VanillaAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerRegistry;
import dev.eiriksb.theywilltalk.web.DashboardServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * They Will Talk - villagers answer players out loud using a local LLM and text-to-speech running on the server.
 * Server-side only: players need Simple Voice Chat (to hear voices) and Sipher (to talk with their voice), or can
 * just type in chat.
 */
@Mod(TheyWillTalk.MOD_ID)
public final class TheyWillTalk {
    public static final String MOD_ID = "theywilltalk";
    public static final Logger LOGGER = LoggerFactory.getLogger("TheyWillTalk");

    private static volatile TheyWillTalk instance;
    private static volatile VoiceOutput voiceOutput = VoiceOutput.NONE;

    private MinecraftServer server;
    private RuntimeManager runtime;
    private Database database;
    private Store store;
    private LlmClient llm;
    private TtsClient tts;
    private QwenTtsClient qwenTts;
    private SpeechRenderer speech;
    private VillagerRegistry villagers;
    private ConversationManager conversations;
    private DashboardServer dashboard;
    private Integrations integrations;
    private VillagerFaces faces;
    private final LiveFeed feed = new LiveFeed();
    private final Deque<Entity> scanQueue = new ArrayDeque<>();
    private final Map<String, Long> eventCooldown = new ConcurrentHashMap<>();
    private long ticks;

    public TheyWillTalk(IEventBus modBus, ModContainer container) {
        instance = this;
        container.registerConfig(ModConfig.Type.COMMON, TwtConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onChat);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onDamage);
        NeoForge.EVENT_BUS.addListener(this::onDeath);
        NeoForge.EVENT_BUS.addListener(this::onTrade);
        if (ModList.get().isLoaded("sipher")) {
            try {
                SipherBridge.register();
            } catch (Throwable t) {
                LOGGER.warn("Sipher integration failed to load (version mismatch?): {}", t.toString());
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RuntimeManager r = runtime;
            if (r != null) {
                r.stop();
            }
        }, "twt-shutdown"));
    }

    // ---- static access for integrations / plugins ------------------------------------------------------------

    public static TheyWillTalk get() {
        return instance;
    }

    public static ConversationManager conversations() {
        TheyWillTalk i = instance;
        return i == null ? null : i.conversations;
    }

    public static void setVoiceOutput(VoiceOutput out) {
        voiceOutput = out;
    }

    public static VoiceOutput voiceOutput() {
        return voiceOutput;
    }

    public static boolean inVoiceGroup(ServerPlayer player) {
        if (!ModList.get().isLoaded("voicechat")) {
            return false;
        }
        return dev.eiriksb.theywilltalk.voice.TwtVoicechatPlugin.recentlySpokeInGroup(player.getUUID());
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path worldData = server.getWorldPath(LevelResource.ROOT).resolve("theywilltalk");
        try {
            database = new Database(worldData.resolve("theywilltalk.db"));
            database.open();
        } catch (Exception e) {
            LOGGER.error("They Will Talk could not open its database; the mod is disabled for this world", e);
            return;
        }
        store = new Store(database);
        runtime = new RuntimeManager(gameDir);
        llm = new LlmClient(() -> runtime.llmBaseUrl(), TwtConfig.PARALLEL_SLOTS.get());
        tts = new TtsClient(() -> runtime.voiceReady() ? runtime.voiceBaseUrl() : null);
        qwenTts = new QwenTtsClient(() -> runtime.qwenTtsBaseUrl());
        QwenTtsClient qwenClone = new QwenTtsClient(() -> runtime.qwenCloneBaseUrl());
        speech = new SpeechRenderer(tts, qwenTts, new VoiceBank(worldData.resolve("voices"), qwenTts, qwenClone));
        villagers = new VillagerRegistry(store, tts);
        faces = new VillagerFaces(gameDir, worldData.resolve("faces"));
        integrations = new Integrations();
        integrations.registerAdapters(villagers);
        villagers.addAdapter(new VanillaAdapter());
        villagers.preload();
        conversations = new ConversationManager(server, villagers, store, llm, speech, TheyWillTalk::voiceOutput, feed, () -> runtime.llmReady());

        if (TwtConfig.AUTO_START.get()) {
            runtime.start();
            runtime.problems().forEach(p -> LOGGER.warn("AI runtime: {}", p));
        }
        if (TwtConfig.DASHBOARD.get()) {
            dashboard = new DashboardServer(this, worldData);
            dashboard.start();
        }
        integrations.onServerStarted(this);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (integrations != null) {
            integrations.onServerStopping();
        }
        if (dashboard != null) {
            dashboard.stop();
            dashboard = null;
        }
        if (conversations != null) {
            conversations.shutdown();
            conversations = null;
        }
        if (llm != null) {
            llm.shutdown();
        }
        if (runtime != null) {
            runtime.stop();
        }
        if (faces != null) {
            faces.shutdown();
        }
        if (database != null) {
            database.close();
            database = null;
        }
        server = null;
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (conversations == null) {
            return;
        }
        conversations.tick();
        ticks++;
        if (ticks % 20 == 0 && tts != null && runtime.voiceReady() && tts.voices().isEmpty()) {
            Thread.ofVirtual().start(tts::refreshVoices);
        }
        // Keep the dashboard's view of the world fresh: every 15 s queue all loaded villagers, then process a few per tick.
        if (ticks % 300 == 0 && scanQueue.isEmpty()) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (villagers.canTalk(e)) {
                        scanQueue.add(e);
                    }
                }
            }
        }
        for (int i = 0; i < 8 && !scanQueue.isEmpty(); i++) {
            Entity e = scanQueue.poll();
            if (e.isAlive() && !e.isRemoved()) {
                VillagerFacts f = villagers.facts(e, null);
                villagers.profile(e, f);
                store.saveFacts(f);
                faces.update(e);
            }
        }
        if (ticks % 1200 == 0) {
            store.recountVanillaPopulations();
        }
        if (integrations != null) {
            integrations.tick(this, ticks);
        }
    }

    // ---- game events -----------------------------------------------------------------------------------------

    private void onChat(ServerChatEvent event) {
        if (conversations == null || !TwtConfig.TEXT_CHAT.get()) {
            return;
        }
        String text = event.getRawText();
        if (text.startsWith("/")) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        server.execute(() -> conversations.hear(player, text, ConversationManager.Channel.TEXT, null, "en"));
    }

    private void onDamage(LivingIncomingDamageEvent event) {
        if (conversations == null || !(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity victim = event.getEntity();
        if (!villagers.canTalk(victim) || cooldown(victim.getUUID() + "|hit|" + player.getUUID(), 20_000)) {
            return;
        }
        conversations.remember(victim, player, "event", player.getGameProfile().getName() + " attacked me!", -8);
    }

    private void onDeath(LivingDeathEvent event) {
        if (store == null || villagers == null) {
            return;
        }
        Entity dead = event.getEntity();
        var profile = villagers.cached(dead.getUUID());
        if (profile == null) {
            return;
        }
        store.markDead(dead.getUUID());
        String cause = event.getSource().getEntity() instanceof ServerPlayer p ? "was killed by " + p.getGameProfile().getName()
                : "died (" + event.getSource().getMsgId() + ")";
        store.event("death", dead.getUUID(), event.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null,
                profile.name() + " " + cause);
        var ev = new com.google.gson.JsonObject();
        ev.addProperty("villager", dead.getUUID().toString());
        ev.addProperty("villagerName", profile.name());
        ev.addProperty("text", profile.name() + " " + cause);
        feed.publish("death", ev);
        if (event.getSource().getEntity() instanceof ServerPlayer killer && dead.level() instanceof ServerLevel level) {
            // Witnesses remember.
            for (Entity w : level.getEntities(dead, dead.getBoundingBox().inflate(24), villagers::canTalk)) {
                conversations.remember(w, killer, "event", killer.getGameProfile().getName() + " killed " + profile.name() + " in front of me!", -15);
            }
        }
    }

    private void onTrade(TradeWithVillagerEvent event) {
        if (conversations == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity v = event.getAbstractVillager();
        if (!villagers.canTalk(v) || cooldown(v.getUUID() + "|trade|" + player.getUUID(), 120_000)) {
            return;
        }
        var result = event.getMerchantOffer().getResult();
        conversations.remember(v, player, "trade", player.getGameProfile().getName() + " traded with me and got "
                + result.getCount() + " " + result.getHoverName().getString(), 1);
    }

    private boolean cooldown(String key, long ms) {
        long now = System.currentTimeMillis();
        Long last = eventCooldown.get(key);
        if (last != null && now - last < ms) {
            return true;
        }
        eventCooldown.put(key, now);
        return false;
    }

    private void onCommands(RegisterCommandsEvent event) {
        TwtCommands.register(event.getDispatcher());
    }

    // ---- accessors for commands / dashboard / integrations ------------------------------------------------------

    public MinecraftServer server() {
        return server;
    }

    public RuntimeManager runtime() {
        return runtime;
    }

    public Store store() {
        return store;
    }

    public LlmClient llm() {
        return llm;
    }

    public TtsClient tts() {
        return tts;
    }

    public SpeechRenderer speech() {
        return speech;
    }

    public VillagerRegistry villagers() {
        return villagers;
    }

    public ConversationManager conversationManager() {
        return conversations;
    }

    public LiveFeed feed() {
        return feed;
    }

    public DashboardServer dashboard() {
        return dashboard;
    }

    /** Villagers' faces drawn from their skins (BlueMap markers, dashboard). */
    public VillagerFaces faces() {
        return faces;
    }

    public Integrations integrations() {
        return integrations;
    }

    /** Finds a loaded entity by UUID in any dimension. Server thread. */
    public Entity findEntity(UUID uuid) {
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e != null) {
                return e;
            }
        }
        return null;
    }
}
