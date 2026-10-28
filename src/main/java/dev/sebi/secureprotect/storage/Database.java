package dev.sebi.secureprotect.storage;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * SQLite storage. Two connections: {@code write} must only be touched from the writer thread,
 * {@code read} from the reader thread (WAL lets them run concurrently).
 */
public final class Database implements AutoCloseable {
    private record UserRef(int id, String name) {
    }

    private record ItemKey(String material, ByteBuffer data) {
    }

    private static final byte[] NO_DATA = new byte[0];
    private static final int ITEM_CACHE_LIMIT = 20_000;
    public static final int DEFAULT_MAX_ITEM_DATA = 16 * 1024;

    private static final String HISTORY_COLUMNS = "a.time, u.name, a.action, i.material, i.data, a.amount";
    private static final String HISTORY_JOINS = " FROM actions a JOIN users u ON u.id = a.user JOIN items i ON i.id = a.item";
    private static final String WORLD_ID = "(SELECT id FROM worlds WHERE name = ?)";

    private final Connection write;
    private final Connection read;
    private final int maxItemData;

    // Writer-thread only.
    private final Map<UUID, UserRef> users = new HashMap<>();
    private final Map<ItemKey, Integer> items = new HashMap<>();
    private final Map<String, Integer> worlds = new HashMap<>();

    public Database(Path file) throws SQLException {
        this(file, DEFAULT_MAX_ITEM_DATA);
    }

    /** @param maxItemData items whose serialized data is larger than this are stored as their plain material. */
    public Database(Path file, int maxItemData) throws SQLException {
        this.maxItemData = maxItemData;
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        write = DriverManager.getConnection(url);
        try (Statement st = write.createStatement()) {
            // auto_vacuum only takes effect on a fresh file, before the first table exists.
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            if (tableExists("materials")) {
                migrateMaterialsToItems(st);
            }
            st.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL)");
            // data is empty for plain items; identical enchanted books share one row.
            st.execute("CREATE TABLE IF NOT EXISTS items(id INTEGER PRIMARY KEY, material TEXT NOT NULL, data BLOB NOT NULL,"
                    + " UNIQUE(material, data))");
            st.execute("CREATE TABLE IF NOT EXISTS worlds(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE IF NOT EXISTS actions(time INTEGER NOT NULL, user INTEGER NOT NULL, world INTEGER NOT NULL,"
                    + " x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, action INTEGER NOT NULL,"
                    + " item INTEGER NOT NULL, amount INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pos ON actions(world, x, z, y, time)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_time ON actions(time)");
        }
        read = DriverManager.getConnection(url);
        try (Statement st = read.createStatement()) {
            st.execute("PRAGMA query_only=1");
        }
    }

    private boolean tableExists(String name) throws SQLException {
        try (PreparedStatement ps = write.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** First schema stored only a material name per entry; keep those rows and give them empty item data. */
    private void migrateMaterialsToItems(Statement st) throws SQLException {
        write.setAutoCommit(false);
        try {
            st.execute("CREATE TABLE items(id INTEGER PRIMARY KEY, material TEXT NOT NULL, data BLOB NOT NULL, UNIQUE(material, data))");
            st.execute("INSERT INTO items(id, material, data) SELECT id, name, x'' FROM materials");
            st.execute("DROP TABLE materials");
            st.execute("ALTER TABLE actions RENAME COLUMN material TO item");
            write.commit();
        } catch (SQLException ex) {
            write.rollback();
            throw ex;
        } finally {
            write.setAutoCommit(true);
        }
    }

    // ---- writer thread ----

    public void insertBatch(List<RawEntry> batch) throws SQLException {
        if (batch.isEmpty()) {
            return;
        }
        write.setAutoCommit(false);
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO actions(time, user, world, x, y, z, action, item, amount) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (RawEntry e : batch) {
                ps.setLong(1, e.time());
                ps.setInt(2, internUser(e.user(), e.userName()));
                ps.setInt(3, internWorld(e.world()));
                ps.setInt(4, e.x());
                ps.setInt(5, e.y());
                ps.setInt(6, e.z());
                ps.setInt(7, e.action().id);
                ps.setInt(8, internItem(e.material(), e.itemData()));
                ps.setInt(9, e.amount());
                ps.addBatch();
            }
            ps.executeBatch();
            write.commit();
        } catch (SQLException | RuntimeException ex) {
            write.rollback();
            // Ids interned inside the rolled back transaction no longer exist.
            users.clear();
            items.clear();
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

    private int internWorld(String name) throws SQLException {
        Integer cached = worlds.get(name);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO worlds(name) VALUES(?) ON CONFLICT(name) DO UPDATE SET name=excluded.name RETURNING id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                worlds.put(name, id);
                return id;
            }
        }
    }

    private int internItem(String material, Supplier<byte[]> dataSupplier) throws SQLException {
        byte[] data = dataSupplier == null ? NO_DATA : dataSupplier.get();
        if (data == null || data.length > maxItemData) {
            data = NO_DATA;
        }
        ItemKey key = new ItemKey(material, ByteBuffer.wrap(data));
        Integer cached = items.get(key);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = write.prepareStatement(
                "INSERT INTO items(material, data) VALUES(?, ?) ON CONFLICT(material, data) DO UPDATE SET material=excluded.material RETURNING id")) {
            ps.setString(1, material);
            ps.setBytes(2, data);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt(1);
                if (items.size() >= ITEM_CACHE_LIMIT) {
                    items.clear();
                }
                items.put(key, id);
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

    /**
     * Drops item rows with data (enchanted books, named items, ...) that no entry references any more.
     * Plain items are tiny and are kept. This scans the whole actions table, so call it rarely.
     */
    public int deleteUnusedItems() throws SQLException {
        try (Statement st = write.createStatement()) {
            int n = st.executeUpdate("DELETE FROM items WHERE length(data) > 0 AND id NOT IN (SELECT DISTINCT item FROM actions)");
            items.clear(); // cached ids may point at deleted rows
            return n;
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
        String where = " WHERE a.world = " + WORLD_ID + " AND a.x = ? AND a.z = ? AND a.y = ? AND a.action IN (" + in + ")";
        int total;
        try (PreparedStatement ps = read.prepareStatement("SELECT COUNT(*) FROM actions a" + where)) {
            bindPos(ps, world, x, y, z);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }
        List<HistoryRow> rows = new ArrayList<>();
        try (PreparedStatement ps = read.prepareStatement("SELECT " + HISTORY_COLUMNS + HISTORY_JOINS
                + where + " ORDER BY a.time DESC, a.rowid DESC LIMIT ? OFFSET ?")) {
            bindPos(ps, world, x, y, z);
            ps.setInt(5, limit);
            ps.setInt(6, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(row(rs));
                }
            }
        }
        return new HistoryPage(rows, total);
    }

    private static HistoryRow row(ResultSet rs) throws SQLException {
        return new HistoryRow(rs.getLong(1), rs.getString(2), Action.of(rs.getInt(3)), rs.getString(4), rs.getBytes(5), rs.getInt(6));
    }

    private static void bindPos(PreparedStatement ps, String world, int x, int y, int z) throws SQLException {
        ps.setString(1, world);
        ps.setInt(2, x);
        ps.setInt(3, z);
        ps.setInt(4, y);
    }

    /** Index of the first position that has any history, or -1. Used for "what was where I am looking". */
    public int firstWithHistory(String world, List<int[]> positions) throws SQLException {
        try (PreparedStatement ps = read.prepareStatement(
                "SELECT 1 FROM actions WHERE world = " + WORLD_ID + " AND x = ? AND z = ? AND y = ? LIMIT 1")) {
            for (int i = 0; i < positions.size(); i++) {
                int[] p = positions.get(i);
                bindPos(ps, world, p[0], p[1], p[2]);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Positions with history inside a cube around the centre, most recently changed first. One indexed
     * query per x slice, so cost grows with the radius, not with the size of the database.
     */
    public NearPage near(String world, int cx, int cy, int cz, int radius, int limit, int offset) throws SQLException {
        record Hit(int x, int y, int z, long time, int count) {
        }
        List<Hit> hits = new ArrayList<>();
        try (PreparedStatement ps = read.prepareStatement(
                "SELECT y, z, MAX(time), COUNT(*) FROM actions WHERE world = " + WORLD_ID
                        + " AND x = ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ? GROUP BY z, y")) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, cz - radius);
                ps.setInt(4, cz + radius);
                ps.setInt(5, cy - radius);
                ps.setInt(6, cy + radius);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hits.add(new Hit(x, rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)));
                    }
                }
            }
        }
        hits.sort(Comparator.comparingLong(Hit::time).reversed());
        List<NearPage.Entry> entries = new ArrayList<>();
        try (PreparedStatement ps = read.prepareStatement("SELECT " + HISTORY_COLUMNS + HISTORY_JOINS
                + " WHERE a.world = " + WORLD_ID + " AND a.x = ? AND a.z = ? AND a.y = ? ORDER BY a.time DESC, a.rowid DESC LIMIT 1")) {
            for (Hit h : hits.subList(Math.min(offset, hits.size()), Math.min(offset + limit, hits.size()))) {
                bindPos(ps, world, h.x(), h.y(), h.z());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        entries.add(new NearPage.Entry(h.x(), h.y(), h.z(), h.count(), row(rs)));
                    }
                }
            }
        }
        return new NearPage(hits.size(), entries);
    }

    public long rowCount() throws SQLException {
        return scalar(read, "SELECT COUNT(*) FROM actions");
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
