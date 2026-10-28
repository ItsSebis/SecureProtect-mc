package dev.sebi.secureprotect.storage;

import java.util.UUID;

/**
 * What the main thread captures. Strings and UUIDs are already-existing references, so building one
 * costs no I/O; the writer thread interns them into ids.
 */
public record RawEntry(long time, UUID user, String userName, String world, int x, int y, int z,
                       Action action, String material, int amount) {
}
