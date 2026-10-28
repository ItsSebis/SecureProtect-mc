package dev.sebi.secureprotect.storage;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * What the main thread captures. Strings and UUIDs are already-existing references, so building one
 * costs no I/O; the writer thread interns them into ids.
 *
 * @param itemData serializes the full item (enchantments, name, ...) when called, or null for a plain
 *                 item. It runs on the writer thread, never on the main thread.
 */
public record RawEntry(long time, UUID user, String userName, String world, int x, int y, int z,
                       Action action, String material, Supplier<byte[]> itemData, int amount) {
}
