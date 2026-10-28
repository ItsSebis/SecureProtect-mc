package dev.sebi.secureprotect.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
    private static final UUID SEBI = UUID.randomUUID();

    @TempDir
    Path dir;
    Database db;

    @BeforeEach
    void open() throws Exception {
        db = new Database(dir.resolve("history.db"));
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    private static RawEntry entry(long time, Action action, String material, int amount) {
        return new RawEntry(time, SEBI, "Sebi", "world", 1, 64, 2, action, material, null, amount);
    }

    @Test
    void insertsAndQueriesNewestFirst() throws Exception {
        db.insertBatch(List.of(
                entry(100, Action.TAKE, "DIAMOND", 64),
                entry(110, Action.PUT, "DIAMOND", 32),
                entry(120, Action.BREAK, "DIAMOND_BLOCK", 1)));

        HistoryPage containers = db.query("world", 1, 64, 2, Action.CONTAINER, 10, 0);
        assertEquals(2, containers.total());
        assertEquals(Action.PUT, containers.rows().get(0).action());
        assertEquals(32, containers.rows().get(0).amount());
        assertEquals("Sebi", containers.rows().get(0).user());
        assertEquals("DIAMOND", containers.rows().get(1).material());

        assertEquals(1, db.query("world", 1, 64, 2, Action.BLOCK, 10, 0).total());
        assertEquals(0, db.query("world", 9, 64, 2, Action.ALL, 10, 0).total());
        assertEquals(0, db.query("nether", 1, 64, 2, Action.ALL, 10, 0).total());
    }

    @Test
    void paginates() throws Exception {
        for (int i = 0; i < 5; i++) {
            db.insertBatch(List.of(entry(100 + i, Action.PLACE, "STONE", 1)));
        }
        HistoryPage second = db.query("world", 1, 64, 2, Set.of(Action.PLACE), 2, 2);
        assertEquals(5, second.total());
        assertEquals(2, second.rows().size());
        assertEquals(102, second.rows().get(0).time());
    }

    @Test
    void renamedPlayerShowsNewName() throws Exception {
        db.insertBatch(List.of(entry(100, Action.BREAK, "STONE", 1)));
        db.insertBatch(List.of(new RawEntry(200, SEBI, "SebiNew", "world", 1, 64, 2, Action.PLACE, "STONE", null, 1)));
        assertEquals("SebiNew", db.query("world", 1, 64, 2, Action.ALL, 10, 0).rows().get(0).user());
    }

    @Test
    void purgesByAgeInChunks() throws Exception {
        db.insertBatch(List.of(entry(10, Action.BREAK, "STONE", 1), entry(20, Action.BREAK, "STONE", 1),
                entry(30, Action.BREAK, "STONE", 1), entry(500, Action.BREAK, "STONE", 1)));
        assertEquals(2, db.deleteOlderThan(100, 2));
        assertEquals(1, db.deleteOlderThan(100, 2));
        assertEquals(1, db.rowCount());
    }

    @Test
    void sizeCapDeletesOldestFirstAndShrinksFile() throws Exception {
        for (int batch = 0; batch < 20; batch++) {
            List<RawEntry> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 5_000; i++) {
                rows.add(new RawEntry(batch * 5_000L + i, SEBI, "Sebi", "world", i, 64, batch, Action.PLACE, "STONE", null, 1));
            }
            db.insertBatch(rows);
        }
        long before = db.usedBytes();
        assertEquals(100_000, db.rowCount());
        assertEquals(60_000, db.deleteOldest(60_000));
        assertTrue(db.usedBytes() < before * 0.7, "used bytes should drop with the rows");
        assertEquals(0, db.query("world", 0, 64, 0, Action.ALL, 1, 0).total(), "oldest row (time 0) is gone");
        assertEquals(1, db.query("world", 4_999, 64, 19, Action.ALL, 1, 0).total(), "newest row is kept");
        long fileBefore = db.fileBytes();
        while (db.freePages() > 0) {
            db.shrink(5_000);
        }
        assertTrue(db.fileBytes() < fileBefore, "file should shrink after incremental vacuum");
    }

    @Test
    void coalescerDropsInteractSpamOnly() {
        Coalescer c = new Coalescer();
        List<RawEntry> out = c.filter(List.of(
                entry(100, Action.INTERACT, "LEVER", 1),
                entry(101, Action.INTERACT, "LEVER", 1),
                entry(101, Action.BREAK, "LEVER", 1),
                entry(103, Action.INTERACT, "LEVER", 1)));
        assertEquals(3, out.size());
    }

    @Test
    void queueDropsOldestWhenFull() throws Exception {
        try (Storage storage = new Storage(db, java.util.logging.Logger.getAnonymousLogger(), 1000, () -> 0, () -> 0L)) {
            for (int i = 0; i < 1_500; i++) {
                storage.log(entry(i, Action.BREAK, "STONE", 1));
            }
            assertTrue(storage.queueDepth() <= 1000);
            assertEquals(500, storage.droppedEntries());
        }
    }

    private static RawEntry book(long time, int x, byte[] data) {
        return new RawEntry(time, SEBI, "Sebi", "world", x, 64, 2, Action.TAKE, "ENCHANTED_BOOK", () -> data, 1);
    }

    @Test
    void storesItemDataOncePerDistinctItem() throws Exception {
        byte[] mending = {1, 2, 3};
        byte[] sharpness = {9, 9};
        db.insertBatch(List.of(book(100, 1, mending), book(101, 1, mending), book(102, 1, sharpness),
                entry(103, Action.TAKE, "ENCHANTED_BOOK", 1)));

        List<HistoryRow> rows = db.query("world", 1, 64, 2, Action.CONTAINER, 10, 0).rows();
        assertEquals(4, rows.size());
        assertEquals(0, rows.get(0).itemData().length, "plain book has no data");
        assertTrue(java.util.Arrays.equals(sharpness, rows.get(1).itemData()));
        assertTrue(java.util.Arrays.equals(mending, rows.get(2).itemData()));
        assertTrue(java.util.Arrays.equals(mending, rows.get(3).itemData()));
        db.insertBatch(List.of(book(104, 1, mending)));
        // 3 distinct items: plain book, mending, sharpness
        try (var st = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("history.db")).createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM items")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
    }

    @Test
    void oversizedItemDataFallsBackToPlainMaterial() throws Exception {
        db.close();
        db = new Database(dir.resolve("small.db"), 4);
        db.insertBatch(List.of(book(100, 1, new byte[]{1, 2, 3, 4, 5})));
        assertEquals(0, db.query("world", 1, 64, 2, Action.ALL, 10, 0).rows().getFirst().itemData().length);
    }

    @Test
    void unusedItemDataIsCleanedUpAfterPurge() throws Exception {
        db.insertBatch(List.of(book(10, 1, new byte[]{7}), book(500, 2, new byte[]{8})));
        db.deleteOlderThan(100, 10);
        assertEquals(1, db.deleteUnusedItems());
        // the cache must not hand out the deleted id again
        db.insertBatch(List.of(book(600, 3, new byte[]{7})));
        assertTrue(java.util.Arrays.equals(new byte[]{7}, db.query("world", 3, 64, 2, Action.ALL, 10, 0).rows().getFirst().itemData()));
    }

    @Test
    void migratesTheFirstSchemaKeepingHistory() throws Exception {
        db.close();
        Path old = dir.resolve("old.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + old); var st = c.createStatement()) {
            st.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL)");
            st.execute("CREATE TABLE materials(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE worlds(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE actions(time INTEGER NOT NULL, user INTEGER NOT NULL, world INTEGER NOT NULL, x INTEGER NOT NULL,"
                    + " y INTEGER NOT NULL, z INTEGER NOT NULL, action INTEGER NOT NULL, material INTEGER NOT NULL, amount INTEGER NOT NULL)");
            st.execute("INSERT INTO users VALUES(1, '" + SEBI + "', 'Sebi')");
            st.execute("INSERT INTO materials VALUES(1, 'DIAMOND')");
            st.execute("INSERT INTO worlds VALUES(1, 'world')");
            st.execute("INSERT INTO actions VALUES(100, 1, 1, 1, 64, 2, 3, 1, 64)");
        }
        db = new Database(old);
        HistoryRow row = db.query("world", 1, 64, 2, Action.ALL, 10, 0).rows().getFirst();
        assertEquals("DIAMOND", row.material());
        assertEquals(64, row.amount());
        db.insertBatch(List.of(entry(200, Action.PUT, "DIAMOND", 1)));
        assertEquals(2, db.query("world", 1, 64, 2, Action.ALL, 10, 0).total());
    }

    @Test
    void firstWithHistoryReturnsTheNearestPositionOnTheRay() throws Exception {
        db.insertBatch(List.of(entry(100, Action.BREAK, "STONE", 1)));
        List<int[]> ray = List.of(new int[]{1, 64, 0}, new int[]{1, 64, 1}, new int[]{1, 64, 2}, new int[]{1, 64, 3});
        assertEquals(2, db.firstWithHistory("world", ray));
        assertEquals(-1, db.firstWithHistory("world", ray.subList(0, 2)));
        assertEquals(-1, db.firstWithHistory("nether", ray));
    }

    @Test
    void nearListsChangedSpotsNewestFirstWithinRadius() throws Exception {
        db.insertBatch(List.of(
                new RawEntry(100, SEBI, "Sebi", "world", 10, 64, 10, Action.BREAK, "STONE", null, 1),
                new RawEntry(200, SEBI, "Sebi", "world", 12, 64, 10, Action.PLACE, "DIRT", null, 1),
                new RawEntry(300, SEBI, "Sebi", "world", 12, 64, 10, Action.BREAK, "DIRT", null, 1),
                new RawEntry(400, SEBI, "Sebi", "world", 40, 64, 10, Action.BREAK, "STONE", null, 1),
                new RawEntry(500, SEBI, "Sebi", "world", 11, 90, 10, Action.BREAK, "STONE", null, 1)));

        NearPage page = db.near("world", 10, 64, 10, 5, 10, 0);
        assertEquals(2, page.total(), "x=40 and y=90 are outside the radius");
        assertEquals(12, page.entries().get(0).x());
        assertEquals(2, page.entries().get(0).count());
        assertEquals(Action.BREAK, page.entries().get(0).latest().action());
        assertEquals(10, page.entries().get(1).x());

        assertEquals(1, db.near("world", 10, 64, 10, 5, 1, 1).entries().size());
        assertEquals(0, db.near("nether", 10, 64, 10, 5, 10, 0).total());
    }
}
