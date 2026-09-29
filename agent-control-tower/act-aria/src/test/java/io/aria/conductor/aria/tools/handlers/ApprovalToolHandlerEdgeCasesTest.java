package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Missing-parameter, decision-normalization and failure paths of
 * {@link ApprovalToolHandler} beyond the happy-path variants in
 * ApprovalToolHandlerTest.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalToolHandlerEdgeCasesTest {

    @Mock private ApprovalGate approvalGate;
    @Mock private ApprovalRepository approvalRepository;

    @InjectMocks
    private ApprovalToolHandler handler;

    @Test
    void decide_missingIdReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "decide_approval", "decision", "approve"));

        assertThat(result).startsWith("Error").contains("Missing required parameter: id");
        verifyNoInteractions(approvalGate);
    }

    @Test
    void decide_missingDecisionReturnsError() {
        String result = handler.execute(Map.of(
                "toolName", "decide_approval", "id", UUID.randomUUID().toString()));

        assertThat(result).startsWith("Error").contains("Missing required parameter: decision");
        verifyNoInteractions(approvalGate);
    }

    @ParameterizedTest(name = "decision \"{0}\" -> approved={1}")
    @CsvSource({
            "APPROVE, true",
            "Yes,     true",
            "TRUE,    true",
            "no,      false",
            "reject,  false",
            "denied,  false",
            "maybe,   false",
    })
    void decide_normalizesDecisionKeywordCaseInsensitively(String decision, boolean expectedApproved) {
        UUID id = UUID.randomUUID();

        String result = handler.execute(Map.of(
                "toolName", "decide_approval", "id", id.toString(), "decision", decision));

        verify(approvalGate).decideApproval(id, expectedApproved, "");
        assertThat(result).contains(expectedApproved ? "approved" : "denied");
    }

    @Test
    void decide_passesReasonThroughToGate() {
        UUID id = UUID.randomUUID();

        handler.execute(Map.of(
                "toolName", "decide_approval", "id", id.toString(),
                "decision", "deny", "reason", "too risky"));

        verify(approvalGate).decideApproval(id, false, "too risky");
    }

    @Test
    void decide_malformedUuidIsMappedToError() {
        String result = handler.execute(Map.of(
                "toolName", "decide_approval", "id", "nope", "decision", "approve"));

        assertThat(result).startsWith("Error");
        verify(approvalGate, never()).decideApproval(any(), anyBoolean(), anyString());
    }

    @Test
    void decide_gateFailureIsMappedToErrorString() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("approval already decided"))
                .when(approvalGate).decideApproval(id, true, "");

        String result = handler.execute(Map.of(
                "toolName", "decide_approval", "id", id.toString(), "decision", "approve"));

        assertThat(result).isEqualTo("Error: approval already decided");
    }

    @Test
    void listPending_rendersEachApprovalWithRunReference() {
        Approval first = Approval.builder().id(UUID.randomUUID()).runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING).requestedAt(Instant.parse("2026-01-05T08:00:00Z"))
                .build();
        Approval second = Approval.builder().id(UUID.randomUUID())
                .status(ApprovalStatus.PENDING).build();
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING))
                .thenReturn(List.of(first, second));

        String result = handler.execute(Map.of("toolName", "list_pending_approvals"));

        assertThat(result).contains("Pending approvals (2 total)");
        assertThat(result).contains(first.getId().toString())
                .contains(first.getRunId().toString())
                .contains("2026-01-05T08:00:00Z");
        // missing run/timestamp fields degrade to N/A instead of "null"
        assertThat(result).contains(second.getId().toString()).contains("N/A");
    }

    @Test
    void unknownToolReturnsError() {
        String result = handler.execute(Map.of("toolName", "escalate_approval"));

        assertThat(result).isEqualTo("Error: Unknown tool: escalate_approval");
    }
}
