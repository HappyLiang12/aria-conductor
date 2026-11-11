package io.aria.conductor.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V81 is the memory kernel. Proven on a fresh H2 (MODE=MySQL) database with the real
 * Flyway chain, so this guards the migration itself rather than Hibernate-generated DDL:
 * the six tables, their indexes and unique constraints, the proposal-idempotency
 * columns, and the provider sensitivity ceiling P4 reads.
 */
class MemoryMigrationIntegrationTest extends BaseH2IntegrationTest {

    private static final List<String> MEMORY_TABLES = List.of(
            "memory_entities", "memory_relations", "memory_facts",
            "memory_assertions", "memory_knowledge_links", "context_manifests");

    private static final List<String> MEMORY_INDEXES = List.of(
            "idx_memory_entity_kind_status", "idx_memory_relation_from",
            "idx_memory_relation_to", "idx_memory_fact_entity_key",
            "idx_memory_assertion_subject", "idx_memory_knowledge_link_knowledge");

    private static final List<String> MEMORY_UNIQUE_CONSTRAINTS = List.of(
            "uq_memory_entity", "uq_memory_relation_proposal", "uq_memory_fact_proposal",
            "uq_memory_knowledge_link", "uq_context_manifest_turn");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void memoryTablesAndProviderCeilingExist() {
        for (String table : MEMORY_TABLES) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.tables where lower(table_name) = ?",
                    Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
        Integer ceiling = jdbc.queryForObject(
                "select count(*) from information_schema.columns " +
                "where lower(table_name) = 'llm_providers' and lower(column_name) = 'sensitivity_ceiling'",
                Integer.class);
        assertThat(ceiling).isEqualTo(1);
    }

    @Test
    void providerCeilingIsNotNullAndDefaultsToInternal() {
        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns where lower(table_name) = 'llm_providers' "
                        + "and lower(column_name) = 'sensitivity_ceiling'", String.class);
        String defaultValue = jdbc.queryForObject(
                "select column_default from information_schema.columns where lower(table_name) = 'llm_providers' "
                        + "and lower(column_name) = 'sensitivity_ceiling'", String.class);

        assertThat(nullable).as("a provider without an explicit ceiling must never be a null ceiling")
                .isEqualTo("NO");
        assertThat(defaultValue).as("the safe default is INTERNAL").contains("INTERNAL");
    }

    @Test
    void proposalHashColumnsExistOnRelationsAndFacts() {
        for (String table : List.of("memory_relations", "memory_facts")) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.columns where lower(table_name) = ? "
                            + "and lower(column_name) = 'proposal_hash'", Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    @Test
    void uniqueConstraintsAndIndexesExist() {
        for (String constraint : MEMORY_UNIQUE_CONSTRAINTS) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.table_constraints "
                            + "where lower(constraint_name) = ? and constraint_type = 'UNIQUE'",
                    Integer.class, constraint);
            assertThat(count).as(constraint).isEqualTo(1);
        }
        for (String index : MEMORY_INDEXES) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from information_schema.indexes where lower(index_name) = ?",
                    Integer.class, index);
            assertThat(count).as(index).isEqualTo(1);
        }
    }
}
