package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingService;
import io.aria.conductor.execution.pipeline.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Housekeeping S5: Aria tool handler — scan is read-only; execute is gated by
 * the human ApprovalGate and passes exclusions through.
 */
@ExtendWith(MockitoExtension.class)
class HousekeepingToolHandlerTest {

    @Mock HousekeepingService housekeepingService;
    @Mock ApprovalGate approvalGate;

    HousekeepingToolHandler handler;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new HousekeepingToolHandler(housekeepingService, approvalGate);
    }

    private RunContext ctx() {
        return new RunContext(runId, UUID.randomUUID(), null, null, 50);
    }

    @Test
    void scan_returnsMarkdownPlanWithCounts() {
        when(housekeepingService.scan(anyBoolean(), any())).thenReturn(new ScanResult(List.of(
                new CategorySummary("runs", 18, List.of()),
                new CategorySummary("kanban", 15, List.of()),
                new CategorySummary("agents", 9, List.of())), Instant.now()));

        String out = handler.execute(Map.of("toolName", "housekeeping_scan"));

        assertThat(out).contains("runs").contains("18")
                .contains("kanban").contains("15")
                .contains("agents").contains("9");
        verify(housekeepingService, never()).execute(any());
    }

    @Test
    void execute_withoutRunContext_returnsError_andNoGate() {
        String out = handler.execute(Map.of("toolName", "housekeeping_execute",
                "categories", List.of("kanban")));

        assertThat(out).startsWith("Error:");
        verifyNoInteractions(approvalGate);
        verify(housekeepingService, never()).execute(any());
    }

    @Test
    void execute_denied_noExecution() {
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenReturn(ApprovalDecision.deny("not now"));

        String out = handler.execute(Map.of("toolName", "housekeeping_execute",
                "categories", List.of("kanban"), "_runContext", ctx()));

        assertThat(out).startsWith("DENIED");
        verify(housekeepingService, never()).execute(any());
    }

    @Test
    void execute_approved_runsWithConfirmAndExclusions() {
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenReturn(ApprovalDecision.approve("ok"));
        when(housekeepingService.execute(any())).thenReturn(new HousekeepingReceipt(
                List.of(new CategoryReceipt("kanban", 14, 0, 0)), Instant.now()));

        Map<String, Object> args = new HashMap<>();
        args.put("toolName", "housekeeping_execute");
        args.put("categories", List.of("kanban"));
        args.put("exclusions", Map.of("kanbanItemIds", List.of("9c796372")));
        args.put("_runContext", ctx());
        String out = handler.execute(args);

        assertThat(out).contains("kanban").contains("14");
        ArgumentCaptor<HousekeepingRequest> captor = ArgumentCaptor.forClass(HousekeepingRequest.class);
        verify(housekeepingService).execute(captor.capture());
        assertThat(captor.getValue().confirm()).isTrue();
        assertThat(captor.getValue().exclusions().kanbanItemIds()).containsExactly("9c796372");
    }
}
