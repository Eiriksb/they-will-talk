package dev.eiriksb.theywilltalk;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/theywilltalk-client.toml: for players who have They Will Talk on their own computer. */
public final class TwtClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue LEND_GPU;
    public static final ModConfigSpec.IntValue LEND_SLOTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        LEND_GPU = b.comment("Lend your GPU to the servers you play on: villagers you talk to think on your computer (it runs the",
                        "language model installed in theywilltalk/runtime, as in singleplayer). Only your own conversations are sent.",
                        "Also /lendgpu on|off.")
                .define("lendGpu", false);
        LEND_SLOTS = b.comment("How many replies your GPU works on at once.")
                .defineInRange("lendSlots", 1, 1, 4);
        SPEC = b.build();
    }

    private TwtClientConfig() {}
}
