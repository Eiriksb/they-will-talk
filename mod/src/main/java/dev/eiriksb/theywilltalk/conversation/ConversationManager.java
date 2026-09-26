package dev.eiriksb.theywilltalk.conversation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.LlmClient;
import dev.eiriksb.theywilltalk.ai.SentenceStream;
import dev.eiriksb.theywilltalk.audio.AudioDsp;
import dev.eiriksb.theywilltalk.audio.SpeechRenderer;
import dev.eiriksb.theywilltalk.audio.VoiceOutput;
import dev.eiriksb.theywilltalk.data.Store;
import dev.eiriksb.theywilltalk.villager.VillagerAdapter;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import dev.eiriksb.theywilltalk.villager.VillagerRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The heart of the mod: decides which villager a player is talking to, runs the
 * LLM -> sentence -> voice pipeline, and keeps sessions, memories and relationships up to date.
 *
 * Threading: {@link #hear} and all world access run on the server thread; thinking and speaking run on virtual
 * threads, and results hop back with {@code server.execute}.
 */
public final class ConversationManager {
    public enum Channel { VOICE, TEXT, COMMAND, DASHBOARD, GREETING, AMBIENT }

    private static final String END = "\u0000END";
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    private final MinecraftServer server;
    private final VillagerRegistry villagers;
    private final Store store;
    private final LlmClient llm;
    private final SpeechRenderer speech;
    private final Supplier<VoiceOutput> voice;
    private final LiveFeed feed;
    private final Supplier<Boolean> llmReady;
    private final boolean sipher = net.neoforged.fml.ModList.get().isLoaded("sipher");
    /** The language each player last spoke in (from Sipher). */
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Speaker> speakers = new ConcurrentHashMap<>();
    private final Map<String, Integer> affinity = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> moodLevels = new ConcurrentHashMap<>();
    private final Map<String, Long> greeted = new ConcurrentHashMap<>();
    private final Map<Long, Long> ambientByArea = new ConcurrentHashMap<>();
    /** What villagers said recently, to recognise their own voice coming back through a player's microphone. */
    private final java.util.concurrent.ConcurrentLinkedDeque<SpokenLine> recentLines = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final Map<String, Deque<Long>> replyTimes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "twt-subtitles");
        t.setDaemon(true);
        return t;
    });
    private int tick;

    // stats for the dashboard
    public volatile long lastLatencyMs;
    public volatile long turnsTotal;

    public ConversationManager(MinecraftServer server, VillagerRegistry villagers, Store store, LlmClient llm, SpeechRenderer speech,
                               Supplier<VoiceOutput> voice, LiveFeed feed, Supplier<Boolean> llmReady) {
        this.server = server;
        this.villagers = villagers;
        this.store = store;
        this.llm = llm;
        this.speech = speech;
        this.voice = voice;
        this.feed = feed;
        this.llmReady = llmReady;
        try {
            for (String[] row : store.db.query("SELECT villager_uuid, player_uuid, affinity FROM relationships",
                    rs -> new String[]{rs.getString(1), rs.getString(2), Integer.toString(rs.getInt(3))}).get()) {
                affinity.put(row[0] + "|" + row[1], Integer.parseInt(row[2]));
            }
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Could not load relationships: {}", e.toString());
        }
    }

    public void shutdown() {
        speakers.values().forEach(s -> {
            Turn t = s.current;
            if (t != null) {
                t.cancel();
            }
        });
        timer.shutdownNow();
    }

    // =========================================================================================================
    // Input
    // =========================================================================================================

    /**
     * A player said something (voice caption from Sipher, typed chat, a command...). Server thread.
     *
     * @return true when a villager took it as addressed to them
     */
    public boolean hear(ServerPlayer player, String text, Channel channel, String originalText, String lang) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (channel == Channel.VOICE && isEcho(player, text)) {
            TheyWillTalk.LOGGER.debug("Ignored voice line from {} (villager echo): {}", player.getGameProfile().getName(), text);
            return false;
        }
        Entity target = findTarget(player, text);
        if (target == null) {
            return false;
        }
        if (!llmReady.get()) {
            player.displayClientMessage(Component.literal("The villagers' minds are still waking up... (AI starting)")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
            return true;
        }
        talk(target, player, text.trim(), channel, originalText, lang);
        return true;
    }

    /** Talk to a specific villager (commands, dashboard). Server thread. */
    public void talk(Entity villager, ServerPlayer player, String text, Channel channel, String originalText, String lang) {
        if (player != null && channel != Channel.GREETING && channel != Channel.DASHBOARD && overLimit(player, villager)) {
            player.displayClientMessage(Component.literal(name(villager) + " needs a moment to catch their breath...")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
            return;
        }
        Speaker sp = speakers.computeIfAbsent(villager.getUUID(), u -> new Speaker(villager));
        sp.entity = new WeakReference<>(villager);
        Turn current = sp.current;
        UUID pid = player == null ? null : player.getUUID();
        if (current != null) {
            if (pid != null && pid.equals(current.playerId) && channel != Channel.GREETING) {
                current.cancel(); // barge-in: the player talks over the villager
            } else {
                synchronized (sp.queue) {
                    if (sp.queue.size() < 3) {
                        sp.queue.add(new Pending(player, text, channel, originalText, lang));
                    }
                }
                if (player != null) {
                    player.displayClientMessage(Component.literal(name(villager) + " is busy talking... they'll get to you.")
                            .withStyle(ChatFormatting.GRAY), true);
                }
                return;
            }
        }
        startTurn(sp, villager, player, text, channel, originalText, lang);
    }

    private Entity findTarget(ServerPlayer player, String text) {
        double radius = TwtConfig.LISTEN_RADIUS.get();
        ServerLevel level = player.serverLevel();
        Entity active = null;
        Session s = sessions.get(player.getUUID());
        if (s != null && System.currentTimeMillis() - s.lastActivity < TwtConfig.ACTIVE_CONVERSATION_SECONDS.get() * 1000L) {
            Entity e = level.getEntity(s.villager);
            if (e != null && e.isAlive() && e.distanceTo(player) <= radius * 1.5 && villagers.canTalk(e) && roughlyFacing(player, e)) {
                active = e;
            }
        }
        List<Entity> nearby = level.getEntities(player, player.getBoundingBox().inflate(radius * 2), villagers::canTalk);
        Entity looked = lookedAt(player, nearby, radius);
        if (looked != null) {
            return looked;
        }
        Entity named = namedIn(text, nearby, player, radius * 2);
        if (named != null) {
            return named;
        }
        if (active != null) {
            return active;
        }
        if (!TwtConfig.REQUIRE_LOOK_OR_NAME.get()) {
            return nearby.stream().filter(e -> e.distanceTo(player) <= Math.min(5, radius))
                    .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player))).orElse(null);
        }
        return null;
    }

    /** Within ~70 degrees of where the player is looking: you turned away, you're talking to someone else. */
    private static boolean roughlyFacing(ServerPlayer player, Entity e) {
        Vec3 to = e.getBoundingBox().getCenter().subtract(player.getEyePosition());
        return to.lengthSqr() < 1 || player.getViewVector(1f).normalize().dot(to.normalize()) > 0.34;
    }

    private boolean overLimit(ServerPlayer player, Entity villager) {
        long now = System.currentTimeMillis();
        Deque<Long> times = replyTimes.computeIfAbsent(player.getUUID() + "|" + villager.getUUID(), k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= TwtConfig.MAX_REPLIES_PER_MINUTE.get()) {
                return true;
            }
            times.addLast(now);
            return false;
        }
    }

    /**
     * True when a "voice line" is most likely a villager's own voice picked up by the player's microphone
     * (speakers instead of headphones): a nearby villager is talking right now, or the words match what one just said.
     */
    private boolean isEcho(ServerPlayer player, String text) {
        long now = System.currentTimeMillis();
        double range = TwtConfig.VOICE_DISTANCE.get();
        for (Speaker sp : speakers.values()) {
            Entity v = sp.entity.get();
            if (v != null && v.level() == player.level() && sp.speakingUntil + 1500 > now && v.distanceTo(player) <= range) {
                return true;
            }
        }
        recentLines.removeIf(l -> now - l.ts > 30_000);
        java.util.Set<String> words = EchoFilter.words(text);
        if (words.isEmpty()) {
            return false;
        }
        for (SpokenLine l : recentLines) {
            if (l.level == player.level() && l.pos.distanceTo(player.position()) <= range && EchoFilter.isEcho(words, l.words)) {
                return true;
            }
        }
        return false;
    }

    private void rememberSpoken(Entity v, String text, long playsUntil) {
        recentLines.add(new SpokenLine(System.currentTimeMillis(), v.level(), v.position(), EchoFilter.words(text)));
        Speaker sp = speakers.get(v.getUUID());
        if (sp != null) {
            sp.speakingUntil = Math.max(sp.speakingUntil, playsUntil);
        }
    }

    private record SpokenLine(long ts, net.minecraft.world.level.Level level, Vec3 pos, java.util.Set<String> words) {}

    /** Forwards to a stream and records when the first non-empty audio was pushed. */
    private record TimedStream(VoiceOutput.SpeechStream inner, long[] firstPush) implements VoiceOutput.SpeechStream {
        @Override
        public void push(short[] pcm48k) {
            if (firstPush[0] < 0 && pcm48k.length > 0) {
                firstPush[0] = System.currentTimeMillis();
            }
            inner.push(pcm48k);
        }

        @Override public void finish() { inner.finish(); }
        @Override public void cancel() { inner.cancel(); }
        @Override public boolean isDone() { return inner.isDone(); }
        @Override public long queuedMs() { return inner.queuedMs(); }
    }

    private static Entity lookedAt(ServerPlayer player, List<Entity> candidates, double radius) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f).normalize();
        Entity best = null;
        double bestScore = 0;
        for (Entity e : candidates) {
            Vec3 to = e.getBoundingBox().getCenter().add(0, e.getBbHeight() * 0.25, 0).subtract(eye);
            double dist = to.length();
            if (dist > radius || dist < 0.01) {
                continue;
            }
            double dot = look.dot(to.normalize());
            // A cone that is wider up close: ~30 degrees at 1 block, ~12 degrees at the edge of hearing.
            double needed = Math.cos(Math.toRadians(Math.max(12, 30 - dist * 2.5)));
            if (dot < needed || !player.hasLineOfSight(e)) {
                continue;
            }
            double score = dot - dist * 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private Entity namedIn(String text, List<Entity> candidates, ServerPlayer player, double radius) {
        List<String> words = new ArrayList<>();
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        if (words.isEmpty()) {
            return null;
        }
        Entity best = null;
        for (Entity e : candidates) {
            if (e.distanceTo(player) > radius) {
                continue;
            }
            VillagerProfile p = villagers.cached(e.getUUID());
            String name = p != null ? p.firstName() : e.hasCustomName() && e.getCustomName() != null ? e.getCustomName().getString() : null;
            if (name == null || name.length() < 3) {
                continue;
            }
            String first = name.split(" ")[0].toLowerCase(Locale.ROOT);
            if (words.contains(first) && (best == null || e.distanceToSqr(player) < best.distanceToSqr(player))) {
                best = e;
            }
        }
        return best;
    }

    // =========================================================================================================
    // A conversational turn
    // =========================================================================================================

    private void startTurn(Speaker sp, Entity villager, ServerPlayer player, String text, Channel channel, String originalText, String lang) {
        VillagerAdapter adapter = villagers.adapterFor(villager);
        if (adapter == null) {
            return;
        }
        VillagerFacts facts = villagers.facts(villager, player);
        VillagerProfile profile = villagers.profile(villager, facts);
        if (facts.kind != VillagerKind.MCA) {
            facts.moodLevel = moodLevels.getOrDefault(villager.getUUID(), 0);
        }
        List<String> world = WorldContext.capture(villager, player, this::nameOf);
        store.saveFacts(facts);

        String playerName = player == null ? "a traveler" : player.getGameProfile().getName();
        UUID pid = player == null ? new UUID(0, 0) : player.getUUID();
        Session session;
        if (player != null) {
            store.seenPlayer(pid, playerName);
            session = sessions.compute(pid, (k, old) -> old != null && old.villager.equals(villager.getUUID())
                    && System.currentTimeMillis() - old.lastActivity < 5 * 60_000L ? old : new Session(villager.getUUID()));
        } else {
            session = new Session(villager.getUUID());
        }
        session.lastActivity = System.currentTimeMillis();
        List<PromptBuilder.Turn> history;
        synchronized (session.history) {
            int n = TwtConfig.HISTORY_TURNS.get();
            history = new ArrayList<>(session.history.subList(Math.max(0, session.history.size() - n), session.history.size()));
        }

        Turn turn = new Turn(villager, player, pid, playerName, text, channel, originalText, lang, facts, profile, adapter);
        turn.replyLang = replyLanguage(channel, lang, pid);
        sp.current = turn;
        if (player != null) {
            sp.attentionTarget = new WeakReference<>(player);
            sp.attentionUntil = System.currentTimeMillis() + 20_000;
            if (channel != Channel.GREETING) {
                player.displayClientMessage(Component.literal(profile.firstName() + " is listening...")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
                Gestures.listening(villager, facts.kind);
            }
        }

        JsonObject ev = new JsonObject();
        ev.addProperty("villager", profile.uuid().toString());
        ev.addProperty("villagerName", profile.name());
        ev.addProperty("player", playerName);
        ev.addProperty("text", text);
        ev.addProperty("channel", channel.name().toLowerCase(Locale.ROOT));
        if (originalText != null && !originalText.equals(text)) {
            ev.addProperty("original", originalText);
            ev.addProperty("lang", lang);
        }
        feed.publish("heard", ev);

        Thread.ofVirtual().name("twt-turn-" + profile.firstName()).start(() -> runTurn(sp, turn, session, history, world));
    }

    private void runTurn(Speaker sp, Turn turn, Session session, List<PromptBuilder.Turn> history, List<String> world) {
        long t0 = System.currentTimeMillis();
        VillagerProfile profile = turn.profile;
        UUID vid = profile.uuid();
        boolean isGreeting = turn.channel == Channel.GREETING;
        try {
            Store.Relationship rel = store.relationship(vid, turn.playerId).get(3, TimeUnit.SECONDS);
            List<Store.Memory> memories = store.memories(vid, turn.playerId, 6).get(3, TimeUnit.SECONDS);
            List<Store.Memory> gossip = store.gossip(turn.playerId, turn.facts.village == null ? null : turn.facts.village.key(), vid, 3)
                    .get(3, TimeUnit.SECONDS);

            if (session.conversationId < 0) {
                session.conversationId = store.startConversation(vid, turn.playerId, turn.channel.name().toLowerCase(Locale.ROOT))
                        .get(3, TimeUnit.SECONDS);
            }
            if (!isGreeting) {
                store.addMessage(session.conversationId, vid, turn.playerId, "player", turn.playerName, turn.text, turn.originalText,
                        turn.lang, null, 0);
            }
            // Answering in the player's language: give the model their own words rather than the English translation.
            boolean ownLanguage = !turn.replyLang.equals(Languages.ENGLISH) && turn.originalText != null && !turn.originalText.isBlank();
            List<LlmClient.Message> messages = PromptBuilder.conversation(profile, turn.facts, turn.playerName, rel, memories, gossip,
                    world, history, ownLanguage ? turn.originalText : turn.text, TwtConfig.MAX_REPLY_WORDS.get(),
                    Languages.name(turn.replyLang));

            VoiceOutput out = voice.get();
            VoiceOutput.SpeechStream stream = out.available() ? out.open(turn.villager.get(), TwtConfig.VOICE_DISTANCE.get().floatValue())
                    : VoiceOutput.SpeechStream.NOOP;
            turn.stream = stream;
            BlockingQueue<String> sentences = turn.sentences;
            SentenceStream ss = new SentenceStream(sentences::add);
            StringBuilder spoken = new StringBuilder();
            Thread speaker = Thread.ofVirtual().name("twt-speak-" + profile.firstName())
                    .start(() -> speakLoop(turn, ss, sentences, stream, spoken, t0));
            // A villager's first cloned line needs their voice clips: make them while the LLM thinks.
            Thread.ofVirtual().start(() -> speech.prepare(profile, turn.facts, "neutral"));
            boolean[] moodPrepared = {false};

            CompletableFuture<LlmClient.Result> future = llm.chat(isGreeting ? LlmClient.Priority.GREETING : LlmClient.Priority.CONVERSATION,
                    messages, TwtConfig.TEMPERATURE.get(), TwtConfig.MAX_REPLY_WORDS.get() * 2 + 24, null, delta -> {
                        if (!turn.cancelled) {
                            ss.accept(delta);
                            if (!moodPrepared[0] && ss.emotionKnown()) {
                                moodPrepared[0] = true;
                                String emotion = ss.emotion();
                                Thread.ofVirtual().start(() -> speech.prepare(profile, turn.facts, emotion));
                            }
                        }
                    });
            turn.llmFuture = future;
            LlmClient.Result result = future.get(90, TimeUnit.SECONDS);
            ss.finish();
            sentences.add(END);
            String fullReply = SentenceStream.clean(result.text());
            if (!turn.cancelled && !fullReply.isEmpty()) {
                server.execute(() -> {
                    Entity v = turn.villager.get();
                    if (v != null) {
                        subtitle(v, profile, turn.facts.kind, fullReply);
                    }
                });
            }
            speaker.join(60_000);
            if (turn.cancelled) {
                return;
            }
            String reply = spoken.isEmpty() ? SentenceStream.clean(result.text()) : spoken.toString().trim();
            String emotion = ss.emotion();
            turnsTotal++;
            store.addMessage(session.conversationId, vid, turn.playerId, "villager", profile.name(), reply, null, turn.replyLang, emotion,
                    turn.firstAudioMs);
            if (!isGreeting) {
                synchronized (session.history) {
                    session.history.add(new PromptBuilder.Turn("player", turn.text, null));
                    session.history.add(new PromptBuilder.Turn("villager", reply, emotion));
                }
            } else {
                synchronized (session.history) {
                    session.history.add(new PromptBuilder.Turn("villager", reply, emotion));
                }
            }
            session.lastActivity = System.currentTimeMillis();
            store.villagerSpoke(vid);
            if (turn.player != null) {
                store.recordTalk(vid, turn.playerId, turn.facts.hearts, turn.facts.reputation, turn.facts.relationToPlayer);
            }

            JsonObject ev = new JsonObject();
            ev.addProperty("villager", vid.toString());
            ev.addProperty("villagerName", profile.name());
            ev.addProperty("player", turn.playerName);
            ev.addProperty("text", reply);
            ev.addProperty("emotion", emotion);
            ev.addProperty("latencyMs", turn.firstAudioMs);
            ev.addProperty("tokensPerSecond", Math.round(llm.lastTokensPerSecond));
            feed.publish("reply", ev);

            if (turn.player != null && !isGreeting) {
                reflect(turn, List.of(new PromptBuilder.Turn("player", turn.text, null), new PromptBuilder.Turn("villager", reply, emotion)));
            }
        } catch (Exception e) {
            if (!turn.cancelled) {
                TheyWillTalk.LOGGER.warn("Conversation with {} failed: {}", profile.name(), e.toString());
                server.execute(() -> {
                    Entity v = turn.villager.get();
                    if (v != null && turn.player != null) {
                        subtitle(v, profile, turn.facts.kind, "Hrmm...? (" + profile.firstName() + " seems lost in thought)");
                    }
                });
            }
        } finally {
            turn.sentences.add(END);
            server.execute(() -> finishTurn(sp, turn));
        }
    }

    /** Rough speaking rate of the voices (about 15 characters a second). */
    private static final long SPOKEN_MS_PER_CHAR = 65;

    private void speakLoop(Turn turn, SentenceStream ss, BlockingQueue<String> sentences, VoiceOutput.SpeechStream stream,
                           StringBuilder spoken, long t0) {
        boolean audio = stream != VoiceOutput.SpeechStream.NOOP;
        // Qwen3-TTS VoiceDesign imagines the voice anew for every request, so a reply split into sentences would change
        // voice at every full stop: without voice cloning it gets the whole reply at once (the LLM finishes a short reply
        // in well under a second). Cloned voices and the voice server's cast voices are fixed and start on the first
        // sentence.
        boolean wholeReply = audio && speech.expressive() && !speech.cloning();
        List<String> held = new ArrayList<>();
        boolean first = true;
        try {
            while (true) {
                String sentence = sentences.poll(60, TimeUnit.SECONDS);
                if (sentence == null || sentence.equals(END) || turn.cancelled) {
                    break;
                }
                if (wholeReply) {
                    held.add(sentence);
                    continue;
                }
                speakPart(turn, ss.emotion(), sentence, List.of(sentence), first, audio, stream, spoken, t0);
                first = false;
            }
            if (!held.isEmpty() && !turn.cancelled) {
                speakPart(turn, ss.emotion(), String.join(" ", held), held, true, audio, stream, spoken, t0);
            }
        } catch (InterruptedException ignored) {
            // cancelled
        } finally {
            stream.finish();
        }
    }

    /** Speaks one piece of a reply (a sentence, or the whole reply) and captions its sentences as they play. */
    private void speakPart(Turn turn, String emotion, String text, List<String> lines, boolean first, boolean audio,
                           VoiceOutput.SpeechStream stream, StringBuilder spoken, long t0) {
        VillagerProfile profile = turn.profile;
        if (first) {
            server.execute(() -> {
                Entity v = turn.villager.get();
                if (v != null) {
                    Gestures.react(v, turn.facts.kind, emotion);
                }
            });
        }
        spoken.append(text).append(' ');
        long delayMs = 0;
        long[] firstPush = {-1};
        if (audio && !turn.cancelled) {
            delayMs = stream.queuedMs();
            // Note when the first audio actually goes out (the engine may still be generating).
            VoiceOutput.SpeechStream timed = first ? new TimedStream(stream, firstPush) : stream;
            try {
                speech.speak(profile, turn.facts, text, emotion, turn.replyLang, first, timed, () -> turn.cancelled);
            } catch (Exception e) {
                TheyWillTalk.LOGGER.debug("TTS failed, subtitles only: {}", e.toString());
            }
        }
        if (first) {
            long at = firstPush[0] > 0 ? firstPush[0] : System.currentTimeMillis();
            turn.firstAudioMs = at - t0 + delayMs;
            lastLatencyMs = turn.firstAudioMs;
        }
        Entity speakerEntity = turn.villager.get();
        long offsetMs = 0; // later sentences of a whole reply are captioned roughly when they're reached
        for (String line : lines) {
            if (speakerEntity != null) {
                rememberSpoken(speakerEntity, line, System.currentTimeMillis() + stream.queuedMs());
            }
            if (audio) {
                timer.schedule(() -> server.execute(() -> {
                    Entity v = turn.villager.get();
                    if (v != null && !turn.cancelled) {
                        caption(v, profile, turn.facts.kind, line, turn.replyLang);
                    }
                }), delayMs + offsetMs, TimeUnit.MILLISECONDS);
            }
            offsetMs += line.length() * SPOKEN_MS_PER_CHAR;
        }
    }

    private void finishTurn(Speaker sp, Turn turn) {
        if (sp.current != turn) {
            return; // superseded by a barge-in; the newer turn owns the queue
        }
        sp.current = null;
        Pending next;
        synchronized (sp.queue) {
            next = sp.queue.poll();
        }
        Entity v = sp.entity.get();
        if (next != null && v != null && v.isAlive()) {
            startTurn(sp, v, next.player, next.text, next.channel, next.originalText, next.lang);
        }
    }

    /** After the reply: ask the LLM (in the background) how this changed the relationship, then remember it. */
    private void reflect(Turn turn, List<PromptBuilder.Turn> exchange) {
        llm.chat(LlmClient.Priority.REFLECTION, PromptBuilder.reflection(turn.profile, turn.playerName, exchange), 0.2, 160,
                PromptBuilder.reflectionSchema(), null).thenAccept(result -> {
            try {
                JsonObject o = JsonParser.parseString(result.text()).getAsJsonObject();
                int delta = Math.max(-5, Math.min(5, o.get("affinity_delta").getAsInt()));
                String mood = o.get("mood").getAsString();
                String memory = o.get("memory").getAsString().trim();
                UUID vid = turn.profile.uuid();
                store.adjustAffinity(vid, turn.playerId, delta);
                affinity.merge(vid + "|" + turn.playerId, delta, (a, b) -> Math.max(-100, Math.min(100, a + b)));
                if (!memory.isEmpty() && memory.length() > 3) {
                    store.addMemory(vid, turn.playerId, "conversation", memory);
                }
                if (turn.facts.kind != VillagerKind.MCA) {
                    int level = switch (mood) {
                        case "excited" -> 8;
                        case "happy" -> 6;
                        case "content" -> 3;
                        case "annoyed" -> -4;
                        case "scared" -> -5;
                        case "sad" -> -6;
                        case "angry" -> -9;
                        default -> 0;
                    };
                    moodLevels.merge(vid, level, (old, n) -> Math.max(-15, Math.min(15, (old + n) / 2)));
                    store.db.exec("UPDATE villagers SET mood=?, mood_level=? WHERE uuid=?", mood, moodLevels.get(vid), vid);
                }
                server.execute(() -> {
                    Entity v = turn.villager.get();
                    if (v != null && turn.player != null && delta != 0) {
                        turn.adapter.applyOutcome(v, turn.player, delta);
                    }
                });
                JsonObject ev = new JsonObject();
                ev.addProperty("villager", vid.toString());
                ev.addProperty("villagerName", turn.profile.name());
                ev.addProperty("player", turn.playerName);
                ev.addProperty("delta", delta);
                ev.addProperty("mood", mood);
                ev.addProperty("memory", memory);
                feed.publish("relationship", ev);
            } catch (Exception e) {
                TheyWillTalk.LOGGER.debug("Reflection parse failed: {}", e.toString());
            }
        });
    }

    // =========================================================================================================
    // Output helpers
    // =========================================================================================================

    private void subtitle(Entity villager, VillagerProfile profile, VillagerKind kind, String rawText) {
        if (!TwtConfig.SUBTITLES.get()) {
            return;
        }
        String text = forPlayers(rawText);
        ChatFormatting color = kindColor(kind);
        MutableComponent name = Component.literal(profile.name()).withStyle(Style.EMPTY.withColor(color)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(profile.personaEnum().key + " • " + kind.label))));
        double range = TwtConfig.VOICE_DISTANCE.get();
        for (ServerPlayer p : ((ServerLevel) villager.level()).players()) {
            double d = p.distanceTo(villager);
            if (d > range) {
                continue;
            }
            MutableComponent line = Component.empty()
                    .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                    .append(name)
                    .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(text).withStyle(d > range * 0.6 ? ChatFormatting.GRAY : ChatFormatting.WHITE));
            p.sendSystemMessage(line);
        }
    }

    /** The sentence being spoken right now, shown above the hotbar of players who can hear it. */
    /** What players see of a villager's line: swear words censored when bleeping is on, slurs always. */
    static String forPlayers(String text) {
        return Profanity.censor(text, TwtConfig.BLEEP_SWEARING.get());
    }

    /** A villager's line as a Sipher caption bubble above them (players with Sipher see it in their language). */
    private void bubble(Entity villager, String language, String rawText, double range) {
        if (sipher && TwtConfig.SIPHER_BUBBLES.get()) {
            dev.eiriksb.theywilltalk.integration.SipherBridge.caption(villager, language, forPlayers(rawText), range);
        }
    }

    /**
     * The language a villager answers in: the player's spoken language (from Sipher) when that's on and the Qwen3-TTS
     * voices can speak it; otherwise English. Greetings use the language the player last spoke in.
     */
    private String replyLanguage(Channel channel, String lang, UUID player) {
        String spoken = Languages.base(lang);
        if (channel == Channel.VOICE && !spoken.isEmpty() && !spoken.equals("und")) {
            playerLanguages.put(player, spoken);
        }
        if (!TwtConfig.REPLY_IN_PLAYER_LANGUAGE.get() || !speech.expressive()) {
            return Languages.ENGLISH;
        }
        String candidate = channel == Channel.VOICE ? spoken
                : channel == Channel.GREETING ? playerLanguages.getOrDefault(player, Languages.ENGLISH) : Languages.ENGLISH;
        return Languages.speakable(candidate) ? Languages.base(candidate) : Languages.ENGLISH;
    }

    /** One sentence as it's spoken: a Sipher bubble above the villager and the action bar line. */
    private void caption(Entity villager, VillagerProfile profile, VillagerKind kind, String rawText, String language) {
        double range = TwtConfig.VOICE_DISTANCE.get();
        bubble(villager, language, rawText, range);
        if (!TwtConfig.SUBTITLES.get()) {
            return;
        }
        String text = forPlayers(rawText);
        Component line = Component.literal(profile.firstName() + ": ").withStyle(kindColor(kind))
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : ((ServerLevel) villager.level()).players()) {
            if (p.distanceTo(villager) <= range) {
                p.displayClientMessage(line, true);
            }
        }
    }

    private static ChatFormatting kindColor(VillagerKind kind) {
        return switch (kind) {
            case MCA -> ChatFormatting.GOLD;
            case MINECOLONIES -> ChatFormatting.AQUA;
            case WANDERING_TRADER -> ChatFormatting.BLUE;
            default -> ChatFormatting.GREEN;
        };
    }

    private String nameOf(Entity e) {
        VillagerProfile p = villagers.cached(e.getUUID());
        return p == null ? null : p.name();
    }

    private static String name(Entity e) {
        return e.hasCustomName() && e.getCustomName() != null ? e.getCustomName().getString() : "The villager";
    }

    // =========================================================================================================
    // Per-tick upkeep: attention, greetings, ambient chatter
    // =========================================================================================================

    public void tick() {
        long now = System.currentTimeMillis();
        for (Speaker sp : speakers.values()) {
            if (sp.attentionUntil < now) {
                continue;
            }
            Entity v = sp.entity.get();
            ServerPlayer target = sp.attentionTarget == null ? null : sp.attentionTarget.get();
            if (v == null || target == null || !v.isAlive() || target.isRemoved() || v.level() != target.level()) {
                sp.attentionUntil = 0;
                continue;
            }
            VillagerAdapter a = villagers.adapterFor(v);
            if (a != null) {
                a.holdAttention(v, target);
            }
        }
        if (++tick % 40 != 0 || !llmReady.get()) {
            return;
        }
        sessions.entrySet().removeIf(e -> now - e.getValue().lastActivity > 10 * 60_000L);
        speakers.entrySet().removeIf(e -> e.getValue().entity.get() == null && e.getValue().current == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (TwtConfig.GREETINGS.get()) {
                maybeGreet(player, now);
            }
            if (TwtConfig.AMBIENT_CHATTER.get()) {
                maybeAmbient(player, now);
            }
        }
    }

    private void maybeGreet(ServerPlayer player, long now) {
        Session s = sessions.get(player.getUUID());
        if (s != null && now - s.lastActivity < 60_000) {
            return;
        }
        List<Entity> close = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(4), villagers::canTalk);
        for (Entity v : close) {
            String key = v.getUUID() + "|" + player.getUUID();
            int aff = affinity.getOrDefault(key, 0);
            Long last = greeted.get(key);
            Speaker sp = speakers.get(v.getUUID());
            if (aff < 20 || (last != null && now - last < 20 * 60_000L) || (sp != null && sp.current != null)
                    || !player.hasLineOfSight(v)) {
                continue;
            }
            greeted.put(key, now);
            talk(v, player, PromptBuilder.greetingRequest(player.getGameProfile().getName()), Channel.GREETING, null, "en");
            return;
        }
    }

    private void maybeAmbient(ServerPlayer player, long now) {
        long area = (((long) player.getBlockX() >> 5) << 32) ^ ((player.getBlockZ() >> 5) & 0xffffffffL) ^ player.level().dimension().hashCode();
        Long last = ambientByArea.get(area);
        if (last != null && now - last < TwtConfig.AMBIENT_INTERVAL_SECONDS.get() * 1000L) {
            return;
        }
        if (player.getRandom().nextFloat() > 0.08f) {
            return; // spread chats out; checked every 2 seconds
        }
        Session s = sessions.get(player.getUUID());
        if (s != null && now - s.lastActivity < 45_000) {
            return;
        }
        List<Entity> near = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(12), e -> villagers.canTalk(e) && idle(e));
        for (int i = 0; i < near.size(); i++) {
            for (int j = i + 1; j < near.size(); j++) {
                Entity a = near.get(i);
                Entity b = near.get(j);
                if (a.distanceTo(b) < 6 && a.distanceTo(b) > 0.5) {
                    ambientByArea.put(area, now);
                    ambient(a, b, player);
                    return;
                }
            }
        }
    }

    private boolean idle(Entity e) {
        if (e instanceof net.minecraft.world.entity.LivingEntity le && (le.isSleeping() || le.isBaby())) {
            return false;
        }
        Speaker sp = speakers.get(e.getUUID());
        return sp == null || sp.current == null;
    }

    private void ambient(Entity a, Entity b, ServerPlayer nearbyPlayer) {
        VillagerFacts fa = villagers.facts(a, null);
        VillagerFacts fb = villagers.facts(b, null);
        VillagerProfile pa = villagers.profile(a, fa);
        VillagerProfile pb = villagers.profile(b, fb);
        List<String> world = WorldContext.capture(a, null, this::nameOf);
        Speaker sa = speakers.computeIfAbsent(a.getUUID(), u -> new Speaker(a));
        Speaker sb = speakers.computeIfAbsent(b.getUUID(), u -> new Speaker(b));
        Turn ta = new Turn(a, null, new UUID(0, 0), pb.name(), "", Channel.AMBIENT, null, "en", fa, pa, villagers.adapterFor(a));
        Turn tb = new Turn(b, null, new UUID(0, 0), pa.name(), "", Channel.AMBIENT, null, "en", fb, pb, villagers.adapterFor(b));
        sa.current = ta;
        sb.current = tb;
        String playerName = nearbyPlayer.getGameProfile().getName();

        Thread.ofVirtual().name("twt-ambient").start(() -> {
            try {
                List<Store.Memory> news = store.gossip(nearbyPlayer.getUUID(), fa.village == null ? null : fa.village.key(), new UUID(0, 0), 2)
                        .get(3, TimeUnit.SECONDS);
                LlmClient.Result r = llm.chat(LlmClient.Priority.AMBIENT, PromptBuilder.ambient(pa, fa, pb, fb, world, playerName, news),
                        0.95, 260, PromptBuilder.ambientSchema(), null).get(60, TimeUnit.SECONDS);
                JsonArray lines = JsonParser.parseString(r.text()).getAsJsonObject().getAsJsonArray("lines");
                VoiceOutput out = voice.get();
                List<String> transcript = new ArrayList<>();
                for (JsonElement el : lines) {
                    if (ta.cancelled || tb.cancelled) {
                        break;
                    }
                    JsonObject line = el.getAsJsonObject();
                    boolean isA = "A".equals(line.get("speaker").getAsString());
                    Entity who = isA ? a : b;
                    VillagerProfile prof = isA ? pa : pb;
                    VillagerFacts facts = isA ? fa : fb;
                    String emotion = line.get("emotion").getAsString();
                    String text = SentenceStream.clean(line.get("text").getAsString());
                    if (text.isEmpty()) {
                        continue;
                    }
                    transcript.add(prof.firstName() + ": " + text);
                    long playMs = 0;
                    if (out.available()) {
                        short[] pcm = speech.render(prof, facts, text, emotion);
                        VoiceOutput.SpeechStream stream = out.open(who, TwtConfig.VOICE_DISTANCE.get().floatValue() * 0.75f);
                        (isA ? ta : tb).stream = stream;
                        stream.push(pcm);
                        stream.finish();
                        playMs = pcm.length * 1000L / AudioDsp.SVC_RATE;
                    }
                    rememberSpoken(who, text, System.currentTimeMillis() + playMs);
                    server.execute(() -> {
                        Gestures.react(who, facts.kind, emotion);
                        subtitle(who, prof, facts.kind, text);
                        bubble(who, Languages.ENGLISH, text, TwtConfig.VOICE_DISTANCE.get() * 0.75);
                    });
                    Thread.sleep(Math.max(1200, playMs + 350));
                }
                if (!transcript.isEmpty()) {
                    JsonObject ev = new JsonObject();
                    ev.addProperty("villager", pa.uuid().toString());
                    ev.addProperty("villagerName", pa.name());
                    ev.addProperty("other", pb.uuid().toString());
                    ev.addProperty("otherName", pb.name());
                    ev.addProperty("text", String.join("\n", transcript));
                    feed.publish("ambient", ev);
                    store.event("ambient", pa.uuid(), null, String.join(" / ", transcript));
                }
            } catch (Exception e) {
                TheyWillTalk.LOGGER.debug("Ambient chat failed: {}", e.toString());
            } finally {
                server.execute(() -> {
                    finishTurn(sa, ta);
                    finishTurn(sb, tb);
                });
            }
        });
    }

    // =========================================================================================================
    // Game events -> memories
    // =========================================================================================================

    /** Something happened between a player and a villager worth remembering (hit, trade, gift...). */
    public void remember(Entity villager, ServerPlayer player, String kind, String text, int affinityDelta) {
        if (!villagers.canTalk(villager)) {
            return;
        }
        VillagerFacts facts = villagers.facts(villager, player);
        VillagerProfile p = villagers.profile(villager, facts);
        store.addMemory(p.uuid(), player.getUUID(), kind, text);
        if (affinityDelta != 0) {
            store.adjustAffinity(p.uuid(), player.getUUID(), affinityDelta);
            affinity.merge(p.uuid() + "|" + player.getUUID(), affinityDelta, (a, b) -> Math.max(-100, Math.min(100, a + b)));
        }
        store.event(kind, p.uuid(), player.getUUID(), p.name() + ": " + text);
        JsonObject ev = new JsonObject();
        ev.addProperty("villager", p.uuid().toString());
        ev.addProperty("villagerName", p.name());
        ev.addProperty("player", player.getGameProfile().getName());
        ev.addProperty("memory", text);
        ev.addProperty("delta", affinityDelta);
        feed.publish("event", ev);
    }

    public int activeConversations() {
        return (int) speakers.values().stream().filter(s -> s.current != null).count();
    }

    public void forgetVillager(UUID villager) {
        sessions.values().removeIf(s -> s.villager.equals(villager));
        affinity.keySet().removeIf(k -> k.startsWith(villager + "|"));
        moodLevels.remove(villager);
    }

    // =========================================================================================================

    private static final class Session {
        final UUID villager;
        final List<PromptBuilder.Turn> history = new ArrayList<>();
        volatile long lastActivity = System.currentTimeMillis();
        volatile long conversationId = -1;

        Session(UUID villager) {
            this.villager = villager;
        }
    }

    private static final class Speaker {
        volatile WeakReference<Entity> entity;
        volatile Turn current;
        final Deque<Pending> queue = new ArrayDeque<>();
        volatile WeakReference<ServerPlayer> attentionTarget;
        volatile long attentionUntil;
        volatile long speakingUntil;

        Speaker(Entity e) {
            this.entity = new WeakReference<>(e);
        }
    }

    private record Pending(ServerPlayer player, String text, Channel channel, String originalText, String lang) {}

    private static final class Turn {
        final WeakReference<Entity> villager;
        final ServerPlayer player;
        final UUID playerId;
        final String playerName;
        final String text;
        final Channel channel;
        final String originalText;
        final String lang;
        final VillagerFacts facts;
        final VillagerProfile profile;
        final VillagerAdapter adapter;
        volatile boolean cancelled;
        volatile CompletableFuture<LlmClient.Result> llmFuture;
        volatile VoiceOutput.SpeechStream stream;
        volatile long firstAudioMs;
        /** Language the villager answers in (the player's, when the voices speak it). */
        volatile String replyLang = Languages.ENGLISH;
        final BlockingQueue<String> sentences = new LinkedBlockingQueue<>();

        Turn(Entity villager, ServerPlayer player, UUID playerId, String playerName, String text, Channel channel, String originalText,
             String lang, VillagerFacts facts, VillagerProfile profile, VillagerAdapter adapter) {
            this.villager = new WeakReference<>(villager);
            this.player = player;
            this.playerId = playerId;
            this.playerName = playerName;
            this.text = text;
            this.channel = channel;
            this.originalText = originalText;
            this.lang = lang;
            this.facts = facts;
            this.profile = profile;
            this.adapter = adapter;
        }

        void cancel() {
            cancelled = true;
            sentences.add(END);
            CompletableFuture<LlmClient.Result> f = llmFuture;
            if (f != null) {
                f.cancel(true);
            }
            VoiceOutput.SpeechStream s = stream;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
