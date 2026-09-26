package dev.eiriksb.theywilltalk.data;

import dev.eiriksb.theywilltalk.TheyWillTalk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite database stored in the world folder ({@code <world>/theywilltalk/theywilltalk.db}). All access goes
 * through one dedicated thread, so the Minecraft server thread never waits on disk.
 */
public final class Database implements AutoCloseable {
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private static final int SCHEMA_VERSION = 3;

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "twt-db");
        t.setDaemon(true);
        return t;
    });
    private final Path file;
    private Connection conn;

    public Database(Path file) {
        this.file = file;
    }

    public void open() throws Exception {
        call(c -> {
            try {
                Files.createDirectories(file.getParent());
                // Instantiate the driver directly: DriverManager's ServiceLoader lookup doesn't see jar-in-jar drivers.
                Properties props = new Properties();
                conn = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), props);
            } catch (Exception e) {
                throw new SQLException(e);
            }
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            migrate();
            return null;
        }).get(30, TimeUnit.SECONDS);
        TheyWillTalk.LOGGER.info("Database opened: {}", file);
    }

    private void migrate() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
            int version = 0;
            try (ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE key='schema'")) {
                if (rs.next()) {
                    version = Integer.parseInt(rs.getString(1));
                }
            }
            if (version < 1) {
                s.execute("""
                        CREATE TABLE IF NOT EXISTS villagers (
                          uuid TEXT PRIMARY KEY, kind TEXT, name TEXT, gender TEXT, persona TEXT, quirk TEXT, backstory TEXT,
                          voice TEXT, pitch REAL, speed REAL, custom_prompt TEXT, created_at INTEGER,
                          job TEXT, job_level INTEGER, age_group TEXT, biome TEXT, village_key TEXT, dimension TEXT,
                          x REAL, y REAL, z REAL, alive INTEGER DEFAULT 1, mood TEXT, mood_level INTEGER DEFAULT 0,
                          traits TEXT, extra_json TEXT, last_seen INTEGER, last_spoke INTEGER, talks INTEGER DEFAULT 0)""");
                s.execute("CREATE INDEX IF NOT EXISTS villagers_village ON villagers(village_key)");
                s.execute("CREATE INDEX IF NOT EXISTS villagers_name ON villagers(name COLLATE NOCASE)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS villages (
                          key TEXT PRIMARY KEY, source TEXT, name TEXT, dimension TEXT, x INTEGER, y INTEGER, z INTEGER,
                          population INTEGER, updated_at INTEGER)""");
                s.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT, first_seen INTEGER, last_seen INTEGER)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS relationships (
                          villager_uuid TEXT, player_uuid TEXT, affinity INTEGER DEFAULT 0, talks INTEGER DEFAULT 0,
                          first_met INTEGER, last_talk INTEGER, hearts INTEGER, reputation INTEGER, relation TEXT,
                          PRIMARY KEY (villager_uuid, player_uuid))""");
                s.execute("CREATE INDEX IF NOT EXISTS rel_player ON relationships(player_uuid)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS memories (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, villager_uuid TEXT, player_uuid TEXT, created INTEGER,
                          kind TEXT, text TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS mem_vp ON memories(villager_uuid, player_uuid, created)");
                s.execute("CREATE INDEX IF NOT EXISTS mem_p ON memories(player_uuid, created)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS conversations (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, villager_uuid TEXT, player_uuid TEXT, started INTEGER,
                          ended INTEGER, channel TEXT, turns INTEGER DEFAULT 0)""");
                s.execute("CREATE INDEX IF NOT EXISTS conv_v ON conversations(villager_uuid, started)");
                s.execute("CREATE INDEX IF NOT EXISTS conv_p ON conversations(player_uuid, started)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS messages (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER, villager_uuid TEXT, player_uuid TEXT,
                          ts INTEGER, role TEXT, speaker TEXT, text TEXT, original_text TEXT, lang TEXT, emotion TEXT,
                          latency_ms INTEGER)""");
                s.execute("CREATE INDEX IF NOT EXISTS msg_conv ON messages(conversation_id, ts)");
                s.execute("CREATE INDEX IF NOT EXISTS msg_v ON messages(villager_uuid, ts)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS family (
                          villager_uuid TEXT, relative_uuid TEXT, relation TEXT, relative_name TEXT,
                          relative_is_player INTEGER DEFAULT 0, deceased INTEGER DEFAULT 0,
                          PRIMARY KEY (villager_uuid, relative_uuid, relation))""");
                s.execute("CREATE INDEX IF NOT EXISTS fam_rel ON family(relative_uuid)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS events (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, villager_uuid TEXT, player_uuid TEXT,
                          text TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS ev_ts ON events(ts)");
            }
            if (version < 2) {
                // v2: hand-written Qwen3-TTS voice descriptions
                boolean has = false;
                try (ResultSet rs = s.executeQuery("PRAGMA table_info(villagers)")) {
                    while (rs.next()) {
                        has |= "voice_design".equals(rs.getString("name"));
                    }
                }
                if (!has) {
                    s.execute("ALTER TABLE villagers ADD COLUMN voice_design TEXT");
                }
            }
            if (version < 3) {
                // v3: errands villagers give players
                s.execute("""
                        CREATE TABLE IF NOT EXISTS errands (
                          id INTEGER PRIMARY KEY AUTOINCREMENT, villager_uuid TEXT, villager_name TEXT, player_uuid TEXT,
                          player_name TEXT, kind TEXT, item TEXT, label TEXT, count INTEGER, progress INTEGER DEFAULT 0, target_uuid TEXT,
                          target_name TEXT, reward INTEGER, status TEXT, created INTEGER, updated INTEGER, deadline INTEGER,
                          request TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS errands_player ON errands(player_uuid, status)");
                s.execute("CREATE INDEX IF NOT EXISTS errands_villager ON errands(villager_uuid, status)");
            }
            s.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '" + SCHEMA_VERSION + "')");
        }
    }

    public <T> CompletableFuture<T> call(Work<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run(conn);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, thread);
    }

    /** Fire-and-forget write; errors are logged. */
    public void exec(String sql, Object... params) {
        call(c -> update(c, sql, params)).exceptionally(t -> {
            TheyWillTalk.LOGGER.warn("DB write failed: {} ({})", sql.lines().findFirst().orElse(sql), t.getMessage());
            return 0;
        });
    }

    public CompletableFuture<Long> insert(String sql, Object... params) {
        return call(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    public <T> CompletableFuture<List<T>> query(String sql, RowMapper<T> mapper, Object... params) {
        return call(c -> queryNow(c, sql, mapper, params));
    }

    public static <T> List<T> queryNow(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            List<T> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
            }
            return out;
        }
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof java.util.UUID u) {
                ps.setString(i + 1, u.toString());
            } else if (p instanceof Boolean b) {
                ps.setInt(i + 1, b ? 1 : 0);
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    @Override
    public void close() {
        try {
            call(c -> {
                if (conn != null) {
                    conn.close();
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            TheyWillTalk.LOGGER.warn("Closing database: {}", e.getMessage());
        }
        thread.shutdown();
    }
}
