package io.aria.conductor.app.dev;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Profile("h2")
public class DevSqlService {

    private static final int SELECT_ROW_CAP = 200;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;
    private final String profile;
    private final String database;

    @Autowired
    public DevSqlService(JdbcTemplate jdbcTemplate,
                         PlatformTransactionManager transactionManager,
                         Environment environment,
                         @Value("${spring.datasource.url:unknown}") String datasourceUrl) {
        this(jdbcTemplate, new TransactionTemplate(transactionManager), List.of(environment.getActiveProfiles()), datasourceUrl);
    }

    DevSqlService(JdbcTemplate jdbcTemplate,
                  TransactionOperations transactionOperations,
                  List<String> activeProfiles,
                  String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionOperations = transactionOperations;
        this.profile = activeProfiles.isEmpty() ? "default" : String.join(",", activeProfiles);
        this.database = datasourceUrl == null || datasourceUrl.isBlank() ? "unknown" : datasourceUrl;
    }

    public DevSqlResponse execute(String sql) {
        String statement = normalizeStatement(sql);
        String statementType = detectStatementType(statement);
        Instant executedAt = Instant.now();

        try {
            validateSingleStatement(statement);
            return transactionOperations.execute(status -> executeInTransaction(statement, statementType, executedAt));
        } catch (RuntimeException error) {
            return new DevSqlResponse(statementType, List.of(), List.of(), 0, false, executedAt, profile, database,
                    statement + ": " + error.getMessage());
        }
    }

    private DevSqlResponse executeInTransaction(String statement, String statementType, Instant executedAt) {
        if ("SELECT".equals(statementType)) {
            return jdbcTemplate.query(statement,
                    (ResultSetExtractor<DevSqlResponse>) resultSet -> buildSelectResponse(statementType, executedAt, resultSet));
        }

        if ("INSERT".equals(statementType) || "UPDATE".equals(statementType) || "DELETE".equals(statementType)) {
            int rowCount = jdbcTemplate.update(statement);
            return new DevSqlResponse(statementType, List.of(), List.of(), rowCount, false, executedAt, profile, database, null);
        }

        jdbcTemplate.execute(statement);
        return new DevSqlResponse("DDL", List.of(), List.of(), 0, false, executedAt, profile, database, null);
    }

    private DevSqlResponse buildSelectResponse(String statementType, Instant executedAt, java.sql.ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<String> columns = columns(metadata);
        ColumnMapRowMapper rowMapper = new ColumnMapRowMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        int rowCount = 0;
        boolean truncated = false;

        while (resultSet.next()) {
            rowCount++;
            if (rowCount <= SELECT_ROW_CAP) {
                rows.add(rowMapper.mapRow(resultSet, rowCount));
                continue;
            }
            truncated = true;
        }

        return new DevSqlResponse(statementType, columns, List.copyOf(rows), rowCount, truncated, executedAt, profile, database, null);
    }

    private String detectStatementType(String statement) {
        String normalized = stripLeadingNoise(statement).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("select") || normalized.startsWith("with")) {
            return "SELECT";
        }
        if (normalized.startsWith("insert")) {
            return "INSERT";
        }
        if (normalized.startsWith("update")) {
            return "UPDATE";
        }
        if (normalized.startsWith("delete")) {
            return "DELETE";
        }
        return "DDL";
    }

    private String normalizeStatement(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (!trimmed.endsWith(";")) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
    }

    private void validateSingleStatement(String statement) {
        // ponytail: semicolons inside literals stay unsupported; allow one trailing terminator only.
        if (statement.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Only a single SQL statement is allowed per request");
        }
    }

    private String stripLeadingNoise(String statement) {
        int offset = 0;
        while (offset < statement.length()) {
            while (offset < statement.length() && Character.isWhitespace(statement.charAt(offset))) {
                offset++;
            }
            if (statement.startsWith("--", offset)) {
                int lineEnd = statement.indexOf('\n', offset + 2);
                offset = lineEnd < 0 ? statement.length() : lineEnd + 1;
                continue;
            }
            if (statement.startsWith("/*", offset)) {
                int blockEnd = statement.indexOf("*/", offset + 2);
                return blockEnd < 0 ? "" : stripLeadingNoise(statement.substring(blockEnd + 2));
            }
            return statement.substring(offset);
        }
        return "";
    }

    private List<String> columns(ResultSetMetaData metadata) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(metadata.getColumnLabel(i));
        }
        return List.copyOf(columns);
    }
}
