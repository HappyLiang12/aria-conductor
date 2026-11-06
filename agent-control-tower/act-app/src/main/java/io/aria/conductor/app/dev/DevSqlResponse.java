package io.aria.conductor.app.dev;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DevSqlResponse(
        String statementType,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        boolean truncated,
        Instant executedAt,
        String profile,
        String database,
        String error
) {
}
