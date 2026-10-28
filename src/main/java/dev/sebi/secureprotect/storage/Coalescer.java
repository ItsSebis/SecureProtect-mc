package dev.sebi.secureprotect.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Drops repeated identical interactions (button/lever spam) within a short window. Writer thread only. */
final class Coalescer {
    private record Key(UUID user, String world, int x, int y, int z, String material) {
    }

    private static final long WINDOW_SECONDS = 2;
    private static final int CLEANUP_SIZE = 4096;

    private final Map<Key, Long> last = new HashMap<>();

    List<RawEntry> filter(List<RawEntry> batch) {
        List<RawEntry> out = new ArrayList<>(batch.size());
        for (RawEntry e : batch) {
            if (e.action() != Action.INTERACT) {
                out.add(e);
                continue;
            }
            Key key = new Key(e.user(), e.world(), e.x(), e.y(), e.z(), e.material());
            Long prev = last.get(key);
            if (prev != null && e.time() - prev < WINDOW_SECONDS) {
                continue;
            }
            last.put(key, e.time());
            out.add(e);
        }
        if (last.size() > CLEANUP_SIZE && !batch.isEmpty()) {
            long now = batch.getLast().time();
            last.values().removeIf(t -> now - t >= WINDOW_SECONDS);
        }
        return out;
    }
}
