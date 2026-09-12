package io.aria.conductor.app;

import io.aria.conductor.execution.kanban.KanbanItem;
import io.aria.conductor.execution.kanban.KanbanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-consistency smoke for the kanban HITL migration: boots the application
 * context (Flyway applies V52/V53) and round-trips the new kanban_items columns
 * through JPA, guarding the entity &lt;-&gt; Flyway column mapping end to end.
 * Backfill semantics are covered by the migration's own UPDATE guards, applied
 * once per environment.
 */
@SpringBootTest
@ActiveProfiles({"test", "noop-llm"})
class KanbanHitlMigrationTest {

    @Autowired
    private KanbanRepository kanbanRepository;

    @Test
    void kanbanItemsCarryNewColumns() {
        KanbanItem item = kanbanRepository.save(KanbanItem.builder()
                .title("migration probe")
                .agentTemplateId("ba-agent")
                .lastError("boom")
                .build());
        KanbanItem reloaded = kanbanRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getAgentTemplateId()).isEqualTo("ba-agent");
        assertThat(reloaded.getLastError()).isEqualTo("boom");
    }

    @Test
    void kanbanItemsCarryOptimisticLockVersionColumn() {
        // V54: version column exists (Flyway) and is managed by JPA
        // (@Version) — inserted rows start at 0 and increment on update.
        KanbanItem item = kanbanRepository.save(KanbanItem.builder()
                .title("version probe")
                .build());
        KanbanItem reloaded = kanbanRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isNotNull();
        assertThat(reloaded.getVersion()).isZero();

        reloaded.setTitle("version probe v2");
        KanbanItem updated = kanbanRepository.save(reloaded);
        kanbanRepository.flush();
        assertThat(updated.getVersion()).isEqualTo(1L);
    }
}
