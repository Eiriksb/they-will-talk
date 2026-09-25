package dev.eiriksb.theywilltalk.integration;

import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.villager.VillagerRegistry;
import net.neoforged.fml.ModList;

/**
 * Optional mod integrations. Each integration class is only loaded when its mod is present, so the mod runs fine
 * with any combination of MCA Reborn, MineColonies, BlueMap, Simple Voice Chat and Sipher.
 */
public final class Integrations {
    private final boolean mca = ModList.get().isLoaded("mca");
    private final boolean minecolonies = ModList.get().isLoaded("minecolonies");
    private final boolean bluemap = ModList.get().isLoaded("bluemap");
    private final boolean voicechat = ModList.get().isLoaded("voicechat");
    private final boolean sipher = ModList.get().isLoaded("sipher");
    private BlueMapMarkers blueMapMarkers;

    public void registerAdapters(VillagerRegistry registry) {
        // Most specific first: MCA villagers extend the vanilla Villager class.
        if (mca) {
            try {
                registry.addAdapter(new McaAdapter());
                TheyWillTalk.LOGGER.info("MCA Reborn detected: villagers use their MCA names, personalities, moods, hearts and family trees");
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("MCA Reborn integration failed to load (version mismatch?): {}", t.toString());
            }
        }
        if (minecolonies) {
            try {
                registry.addAdapter(new MineColoniesAdapter());
                TheyWillTalk.LOGGER.info("MineColonies detected: citizens can talk");
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("MineColonies integration failed to load (version mismatch?): {}", t.toString());
            }
        }
    }

    public void onServerStarted(TheyWillTalk mod) {
        if (mca) {
            McaAdapter.warnIfMcaChatAiEnabled();
        }
        if (!sipher) {
            TheyWillTalk.LOGGER.info("Sipher not installed: players can talk to villagers by typing in chat");
        }
        if (!voicechat) {
            TheyWillTalk.LOGGER.info("Simple Voice Chat not installed: villagers answer with subtitles only");
        }
        if (bluemap && TwtConfig.BLUEMAP_MARKERS.get()) {
            try {
                blueMapMarkers = new BlueMapMarkers(mod);
                blueMapMarkers.enable();
            } catch (Throwable t) {
                TheyWillTalk.LOGGER.warn("BlueMap integration failed: {}", t.toString());
            }
        }
    }

    public void tick(TheyWillTalk mod, long ticks) {
        if (blueMapMarkers != null && ticks % 200 == 0) {
            blueMapMarkers.refresh();
        }
    }

    public void onServerStopping() {
        if (blueMapMarkers != null) {
            blueMapMarkers.disable();
            blueMapMarkers = null;
        }
    }

    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("mca", mca);
        o.addProperty("minecolonies", minecolonies);
        o.addProperty("bluemap", bluemap);
        o.addProperty("voicechat", voicechat);
        o.addProperty("sipher", sipher);
        o.addProperty("sipherLines", sipher ? SipherBridge.linesHeard() : 0);
        return o;
    }

    public boolean hasBlueMap() {
        return bluemap;
    }

    /** Dimension -> BlueMap map id, empty until BlueMap is up. */
    public java.util.Map<String, String> blueMapMaps() {
        return blueMapMarkers == null ? java.util.Map.of() : blueMapMarkers.mapIds();
    }
}
