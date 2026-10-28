package dev.sebi.secureprotect.storage;

/** @param itemData serialized item, empty for a plain item or a block. */
public record HistoryRow(long time, String user, Action action, String material, byte[] itemData, int amount) {
}
