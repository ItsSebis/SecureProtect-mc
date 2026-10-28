package dev.sebi.secureprotect.storage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the queue and the two database threads. {@link #log} is the only call made on the main thread
 * and does no I/O.
 */
public final class Storage implements AutoCloseable {
    private static final int FLUSH_BATCH = 10_000;
    private static final int PURGE_CHUNK = 10_000;
    private static final int SHRINK_PAGES = 5_000;
    private static final long CHUNK_PAUSE_MS = 100;

    private final Database db;
    private final Logger log;
    private final int queueLimit;
    private final IntSupplier retentionDays;
    private final LongSupplier maxBytes;

    private final ConcurrentLinkedQueue<RawEntry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final Coalescer coalescer = new Coalescer();
    private final AtomicBoolean retentionRunning = new AtomicBoolean();

    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "SecureProtect-writer"));
    private final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "SecureProtect-reader"));

    public Storage(Database db, Logger log, int queueLimit, IntSupplier retentionDays, LongSupplier maxBytes) {
        this.db = db;
        this.log = log;
        this.queueLimit = queueLimit;
        this.retentionDays = retentionDays;
        this.maxBytes = maxBytes;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start(long flushIntervalMs) {
        writer.scheduleWithFixedDelay(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        writer.scheduleWithFixedDelay(this::runRetention, 1, 60, TimeUnit.MINUTES);
    }

    /** Main thread. Never blocks. */
    public void log(RawEntry entry) {
        if (queued.incrementAndGet() > queueLimit) {
            if (queue.poll() != null) {
                queued.decrementAndGet();
            }
            if (dropped.incrementAndGet() % 10_000 == 1) {
                log.warning("History queue is full, dropping the oldest entries (" + dropped.get() + " dropped so far)."
                        + " The database cannot keep up.");
            }
        }
        queue.add(entry);
    }

    public int queueDepth() {
        return queued.get();
    }

    public long droppedEntries() {
        return dropped.get();
    }

    // ---- writer thread ----

    private void flush() {
        try {
            List<RawEntry> batch;
            while (!(batch = drain()).isEmpty()) {
                db.insertBatch(coalescer.filter(batch));
            }
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Failed to write history batch", t);
        }
    }

    private List<RawEntry> drain() {
        List<RawEntry> batch = new ArrayList<>();
        RawEntry e;
        while (batch.size() < FLUSH_BATCH && (e = queue.poll()) != null) {
            queued.decrementAndGet();
            batch.add(e);
        }
        return batch;
    }

    // ---- lookups ----

    /** Flushes pending entries first so a lookup sees what just happened. */
    public CompletableFuture<HistoryPage> query(String world, int x, int y, int z, Set<Action> actions, int limit, int offset) {
        return CompletableFuture.runAsync(this::flush, writer).thenApplyAsync(ignored -> {
            try {
                return db.query(world, x, y, z, actions, limit, offset);
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }, reader);
    }

    public record Stats(long rows, long fileBytes, int queued, long dropped) {
    }

    public CompletableFuture<Stats> stats() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new Stats(db.rowCount(), db.fileBytes(), queued.get(), dropped.get());
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }, reader);
    }

    // ---- retention (chunked so the writer is never blocked for long) ----

    /** Deletes everything older than {@code days}; the future completes with the number of deleted rows. */
    public CompletableFuture<Long> purgeOlderThan(int days) {
        CompletableFuture<Long> done = new CompletableFuture<>();
        long cutoff = System.currentTimeMillis() / 1000 - days * 86_400L;
        writer.execute(() -> purgeStep(cutoff, 0, done));
        return done;
    }

    private void purgeStep(long cutoff, long total, CompletableFuture<Long> done) {
        try {
            flush();
            int n = db.deleteOlderThan(cutoff, PURGE_CHUNK);
            if (n == PURGE_CHUNK) {
                writer.schedule(() -> purgeStep(cutoff, total + n, done), CHUNK_PAUSE_MS, TimeUnit.MILLISECONDS);
            } else {
                done.complete(total + n);
                shrinkStep();
            }
        } catch (Throwable t) {
            done.completeExceptionally(t);
        }
    }

    private void runRetention() {
        if (!retentionRunning.compareAndSet(false, true)) {
            return;
        }
        int days = retentionDays.getAsInt();
        if (days > 0) {
            CompletableFuture<Long> age = new CompletableFuture<>();
            age.whenComplete((n, err) -> {
                if (err != null) {
                    log.log(Level.WARNING, "Age based purge failed", err);
                } else if (n > 0) {
                    log.info("Purged " + n + " history entries older than " + days + " days.");
                }
                writer.execute(() -> sizeStep(false));
            });
            long cutoff = System.currentTimeMillis() / 1000 - days * 86_400L;
            purgeStep(cutoff, 0, age);
        } else {
            sizeStep(false);
        }
    }

    /** Once over the cap, keeps deleting the oldest rows until usage is back under 90% of it. */
    private void sizeStep(boolean enforcing) {
        try {
            long max = maxBytes.getAsLong();
            long used = db.usedBytes();
            boolean over = max > 0 && (enforcing ? used > max * 0.9 : used > max);
            if (!over) {
                retentionRunning.set(false);
                shrinkStep();
                return;
            }
            if (!enforcing) {
                log.warning("History is over its size cap, dropping the oldest entries.");
            }
            if (db.deleteOldest(PURGE_CHUNK) == 0) {
                retentionRunning.set(false);
                shrinkStep();
                return;
            }
            writer.schedule(() -> sizeStep(true), CHUNK_PAUSE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            retentionRunning.set(false);
            log.log(Level.WARNING, "Size based purge failed", t);
        }
    }

    private void shrinkStep() {
        try {
            if (db.freePages() > 1_000) {
                db.shrink(SHRINK_PAGES);
                writer.schedule(this::shrinkStep, CHUNK_PAUSE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "Shrinking the history file failed", t);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        reader.shutdown();
        try {
            writer.awaitTermination(10, TimeUnit.SECONDS);
            reader.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        flush();
        try {
            db.close();
        } catch (SQLException ex) {
            log.log(Level.WARNING, "Failed to close the history database", ex);
        }
    }
}
