package io.aria.conductor.execution.kanban;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KanbanServiceAskCountTest {

    @Autowired private KanbanService kanbanService;
    @Autowired private KanbanRepository kanbanRepository;
    @Autowired private ApprovalRepository approvalRepository;

    @Test
    void createHonorsRequestedStatus() {
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("card").status(KanbanStatus.BACKLOG).build();
        assertThat(kanbanService.create(request).getStatus()).isEqualTo(KanbanStatus.BACKLOG);
    }

    @Test
    void createPersistsAgentTemplateId() {
        CreateKanbanItemRequest request = CreateKanbanItemRequest.builder()
                .title("assigned card").agentTemplateId("ba-agent").build();

        KanbanItem created = kanbanService.create(request);
        KanbanItem loaded = kanbanRepository.findById(created.getId()).orElseThrow();

        assertThat(loaded.getAgentTemplateId()).isEqualTo("ba-agent");
    }

    @Test
    void listPopulatesPendingAskCount() {
        KanbanItem card = kanbanService.create(CreateKanbanItemRequest.builder().title("reviewed").build());
        approvalRepository.save(Approval.builder()
                .runId(UUID.randomUUID()).status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .kanbanItemId(card.getId()).build());
        approvalRepository.save(Approval.builder()
                .runId(UUID.randomUUID()).status(ApprovalStatus.DENIED)
                .askType(Approval.AskType.QUESTION)
                .kanbanItemId(card.getId()).build());

        KanbanItem loaded = kanbanService.list(null).stream()
                .filter(i -> i.getId().equals(card.getId())).findFirst().orElseThrow();

        assertThat(loaded.getPendingAskCount()).isEqualTo(1);
    }
}
