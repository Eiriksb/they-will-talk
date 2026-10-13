package dev.eiriksb.theywilltalk.lend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtClientConfig;
import dev.eiriksb.theywilltalk.ai.LlmClient;
import dev.eiriksb.theywilltalk.runtime.RuntimeManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's side of lending their GPU: when the server takes lent GPUs and {@code lendGpu} is on, start the language
 * model installed on this computer (the same one singleplayer uses) and answer the requests the server sends.
 */
public final class LendClient {
    private static final Map<Long, CompletableFuture<LlmClient.Result>> running = new ConcurrentHashMap<>();
    private static volatile RuntimeManager runtime;
    private static volatile LlmClient llm;
    /** What the server we're on said when we joined; null in singleplayer or on servers without They Will Talk. */
    private static volatile LendNet.Welcome server;

    private LendClient() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            server = null;
            stop(false);
        });
        NeoForge.EVENT_BUS.addListener(LendClient::commands);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RuntimeManager r = runtime;
            if (r != null) {
                r.stop();
            }
        }, "twt-lend-shutdown"));
    }

    static void welcome(LendNet.Welcome w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer()) {
            return; // singleplayer already thinks on this computer
        }
        server = w;
        if (!w.accepting()) {
            return;
        }
        if (TwtClientConfig.LEND_GPU.get()) {
            start();
        } else if (!w.ownModel()) {
            tell("This server has no villager AI of its own. /lendgpu on lets the villagers you talk to think on your GPU.");
        }
    }

    private static synchronized void start() {
        if (runtime != null) {
            return;
        }
        RuntimeManager rt = new RuntimeManager(FMLPaths.GAMEDIR.get());
        if (rt.setupNeeded()) {
            tell("Lending your GPU needs the villager AI on this computer: open a singleplayer world and install it on the They Will Talk"
                    + " dashboard (Models page).");
            return;
        }
        runtime = rt;
        tell("Starting the villager AI on your GPU...");
        Thread.ofVirtual().name("twt-lend-start").start(() -> {
            rt.startBrainOnly();
            long deadline = System.currentTimeMillis() + 5 * 60_000;
            while (runtime == rt && !rt.llmReady() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (runtime != rt) {
                return;
            }
            if (!rt.llmReady()) {
                tell("The villager AI didn't start on your computer: " + String.join(" ", rt.problems()));
                stop(false);
                return;
            }
            int slots = TwtClientConfig.LEND_SLOTS.get();
            llm = new LlmClient(rt::llmBaseUrl, slots);
            if (server != null && server.accepting()) {
                PacketDistributor.sendToServer(new LendNet.Offer(rt.llmModelName(), slots));
                TheyWillTalk.LOGGER.info("[lend] lending {} to the server", rt.llmModelName());
            }
        });
    }

    private static synchronized void stop(boolean tellServer) {
        if (tellServer && server != null && Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new LendNet.Offer("", 0));
        }
        running.values().forEach(f -> f.cancel(true));
        running.clear();
        if (llm != null) {
            llm.shutdown();
            llm = null;
        }
        if (runtime != null) {
            RuntimeManager rt = runtime;
            runtime = null;
            Thread.ofVirtual().start(rt::stop);
        }
    }

    static void job(LendNet.Job job) {
        LlmClient c = llm;
        if (c == null) {
            PacketDistributor.sendToServer(new LendNet.Done(job.id(), false, "not lending", 0, 0));
            return;
        }
        List<LlmClient.Message> messages = new ArrayList<>();
        double temperature;
        int maxTokens;
        JsonObject schema;
        try {
            JsonObject o = JsonParser.parseString(job.request()).getAsJsonObject();
            for (JsonElement e : o.getAsJsonArray("messages")) {
                JsonObject m = e.getAsJsonObject();
                messages.add(new LlmClient.Message(m.get("role").getAsString(), m.get("content").getAsString()));
            }
            temperature = o.get("temperature").getAsDouble();
            maxTokens = Math.min(2048, o.get("maxTokens").getAsInt());
            schema = o.has("schema") ? o.getAsJsonObject("schema") : null;
        } catch (RuntimeException e) {
            PacketDistributor.sendToServer(new LendNet.Done(job.id(), false, "bad request", 0, 0));
            return;
        }
        CompletableFuture<LlmClient.Result> f = c.chat(LlmClient.Priority.CONVERSATION, messages, temperature, maxTokens, schema,
                piece -> PacketDistributor.sendToServer(new LendNet.Delta(job.id(), piece.length() > 2000 ? piece.substring(0, 2000) : piece)));
        running.put(job.id(), f);
        f.whenComplete((r, t) -> {
            running.remove(job.id());
            if (f.isCancelled() || Minecraft.getInstance().getConnection() == null) {
                return;
            }
            if (t != null) {
                PacketDistributor.sendToServer(new LendNet.Done(job.id(), false, String.valueOf(t.getMessage()), 0, 0));
            } else {
                String text = r.text().length() > LendNet.Done.MAX_TEXT ? r.text().substring(0, LendNet.Done.MAX_TEXT) : r.text();
                PacketDistributor.sendToServer(new LendNet.Done(job.id(), true, text, (int) Math.max(0, r.firstTokenMs()), r.tokens()));
            }
        });
    }

    static void cancel(LendNet.Cancel c) {
        CompletableFuture<LlmClient.Result> f = running.remove(c.id());
        if (f != null) {
            f.cancel(true);
        }
    }

    private static void tell(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
            }
        });
    }

    // ---- /lendgpu ------------------------------------------------------------------------------------------------

    private static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lendgpu").executes(LendClient::status)
                .then(Commands.literal("on").executes(ctx -> set(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> set(ctx, false))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        LendNet.Welcome w = server;
        String text = llm != null ? "Your GPU is lent to this server: villagers you talk to think on your computer. /lendgpu off to stop."
                : runtime != null ? "The villager AI is starting on your GPU..."
                : w == null ? "This server can't use a lent GPU (or you're in singleplayer, which already thinks on your computer)."
                : !w.accepting() ? "This server doesn't take lent GPUs."
                : "You're not lending your GPU. /lendgpu on lets the villagers you talk to think on your computer.";
        ctx.getSource().sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean on) {
        TwtClientConfig.LEND_GPU.set(on);
        TwtClientConfig.LEND_GPU.save();
        if (on) {
            LendNet.Welcome w = server;
            if (w != null && w.accepting()) {
                start();
            } else {
                status(ctx);
            }
        } else {
            stop(true);
            ctx.getSource().sendSystemMessage(Component.literal("You're no longer lending your GPU.").withStyle(ChatFormatting.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }
}
