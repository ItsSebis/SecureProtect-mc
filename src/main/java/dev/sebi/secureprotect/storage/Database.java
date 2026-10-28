package dev.sebi.secureprotect.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQLite storage. Two connections: {@code write} must only be touched from the writer thread,
 * {@code read} from the reader thread (WAL lets them run concurrently).
 */
public final class Database implements AutoCloseable {
    private record UserRef(int id, String name) {
    }

    private final Connection write;
    private final Connection read;

    // Writer-thread only.
    private final Map<UUID, UserRef> users = new HashMap<>();
    private final Map<String, Integer> materials = new HashMap<>();
    private final Map<String, Integer> worlds = new HashMap<>();

    public Database(Path file) throws SQLException {
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        write = DriverManager.getConnection(url);
        try (Statement st = write.createStatement()) {
            // auto_vacuum only takes effect on a fresh file, before the first table exists.
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS materials(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE IF NOT EXISTS worlds(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE IF NOT EXISTS actions(time INTEGER NOT NULL, user INTEGER NOT NULL, world INTEGER NOT NULL,"
                    + " x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action INTEGER NOT NULL,"
                    + " material INTEGER NOT NULL, amount INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pos ON actions(world, x, z, y, time)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_time ON actions(time)");
        }
        read = DriverManager.getConnection(url);
        try (Statement st = read.createStatement()) {
            st.execute("PRAGMA query_only=1");
        }
    }

    // ---- writer thread ----

    public void insertBatch(List<RawEntry> batch) throws SQLException {
        if (batch.isEmpty()) {
            return;
        }
        write.setAutoCommit(false);
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO actions(time, user, world, x, y, z, action, material, amount) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (RawEntry e : batch) {
                ps.setLong(1, e.time());
                ps.setInt(2, internUser(e.user(), e.userName()));
                ps.setInt(3, intern("worlds", worlds, e.world()));
                ps.setInt(4, e.x());
                ps.setInt(5, e.y());
                ps.setInt(6, e.z());
                ps.setInt(7, e.action().id);
                ps.setInt(8, intern("materials", materials, e.material()));
                ps.setInt(9, e.amount());
                ps.addBatch();
            }
            ps.executeBatch();
            write.commit();
        } catch (SQLException | RuntimeException ex) {
            write.rollback();
            // Ids interned inside the rolled back transaction no longer exist.
            users.clear();
            materials.clear();
            worlds.clear();
            throw ex;
        } finally {
            write.setAutoCommit(true);
        }
    }

    private int internUser(UUID uuid, String name) throws SQLException {
        UserRef ref = users.get(uuid);
        if (ref != null && ref.name().equals(name)) {
            return ref.id();
        }
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO users(uuid, name) VALUES(?, ?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name RETURNING id")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                users.put(uuid, new UserRef(id, name));
                return id;
            }
        }
    }

    private int intern(String table, Map<String, Integer> cache, String name) throws SQLException {
        Integer cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO " + table + "(name) VALUES(?) ON CONFLICT(name) DO UPDATE SET name=excluded.name RETURNING id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                cache.put(name, id);
                return id;
            }
        }
    }

    public int deleteOlderThan(long cutoffEpochSeconds, int limit) throws SQLException {
        try (PreparedStatement ps = write.prepareStatement(
                "DELETE FROM actions WHERE rowid IN (SELECT rowid FROM actions WHERE time < ? LIMIT ?)")) {
            ps.setLong(1, cutoffEpochSeconds);
            ps.setInt(2, limit);
            return ps.executeUpdate();
        }
    }

    /** Rows are appended in time order, so the lowest rowids are the oldest entries. */
    public int deleteOldest(int limit) throws SQLException {
        try (PreparedStatement ps = write.prepareStatement(
                "DELETE FROM actions WHERE rowid IN (SELECT rowid FROM actions ORDER BY rowid LIMIT ?)")) {
            ps.setInt(1, limit);
            return ps.executeUpdate();
        }
    }

    /** Bytes in use, excluding free pages that deletes left behind. */
    public long usedBytes() throws SQLException {
        return (pragma(write, "page_count") - pragma(write, "freelist_count")) * pragma(write, "page_size");
    }

    public long freePages() throws SQLException {
        return pragma(write, "freelist_count");
    }

    /** Hands up to {@code pages} free pages back to the filesystem. */
    public void shrink(int pages) throws SQLException {
        try (Statement st = write.createStatement()) {
            st.execute("PRAGMA incremental_vacuum(" + pages + ")");
        }
    }

    private static long pragma(Connection c, String name) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ---- reader thread ----

    public HistoryPage query(String world, int x, int y, int z, Set<Action> actions, int limit, int offset) throws SQLException {
        String in = actions.stream().map(a -> Integer.toString(a.id)).collect(Collectors.joining(","));
        String where = " WHERE a.world = (SELECT id FROM worlds WHERE name = ?) AND a.x = ? AND a.z = ? AND a.y = ?"
                + " AND a.action IN (" + in + ")";
        int total;
        try (PreparedStatement ps = read.prepareStatement("SELECT COUNT(*) FROM actions a" + where)) {
            bindPos(ps, world, x, y, z);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }
        List<HistoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps = read.prepareStatement(
                "SELECT a.time, u.name, a.action, m.name, a.amount FROM actions a"
                        + " JOIN users u ON u.id = a.user JOIN materials m ON m.id = a.material"
                        + where + " ORDER BY a.time DESC, a.rowid DESC LIMIT ? OFFSET ?")) {
            bindPos(ps, world, x, y, z);
            ps.setInt(5, limit);
            ps.setInt(6, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HistoryRow(rs.getLong(1), rs.getString(2), Action.of(rs.getInt(3)), rs.getString(4), rs.getInt(5)));
                }
            }
        }
        return new HistoryPage(rows, total);
    }

    private static void bindPos(PreparedStatement ps, String world, int x, int y, int z) throws SQLException {
        ps.setString(1, world);
        ps.setInt(2, x);
        ps.setInt(3, z);
        ps.setInt(4, y);
    }

    public long rowCount() throws SQLException {
        return pragma(read, "page_count") == 0 ? 0 : scalar(read, "SELECT COUNT(*) FROM actions");
    }

    public long fileBytes() throws SQLException {
        return pragma(read, "page_count") * pragma(read, "page_size");
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            read.close();
        } finally {
            write.close();
        }
    }
}
