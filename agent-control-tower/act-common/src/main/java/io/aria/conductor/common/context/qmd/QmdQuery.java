package io.aria.conductor.common.context.qmd;

import io.aria.conductor.common.model.Sensitivity;

import java.util.List;

public record QmdQuery(
        String url,
        List<String> collections,
        String text,
        String scope,
        Sensitivity ceiling,
        int limit,
        double minScore,
        long timeoutMillis,
        String vaultDir) {

    public QmdQuery {
        collections = collections == null ? List.of() : List.copyOf(collections);
    }
}
