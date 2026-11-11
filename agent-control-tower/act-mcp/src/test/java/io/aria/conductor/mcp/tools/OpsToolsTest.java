package io.aria.conductor.mcp.tools;

import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategorySummary;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.ScanResult;
import io.aria.conductor.execution.housekeeping.HousekeepingService;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.McpActorContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsToolsTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");

    @Mock HousekeepingService housekeepingService;
    McpProperties mcpProperties;
    OpsTools tools;

    /** The real reviewed policy: housekeeping_execute is OPERATOR_ONLY. */
    private final ToolPolicyRegistry toolPolicies = new ToolPolicyRegistry();

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new OpsTools(housekeepingService, toolPolicies, mcpProperties);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    private ToolContext workerContext() {
        return context(ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z")));
    }

    private ToolContext operatorContext() {
        return context(ActorPrincipal.operator(null));
    }

    @Test
    void housekeepingScan_mapsExclusionsAndWraps() {
        ScanResult result = new ScanResult(
                List.of(new CategorySummary("runs", 2, List.of())), Instant.now());
        when(housekeepingService.scan(eq(true), any(Exclusions.class))).thenReturn(result);

        String json = tools.housekeepingScan(true, List.of("run-1"), null, null, null);

        ArgumentCaptor<Exclusions> captor = ArgumentCaptor.forClass(Exclusions.class);
        verify(housekeepingService).scan(eq(true), captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new Exclusions(List.of("run-1"), List.of(), List.of(), List.of()));
        assertThat(json).contains("\"ok\":true").contains("\"runs\"");
    }

    @Test
    void housekeepingScan_nullExclusionsBecomeEmpty() {
        when(housekeepingService.scan(eq(false), eq(Exclusions.empty())))
                .thenReturn(new ScanResult(List.of(), Instant.now()));

        String json = tools.housekeepingScan(false, null, null, null, null);

        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void housekeepingExecute_passesRequestAndWraps() {
        HousekeepingReceipt receipt = new HousekeepingReceipt(
                List.of(new CategoryReceipt("kanban", 3, 0, 1)), Instant.now());
        when(housekeepingService.execute(any())).thenReturn(receipt);

        String json = tools.housekeepingExecute(List.of("kanban"), false, null, List.of("k-1"), null, null, true,
                operatorContext());

        ArgumentCaptor<HousekeepingRequest> captor = ArgumentCaptor.forClass(HousekeepingRequest.class);
        verify(housekeepingService).execute(captor.capture());
        HousekeepingRequest request = captor.getValue();
        assertThat(request.categories()).containsExactly("kanban");
        assertThat(request.confirm()).isTrue();
        assertThat(request.exclusions().kanbanItemIds()).containsExactly("k-1");
        assertThat(json).contains("\"ok\":true").contains("kanban");
    }

    /**
     * C4/F9: the destructive batch delete is OPERATOR_ONLY, so a run-scoped worker is
     * refused with an actionable message and no category is ever scanned or deleted.
     */
    @Test
    void aWorkerActorCannotExecuteHousekeeping() {
        String json = tools.housekeepingExecute(List.of("kanban", "agents", "runs"), false, null, null, null, null,
                true, workerContext());

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required: executing housekeeping is an operator decision. "
                        + "Run it from the Housekeeping panel on the Ops page.\"");
        verifyNoInteractions(housekeepingService);
    }

    @Test
    void anOperatorActorCanStillExecuteHousekeeping() {
        when(housekeepingService.execute(any())).thenReturn(new HousekeepingReceipt(
                List.of(new CategoryReceipt("runs", 1, 0, 0)), Instant.now()));

        String json = tools.housekeepingExecute(List.of("runs"), false, null, null, null, null, true,
                operatorContext());

        assertThat(json).contains("\"ok\":true");
        verify(housekeepingService).execute(any());
    }

    @Test
    void housekeepingExecute_unknownCategoryIsValidationWithoutStack() {
        String json = tools.housekeepingExecute(List.of("bogus"), false, null, null, null, null, true,
                operatorContext());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"").contains("Valid");
        assertThat(json).doesNotContain("stackTrace");
        verifyNoInteractions(housekeepingService);
    }

    @Test
    void housekeepingExecute_emptyCategoriesIsValidation() {
        String json = tools.housekeepingExecute(List.of(), false, null, null, null, null, true, operatorContext());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        verifyNoInteractions(housekeepingService);
    }

    @Test
    void housekeepingExecute_confirmFalseIsValidation() {
        when(housekeepingService.execute(any()))
                .thenThrow(new IllegalArgumentException("Housekeeping execute requires explicit confirm"));

        String json = tools.housekeepingExecute(List.of("runs"), false, null, null, null, null, false,
                operatorContext());

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
    }

    @Test
    void housekeepingExecute_inFlightIsConflict() {
        when(housekeepingService.execute(any()))
                .thenThrow(new IllegalStateException("Housekeeping execute already in flight"));

        String json = tools.housekeepingExecute(List.of("runs"), false, null, null, null, null, true,
                operatorContext());

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
        assertThat(json).doesNotContain("stackTrace");
    }
}
