package dev.eiriksb.theywilltalk.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.TwtConfig;
import dev.eiriksb.theywilltalk.ai.TtsClient;
import dev.eiriksb.theywilltalk.audio.AudioDsp;
import dev.eiriksb.theywilltalk.audio.Prosody;
import dev.eiriksb.theywilltalk.conversation.ConversationManager;
import dev.eiriksb.theywilltalk.data.Database;
import dev.eiriksb.theywilltalk.runtime.GpuInfo;
import dev.eiriksb.theywilltalk.runtime.ManagedProcess;
import dev.eiriksb.theywilltalk.runtime.RuntimeManager;
import dev.eiriksb.theywilltalk.villager.Persona;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** JSON endpoints under /api. */
final class Api {
    private final DashboardServer dash;

    Api(DashboardServer dash) {
        this.dash = dash;
    }

    private TheyWillTalk mod() {
        return dash.mod;
    }

    private Database db() {
        return mod().store().db;
    }

    void handle(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        Map<String, String> q = DashboardServer.query(ex.getRequestURI());
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        String head = parts[0];
        switch (head) {
            case "overview" -> DashboardServer.sendJson(ex, 200, overview());
            case "villagers" -> DashboardServer.sendJson(ex, 200, villagers(q));
            case "villager" -> villager(ex, method, parts);
            case "villages" -> DashboardServer.sendJson(ex, 200, villages());
            case "village" -> DashboardServer.sendJson(ex, 200, village(parts.length > 1 ? parts[1] : ""));
            case "players" -> DashboardServer.sendJson(ex, 200, players());
            case "player" -> DashboardServer.sendJson(ex, 200, player(parts.length > 1 ? parts[1] : ""));
            case "conversations" -> DashboardServer.sendJson(ex, 200, searchMessages(q));
            case "conversation" -> DashboardServer.sendJson(ex, 200, conversation(Long.parseLong(parts[1])));
            case "events" -> DashboardServer.sendJson(ex, 200, events(q));
            case "map" -> DashboardServer.sendJson(ex, 200, map());
            case "runtime" -> {
                if (parts.length > 1 && parts[1].equals("restart") && method.equals("POST")) {
                    Thread.ofVirtual().start(() -> mod().runtime().start());
                    DashboardServer.sendJson(ex, 200, ok());
                } else {
                    DashboardServer.sendJson(ex, 200, runtime());
                }
            }
            case "voices" -> {
                if (parts.length > 1 && parts[1].equals("preview")) {
                    voicePreview(ex);
                } else {
                    DashboardServer.sendJson(ex, 200, TtsClient.toJson(mod().tts().voices()));
                }
            }
            case "personas" -> DashboardServer.sendJson(ex, 200, personas());
            case "stream" -> stream(ex);
            default -> DashboardServer.sendJson(ex, 404, DashboardServer.error("unknown endpoint " + path));
        }
    }

    // ---- generic SQL -> JSON ----------------------------------------------------------------------------------

    private JsonArray rows(String sql, Object... params) throws Exception {
        return db().call(c -> rowsNow(c, sql, params)).get(10, TimeUnit.SECONDS);
    }

    private JsonObject row(String sql, Object... params) throws Exception {
        JsonArray a = rows(sql, params);
        return a.isEmpty() ? null : a.get(0).getAsJsonObject();
    }

    static JsonArray rowsNow(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                ps.setObject(i + 1, p instanceof UUID u ? u.toString() : p);
            }
            JsonArray out = new JsonArray();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String name = md.getColumnLabel(i);
                        Object v = rs.getObject(i);
                        if (v == null) {
                            o.add(name, null);
                        } else if (v instanceof Number n) {
                            o.addProperty(name, md.getColumnType(i) == Types.REAL || md.getColumnType(i) == Types.DOUBLE || md.getColumnType(i) == Types.FLOAT
                                    ? n.doubleValue() : n.longValue());
                        } else {
                            String s = v.toString();
                            if (name.endsWith("_json") && s.startsWith("{")) {
                                o.add(name, JsonParser.parseString(s));
                            } else {
                                o.addProperty(name, s);
                            }
                        }
                    }
                    out.add(o);
                }
            }
            return out;
        }
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    // ---- overview ---------------------------------------------------------------------------------------------

    private JsonObject overview() throws Exception {
        JsonObject o = new JsonObject();
        long dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L;
        o.add("counts", row("""
                SELECT (SELECT COUNT(*) FROM villagers WHERE alive=1) AS villagers,
                       (SELECT COUNT(*) FROM villagers WHERE alive=0) AS deceased,
                       (SELECT COUNT(*) FROM villages) AS villages,
                       (SELECT COUNT(*) FROM players) AS players,
                       (SELECT COUNT(*) FROM conversations) AS conversations,
                       (SELECT COUNT(*) FROM messages WHERE role='villager') AS replies,
                       (SELECT COUNT(*) FROM messages WHERE role='villager' AND ts > ?) AS repliesToday,
                       (SELECT COUNT(*) FROM memories) AS memories,
                       (SELECT CAST(AVG(latency_ms) AS INTEGER) FROM (SELECT latency_ms FROM messages WHERE role='villager' AND latency_ms > 0 ORDER BY ts DESC LIMIT 50)) AS avgLatencyMs
                """, dayAgo));
        o.add("byKind", rows("SELECT kind, COUNT(*) AS n FROM villagers WHERE alive=1 GROUP BY kind ORDER BY n DESC"));
        o.add("topTalkers", rows("""
                SELECT v.uuid, v.name, v.kind, v.job, v.talks, vl.name AS village FROM villagers v
                LEFT JOIN villages vl ON vl.key = v.village_key WHERE v.talks > 0 ORDER BY v.talks DESC LIMIT 6"""));
        o.add("activityByHour", rows("""
                SELECT CAST((ts / 3600000) AS INTEGER) AS hour, COUNT(*) AS n FROM messages
                WHERE role='villager' AND ts > ? GROUP BY hour ORDER BY hour""", dayAgo));
        JsonArray feed = new JsonArray();
        mod().feed().recent().forEach(feed::add);
        o.add("feed", feed);
        o.add("runtime", runtimeSummary());
        o.add("integrations", mod().integrations().status());
        return o;
    }

    private JsonObject runtimeSummary() {
        RuntimeManager rt = mod().runtime();
        JsonObject o = new JsonObject();
        o.addProperty("llmReady", rt.llmReady());
        o.addProperty("voiceReady", rt.voiceReady());
        o.addProperty("model", rt.llmModelName());
        o.addProperty("voices", mod().tts().voices().size());
        o.addProperty("expressiveVoices", mod().speech().expressive());
        o.addProperty("qwenTtsReady", rt.qwenTtsReady());
        o.addProperty("qwenTtsModel", rt.qwenTtsModelName());
        o.addProperty("tokensPerSecond", Math.round(mod().llm().lastTokensPerSecond));
        o.addProperty("firstTokenMs", mod().llm().lastFirstTokenMs);
        ConversationManager cm = mod().conversationManager();
        o.addProperty("lastLatencyMs", cm == null ? 0 : cm.lastLatencyMs);
        o.addProperty("activeConversations", cm == null ? 0 : cm.activeConversations());
        o.addProperty("queued", mod().llm().queued());
        JsonArray gpus = new JsonArray();
        for (GpuInfo.Gpu g : GpuInfo.query()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", g.name());
            j.addProperty("memoryUsedMb", g.memoryUsedMb());
            j.addProperty("memoryTotalMb", g.memoryTotalMb());
            j.addProperty("utilization", g.utilization());
            j.addProperty("temperature", g.temperature());
            j.addProperty("powerW", g.powerW());
            gpus.add(j);
        }
        o.add("gpus", gpus);
        JsonArray problems = new JsonArray();
        rt.problems().forEach(problems::add);
        o.add("problems", problems);
        return o;
    }

    // ---- villagers --------------------------------------------------------------------------------------------

    private JsonObject villagers(Map<String, String> q) throws Exception {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        String search = q.getOrDefault("q", "").trim();
        if (!search.isEmpty()) {
            where.append(" AND (v.name LIKE ? OR v.job LIKE ? OR v.persona LIKE ? OR vl.name LIKE ?)");
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (!q.getOrDefault("village", "").isEmpty()) {
            where.append(" AND v.village_key = ?");
            params.add(q.get("village"));
        }
        if (!q.getOrDefault("kind", "").isEmpty()) {
            where.append(" AND v.kind = ?");
            params.add(q.get("kind"));
        }
        String alive = q.getOrDefault("alive", "1");
        if (!alive.equals("all")) {
            where.append(" AND v.alive = ?");
            params.add(Integer.parseInt(alive));
        }
        String sort = switch (q.getOrDefault("sort", "talks")) {
            case "name" -> "v.name COLLATE NOCASE ASC";
            case "village" -> "vl.name COLLATE NOCASE ASC, v.name";
            case "recent" -> "v.last_spoke DESC NULLS LAST";
            case "job" -> "v.job, v.name";
            default -> "v.talks DESC, v.name";
        };
        int limit = Math.min(500, Integer.parseInt(q.getOrDefault("limit", "200")));
        int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
        JsonObject o = new JsonObject();
        o.add("total", row("SELECT COUNT(*) AS n FROM villagers v LEFT JOIN villages vl ON vl.key = v.village_key" + where, params.toArray())
                .get("n"));
        params.add(limit);
        params.add(offset);
        o.add("items", rows("""
                SELECT v.uuid, v.name, v.kind, v.gender, v.persona, v.job, v.job_level, v.age_group, v.voice, v.alive, v.talks,
                       v.last_spoke, v.last_seen, v.mood, v.village_key, vl.name AS village, v.dimension, v.x, v.y, v.z,
                       (SELECT COUNT(*) FROM relationships r WHERE r.villager_uuid = v.uuid) AS knownPlayers
                FROM villagers v LEFT JOIN villages vl ON vl.key = v.village_key""" + where + " ORDER BY " + sort + " LIMIT ? OFFSET ?",
                params.toArray()));
        return o;
    }

    private void villager(HttpExchange ex, String method, String[] parts) throws Exception {
        if (parts.length < 2) {
            DashboardServer.sendJson(ex, 400, DashboardServer.error("missing uuid"));
            return;
        }
        UUID id = UUID.fromString(parts[1]);
        String sub = parts.length > 2 ? parts[2] : "";
        switch (sub) {
            case "" -> {
                if (method.equals("POST")) {
                    DashboardServer.sendJson(ex, 200, editVillager(id, DashboardServer.readJson(ex)));
                } else {
                    DashboardServer.sendJson(ex, 200, villagerDetail(id));
                }
            }
            case "conversations" -> DashboardServer.sendJson(ex, 200, villagerConversations(id));
            case "family" -> DashboardServer.sendJson(ex, 200, family(id));
            case "say" -> DashboardServer.sendJson(ex, 200, askVillager(id, DashboardServer.readJson(ex)));
            case "forget" -> {
                db().exec("DELETE FROM memories WHERE villager_uuid=?", id);
                db().exec("DELETE FROM relationships WHERE villager_uuid=?", id);
                if (mod().conversationManager() != null) {
                    mod().conversationManager().forgetVillager(id);
                }
                DashboardServer.sendJson(ex, 200, ok());
            }
            default -> DashboardServer.sendJson(ex, 404, DashboardServer.error("unknown"));
        }
    }

    private JsonObject villagerDetail(UUID id) throws Exception {
        JsonObject v = row("""
                SELECT v.*, vl.name AS village, vl.source AS village_source FROM villagers v
                LEFT JOIN villages vl ON vl.key = v.village_key WHERE v.uuid = ?""", id);
        if (v == null) {
            return DashboardServer.error("not found");
        }
        v.add("relationships", rows("""
                SELECT r.player_uuid, p.name AS player, r.affinity, r.talks, r.first_met, r.last_talk, r.hearts, r.reputation, r.relation
                FROM relationships r LEFT JOIN players p ON p.uuid = r.player_uuid WHERE r.villager_uuid = ? ORDER BY r.last_talk DESC""", id));
        v.add("memories", rows("""
                SELECT m.id, m.created, m.kind, m.text, m.player_uuid, p.name AS player FROM memories m
                LEFT JOIN players p ON p.uuid = m.player_uuid WHERE m.villager_uuid = ? ORDER BY m.created DESC LIMIT 100""", id));
        v.add("familyLinks", rows("SELECT relative_uuid, relation, relative_name, relative_is_player, deceased FROM family WHERE villager_uuid=?", id));
        v.add("events", rows("SELECT ts, type, text FROM events WHERE villager_uuid=? ORDER BY ts DESC LIMIT 30", id));
        v.addProperty("loaded", onServer(() -> mod().findEntity(id) != null).get(2, TimeUnit.SECONDS));
        VillagerProfile prof = mod().villagers().cached(id);
        if (prof != null) {
            dev.eiriksb.theywilltalk.villager.VillagerFacts f = new dev.eiriksb.theywilltalk.villager.VillagerFacts();
            f.kind = prof.kind();
            f.job = v.has("job") && !v.get("job").isJsonNull() ? v.get("job").getAsString() : null;
            f.ageGroup = v.has("age_group") && !v.get("age_group").isJsonNull() ? v.get("age_group").getAsString() : "adult";
            v.addProperty("generatedVoiceDesign", dev.eiriksb.theywilltalk.villager.VoiceDesign.base(prof, f));
        }
        v.addProperty("expressiveVoices", mod().speech().expressive());
        return v;
    }

    private JsonObject villagerConversations(UUID id) throws Exception {
        JsonObject o = new JsonObject();
        JsonArray convs = rows("""
                SELECT c.id, c.player_uuid, p.name AS player, c.started, c.ended, c.channel, c.turns FROM conversations c
                LEFT JOIN players p ON p.uuid = c.player_uuid WHERE c.villager_uuid = ? ORDER BY c.started DESC LIMIT 60""", id);
        for (var e : convs) {
            JsonObject c = e.getAsJsonObject();
            c.add("messages", rows("SELECT ts, role, speaker, text, original_text, lang, emotion, latency_ms FROM messages WHERE conversation_id=? ORDER BY ts",
                    c.get("id").getAsLong()));
        }
        o.add("conversations", convs);
        return o;
    }

    private JsonObject editVillager(UUID id, JsonObject body) throws Exception {
        VillagerProfile p = mod().villagers().cached(id);
        if (p == null) {
            p = mod().store().loadProfileNow(id).orElse(null);
        }
        if (p == null) {
            return DashboardServer.error("not found");
        }
        String name = str(body, "name", p.name());
        VillagerProfile updated = p.withName(name)
                .withPersona(str(body, "persona", p.persona()))
                .withStory(str(body, "quirk", p.quirk()), str(body, "backstory", p.backstory()), str(body, "customPrompt", p.customPrompt()))
                .withVoice(str(body, "voice", p.voice()),
                        body.has("pitch") ? clamp(body.get("pitch").getAsDouble(), 0.7, 1.5) : p.pitch(),
                        body.has("speed") ? clamp(body.get("speed").getAsDouble(), 0.6, 1.6) : p.speed());
        VillagerProfile withDesign = body.has("voiceDesign") ? updated.withVoiceDesign(str(body, "voiceDesign", "").trim()) : updated;
        mod().villagers().update(withDesign);
        if (!name.equals(p.name())) {
            onServer(() -> {
                Entity e = mod().findEntity(id);
                if (e != null && updated.kind() != dev.eiriksb.theywilltalk.villager.VillagerKind.MCA
                        && updated.kind() != dev.eiriksb.theywilltalk.villager.VillagerKind.MINECOLONIES) {
                    e.setCustomName(Component.literal(name));
                }
                return null;
            });
        }
        return ok();
    }

    private JsonObject askVillager(UUID id, JsonObject body) throws Exception {
        String text = str(body, "text", "").trim();
        if (text.isEmpty()) {
            return DashboardServer.error("empty message");
        }
        boolean found = onServer(() -> {
            Entity e = mod().findEntity(id);
            if (e == null || mod().conversationManager() == null) {
                return false;
            }
            ServerPlayer nearest = null;
            double best = 32 * 32;
            for (ServerPlayer p : mod().server().getPlayerList().getPlayers()) {
                if (p.level() == e.level() && p.distanceToSqr(e) < best) {
                    best = p.distanceToSqr(e);
                    nearest = p;
                }
            }
            mod().conversationManager().talk(e, nearest, text, ConversationManager.Channel.DASHBOARD, null, "en");
            return true;
        }).get(3, TimeUnit.SECONDS);
        return found ? ok() : DashboardServer.error("That villager isn't loaded right now (nobody is near them).");
    }

    /** Family graph around a villager, up to 3 generations each way (MCA family trees, MineColonies families). */
    private JsonObject family(UUID root) throws Exception {
        return db().call(c -> {
            Map<String, JsonObject> nodes = new HashMap<>();
            JsonArray edges = new JsonArray();
            Set<String> seenEdges = new HashSet<>();
            Deque<String[]> frontier = new ArrayDeque<>();
            frontier.add(new String[]{root.toString(), "0"});
            Set<String> visited = new HashSet<>();
            while (!frontier.isEmpty() && nodes.size() < 80) {
                String[] cur = frontier.poll();
                String id = cur[0];
                int depth = Integer.parseInt(cur[1]);
                if (!visited.add(id)) {
                    continue;
                }
                nodes.computeIfAbsent(id, k -> node(c, k, null, false, false));
                if (depth >= 3) {
                    continue;
                }
                for (var e : rowsNow(c, "SELECT relative_uuid, relation, relative_name, relative_is_player, deceased FROM family WHERE villager_uuid=?", id)) {
                    JsonObject l = e.getAsJsonObject();
                    String rel = l.get("relative_uuid").getAsString();
                    nodes.computeIfAbsent(rel, k -> node(c, k, l.get("relative_name").getAsString(), l.get("relative_is_player").getAsLong() == 1,
                            l.get("deceased").getAsLong() == 1));
                    addEdge(edges, seenEdges, id, rel, l.get("relation").getAsString());
                    if (!rel.startsWith("name:")) {
                        frontier.add(new String[]{rel, Integer.toString(depth + 1)});
                    }
                }
                // Reverse links: someone lists this villager as their relative.
                for (var e : rowsNow(c, "SELECT villager_uuid, relation FROM family WHERE relative_uuid=?", id)) {
                    JsonObject l = e.getAsJsonObject();
                    String other = l.get("villager_uuid").getAsString();
                    nodes.computeIfAbsent(other, k -> node(c, k, null, false, false));
                    addEdge(edges, seenEdges, other, id, l.get("relation").getAsString());
                    frontier.add(new String[]{other, Integer.toString(depth + 1)});
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("root", root.toString());
            JsonArray n = new JsonArray();
            nodes.values().forEach(n::add);
            o.add("nodes", n);
            o.add("edges", edges);
            return o;
        }).get(10, TimeUnit.SECONDS);
    }

    private static void addEdge(JsonArray edges, Set<String> seen, String from, String to, String relation) {
        if (!seen.add(from + ">" + to + ">" + relation)) {
            return;
        }
        JsonObject e = new JsonObject();
        e.addProperty("from", from);
        e.addProperty("to", to);
        e.addProperty("relation", relation);
        edges.add(e);
    }

    private static JsonObject node(Connection c, String id, String fallbackName, boolean player, boolean deceased) {
        JsonObject n = new JsonObject();
        n.addProperty("id", id);
        try {
            JsonArray r = rowsNow(c, "SELECT name, kind, job, alive, gender FROM villagers WHERE uuid=?", id);
            if (r.isEmpty()) {
                JsonArray p = rowsNow(c, "SELECT name FROM players WHERE uuid=?", id);
                n.addProperty("name", !p.isEmpty() ? p.get(0).getAsJsonObject().get("name").getAsString()
                        : fallbackName == null ? "Unknown" : fallbackName);
                n.addProperty("player", player || !p.isEmpty());
                n.addProperty("known", false);
                n.addProperty("alive", !deceased);
            } else {
                JsonObject v = r.get(0).getAsJsonObject();
                n.add("name", v.get("name"));
                n.add("kind", v.get("kind"));
                n.add("job", v.get("job"));
                n.add("gender", v.get("gender"));
                n.addProperty("alive", v.get("alive").getAsLong() == 1 && !deceased);
                n.addProperty("player", false);
                n.addProperty("known", true);
            }
        } catch (SQLException e) {
            n.addProperty("name", fallbackName == null ? "?" : fallbackName);
        }
        return n;
    }

    // ---- villages, players, conversations ---------------------------------------------------------------------

    private JsonArray villages() throws Exception {
        return rows("""
                SELECT vl.*, (SELECT COUNT(*) FROM villagers v WHERE v.village_key = vl.key AND v.alive=1) AS known,
                       (SELECT COALESCE(SUM(v.talks),0) FROM villagers v WHERE v.village_key = vl.key) AS talks
                FROM villages vl ORDER BY talks DESC, known DESC""");
    }

    private JsonObject village(String key) throws Exception {
        JsonObject v = row("SELECT * FROM villages WHERE key=?", key);
        if (v == null) {
            return DashboardServer.error("not found");
        }
        v.add("residents", rows("""
                SELECT uuid, name, kind, job, persona, alive, talks, age_group, gender, mood FROM villagers
                WHERE village_key=? ORDER BY alive DESC, talks DESC, name""", key));
        v.add("gossip", rows("""
                SELECT m.created, m.text, v.name AS villager, p.name AS player FROM memories m
                JOIN villagers v ON v.uuid = m.villager_uuid LEFT JOIN players p ON p.uuid = m.player_uuid
                WHERE v.village_key = ? ORDER BY m.created DESC LIMIT 40""", key));
        return v;
    }

    private JsonArray players() throws Exception {
        JsonArray players = rows("""
                SELECT p.uuid, p.name, p.first_seen, p.last_seen,
                  (SELECT COUNT(*) FROM relationships r WHERE r.player_uuid = p.uuid) AS villagersKnown,
                  (SELECT COALESCE(SUM(r.talks),0) FROM relationships r WHERE r.player_uuid = p.uuid) AS talks,
                  (SELECT CAST(AVG(r.affinity) AS INTEGER) FROM relationships r WHERE r.player_uuid = p.uuid) AS avgAffinity
                FROM players p ORDER BY p.last_seen DESC""");
        Set<String> online = onServer(() -> {
            Set<String> s = new HashSet<>();
            mod().server().getPlayerList().getPlayers().forEach(p -> s.add(p.getUUID().toString()));
            return s;
        }).get(2, TimeUnit.SECONDS);
        for (var e : players) {
            JsonObject p = e.getAsJsonObject();
            p.addProperty("online", online.contains(p.get("uuid").getAsString()));
        }
        return players;
    }

    private JsonObject player(String uuid) throws Exception {
        JsonObject p = row("SELECT * FROM players WHERE uuid=?", uuid);
        if (p == null) {
            return DashboardServer.error("not found");
        }
        p.add("relationships", rows("""
                SELECT r.villager_uuid, v.name AS villager, v.kind, v.job, vl.name AS village, r.affinity, r.talks, r.hearts,
                       r.relation, r.last_talk, v.alive
                FROM relationships r JOIN villagers v ON v.uuid = r.villager_uuid LEFT JOIN villages vl ON vl.key = v.village_key
                WHERE r.player_uuid=? ORDER BY r.affinity DESC""", uuid));
        p.add("memories", rows("""
                SELECT m.created, m.kind, m.text, v.name AS villager, m.villager_uuid FROM memories m
                JOIN villagers v ON v.uuid = m.villager_uuid WHERE m.player_uuid=? ORDER BY m.created DESC LIMIT 60""", uuid));
        return p;
    }

    private JsonObject searchMessages(Map<String, String> q) throws Exception {
        String search = q.getOrDefault("q", "").trim();
        int limit = Math.min(300, Integer.parseInt(q.getOrDefault("limit", "100")));
        JsonObject o = new JsonObject();
        if (search.isEmpty()) {
            o.add("items", rows("""
                    SELECT c.id, c.villager_uuid, v.name AS villager, c.player_uuid, p.name AS player, c.started, c.ended, c.turns, c.channel,
                      (SELECT text FROM messages m WHERE m.conversation_id = c.id ORDER BY ts DESC LIMIT 1) AS lastText
                    FROM conversations c LEFT JOIN villagers v ON v.uuid = c.villager_uuid LEFT JOIN players p ON p.uuid = c.player_uuid
                    WHERE c.turns > 0 ORDER BY c.ended DESC LIMIT ?""", limit));
            o.addProperty("mode", "recent");
        } else {
            o.add("items", rows("""
                    SELECT m.conversation_id AS id, m.ts, m.role, m.speaker, m.text, m.emotion, v.name AS villager, m.villager_uuid,
                           p.name AS player FROM messages m LEFT JOIN villagers v ON v.uuid = m.villager_uuid
                    LEFT JOIN players p ON p.uuid = m.player_uuid WHERE m.text LIKE ? ORDER BY m.ts DESC LIMIT ?""",
                    "%" + search + "%", limit));
            o.addProperty("mode", "search");
        }
        return o;
    }

    private JsonObject conversation(long id) throws Exception {
        JsonObject c = row("""
                SELECT c.*, v.name AS villager, p.name AS player FROM conversations c LEFT JOIN villagers v ON v.uuid = c.villager_uuid
                LEFT JOIN players p ON p.uuid = c.player_uuid WHERE c.id=?""", id);
        if (c == null) {
            return DashboardServer.error("not found");
        }
        c.add("messages", rows("SELECT * FROM messages WHERE conversation_id=? ORDER BY ts", id));
        return c;
    }

    private JsonArray events(Map<String, String> q) throws Exception {
        return rows("""
                SELECT e.*, v.name AS villager, p.name AS player FROM events e LEFT JOIN villagers v ON v.uuid = e.villager_uuid
                LEFT JOIN players p ON p.uuid = e.player_uuid ORDER BY e.ts DESC LIMIT ?""",
                Math.min(500, Integer.parseInt(q.getOrDefault("limit", "100"))));
    }

    // ---- map --------------------------------------------------------------------------------------------------

    private JsonObject map() throws Exception {
        JsonObject o = new JsonObject();
        o.add("villagers", rows("""
                SELECT v.uuid, v.name, v.kind, v.job, v.dimension, v.x, v.y, v.z, v.talks, v.village_key, vl.name AS village
                FROM villagers v LEFT JOIN villages vl ON vl.key = v.village_key WHERE v.alive=1 AND v.dimension IS NOT NULL"""));
        o.add("villages", rows("SELECT key, name, source, dimension, x, y, z, population FROM villages"));
        o.add("players", onServer(() -> {
            JsonArray a = new JsonArray();
            for (ServerPlayer p : mod().server().getPlayerList().getPlayers()) {
                JsonObject j = new JsonObject();
                j.addProperty("uuid", p.getUUID().toString());
                j.addProperty("name", p.getGameProfile().getName());
                j.addProperty("dimension", p.level().dimension().location().toString());
                j.addProperty("x", p.getX());
                j.addProperty("y", p.getY());
                j.addProperty("z", p.getZ());
                a.add(j);
            }
            return a;
        }).get(2, TimeUnit.SECONDS));
        JsonObject bm = new JsonObject();
        bm.addProperty("installed", mod().integrations().hasBlueMap());
        bm.addProperty("url", TwtConfig.BLUEMAP_URL.get());
        o.add("bluemap", bm);
        return o;
    }

    // ---- runtime & voices -------------------------------------------------------------------------------------

    private JsonObject runtime() {
        JsonObject o = runtimeSummary();
        RuntimeManager rt = mod().runtime();
        o.addProperty("runtimeDir", String.valueOf(rt.runtimeDir()));
        JsonArray procs = new JsonArray();
        for (ManagedProcess p : new ManagedProcess[]{rt.llmProcess(), rt.qwenTtsProcess(), rt.voiceProcess()}) {
            if (p == null) {
                continue;
            }
            JsonObject j = new JsonObject();
            j.addProperty("name", p.name());
            j.addProperty("state", p.state().name());
            j.addProperty("pid", p.pid());
            j.addProperty("uptimeMs", p.uptimeMs());
            j.addProperty("lastError", p.lastError());
            j.addProperty("logFile", p.logFile().toString());
            JsonArray log = new JsonArray();
            p.tailLog(60).forEach(log::add);
            j.add("log", log);
            procs.add(j);
        }
        o.add("processes", procs);
        o.addProperty("requests", mod().llm().requests.get());
        o.addProperty("tokensGenerated", mod().llm().tokensGenerated.get());
        JsonObject cfg = new JsonObject();
        cfg.addProperty("llmModel", TwtConfig.LLM_MODEL.get());
        cfg.addProperty("gpuLayers", TwtConfig.GPU_LAYERS.get());
        cfg.addProperty("contextSize", TwtConfig.CONTEXT_SIZE.get());
        cfg.addProperty("parallelSlots", TwtConfig.PARALLEL_SLOTS.get());
        cfg.addProperty("ttsEngine", TwtConfig.TTS_ENGINE.get());
        cfg.addProperty("ttsThreads", TwtConfig.TTS_THREADS.get());
        cfg.addProperty("listenRadius", TwtConfig.LISTEN_RADIUS.get());
        cfg.addProperty("voiceDistance", TwtConfig.VOICE_DISTANCE.get());
        cfg.addProperty("textChat", TwtConfig.TEXT_CHAT.get());
        cfg.addProperty("ambientChatter", TwtConfig.AMBIENT_CHATTER.get());
        o.add("config", cfg);
        return o;
    }

    private JsonArray personas() {
        JsonArray a = new JsonArray();
        for (Persona p : Persona.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", p.key);
            o.addProperty("description", p.description);
            a.add(o);
        }
        return a;
    }

    private void voicePreview(HttpExchange ex) throws Exception {
        JsonObject body = DashboardServer.readJson(ex);
        String text = str(body, "text", "Hrmm. Welcome, traveler! Mind the carrots.");
        String design = str(body, "design", "").trim();
        if (!design.isEmpty() && mod().speech().expressive()) {
            // Qwen3-TTS: a voice described in words, acting out the chosen emotion
            short[] pcm = mod().speech().renderDesign(design, str(body, "emotion", "neutral"),
                    text.length() > 400 ? text.substring(0, 400) : text, body.has("seed") ? body.get("seed").getAsLong() : 42);
            ByteBuffer bb = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.asShortBuffer().put(pcm);
            DashboardServer.send(ex, 200, "audio/wav", wav(bb.array(), AudioDsp.SVC_RATE));
            return;
        }
        String voice = str(body, "voice", "");
        double pitch = body.has("pitch") ? clamp(body.get("pitch").getAsDouble(), 0.7, 1.5) : 1.0;
        double speed = body.has("speed") ? clamp(body.get("speed").getAsDouble(), 0.6, 1.6) : 1.0;
        String emotion = str(body, "emotion", "neutral");
        Prosody prosody = Prosody.forLine(pitch, speed, emotion, 0);
        TtsClient.Speech speech = mod().tts().synthesize(text.length() > 400 ? text.substring(0, 400) : text, voice, prosody.ttsSpeed());
        short[] pcm = prosody.render(speech.pcm(), speech.sampleRate());
        ByteBuffer bb = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(pcm);
        ex.getResponseHeaders().set("X-Gen-Ms", Long.toString(speech.genMs()));
        DashboardServer.send(ex, 200, "audio/wav", wav(bb.array(), AudioDsp.SVC_RATE));
    }

    private static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                .putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }

    // ---- live stream (Server-Sent Events) ---------------------------------------------------------------------

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        LinkedBlockingQueue<JsonObject> q = new LinkedBlockingQueue<>(500);
        Consumer<JsonObject> listener = q::offer;
        mod().feed().subscribe(listener);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            while (true) {
                JsonObject ev = q.poll(15, TimeUnit.SECONDS);
                String chunk = ev == null ? ": ping\n\n" : "data: " + ev + "\n\n";
                os.write(chunk.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException | InterruptedException e) {
            // browser closed the tab
        } finally {
            mod().feed().unsubscribe(listener);
        }
    }

    // ---- utils ------------------------------------------------------------------------------------------------

    private <T> CompletableFuture<T> onServer(java.util.concurrent.Callable<T> task) {
        MinecraftServer server = mod().server();
        CompletableFuture<T> f = new CompletableFuture<>();
        if (server == null) {
            f.completeExceptionally(new IllegalStateException("server not running"));
            return f;
        }
        server.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
