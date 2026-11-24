package io.aria.conductor.common.context.qmd;

import java.util.List;

public record QmdResult(List<QmdHit> hits, String degradedReason) {

    public QmdResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public static QmdResult empty() {
        return new QmdResult(List.of(), null);
    }

    public static QmdResult degraded(String reason) {
        return new QmdResult(List.of(), reason);
    }
}
