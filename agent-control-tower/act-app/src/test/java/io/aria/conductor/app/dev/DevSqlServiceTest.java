package io.aria.conductor.app.dev;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevSqlServiceTest {

    private JdbcTemplate jdbcTemplate;
    private RecordingTransactionOperations transactionOperations;
    private DevSqlService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionOperations = new RecordingTransactionOperations();
        service = new DevSqlService(jdbcTemplate, transactionOperations, List.of("h2"), "jdbc:h2:file:./data/act_db");
    }

    @Test
    void selectReturnsColumnsRowsRowCountAndTruncatedFalse() {
        List<Map<String, Object>> rows = List.of(
                orderedRow(1, "alpha"),
                orderedRow(2, "beta")
        );
        mockSelectQuery("select id, name from agents", rows);

        DevSqlResponse response = service.execute("select id, name from agents");

        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.columns()).containsExactly("ID", "NAME");
        assertThat(response.rows()).containsExactlyElementsOf(rows);
        assertThat(response.rowCount()).isEqualTo(2);
        assertThat(response.truncated()).isFalse();
        assertThat(response.error()).isNull();
    }

    @Test
    void selectSetsTruncatedWhenRowCapIsExceeded() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            rows.add(orderedRow(i, "agent-" + i));
        }
        mockSelectQuery("select id, name from agents", rows);

        DevSqlResponse response = service.execute("select id, name from agents");

        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.rows()).hasSize(200);
        assertThat(response.rowCount()).isEqualTo(201);
        assertThat(response.truncated()).isTrue();
        verify(jdbcTemplate, never()).queryForList(anyString());
        verify(jdbcTemplate).query(eq("select id, name from agents"), org.mockito.ArgumentMatchers.<ResultSetExtractor<DevSqlResponse>>any());
    }

    @Test
    void rejectsMultipleStatementsInOneRequest() {
        DevSqlResponse response = service.execute("select 1; select 2");

        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.error()).contains("single SQL statement");
        assertThat(response.rows()).isEmpty();
        assertThat(response.columns()).isEmpty();
        assertThat(response.rowCount()).isZero();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void detectsCommentPrefixedSelectAsSelect() {
        String sql = "  -- heading comment\n select id, name from agents";
        String executedSql = "-- heading comment\n select id, name from agents";
        List<Map<String, Object>> rows = List.of(orderedRow(1, "alpha"));
        mockSelectQuery(executedSql, rows);

        DevSqlResponse response = service.execute(sql);

        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.rows()).containsExactlyElementsOf(rows);
        assertThat(response.columns()).containsExactly("ID", "NAME");
    }

    @Test
    void commitsRequestTransactionOnSuccess() {
        when(jdbcTemplate.update("update runs set status='FAILED' where id='r1'"))
                .thenReturn(1);

        DevSqlResponse response = service.execute("update runs set status='FAILED' where id='r1'");

        assertThat(response.error()).isNull();
        assertThat(transactionOperations.committed).isTrue();
        assertThat(transactionOperations.rolledBack).isFalse();
    }

    @Test
    void rollsBackRequestTransactionOnFailure() {
        when(jdbcTemplate.update("update runs set status='FAILED' where id='r1'"))
                .thenThrow(new BadSqlGrammarException("execute", "update runs set status='FAILED' where id='r1'", new SQLException("bad sql")));

        DevSqlResponse response = service.execute("update runs set status='FAILED' where id='r1'");

        assertThat(response.error()).contains("bad SQL grammar");
        assertThat(transactionOperations.committed).isFalse();
        assertThat(transactionOperations.rolledBack).isTrue();
    }

    @Test
    void updateReturnsAffectedRowCount() {
        when(jdbcTemplate.update("update runs set status='FAILED' where id='r1'"))
                .thenReturn(1);

        DevSqlResponse response = service.execute("update runs set status='FAILED' where id='r1'");

        assertThat(response.statementType()).isEqualTo("UPDATE");
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(response.rows()).isEmpty();
        assertThat(response.columns()).isEmpty();
        assertThat(response.truncated()).isFalse();
        assertThat(response.error()).isNull();
    }

    @Test
    void ddlReturnsSuccessWithoutRows() {
        doNothing().when(jdbcTemplate).execute("create table demo(id int)");

        DevSqlResponse response = service.execute("create table demo(id int)");

        assertThat(response.statementType()).isEqualTo("DDL");
        assertThat(response.rowCount()).isEqualTo(0);
        assertThat(response.rows()).isEmpty();
        assertThat(response.columns()).isEmpty();
        assertThat(response.truncated()).isFalse();
        assertThat(response.error()).isNull();
    }

    @Test
    void invalidSqlBubblesAsError() {
        when(jdbcTemplate.query(eq("select from broken"), org.mockito.ArgumentMatchers.<ResultSetExtractor<DevSqlResponse>>any()))
                .thenThrow(new BadSqlGrammarException("execute", "select from broken", new SQLException("bad sql")));

        DevSqlResponse response = service.execute("select from broken");

        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.rows()).isEmpty();
        assertThat(response.columns()).isEmpty();
        assertThat(response.rowCount()).isEqualTo(0);
        assertThat(response.truncated()).isFalse();
        assertThat(response.error()).contains("select from broken");
    }

    private Map<String, Object> orderedRow(int id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", id);
        row.put("NAME", name);
        return row;
    }

    private void mockSelectQuery(String sql, List<Map<String, Object>> rows) {
        when(jdbcTemplate.query(eq(sql), org.mockito.ArgumentMatchers.<ResultSetExtractor<DevSqlResponse>>any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(mockResultSet(rows));
                });
    }

    private ResultSet mockResultSet(List<Map<String, Object>> rows) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet());
        AtomicInteger cursor = new AtomicInteger(-1);

        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            when(metadata.getColumnLabel(i + 1)).thenReturn(column);
            when(metadata.getColumnName(i + 1)).thenReturn(column);
        }
        when(resultSet.next()).thenAnswer(invocation -> cursor.incrementAndGet() < rows.size());
        when(resultSet.getObject(anyInt())).thenAnswer(invocation -> {
            int columnIndex = invocation.getArgument(0, Integer.class) - 1;
            return rows.get(cursor.get()).get(columns.get(columnIndex));
        });

        return resultSet;
    }

    private static final class RecordingTransactionOperations implements TransactionOperations {

        private boolean committed;
        private boolean rolledBack;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            try {
                T result = action.doInTransaction(new SimpleTransactionStatus());
                committed = true;
                return result;
            } catch (RuntimeException error) {
                rolledBack = true;
                throw error;
            }
        }
    }
}
