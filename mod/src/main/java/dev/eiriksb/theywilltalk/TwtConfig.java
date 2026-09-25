package dev.eiriksb.theywilltalk;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/theywilltalk-common.toml */
public final class TwtConfig {
    public static final ModConfigSpec SPEC;

    // runtime
    public static final ModConfigSpec.BooleanValue AUTO_START;
    public static final ModConfigSpec.ConfigValue<String> RUNTIME_DIR;
    public static final ModConfigSpec.ConfigValue<String> LLM_MODEL;
    public static final ModConfigSpec.IntValue GPU_LAYERS;
    public static final ModConfigSpec.IntValue CONTEXT_SIZE;
    public static final ModConfigSpec.IntValue PARALLEL_SLOTS;
    public static final ModConfigSpec.ConfigValue<String> TTS_ENGINE;
    public static final ModConfigSpec.ConfigValue<String> QWEN_TTS_MODEL;
    public static final ModConfigSpec.IntValue TTS_THREADS;
    public static final ModConfigSpec.IntValue PROCESS_NICE;
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_LLM_URL;

    // conversation
    public static final ModConfigSpec.DoubleValue LISTEN_RADIUS;
    public static final ModConfigSpec.BooleanValue TEXT_CHAT;
    public static final ModConfigSpec.BooleanValue REQUIRE_LOOK_OR_NAME;
    public static final ModConfigSpec.IntValue ACTIVE_CONVERSATION_SECONDS;
    public static final ModConfigSpec.IntValue MAX_REPLY_WORDS;
    public static final ModConfigSpec.BooleanValue SUBTITLES;
    public static final ModConfigSpec.DoubleValue VOICE_DISTANCE;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    public static final ModConfigSpec.IntValue HISTORY_TURNS;
    public static final ModConfigSpec.BooleanValue IGNORE_GROUP_SPEECH;
    public static final ModConfigSpec.IntValue MAX_REPLIES_PER_MINUTE;

    // villagers
    public static final ModConfigSpec.BooleanValue VANILLA_VILLAGERS;
    public static final ModConfigSpec.BooleanValue WANDERING_TRADERS;
    public static final ModConfigSpec.BooleanValue MCA_VILLAGERS;
    public static final ModConfigSpec.BooleanValue MINECOLONIES_CITIZENS;
    public static final ModConfigSpec.BooleanValue NAME_VANILLA_VILLAGERS;
    public static final ModConfigSpec.BooleanValue VILLAGER_SOUNDS;
    public static final ModConfigSpec.BooleanValue AMBIENT_CHATTER;
    public static final ModConfigSpec.IntValue AMBIENT_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue GREETINGS;

    // dashboard
    public static final ModConfigSpec.BooleanValue DASHBOARD;
    public static final ModConfigSpec.ConfigValue<String> DASHBOARD_BIND;
    public static final ModConfigSpec.IntValue DASHBOARD_PORT;
    public static final ModConfigSpec.ConfigValue<String> BLUEMAP_URL;
    public static final ModConfigSpec.BooleanValue BLUEMAP_MARKERS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("The bundled AI runtime (llama.cpp LLM server + text-to-speech voice server).").push("runtime");
        AUTO_START = b.comment("Start the AI processes automatically when the server starts.")
                .define("autoStart", true);
        RUNTIME_DIR = b.comment("Folder holding the bundled runtime. Empty = <server>/theywilltalk/runtime")
                .define("runtimeDir", "");
        LLM_MODEL = b.comment("GGUF file name inside runtime/models/llm, or 'auto' for the first one found.",
                        "Bundled: gemma-4-E2B (fast, ~1.6 GB VRAM). Drop gemma-4-E4B in for richer replies (~3 GB VRAM).")
                .define("llmModel", "auto");
        GPU_LAYERS = b.comment("Model layers to put on the GPU. 99 = everything, 0 = CPU only (slow).")
                .defineInRange("gpuLayers", 99, 0, 999);
        CONTEXT_SIZE = b.comment("Total LLM context shared by all parallel conversations (tokens).")
                .defineInRange("contextSize", 8192, 2048, 131072);
        PARALLEL_SLOTS = b.comment("How many villagers can think at the same time.")
                .defineInRange("parallelSlots", 2, 1, 8);
        TTS_ENGINE = b.comment("Voice engine:",
                        "  'auto'       - Qwen3-TTS when it is bundled, otherwise Kokoro",
                        "  'qwen3'      - Qwen3-TTS on the GPU: every villager gets a designed voice and really acts out emotions",
                        "  'kokoro'     - 54 voices on the CPU, pitch/speed mood shaping (lightest on the GPU)",
                        "  'supertonic' - 10 voices on the CPU, the lightest option overall")
                .define("ttsEngine", "auto");
        QWEN_TTS_MODEL = b.comment("Qwen3-TTS model file in runtime/models/qwentts, or 'auto' (prefers the VoiceDesign model).")
                .define("qwenTtsModel", "auto");
        TTS_THREADS = b.comment("CPU threads the voice engine may use.")
                .defineInRange("ttsThreads", 4, 1, 32);
        PROCESS_NICE = b.comment("Linux only: 'nice' value for the AI processes so the Minecraft server thread always wins (0-19).")
                .defineInRange("processNice", 5, 0, 19);
        EXTERNAL_LLM_URL = b.comment("Advanced: use an already running OpenAI-compatible server instead of the bundled one",
                        "(e.g. http://127.0.0.1:8080). Empty = use the bundled llama-server.")
                .define("externalLlmUrl", "");
        b.pop();

        b.comment("How players talk to villagers.").push("conversation");
        LISTEN_RADIUS = b.comment("How close (blocks) a player must be for a villager to hear them.")
                .defineInRange("listenRadius", 8.0, 2.0, 32.0);
        TEXT_CHAT = b.comment("Villagers also answer typed chat messages.")
                .define("textChat", true);
        REQUIRE_LOOK_OR_NAME = b.comment("A villager only answers when you look at it or say its name",
                        "(so players can keep talking to each other near villagers).")
                .define("requireLookOrName", true);
        ACTIVE_CONVERSATION_SECONDS = b.comment("After talking, you can keep chatting with the same villager for this long without",
                        "saying its name - as long as you're still roughly facing it.")
                .defineInRange("activeConversationSeconds", 20, 5, 600);
        MAX_REPLY_WORDS = b.comment("Soft limit for how long a single villager reply is.")
                .defineInRange("maxReplyWords", 30, 10, 200);
        SUBTITLES = b.comment("Show what villagers say in chat.")
                .define("subtitles", true);
        VOICE_DISTANCE = b.comment("How far (blocks) a villager's voice carries.")
                .defineInRange("voiceDistance", 24.0, 4.0, 128.0);
        TEMPERATURE = b.comment("LLM creativity (0.2 = predictable, 1.2 = chaotic).")
                .defineInRange("temperature", 0.85, 0.0, 2.0);
        HISTORY_TURNS = b.comment("How many previous lines of the current conversation the villager remembers verbatim.")
                .defineInRange("historyTurns", 10, 0, 50);
        IGNORE_GROUP_SPEECH = b.comment("Ignore speech from players who are in a Simple Voice Chat group.")
                .define("ignoreGroupSpeech", true);
        MAX_REPLIES_PER_MINUTE = b.comment("A villager answers the same player at most this often (stops runaway loops).")
                .defineInRange("maxRepliesPerMinute", 10, 1, 60);
        b.pop();

        b.comment("Which villagers can talk.").push("villagers");
        VANILLA_VILLAGERS = b.define("vanillaVillagers", true);
        WANDERING_TRADERS = b.define("wanderingTraders", true);
        MCA_VILLAGERS = b.comment("MCA Reborn villagers (requires MCA).").define("mcaVillagers", true);
        MINECOLONIES_CITIZENS = b.comment("MineColonies citizens (requires MineColonies).").define("minecoloniesCitizens", true);
        NAME_VANILLA_VILLAGERS = b.comment("Give vanilla villagers their generated name as a (hover) name tag.")
                .define("nameVanillaVillagers", true);
        VILLAGER_SOUNDS = b.comment("Vanilla villagers 'hmm', 'yes' and 'no' while they think and react.")
                .define("villagerSounds", true);
        AMBIENT_CHATTER = b.comment("Villagers near players occasionally chat with each other.")
                .define("ambientChatter", true);
        AMBIENT_INTERVAL_SECONDS = b.comment("Minimum seconds between ambient chats in the same area.")
                .defineInRange("ambientIntervalSeconds", 300, 20, 3600);
        GREETINGS = b.comment("Villagers who know you well greet you when you walk up to them.")
                .define("greetings", true);
        b.pop();

        b.comment("Admin dashboard (web).").push("dashboard");
        DASHBOARD = b.define("enabled", true);
        DASHBOARD_BIND = b.comment("Address to bind. 127.0.0.1 = only reachable from the server machine itself.",
                        "Use 0.0.0.0 to reach it from other machines (protected by the admin token).")
                .define("bindAddress", "127.0.0.1");
        DASHBOARD_PORT = b.defineInRange("port", 8765, 1, 65535);
        BLUEMAP_URL = b.comment("Public URL of the BlueMap web app, used to embed the 3D map in the dashboard.")
                .define("bluemapUrl", "http://localhost:8100");
        BLUEMAP_MARKERS = b.comment("Show talking villagers and villages as BlueMap markers.")
                .define("bluemapMarkers", true);
        b.pop();

        SPEC = b.build();
    }

    private TwtConfig() {}
}
