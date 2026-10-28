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
        return new RawEntry(time, SEBI, "Sebi", "world", 1, 64, 2, action, material, amount);
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
        db.insertBatch(List.of(new RawEntry(200, SEBI, "SebiNew", "world", 1, 64, 2, Action.PLACE, "STONE", 1)));
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
                rows.add(new RawEntry(batch * 5_000L + i, SEBI, "Sebi", "world", i, 64, batch, Action.PLACE, "STONE", 1));
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
}
