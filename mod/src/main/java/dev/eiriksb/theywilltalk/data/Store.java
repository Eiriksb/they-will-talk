package dev.eiriksb.theywilltalk.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.eiriksb.theywilltalk.villager.VillagerFacts;
import dev.eiriksb.theywilltalk.villager.VillagerKind;
import dev.eiriksb.theywilltalk.villager.VillagerProfile;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public void addMemory(UUID villager, UUID player, String kind, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        db.exec("INSERT INTO memories (villager_uuid, player_uuid, created, kind, text) VALUES (?,?,?,?,?)",
                villager, player, System.currentTimeMillis(), kind, text.trim());
    }

    public record Memory(long created, String kind, String text, String villagerName) {}

    public CompletableFuture<List<Memory>> memories(UUID villager, UUID player, int limit) {
        return db.query("""
                        SELECT created, kind, text, NULL FROM memories WHERE villager_uuid=? AND (player_uuid=? OR player_uuid IS NULL)
                        ORDER BY created DESC LIMIT ?""",
                rs -> new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), null), villager, player, limit);
    }

    /** What other villagers of the same village remember about this player - the village gossip network. */
    public CompletableFuture<List<Memory>> gossip(UUID player, String villageKey, UUID exclude, int limit) {
        if (villageKey == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return db.query("""
                        SELECT m.created, m.kind, m.text, v.name FROM memories m JOIN villagers v ON v.uuid = m.villager_uuid
                        WHERE m.player_uuid=? AND v.village_key=? AND m.villager_uuid<>? AND m.created > ?
                        ORDER BY m.created DESC LIMIT ?""",
                rs -> new Memory(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                player, villageKey, exclude, System.currentTimeMillis() - 7L * 24 * 3600 * 1000, limit);
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

    public void event(String type, UUID villager, UUID player, String text) {
        db.exec("INSERT INTO events (ts, type, villager_uuid, player_uuid, text) VALUES (?,?,?,?,?)",
                System.currentTimeMillis(), type, villager, player, text);
    }
}
