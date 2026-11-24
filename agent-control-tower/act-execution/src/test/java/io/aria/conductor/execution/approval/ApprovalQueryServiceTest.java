package io.aria.conductor.execution.approval;

import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.execution.controller.ApprovalController;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static io.aria.conductor.test.TestDataBuilder.anApproval;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The tier the phone's {@code list_approvals} path shows (spec X15). The stored
 * {@code riskTier} is the classification of last resort, not a display override: the
 * resolver owns every name it can classify (a configured row, the reviewed catalog or a
 * native bash/edit/write), so a tier change after the ask reaches the queue rank; only a
 * name no layer knows — the live-only write tools — is left to the stored tier.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalQueryServiceTest {

    @Mock
    private ApprovalRepository approvalRepository;
    @Mock
    private ToolCallRepository toolCallRepository;
    @Mock
    private ToolRiskResolver toolRiskResolver;

    private ApprovalQueryService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalQueryService(approvalRepository, toolCallRepository, toolRiskResolver);
    }

    /**
     * {@code edit} has no catalog policy but is classified by the resolver's native map
     * (PUSH). A row stored before that classification — or before a later change of it —
     * must not keep the display and the queue rank on the stale tier.
     */
    @Test
    void listUsesTheResolverTierForANativeToolEvenWhenTheRowStoredAnother() {
        Approval stale = ask("edit", "WRITE_LOCAL");
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(stale));
        when(toolRiskResolver.knows("edit")).thenReturn(true);
        when(toolRiskResolver.resolve("edit")).thenReturn(RiskTier.PUSH);

        List<ApprovalController.ApprovalDetail> details = service.list(ApprovalStatus.PENDING);

        assertThat(details).singleElement().satisfies(detail -> {
            assertThat(detail.toolName()).isEqualTo("edit");
            assertThat(detail.riskTier())
                    .as("the resolver knows edit, so its current PUSH wins over the stored tier")
                    .isEqualTo("PUSH");
            assertThat(detail.toolPolicy()).as("a native tool has no reviewed catalog authority").isNull();
        });
    }

    /**
     * The live-only write tools exist only in the live turn: no catalog entry, no
     * configured row, no native name. The resolver would say READ, so the row's own
     * reviewed tier is the only classification there is.
     */
    @Test
    void listFallsBackToTheStoredTierForATrulyUnknownLiveOnlyName() {
        Approval liveWrite = ask("start_agent_run", "WRITE_LOCAL");
        when(approvalRepository.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of(liveWrite));
        when(toolRiskResolver.knows("start_agent_run")).thenReturn(false);

        List<ApprovalController.ApprovalDetail> details = service.list(ApprovalStatus.PENDING);

        assertThat(details).singleElement().satisfies(detail -> {
            assertThat(detail.toolName()).isEqualTo("start_agent_run");
            assertThat(detail.riskTier())
                    .as("the resolver cannot classify it, so the stored reviewed tier is shown")
                    .isEqualTo("WRITE_LOCAL");
            assertThat(detail.toolPolicy()).isNull();
        });
    }

    /** An ACP ask carries its own tool identity and tier (no ToolCall link), like the phone sees it. */
    private static Approval ask(String toolName, String riskTier) {
        Approval approval = anApproval().build();
        approval.setToolName(toolName);
        approval.setRiskTier(riskTier);
        return approval;
    }
}
