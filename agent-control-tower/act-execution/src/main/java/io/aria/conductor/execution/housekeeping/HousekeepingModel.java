package io.aria.conductor.execution.housekeeping;

import java.time.Instant;
import java.util.List;

/**
 * Housekeeping S2: request/response records for the cleanup feature
 * (scan preview, dry-run and execute receipt).
 */
public final class HousekeepingModel {

    private HousekeepingModel() {
    }

    /** One leftover item shown in a category preview. */
    public record CategoryItem(String id, String title, String status, String age) {
    }

    /** Aggregate for one category: selectable key, total count, bounded preview. */
    public record CategorySummary(String key, long count, List<CategoryItem> preview) {
    }

    public record ScanResult(List<CategorySummary> categories, Instant scannedAt) {
    }

    /** Ids the operator wants to keep, per category target type. */
    public record Exclusions(List<String> runIds, List<String> kanbanItemIds,
                             List<String> agentIds, List<String> approvalIds) {
        public static Exclusions empty() {
            return new Exclusions(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record HousekeepingRequest(List<String> categories, boolean includeStuck,
                                      Exclusions exclusions, boolean confirm) {
    }

    /** Per-category outcome of an execute batch. */
    public record CategoryReceipt(String key, int cleared, int failed, int skipped) {
    }

    public record HousekeepingReceipt(List<CategoryReceipt> categories, Instant executedAt) {
    }
}
