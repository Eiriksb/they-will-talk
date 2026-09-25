package dev.eiriksb.theywilltalk.integration;

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
        iconUrls.put(map.getId() + "|" + path, map.getAssetStorage().getAssetUrl(path));
    }

    private record Row(String uuid, String name, String kind, String job, String persona, String village, String dimension,
                       double x, double y, double z, String lastLine, int talks) {}

    private record VillageRow(String key, String name, String source, String dimension, int x, int y, int z, int population) {}

    void refresh() {
        BlueMapAPI a = api;
        if (a == null || mod.store() == null) {
            return;
        }
        mod.store().db.query("""
                        SELECT v.uuid, v.name, v.kind, v.job, v.persona, vl.name, v.dimension, v.x, v.y, v.z,
                          (SELECT m.text FROM messages m WHERE m.villager_uuid = v.uuid AND m.role='villager' ORDER BY m.ts DESC LIMIT 1),
                          v.talks
                        FROM villagers v LEFT JOIN villages vl ON vl.key = v.village_key
                        WHERE v.alive = 1 AND v.dimension IS NOT NULL""",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getString(11), rs.getInt(12))
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
                    POIMarker.Builder b = POIMarker.builder()
                            .label(r.name)
                            .position(r.x, r.y + 1.8, r.z)
                            .detail(detail(r))
                            .maxDistance(900);
                    if (villagerIcon != null) {
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
                            .detail("<div style='padding:4px'><b>" + esc(v.name) + "</b><br>" + esc(v.source) + " village &middot; "
                                    + Math.max(0, v.population) + " residents</div>")
                            .maxDistance(5000);
                    if (villageIcon != null) {
                        b.icon(villageIcon, 16, 16);
                    }
                    villageSet.put("village-" + v.key, b.build());
                }
            }
        }
    }

    private static String detail(Row r) {
        StringBuilder sb = new StringBuilder("<div style='max-width:240px;padding:4px;line-height:1.35'>");
        sb.append("<b>").append(esc(r.name)).append("</b><br>");
        sb.append("<small>").append(esc(r.job == null ? r.kind : r.job)).append(" &middot; ").append(esc(r.persona)).append("</small><br>");
        if (r.village != null) {
            sb.append("<small>").append(esc(r.village)).append("</small><br>");
        }
        sb.append("<small>").append(r.talks).append(" conversations</small>");
        if (r.lastLine != null) {
            sb.append("<div style='margin-top:4px;font-style:italic'>&ldquo;").append(esc(r.lastLine)).append("&rdquo;</div>");
        }
        return sb.append("</div>").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static final String VILLAGER_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><rect x="3" y="1" width="10" height="3" fill="#4a2f1b"/>
            <rect x="3" y="3" width="10" height="9" fill="#bd8b72"/><rect x="4" y="5" width="3" height="1" fill="#3b2414"/>
            <rect x="9" y="5" width="3" height="1" fill="#3b2414"/><rect x="5" y="6" width="1" height="1" fill="#2f8a3b"/>
            <rect x="10" y="6" width="1" height="1" fill="#2f8a3b"/><rect x="7" y="7" width="2" height="4" fill="#a86f58"/>
            <rect x="3" y="12" width="10" height="3" fill="#6b4a2b"/></svg>""";

    private static final String VILLAGE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><path d="M8 1 L15 7 L13 7 L13 15 L3 15 L3 7 L1 7 Z" fill="#c8963e" stroke="#5a3d1a"/>
            <rect x="6.5" y="10" width="3" height="5" fill="#5a3d1a"/><circle cx="8" cy="6.5" r="1.5" fill="#f5d76e"/></svg>""";
}
