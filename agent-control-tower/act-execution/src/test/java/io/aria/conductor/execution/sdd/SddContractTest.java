package io.aria.conductor.execution.sdd;

import io.aria.conductor.common.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SddContractTest {

    @Test
    void workflowStepHasKindAndAttemptCountDefaults() {
        WorkflowStep step = WorkflowStep.builder().build();
        assertThat(step.getKind()).isEqualTo(WorkflowStep.StepKind.GENERIC);
        assertThat(step.getAttemptCount()).isZero();
        assertThat(WorkflowStep.StepKind.values()).containsExactly(
                WorkflowStep.StepKind.GENERIC, WorkflowStep.StepKind.BA,
                WorkflowStep.StepKind.DEV, WorkflowStep.StepKind.QA,
                WorkflowStep.StepKind.CODE_REVIEW);
    }

    @Test
    void workflowChainSupportsWaitingApprovalAndReportLink() {
        assertThat(WorkflowChain.Status.values()).contains(WorkflowChain.Status.WAITING_APPROVAL);
        WorkflowChain chain = WorkflowChain.builder().build();
        assertThat(chain.getReportArtifactId()).isNull();
    }

    @Test
    void approvalCarriesTypeContentAndKnowledgeLink() {
        assertThat(Approval.ApprovalType.values())
                .containsExactly(Approval.ApprovalType.TOOL_CALL, Approval.ApprovalType.SPEC_REVIEW);
        assertThat(Approval.ContentKind.values())
                .containsExactly(Approval.ContentKind.MARKDOWN, Approval.ContentKind.HTML);
        Approval a = Approval.builder().build();
        assertThat(a.getApprovalType()).isEqualTo(Approval.ApprovalType.TOOL_CALL);
        assertThat(a.getContent()).isNull();
        assertThat(a.getKnowledgeItemId()).isNull();
    }

    @Test
    void knowledgeTypeHasSpecAndKanbanHasReview() {
        assertThat(KnowledgeType.values()).contains(KnowledgeType.SPEC);
        assertThat(io.aria.conductor.execution.kanban.KanbanStatus.values())
                .contains(io.aria.conductor.execution.kanban.KanbanStatus.REVIEW);
    }
}