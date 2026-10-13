package dev.eiriksb.theywilltalk.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.TheyWillTalk;
import dev.eiriksb.theywilltalk.api.NewMemory;
import dev.eiriksb.theywilltalk.api.Relation;
import dev.eiriksb.theywilltalk.api.Subject;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Domain-level reads and writes on top of {@link Database}. */
public final class Store {
    private static final Gson GSON = new Gson();
    public final Database db;

    public Store(Database db) {
        this.db = db;
    }

    // ---- villagers -------------------------------------------------------------------------------------------

    public Optional<VillagerProfile> loadProfileNow(UUID uuid) {
        try {
            return db.query("SELECT * FROM villagers WHERE uuid=?", Store::profile, uuid).get().stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static VillagerProfile profile(ResultSet rs) throws SQLException {
        return new VillagerProfile(UUID.fromString(rs.getString("uuid")), VillagerKind.byId(rs.getString("kind")),
                rs.getString("name"), rs.getString("gender"), rs.getString("persona"), rs.getString("quirk"),
                rs.getString("backstory"), rs.getString("voice"), rs.getDouble("pitch"), rs.getDouble("speed"),
                rs.getString("custom_prompt"), rs.getLong("created_at"), rs.getString("voice_design"));
    }

    public void saveProfile(VillagerProfile p) {
        db.exec("""
                INSERT INTO villagers (uuid, kind, name, gender, persona, quirk, backstory, voice, pitch, speed, custom_prompt, created_at, voice_design)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET kind=excluded.kind, name=excluded.name, gender=excluded.gender,
                  persona=excluded.persona, quirk=excluded.quirk, backstory=excluded.backstory, voice=excluded.voice,
                  pitch=excluded.pitch, speed=excluded.speed, custom_prompt=excluded.custom_prompt, voice_design=excluded.voice_design""",
                p.uuid(), p.kind().id, p.name(), p.gender(), p.persona(), p.quirk(), p.backstory(), p.voice(), p.pitch(),
                p.speed(), p.customPrompt(), p.createdAt(), p.voiceDesign());
    }

    /** Live state snapshot (position, job, mood, family, village) - called periodically and when talking. */
    public void saveFacts(VillagerFacts f) {
        JsonObject extra = new JsonObject();
        f.extra.forEach(extra::addProperty);
        if (!f.offers.isEmpty()) {
            extra.add("offers", GSON.toJsonTree(f.offers));
        }
        long now = System.currentTimeMillis();
        db.exec("""
                UPDATE villagers SET job=?, job_level=?, age_group=?, biome=?, village_key=?, dimension=?, x=?, y=?, z=?,
                  alive=?, mood=?, mood_level=?, traits=?, extra_json=?, last_seen=? WHERE uuid=?""",
                f.job, f.jobLevel, f.ageGroup, f.biomeType, f.village == null ? null : f.village.key(), f.dimension,
                f.x, f.y, f.z, f.alive, f.mood, f.moodLevel, String.join(", ", f.traits), extra.toString(), now, f.uuid);
        if (f.village != null) {
            saveVillage(f.village);
        }
        if (!f.family.isEmpty()) {
            db.call(c -> {
                Database.update(c, "DELETE FROM family WHERE villager_uuid=?", f.uuid.toString());
                for (VillagerFacts.FamilyLink l : f.family) {
                    Database.update(c, """
                            INSERT OR REPLACE INTO family (villager_uuid, relative_uuid, relation, relative_name, relative_is_player, deceased)
                            VALUES (?,?,?,?,?,?)""", f.uuid.toString(), l.uuid() == null ? "name:" + l.name() : l.uuid().toString(),
                            l.relation(), l.name(), l.player() ? 1 : 0, l.deceased() ? 1 : 0);
                }
                return null;
            });
        }
    }

    public void markDead(UUID villager) {
        db.exec("UPDATE villagers SET alive=0, last_seen=? WHERE uuid=?", System.currentTimeMillis(), villager);
    }

    public void saveVillage(VillagerFacts.Village v) {
        db.exec("""
                INSERT INTO villages (key, source, name, dimension, x, y, z, population, updated_at) VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(key) DO UPDATE SET name=excluded.name, x=excluded.x, y=excluded.y, z=excluded.z,
                  population=CASE WHEN excluded.population >= 0 THEN excluded.population ELSE villages.population END,
                  updated_at=excluded.updated_at""",
                v.key(), v.source(), v.name(), v.dimension(), v.x(), v.y(), v.z(), v.population(), System.currentTimeMillis());
    }

    /** Vanilla villages don't track population themselves: count the living villagers we know of. */
    public void recountVanillaPopulations() {
        db.exec("""
                UPDATE villages SET population = (SELECT COUNT(*) FROM villagers v WHERE v.village_key = villages.key AND v.alive = 1)
                WHERE source = 'vanilla'""");
    }

    // ---- players & relationships ------------------------------------------------------------------------------

    public void seenPlayer(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        db.exec("""
                INSERT INTO players (uuid, name, first_seen, last_seen) VALUES (?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_seen=excluded.last_seen""", uuid, name, now, now);
    }

    public record Relationship(int affinity, int talks, long firstMet, long lastTalk) {
        public static final Relationship NONE = new Relationship(0, 0, 0, 0);
    }

    public CompletableFuture<Relationship> relationship(UUID villager, UUID player) {
        return db.query("SELECT affinity, talks, first_met, last_talk FROM relationships WHERE villager_uuid=? AND player_uuid=?",
                        rs -> new Relationship(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)), villager, player)
                .thenApply(l -> l.isEmpty() ? Relationship.NONE : l.getFirst());
    }

    public void recordTalk(UUID villager, UUID player, Integer hearts, Integer reputation, String relation) {
        long now = System.currentTimeMillis();
        db.exec("""
                INSERT INTO relationships (villager_uuid, player_uuid, affinity, talks, first_met, last_talk, hearts, reputation, relation)
                VALUES (?,?,0,1,?,?,?,?,?)
                ON CONFLICT(villager_uuid, player_uuid) DO UPDATE SET talks=talks+1, last_talk=excluded.last_talk,
                  hearts=COALESCE(excluded.hearts, hearts), reputation=COALESCE(excluded.reputation, reputation),
                  relation=COALESCE(excluded.relation, relation)""",
                villager, player, now, now, hearts, reputation, relation);
    }

    public void villagerSpoke(UUID villager) {
        db.exec("UPDATE villagers SET talks=talks+1, last_spoke=? WHERE uuid=?", System.currentTimeMillis(), villager);
    }

    public void adjustAffinity(UUID villager, UUID player, int delta) {
        long now = System.currentTimeMillis();
        db.exec("""
                INSERT INTO relationships (villager_uuid, player_uuid, affinity, talks, first_met, last_talk) VALUES (?,?,?,0,?,?)
                ON CONFLICT(villager_uuid, player_uuid) DO UPDATE SET affinity=MAX(-100, MIN(100, affinity + ?))""",
                villager, player, Math.max(-100, Math.min(100, delta)), now, now, delta);
    }

    // ---- memories ---------------------------------------------------------------------------------------------

    /** Something a villager remembers about a player (player null: about nothing in particular), worth 3 of 10. */
    public void addMemory(UUID villager, UUID player, String kind, String text) {
        addMemory(villager, player, kind, text, 3);
    }

    public void addMemory(UUID villager, UUID player, String kind, String text, int importance) {
        remember(NewMemory.of(villager, player == null ? Subject.NONE : Subject.player(player), text).withKind(kind).withImportance(importance))
                .exceptionally(t -> {
                    TheyWillTalk.LOGGER.warn("Could not save a memory: {}", t.toString());
                    return -1L;
                });
    }

    /** Adds a memory; completes with its id (-1 when there was nothing to remember). */
    public CompletableFuture<Long> remember(NewMemory m) {
        String text = m.text() == null ? "" : m.text().trim();
        if (text.isEmpty()) {
            return CompletableFuture.completedFuture(-1L);
        }
        Subject s = m.subject();
        String player = "player".equals(s.type()) ? s.id() : null;
        return db.insert("""
                        INSERT INTO memories (villager_uuid, player_uuid, created, kind, text, subject_type, subject_id, importance, source)
                        VALUES (?,?,?,?,?,?,?,?,?)""",
                m.villager(), player, System.currentTimeMillis(), m.kind(), text, s.isNone() ? null : s.type(), s.isNone() ? null : s.id(),
                m.importance(), m.source());
    }

    /**
     * A memory for the prompts.
     *
     * @param villagerName who remembers it (gossip), else null
     * @param toldBy       who told them (hearsay), else null
     */
    public record Memory(long created, String kind, String text, String villagerName, int importance, String toldBy, long id) {}

    /** Hearsay names its teller: memories.source is 'told_by:<uuid>'. */
    private static final String TELLER = " LEFT JOIN villagers t ON m.source = 'told_by:' || t.uuid ";

    public CompletableFuture<List<Memory>> memories(UUID villager, UUID player, int limit) {
        return db.query("""
                        SELECT m.created, m.kind, m.text, m.importance, t.name, m.id FROM memories m""" + TELLER + """
                        WHERE m.villager_uuid=? AND (m.player_uuid=? OR m.subject_type IS NULL) AND m.summarised_into IS NULL
                        ORDER BY m.created DESC LIMIT ?""",
                rs -> new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), null, rs.getInt(4), rs.getString(5), rs.getLong(6)),
                villager, player, limit);
    }

    /** What other villagers of the same village saw of this player themselves - the village gossip network. */
    public CompletableFuture<List<Memory>> gossip(UUID player, String villageKey, UUID exclude, int limit) {
        if (villageKey == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.query("""
                        SELECT m.created, m.kind, m.text, v.name, m.importance, m.id FROM memories m JOIN villagers v ON v.uuid = m.villager_uuid
                        WHERE m.player_uuid=? AND v.village_key=? AND m.villager_uuid<>? AND m.created > ?
                          AND m.source NOT LIKE 'told_by:%' AND m.summarised_into IS NULL
                        ORDER BY m.created DESC LIMIT ?""",
                rs -> new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), null, rs.getLong(6)),
                player, villageKey, exclude, System.currentTimeMillis() - 7L * 24 * 3600 * 1000, limit);
    }

    // The memory API (dev.eiriksb.theywilltalk.api.MemoryApi) reads these.

    private static final String API_COLS = "m.id, m.villager_uuid, m.created, m.kind, m.text, m.subject_type, m.subject_id, m.importance, m.source, t.name AS teller";

    private static dev.eiriksb.theywilltalk.api.Memory apiMemory(ResultSet rs) throws SQLException {
        String type = rs.getString("subject_type");
        Subject subject = type == null ? Subject.NONE : new Subject(type, rs.getString("subject_id"));
        return new dev.eiriksb.theywilltalk.api.Memory(rs.getLong("id"), UUID.fromString(rs.getString("villager_uuid")),
                rs.getLong("created"), rs.getString("kind"), rs.getString("text"), subject, Math.max(1, rs.getInt("importance")),
                rs.getString("source"), rs.getString("teller"));
    }

    public CompletableFuture<List<dev.eiriksb.theywilltalk.api.Memory>> memoriesAbout(UUID villager, Subject subject, int limit) {
        if (subject.isNone()) {
            return db.query("SELECT " + API_COLS + " FROM memories m" + TELLER + """
                    WHERE m.villager_uuid=? AND m.subject_type IS NULL AND m.summarised_into IS NULL ORDER BY m.created DESC LIMIT ?""",
                    Store::apiMemory, villager, limit);
        }
        return db.query("SELECT " + API_COLS + " FROM memories m" + TELLER + """
                WHERE m.villager_uuid=? AND m.subject_type=? AND m.subject_id=? AND m.summarised_into IS NULL
                ORDER BY m.created DESC LIMIT ?""", Store::apiMemory, villager, subject.type(), subject.id(), limit);
    }

    public CompletableFuture<List<dev.eiriksb.theywilltalk.api.Memory>> recentMemories(UUID villager, int limit) {
        return db.query("SELECT " + API_COLS + " FROM memories m" + TELLER + """
                WHERE m.villager_uuid=? AND m.summarised_into IS NULL ORDER BY m.created DESC LIMIT ?""", Store::apiMemory, villager, limit);
    }

    private record Scored(dev.eiriksb.theywilltalk.api.Memory memory, double bm25) {}

    /** The memories that best fit {@code text}: shared words, then importance and recency ({@link MemoryRanking}). */
    public CompletableFuture<List<dev.eiriksb.theywilltalk.api.Memory>> recall(UUID villager, String text, int limit) {
        String query = MemoryRanking.ftsQuery(text);
        if (query == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.call(c -> {
            List<Scored> found = Database.queryNow(c, "SELECT " + API_COLS + ", bm25(memories_fts) AS rel FROM memories_fts"
                            + " JOIN memories m ON m.id = memories_fts.rowid" + TELLER + """
                            WHERE memories_fts MATCH ? AND m.villager_uuid=? AND m.summarised_into IS NULL ORDER BY rel LIMIT 40""",
                    rs -> new Scored(apiMemory(rs), rs.getDouble("rel")), query, villager.toString());
            return MemoryRanking.rank(found, Scored::bm25, s -> s.memory().importance(), s -> s.memory().created(),
                    System.currentTimeMillis(), limit).stream().map(Scored::memory).toList();
        });
    }

    /**
     * {@code from} passes on up to {@code limit} memories of at least {@code minImportance} that {@code to} doesn't have:
     * copied as hearsay, one point less important. Completes with how many were passed on.
     */
    public CompletableFuture<Integer> tell(UUID from, UUID to, Subject subject, int minImportance, int limit) {
        return db.call(c -> {
            String about = subject.isNone() ? "" : " AND m.subject_type=? AND m.subject_id=?";
            List<Object> params = new ArrayList<>(List.of(from.toString()));
            if (!subject.isNone()) {
                params.add(subject.type());
                params.add(subject.id());
            }
            params.addAll(List.of(minImportance, to.toString(), "told_by:" + to, limit));
            List<dev.eiriksb.theywilltalk.api.Memory> news = Database.queryNow(c, "SELECT " + API_COLS + " FROM memories m" + TELLER
                            + "WHERE m.villager_uuid=?" + about + """
                             AND m.importance >= ? AND m.summarised_into IS NULL
                              AND NOT EXISTS (SELECT 1 FROM memories k WHERE k.villager_uuid=? AND k.text=m.text)
                              AND m.source <> ?
                            ORDER BY m.importance DESC, m.created DESC LIMIT ?""",
                    Store::apiMemory, params.toArray());
            long now = System.currentTimeMillis();
            for (dev.eiriksb.theywilltalk.api.Memory m : news) {
                Subject s = m.subject();
                Database.update(c, """
                                INSERT INTO memories (villager_uuid, player_uuid, created, kind, text, subject_type, subject_id, importance, source)
                                VALUES (?,?,?,?,?,?,?,?,?)""",
                        to.toString(), "player".equals(s.type()) ? s.id() : null, now, m.kind(), m.text(), s.isNone() ? null : s.type(),
                        s.isNone() ? null : s.id(), Math.max(1, m.importance() - 1), "told_by:" + from);
            }
            return news.size();
        });
    }

    // ---- villager <-> villager relations ------------------------------------------------------------------------

    private static Relation relation(ResultSet rs) throws SQLException {
        return new Relation(UUID.fromString(rs.getString("a")), UUID.fromString(rs.getString("b")), rs.getString("name"),
                rs.getInt("affinity"), rs.getInt("familiarity"), rs.getString("kind"), rs.getLong("last_interaction"));
    }

    public CompletableFuture<Relation> villagerRelation(UUID a, UUID b) {
        return db.query("""
                        SELECT r.a, r.b, v.name, r.affinity, r.familiarity, r.kind, r.last_interaction FROM villager_relations r
                        LEFT JOIN villagers v ON v.uuid = r.b WHERE r.a=? AND r.b=?""", Store::relation, a, b)
                .thenApply(l -> l.isEmpty() ? Relation.none(a, b) : l.getFirst());
    }

    public CompletableFuture<List<Relation>> villagerRelations(UUID a, int limit) {
        return db.query("""
                        SELECT r.a, r.b, v.name, r.affinity, r.familiarity, r.kind, r.last_interaction FROM villager_relations r
                        LEFT JOIN villagers v ON v.uuid = r.b WHERE r.a=? ORDER BY r.familiarity DESC, r.last_interaction DESC LIMIT ?""",
                Store::relation, a, limit);
    }

    public void adjustVillagerRelation(UUID a, UUID b, int affinityDelta, int familiarityDelta) {
        long now = System.currentTimeMillis();
        int aff = Math.max(-100, Math.min(100, affinityDelta));
        db.exec("""
                INSERT INTO villager_relations (a, b, affinity, familiarity, last_interaction) VALUES (?,?,?,?,?)
                ON CONFLICT(a, b) DO UPDATE SET affinity=MAX(-100, MIN(100, affinity + ?)), familiarity=MAX(0, familiarity + ?),
                  last_interaction=excluded.last_interaction""",
                a, b, aff, Math.max(0, familiarityDelta), now, affinityDelta, familiarityDelta);
    }

    public void setVillagerRelationKind(UUID a, UUID b, String kind) {
        db.exec("""
                INSERT INTO villager_relations (a, b, kind, last_interaction) VALUES (?,?,?,?)
                ON CONFLICT(a, b) DO UPDATE SET kind=excluded.kind""", a, b, kind, System.currentTimeMillis());
    }

    // ---- conversations ----------------------------------------------------------------------------------------

    public CompletableFuture<Long> startConversation(UUID villager, UUID player, String channel) {
        return db.insert("INSERT INTO conversations (villager_uuid, player_uuid, started, ended, channel) VALUES (?,?,?,?,?)",
                villager, player, System.currentTimeMillis(), System.currentTimeMillis(), channel);
    }

    public void addMessage(long conversationId, UUID villager, UUID player, String role, String speaker, String text,
                           String original, String lang, String emotion, long latencyMs) {
        long now = System.currentTimeMillis();
        db.exec("""
                INSERT INTO messages (conversation_id, villager_uuid, player_uuid, ts, role, speaker, text, original_text, lang, emotion, latency_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                conversationId, villager, player, now, role, speaker, text, original, lang, emotion, latencyMs);
        db.exec("UPDATE conversations SET ended=?, turns=turns+1 WHERE id=?", now, conversationId);
    }

    /** The last {@code limit} lines between a villager and a player, oldest first. */
    public CompletableFuture<java.util.List<dev.eiriksb.theywilltalk.api.ChatLine>> history(UUID villager, UUID player, int limit) {
        return db.query("SELECT ts, role, speaker, text, emotion FROM messages WHERE villager_uuid=? AND player_uuid=? ORDER BY ts DESC, id DESC LIMIT ?",
                rs -> new dev.eiriksb.theywilltalk.api.ChatLine(rs.getLong(1), !"villager".equals(rs.getString(2)), rs.getString(3),
                        rs.getString(4), "villager".equals(rs.getString(2)) ? rs.getString(5) : null), villager, player, limit)
                .thenApply(l -> {
                    java.util.List<dev.eiriksb.theywilltalk.api.ChatLine> out = new java.util.ArrayList<>(l);
                    java.util.Collections.reverse(out);
                    return out;
                });
    }

    public void event(String type, UUID villager, UUID player, String text) {
        db.exec("INSERT INTO events (ts, type, villager_uuid, player_uuid, text) VALUES (?,?,?,?,?)",
                System.currentTimeMillis(), type, villager, player, text);
    }
}
