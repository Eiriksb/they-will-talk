package dev.eiriksb.theywilltalk.integration;

import dev.eiriksb.theywilltalk.TwtConfig;

import dev.eiriksb.theywilltalk.conversation.Profanity;

import java.util.ArrayList;

import dev.eiriksb.theywilltalk.faces.VillagerFaces;

import java.nio.ByteBuffer;

import java.util.UUID;

import java.util.LinkedHashMap;
import net.minecraft.server.level.ServerLevel;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import dev.eiriksb.theywilltalk.TheyWillTalk;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Shows every talking villager (and every village/colony) as a marker on BlueMap, with a popup of who they are and
 * the last thing they said. Markers are in-memory on BlueMap's side, so they are re-added on every (re)enable.
 */
final class BlueMapMarkers {
    private static final String VILLAGERS_SET = "theywilltalk-villagers";
    private static final String VILLAGES_SET = "theywilltalk-villages";
    private static final String VILLAGER_ICON = "theywilltalk/villager.svg";
    private static final String VILLAGE_ICON = "theywilltalk/village.svg";

    private final TheyWillTalk mod;
    private final Consumer<BlueMapAPI> onEnable = this::setup;
    private final Consumer<BlueMapAPI> onDisable = api -> this.api = null;
    private volatile BlueMapAPI api;
    private final Map<String, FaceIcon> faceIcons = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> iconUrls = new ConcurrentHashMap<>();

    BlueMapMarkers(TheyWillTalk mod) {
        this.mod = mod;
    }

    void enable() {
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    void disable() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        api = null;
    }

    /** BlueMap's map id for each dimension it maps (its first map when there are several), while BlueMap runs. */
    Map<String, String> mapIds() {
        BlueMapAPI a = api;
        Map<String, String> ids = new LinkedHashMap<>();
        if (a == null) {
            return ids;
        }
        for (ServerLevel level : mod.server().getAllLevels()) {
            String dim = level.dimension().location().toString();
            a.getWorld(level).or(() -> a.getWorld(dim))
                    .flatMap(w -> w.getMaps().stream().findFirst())
                    .ifPresent(m -> ids.put(dim, m.getId()));
        }
        return ids;
    }

    private void setup(BlueMapAPI api) {
        this.api = api;
        for (BlueMapMap map : api.getMaps()) {
            try {
                writeIcon(map, VILLAGER_ICON, VILLAGER_SVG);
                writeIcon(map, VILLAGE_ICON, VILLAGE_SVG);
            } catch (Exception e) {
                TheyWillTalk.LOGGER.debug("BlueMap icon write failed for {}: {}", map.getId(), e.toString());
            }
        }
        TheyWillTalk.LOGGER.info("BlueMap connected: talking villagers will appear on the map");
        refresh();
    }

    private void writeIcon(BlueMapMap map, String path, String svg) throws Exception {
        try (OutputStream out = map.getAssetStorage().writeAsset(path)) {
            out.write(svg.getBytes(StandardCharsets.UTF_8));
        }
        // Versioned by content: browsers that cached an older icon under the same path fetch the new one.
        iconUrls.put(map.getId() + "|" + path, map.getAssetStorage().getAssetUrl(path) + "?v=" + Integer.toHexString(svg.hashCode()));
    }

    private record FaceIcon(String url, int width, int height) {}

    /** The villager's own face (drawn from their skin) as a marker icon, written to the map's assets when it changes. */
    private FaceIcon faceIcon(BlueMapMap map, String uuid) {
        VillagerFaces faces = mod.faces();
        if (faces == null) {
            return null;
        }
        UUID id = UUID.fromString(uuid);
        String version = faces.version(id).orElse(null);
        if (version == null) {
            return null;
        }
        String key = map.getId() + "|" + uuid;
        FaceIcon icon = faceIcons.get(key);
        if (icon != null && icon.url().endsWith("?v=" + version)) {
            return icon;
        }
        byte[] png = faces.face(id).orElse(null);
        if (png == null || png.length < 24) {
            return null;
        }
        String path = "theywilltalk/faces/" + uuid + ".png";
        try (OutputStream out = map.getAssetStorage().writeAsset(path)) {
            out.write(png);
        } catch (Exception e) {
            TheyWillTalk.LOGGER.debug("BlueMap face write failed: {}", e.toString());
            return null;
        }
        ByteBuffer header = ByteBuffer.wrap(png, 16, 8); // PNG IHDR: width, height
        icon = new FaceIcon(map.getAssetStorage().getAssetUrl(path) + "?v=" + version, header.getInt(), header.getInt());
        faceIcons.put(key, icon);
        return icon;
    }

    private record Row(String uuid, String name, String kind, String job, String persona, String village, String dimension,
                       double x, double y, double z, String lastLine, int talks, String mood) {}

    private record VillageRow(String key, String name, String source, String dimension, int x, int y, int z, int population) {}

    void refresh() {
        BlueMapAPI a = api;
        if (a == null || mod.store() == null) {
            return;
        }
        mod.store().db.query("""
                        SELECT v.uuid, v.name, v.kind, v.job, v.persona, vl.name, v.dimension, v.x, v.y, v.z,
                          (SELECT m.text FROM messages m WHERE m.villager_uuid = v.uuid AND m.role='villager' ORDER BY m.ts DESC LIMIT 1),
                          v.talks, v.mood
                        FROM villagers v LEFT JOIN villages vl ON vl.key = v.village_key
                        WHERE v.alive = 1 AND v.dimension IS NOT NULL""",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getString(11), rs.getInt(12),
                        rs.getString(13))
        ).thenCombine(mod.store().db.query("SELECT key, name, source, dimension, x, y, z, population FROM villages",
                rs -> new VillageRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8))), (rows, villages) -> {
            apply(a, rows, villages);
            return null;
        }).exceptionally(t -> {
            TheyWillTalk.LOGGER.debug("BlueMap refresh failed: {}", t.toString());
            return null;
        });
    }

    private void apply(BlueMapAPI a, List<Row> rows, List<VillageRow> villages) {
        Set<String> dims = new HashSet<>();
        rows.forEach(r -> dims.add(r.dimension));
        villages.forEach(v -> dims.add(v.dimension));
        for (String dim : dims) {
            BlueMapWorld world = a.getWorld(dim).orElse(null);
            if (world == null) {
                continue;
            }
            for (BlueMapMap map : world.getMaps()) {
                MarkerSet villagerSet = map.getMarkerSets().computeIfAbsent(VILLAGERS_SET,
                        k -> MarkerSet.builder().label("Talking villagers").toggleable(true).defaultHidden(false).build());
                MarkerSet villageSet = map.getMarkerSets().computeIfAbsent(VILLAGES_SET,
                        k -> MarkerSet.builder().label("Villages & colonies").toggleable(true).defaultHidden(false).build());
                Set<String> seen = new HashSet<>();
                String villagerIcon = iconUrls.get(map.getId() + "|" + VILLAGER_ICON);
                for (Row r : rows) {
                    if (!r.dimension.equals(dim)) {
                        continue;
                    }
                    String id = "v-" + r.uuid;
                    seen.add(id);
                    FaceIcon face = faceIcon(map, r.uuid);
                    POIMarker.Builder b = POIMarker.builder()
                            .label(r.name)
                            .position(r.x, r.y + 1.8, r.z)
                            .detail(detail(r, face))
                            .maxDistance(900);
                    if (face != null) {
                        b.icon(face.url(), face.width() / 2, face.height() / 2);
                    } else if (villagerIcon != null) {
                        b.icon(villagerIcon, 12, 12);
                    }
                    villagerSet.put(id, b.build());
                }
                villagerSet.getMarkers().keySet().removeIf(k -> !seen.contains(k));

                String villageIcon = iconUrls.get(map.getId() + "|" + VILLAGE_ICON);
                for (VillageRow v : villages) {
                    if (!v.dimension.equals(dim)) {
                        continue;
                    }
                    POIMarker.Builder b = POIMarker.builder()
                            .label(v.name)
                            .position(v.x + 0.5, v.y + 3, v.z + 0.5)
                            .detail(CARD.formatted(200) + "<div style='font-size:15px;font-weight:700'>" + esc(v.name) + "</div>"
                                    + "<div style='opacity:.75'>" + esc(villageKind(v.source)) + " &middot; " + Math.max(0, v.population)
                                    + (v.population == 1 ? " resident" : " residents") + "</div></div>")
                            .maxDistance(5000);
                    if (villageIcon != null) {
                        b.icon(villageIcon, 16, 16);
                    }
                    villageSet.put("village-" + v.key, b.build());
                }
            }
        }
    }

    /**
     * Popup cards. BlueMap lays the popup out inside the tiny marker, so without a fixed width the text wraps after every
     * word; its own font is also large. The card sets both (BlueMap caps popups at 240 px).
     */
    private static final String CARD = "<div style='width:%dpx;font-size:13px;line-height:1.4;white-space:normal;text-align:left'>";

    private static String detail(Row r, FaceIcon face) {
        StringBuilder sb = new StringBuilder(CARD.formatted(240));
        sb.append("<div style='display:flex;gap:10px;align-items:center'>");
        if (face != null) {
            sb.append("<img src='").append(esc(face.url())).append("' alt='' style='width:40px;height:40px;object-fit:contain;")
                    .append("image-rendering:pixelated;flex:none'>");
        }
        sb.append("<div style='min-width:0'><div style='font-size:15px;font-weight:700;line-height:1.2'>").append(esc(r.name)).append("</div>");
        String job = r.job == null || r.job.isBlank() ? kindLabel(r.kind) : r.job;
        sb.append("<div style='opacity:.75'>").append(esc(capitalize(job)));
        if (r.persona != null) {
            sb.append(" &middot; ").append(esc(r.persona));
        }
        sb.append("</div></div></div>");
        List<String> facts = new ArrayList<>();
        if (r.village != null) {
            facts.add(esc(r.village));
        }
        if (r.mood != null && !r.mood.isBlank()) {
            facts.add(esc(r.mood));
        }
        facts.add(r.talks == 1 ? "1 conversation" : r.talks + " conversations");
        sb.append("<div style='margin-top:8px;font-size:12px;opacity:.7'>").append(String.join(" &middot; ", facts)).append("</div>");
        if (r.lastLine != null && !r.lastLine.isBlank()) {
            sb.append("<div style='margin-top:8px;padding-left:8px;border-left:2px solid rgba(255,255,255,.35);font-style:italic;")
                    .append("display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden'>&ldquo;")
                    .append(esc(Profanity.censor(r.lastLine, TwtConfig.BLEEP_SWEARING.get()))).append("&rdquo;</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "minecolonies" -> "Colonist";
            case "wandering_trader" -> "Wandering trader";
            default -> "Villager";
        };
    }

    private static String villageKind(String source) {
        return switch (source == null ? "" : source) {
            case "mca" -> "MCA village";
            case "minecolonies" -> "Colony";
            default -> "Village";
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    // Explicit width/height: BlueMap shows icons at their own size, and a size-less SVG collapses to 0x0 (invisible).
    private static final String VILLAGER_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 16 16" shape-rendering="crispEdges"><rect x="3" y="1" width="10" height="3" fill="#4a2f1b"/>
            <rect x="3" y="3" width="10" height="9" fill="#bd8b72"/><rect x="4" y="5" width="3" height="1" fill="#3b2414"/>
            <rect x="9" y="5" width="3" height="1" fill="#3b2414"/><rect x="5" y="6" width="1" height="1" fill="#2f8a3b"/>
            <rect x="10" y="6" width="1" height="1" fill="#2f8a3b"/><rect x="7" y="7" width="2" height="4" fill="#a86f58"/>
            <rect x="3" y="12" width="10" height="3" fill="#6b4a2b"/></svg>""";

    private static final String VILLAGE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 16 16"><path d="M8 1 L15 7 L13 7 L13 15 L3 15 L3 7 L1 7 Z" fill="#c8963e" stroke="#5a3d1a"/>
            <rect x="6.5" y="10" width="3" height="5" fill="#5a3d1a"/><circle cx="8" cy="6.5" r="1.5" fill="#f5d76e"/></svg>""";
}
