package dev.sebi.secureprotect.storage;

import java.util.List;

public record HistoryPage(List<HistoryRow> rows, int total) {
}
