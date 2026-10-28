package dev.sebi.secureprotect.storage;

public record HistoryRow(long time, String user, Action action, String material, int amount) {
}
