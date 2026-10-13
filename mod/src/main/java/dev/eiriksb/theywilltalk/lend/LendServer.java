package dev.eiriksb.theywilltalk.lend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.LlmClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The server's side of lent GPUs: a player who offers theirs gets their own conversations sent to it (and only those).
 * Whatever comes back is just text: a modified client can make a villager say odd things to that player, nothing more.
 */
public final class LendServer {
    /** A lent GPU that says nothing for this long has dropped the request. */
    static final long QUIET_MS = 30_000;
    static final long MAX_MS = 120_000;

    private static final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private static final AtomicLong ids = new AtomicLong();

    private record Pending(UUID player, Consumer<String> onDelta, int maxChars, CompletableFuture<LendNet.Done> done, AtomicLong heard,
                           AtomicInteger chars) {}

    private LendServer() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                welcome(p);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            TheyWillTalk mod = TheyWillTalk.get();
            if (mod != null && mod.llm() != null) {
                mod.llm().unlend(e.getEntity().getUUID());
            }
        });
    }

    private static void welcome(ServerPlayer p) {
        TheyWillTalk mod = TheyWillTalk.get();
        if (mod == null || mod.runtime() == null || !p.connection.hasChannel(LendNet.Welcome.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(p, new LendNet.Welcome(TwtConfig.LENT_GPUS.get(), mod.runtime().llmReady() || !mod.runtime().setupNeeded()));
    }

    static void offer(LendNet.Offer offer, IPayloadContext ctx) {
        TheyWillTalk mod = TheyWillTalk.get();
        if (!(ctx.player() instanceof ServerPlayer player) || mod == null || mod.llm() == null) {
            return;
        }
        if (offer.slots() <= 0) {
            mod.llm().unlend(player.getUUID());
            return;
        }
        if (!TwtConfig.LENT_GPUS.get()) {
            player.sendSystemMessage(Component.literal("This server doesn't take lent GPUs.").withStyle(ChatFormatting.GRAY));
            return;
        }
        String model = offer.model().isBlank() ? "a language model" : offer.model().strip();
        mod.llm().lend(player.getUUID(), new PlayerGpu(player, model, Math.clamp(offer.slots(), 1, 4)));
        player.sendSystemMessage(Component.literal("Thanks for lending your GPU! Villagers you talk to now think on your computer ("
                + model + ").").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    static void delta(LendNet.Delta d, IPayloadContext ctx) {
        Pending p = pending.get(d.id());
        if (p == null || !p.player.equals(ctx.player().getUUID()) || p.chars.addAndGet(d.piece().length()) > p.maxChars) {
            return;
        }
        p.heard.set(System.currentTimeMillis());
        try {
            p.onDelta.accept(d.piece());
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Lent answer handler failed", e);
        }
    }

    static void done(LendNet.Done d, IPayloadContext ctx) {
        Pending p = pending.get(d.id());
        if (p != null && p.player.equals(ctx.player().getUUID())) {
            p.done.complete(d);
        }
    }

    /** The request as it's sent: everything a model needs, nothing about the game. */
    static String json(LlmClient.Request r) {
        JsonObject o = new JsonObject();
        JsonArray messages = new JsonArray();
        for (LlmClient.Message m : r.messages()) {
            JsonObject j = new JsonObject();
            j.addProperty("role", m.role());
            j.addProperty("content", m.content());
            messages.add(j);
        }
        o.add("messages", messages);
        o.addProperty("temperature", r.temperature());
        o.addProperty("maxTokens", r.maxTokens());
        if (r.jsonSchema() != null) {
            o.add("schema", r.jsonSchema());
        }
        return o.toString();
    }

    /** One player's GPU, reached through their connection. */
    private record PlayerGpu(ServerPlayer player, String model, int slots) implements LlmClient.Backend {
        @Override
        public String name() {
            return player.getGameProfile().getName() + " (" + model + ")";
        }

        @Override
        public LlmClient.Result run(LlmClient.Request request, Consumer<String> onDelta, BooleanSupplier cancelled)
                throws IOException, InterruptedException {
            long id = ids.incrementAndGet();
            long start = System.currentTimeMillis();
            int maxChars = Math.min(LendNet.Done.MAX_TEXT, request.maxTokens() * 16 + 256);
            Pending p = new Pending(player.getUUID(), onDelta, maxChars, new CompletableFuture<>(), new AtomicLong(start), new AtomicInteger());
            pending.put(id, p);
            try {
                if (player.hasDisconnected()) {
                    throw new IOException(player.getGameProfile().getName() + " left");
                }
                PacketDistributor.sendToPlayer(player, new LendNet.Job(id, json(request)));
                while (true) {
                    try {
                        LendNet.Done d = p.done.get(250, TimeUnit.MILLISECONDS);
                        if (!d.ok()) {
                            throw new IOException("the lent GPU couldn't answer: " + d.text());
                        }
                        String text = d.text().length() > maxChars ? d.text().substring(0, maxChars) : d.text();
                        return new LlmClient.Result(text, d.firstTokenMs(), System.currentTimeMillis() - start, d.tokens());
                    } catch (TimeoutException e) {
                        long now = System.currentTimeMillis();
                        if (cancelled.getAsBoolean()) {
                            PacketDistributor.sendToPlayer(player, new LendNet.Cancel(id));
                            throw new CancellationException();
                        }
                        if (player.hasDisconnected()) {
                            throw new IOException(player.getGameProfile().getName() + " left");
                        }
                        if (now - p.heard.get() > QUIET_MS || now - start > MAX_MS) {
                            PacketDistributor.sendToPlayer(player, new LendNet.Cancel(id));
                            throw new IOException("the lent GPU took too long");
                        }
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IOException(e.getCause());
                    }
                }
            } finally {
                pending.remove(id);
            }
        }
    }
}
