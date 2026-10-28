package dev.sebi.secureprotect.storage;

import java.util.List;

/** One page of positions near a point, most recently changed first. */
public record NearPage(int total, List<Entry> entries) {
    /** @param count total entries logged at this position; {@code latest} is the newest of them. */
    public record Entry(int x, int y, int z, int count, HistoryRow latest) {
    }
}
