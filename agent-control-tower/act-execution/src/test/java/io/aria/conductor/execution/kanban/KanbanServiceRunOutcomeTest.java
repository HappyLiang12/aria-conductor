package io.aria.conductor.execution.kanban;

import io.aria.conductor.agent.eligibility.AgentPickupEligibility;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

class KanbanServiceRunOutcomeTest {

    @Test
    void listEnrichesEachLinkedRunOutcomeFromOneBatchedQuery() {
        KanbanRepository kanbanRepository = Mockito.mock(KanbanRepository.class);
        ApprovalRepository approvalRepository = Mockito.mock(ApprovalRepository.class);
        RunRepository runRepository = Mockito.mock(RunRepository.class);
        KanbanService service = new KanbanService(kanbanRepository,
                Mockito.mock(ApplicationEventPublisher.class), runRepository, approvalRepository,
                Mockito.mock(AgentRepository.class), new AgentPickupEligibility());

        UUID completedRun = UUID.randomUUID();
        UUID failedRun = UUID.randomUUID();
        KanbanItem completedCard = item("c1", completedRun.toString());
        KanbanItem failedCard = item("c2", failedRun.toString());
        KanbanItem unlinked = item("c3", null);
        KanbanItem ghost = item("c4", UUID.randomUUID().toString());
        when(kanbanRepository.findAll()).thenReturn(List.of(completedCard, failedCard, unlinked, ghost));
        when(approvalRepository.countPendingByKanbanItemIds(anyCollection())).thenReturn(List.of());
        when(runRepository.findAllById(anyCollection())).thenReturn(List.of(
                run(completedRun, RunStatus.COMPLETED), run(failedRun, RunStatus.FAILED)));

        List<KanbanItem> items = service.list(null);

        assertThat(items).extracting(KanbanItem::getRunOutcome)
                .containsExactly("COMPLETED", "FAILED", "UNKNOWN", "UNKNOWN");
    }

    @Test
    void runOutcomeMapsEveryStatusClass() {
        assertThat(KanbanService.runOutcome(RunStatus.RUNNING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.PENDING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.INITIALIZING)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.PAUSED)).isEqualTo("ACTIVE");
        assertThat(KanbanService.runOutcome(RunStatus.CANCELLED)).isEqualTo("CANCELLED");
        assertThat(KanbanService.runOutcome(RunStatus.ABORTED)).isEqualTo("CANCELLED");
        assertThat(KanbanService.runOutcome(RunStatus.COMPLETED)).isEqualTo("COMPLETED");
        assertThat(KanbanService.runOutcome(RunStatus.FAILED)).isEqualTo("FAILED");
        assertThat(KanbanService.runOutcome(null)).isEqualTo("UNKNOWN");
    }

    private static KanbanItem item(String id, String linkedRunId) {
        KanbanItem item = new KanbanItem();
        item.setId(id);
        item.setStatus(KanbanStatus.REVIEW);
        item.setLinkedRunId(linkedRunId);
        return item;
    }

    private static Run run(UUID id, RunStatus status) {
        return Run.builder().id(id).status(status).build();
    }
}
