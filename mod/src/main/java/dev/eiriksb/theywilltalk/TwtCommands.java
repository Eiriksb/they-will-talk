package dev.eiriksb.theywilltalk;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import dev.eiriksb.theywilltalk.runtime.GpuInfo;
import dev.eiriksb.theywilltalk.runtime.ManagedProcess;
import dev.eiriksb.theywilltalk.runtime.RuntimeManager;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import dev.eiriksb.theywilltalk.web.DashboardServer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Comparator;

/**
 * <pre>
 * /twt talk &lt;message&gt;                talk to the villager you're looking at (typed, no voice needed)
 * /twt status                          AI runtime, GPU and conversation stats            (op)
 * /twt dashboard                       clickable admin dashboard login link              (op)
 * /twt say &lt;villager&gt; &lt;message&gt;       make a villager answer you / the console          (op)
 * /twt hear &lt;player&gt; &lt;message&gt;        pretend a player said something by voice          (op)
 * /twt relay &lt;player&gt; &lt;lang&gt; &lt;text&gt;  push a line through Sipher's real caption relay    (op)
 * /twt info &lt;villager&gt;                 who is this villager?                              (op)
 * /twt restart                         restart the AI processes                           (op)
 * /twt event &lt;villager&gt; &lt;player&gt; &lt;what&gt;  greet | gift | errand | fetch | hunt | deliver     (op)
 * /twt errands                         your errands, with buttons
 * /twt errand accept|decline|abandon &lt;id&gt;
 * /twt dev bot &lt;name&gt;                 a stand-in player for testing (dev runs only)      (op)
 * </pre>
 */
final class TwtCommands {
    private TwtCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("twt")
                .then(Commands.literal("talk")
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(TwtCommands::talk)))
                .then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(TwtCommands::status))
                .then(Commands.literal("dashboard").requires(s -> s.hasPermission(3)).executes(TwtCommands::dashboard))
                .then(Commands.literal("restart").requires(s -> s.hasPermission(3)).executes(TwtCommands::restart))
                .then(Commands.literal("say").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("villager", EntityArgument.entity())
                                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(TwtCommands::say))))
                .then(Commands.literal("hear").requires(s -> s.hasPermission(3))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(TwtCommands::hear))))
                .then(Commands.literal("relay").requires(s -> s.hasPermission(3) && net.neoforged.fml.ModList.get().isLoaded("sipher"))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("lang", StringArgumentType.word())
                                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(TwtCommands::relay)))))
                .then(Commands.literal("info").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("villager", EntityArgument.entity()).executes(TwtCommands::info)))
                .then(Commands.literal("event").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("villager", EntityArgument.entity())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("what", StringArgumentType.word())
                                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.List.of("greet", "gift", "errand", "fetch", "hunt", "deliver"), b))
                                                .executes(TwtCommands::event)))))
                .then(Commands.literal("errands").executes(TwtCommands::errands))
                .then(Commands.literal("errand")
                        .then(Commands.literal("accept").then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> errand(ctx, "accept"))))
                        .then(Commands.literal("decline").then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> errand(ctx, "decline"))))
                        .then(Commands.literal("abandon").then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> errand(ctx, "abandon")))))
                .then(Commands.literal("dev").requires(s -> s.hasPermission(3) && Boolean.getBoolean("theywilltalk.devCommands"))
                        .then(Commands.literal("bot").then(Commands.argument("name", StringArgumentType.word()).executes(TwtCommands::bot)))));
    }

    private static int event(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null || mod.events() == null) {
            return 0;
        }
        Entity v = EntityArgument.getEntity(ctx, "villager");
        if (!mod.villagers().canTalk(v)) {
            ctx.getSource().sendFailure(Component.literal("That entity can't talk."));
            return 0;
        }
        String result = mod.events().trigger(v, EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "what"));
        ctx.getSource().sendSuccess(() -> Component.literal(result), true);
        return 1;
    }

    private static int errands(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null || mod.events() == null) {
            return 0;
        }
        mod.events().list(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int errand(CommandContext<CommandSourceStack> ctx, String action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null || mod.events() == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long id = LongArgumentType.getLong(ctx, "id");
        boolean done = switch (action) {
            case "accept" -> mod.events().accept(player, id);
            case "decline" -> mod.events().decline(player, id);
            default -> mod.events().abandon(player, id);
        };
        return done ? 1 : 0;
    }

    /**
     * Development aid: a server-side stand-in player (no client) at the command's position, for trying villager events
     * from the console. What it's told goes to the log. Only with -Dtheywilltalk.devCommands=true.
     */
    private static int bot(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        var source = ctx.getSource();
        var server = source.getServer();
        if (server.getPlayerList().getPlayerByName(name) != null) {
            source.sendFailure(Component.literal(name + " is already online."));
            return 0;
        }
        var profile = new com.mojang.authlib.GameProfile(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name), name);
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer bot = new ServerPlayer(server, source.getLevel(), profile, cookie.clientInformation()) {
            @Override
            public void sendSystemMessage(Component message, boolean overlay) {
                TheyWillTalk.LOGGER.info("[{} {}] {}", name, overlay ? "action bar" : "chat", message.getString());
            }
        };
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        try {
            server.getPlayerList().placeNewPlayer(connection, bot, cookie);
        } catch (RuntimeException e) {
            // Mods greeting a new player with client-only packets (MCA's welcome screen) fail on the fake connection.
            TheyWillTalk.LOGGER.info("Bot {} joined with a hiccup: {}", name, e.getMessage());
        }
        var pos = source.getPosition();
        bot.teleportTo(source.getLevel(), pos.x, pos.y, pos.z, 0, 0);
        source.sendSuccess(() -> Component.literal("Bot " + name + " joined at " + (int) pos.x + " " + (int) pos.y + " " + (int) pos.z), false);
        return 1;
    }

    private static TheyWillTalk mod(CommandContext<CommandSourceStack> ctx) {
        TheyWillTalk mod = TheyWillTalk.get();
        if (mod == null || mod.conversationManager() == null) {
            ctx.getSource().sendFailure(Component.literal("They Will Talk is not running on this world."));
            return null;
        }
        return mod;
    }

    private static int talk(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String msg = StringArgumentType.getString(ctx, "message");
        if (!mod.conversationManager().hear(player, msg, ConversationManager.Channel.COMMAND, null, "en")) {
            // Not looking at anyone: fall back to the closest villager in earshot.
            double r = TwtConfig.LISTEN_RADIUS.get();
            Entity nearest = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(r), mod.villagers()::canTalk)
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(player))).orElse(null);
            if (nearest == null) {
                ctx.getSource().sendFailure(Component.literal("No villager close enough to hear you."));
                return 0;
            }
            mod.conversationManager().talk(nearest, player, msg, ConversationManager.Channel.COMMAND, null, "en");
        }
        return 1;
    }

    private static int say(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        Entity v = EntityArgument.getEntity(ctx, "villager");
        if (!mod.villagers().canTalk(v)) {
            ctx.getSource().sendFailure(Component.literal("That entity can't talk."));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        mod.conversationManager().talk(v, player, StringArgumentType.getString(ctx, "message"), ConversationManager.Channel.COMMAND, null, "en");
        ctx.getSource().sendSuccess(() -> Component.literal("Asked " + v.getName().getString() + "..."), false);
        return 1;
    }

    private static int hear(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        boolean heard = mod.conversationManager().hear(player, StringArgumentType.getString(ctx, "message"),
                ConversationManager.Channel.VOICE, null, "en");
        ctx.getSource().sendSuccess(() -> Component.literal(heard ? "A villager heard it." : "No villager was addressed (look at one or say its name)."), false);
        return heard ? 1 : 0;
    }

    /** Testing aid without a microphone: "text | english" through Sipher's caption relay, like a transcribed voice line. */
    private static int relay(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String lang = StringArgumentType.getString(ctx, "lang");
        String[] parts = StringArgumentType.getString(ctx, "text").split("\\|", 2);
        String text = parts[0].trim();
        // Like Sipher's clients: no English when the line already is English.
        String english = parts.length > 1 ? parts[1].trim() : "";
        dev.eiriksb.theywilltalk.integration.SipherBridge.relay(player, lang, text, english);
        ctx.getSource().sendSuccess(() -> Component.literal("Relayed through Sipher: " + (english.isEmpty() ? text : english)), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        Entity v = EntityArgument.getEntity(ctx, "villager");
        if (!mod.villagers().canTalk(v)) {
            ctx.getSource().sendFailure(Component.literal("That entity can't talk."));
            return 0;
        }
        VillagerFacts f = mod.villagers().facts(v, ctx.getSource().getPlayer());
        VillagerProfile p = mod.villagers().profile(v, f);
        CommandSourceStack s = ctx.getSource();
        s.sendSuccess(() -> Component.literal(p.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal("  " + p.kind().label + (f.job == null ? "" : " • " + f.job)).withStyle(ChatFormatting.GRAY)), false);
        s.sendSuccess(() -> Component.literal("Personality: " + p.persona() + " — " + p.personaEnum().description), false);
        s.sendSuccess(() -> Component.literal("Quirk: " + p.quirk()), false);
        s.sendSuccess(() -> Component.literal("Backstory: " + p.backstory()), false);
        s.sendSuccess(() -> Component.literal("Voice: " + p.voice() + String.format(" (pitch %.2f, speed %.2f)", p.pitch(), p.speed())), false);
        if (f.village != null) {
            s.sendSuccess(() -> Component.literal("Village: " + f.village.name()), false);
        }
        if (!f.family.isEmpty()) {
            s.sendSuccess(() -> Component.literal("Family: " + f.family.stream().map(l -> l.relation() + " " + l.name()).toList()), false);
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        RuntimeManager rt = mod.runtime();
        CommandSourceStack s = ctx.getSource();
        s.sendSuccess(() -> Component.literal("They Will Talk").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        s.sendSuccess(() -> line("LLM", rt.llmProcess(), rt.llmReady(), rt.llmModelName()), false);
        s.sendSuccess(() -> line("Voice", rt.voiceProcess(), rt.voiceReady(), mod.tts().voices().size() + " voices"), false);
        for (GpuInfo.Gpu g : GpuInfo.query()) {
            s.sendSuccess(() -> Component.literal(String.format("GPU: %s, %d/%d MB VRAM, %d%% busy, %d°C", g.name(), g.memoryUsedMb(),
                    g.memoryTotalMb(), g.utilization(), g.temperature())).withStyle(ChatFormatting.GRAY), false);
        }
        ConversationManager cm = mod.conversationManager();
        s.sendSuccess(() -> Component.literal(String.format("Replies: %d • last first-voice latency %d ms • %.0f tokens/s • %d talking now",
                cm.turnsTotal, cm.lastLatencyMs, mod.llm().lastTokensPerSecond, cm.activeConversations())).withStyle(ChatFormatting.GRAY), false);
        rt.problems().forEach(p -> s.sendSuccess(() -> Component.literal("! " + p).withStyle(ChatFormatting.RED), false));
        return 1;
    }

    private static Component line(String label, ManagedProcess p, boolean ready, String detail) {
        String state = p == null ? (ready ? "external" : "not started") : p.state().name().toLowerCase();
        return Component.literal(label + ": ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(state).withStyle(ready ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .append(Component.literal(" " + (detail == null ? "" : detail)).withStyle(ChatFormatting.GRAY))
                .append(p != null && !p.lastError().isEmpty() && !ready ? Component.literal(" (" + p.lastError() + ")").withStyle(ChatFormatting.RED) : Component.empty());
    }

    private static int dashboard(CommandContext<CommandSourceStack> ctx) {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        DashboardServer dash = mod.dashboard();
        if (dash == null) {
            ctx.getSource().sendFailure(Component.literal("The dashboard is disabled in config/theywilltalk-common.toml"));
            return 0;
        }
        String url = dash.loginUrl();
        MutableComponent link = Component.literal(url).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open the admin dashboard"))));
        ctx.getSource().sendSuccess(() -> Component.literal("Admin dashboard (one-time login link, valid 10 minutes): ").append(link), false);
        ctx.getSource().sendSuccess(() -> Component.literal("Admin token is stored in " + dash.tokenFile()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int restart(CommandContext<CommandSourceStack> ctx) {
        TheyWillTalk mod = mod(ctx);
        if (mod == null) {
            return 0;
        }
        Thread.ofVirtual().start(() -> mod.runtime().start());
        ctx.getSource().sendSuccess(() -> Component.literal("Restarting the AI runtime..."), true);
        return 1;
    }
}
