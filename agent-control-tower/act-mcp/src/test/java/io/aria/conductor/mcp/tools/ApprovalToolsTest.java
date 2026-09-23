package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.execution.approval.ApprovalQueryService;
import io.aria.conductor.execution.approval.PermissionChoice;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.controller.ApprovalController.ApprovalDetail;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MCP approval boundary. {@code decide_approval} is operator-only: the tool
 * resolves the actor from the transport context only and a run-scoped worker
 * credential is refused before the coordinator is reached (Task 12: a worker may
 * never self-approve, on either MCP transport).
 */
@ExtendWith(MockitoExtension.class)
class ApprovalToolsTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-0000-0000-000000000402");

    @Mock ApprovalQueryService approvalQueryService;
    @Mock PermissionCoordinator permissionCoordinator;
    McpProperties mcpProperties;
    ApprovalTools tools;

    /** The real reviewed policy: decide_approval is OPERATOR_ONLY. */
    private final ToolPolicyRegistry toolPolicies = new ToolPolicyRegistry();

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new ApprovalTools(approvalQueryService, permissionCoordinator, toolPolicies, mcpProperties);
    }

    private ApprovalDetail detail(UUID id, String type, String status) {
        return new ApprovalDetail(id, UUID.randomUUID(), null, ApprovalStatus.valueOf(status),
                "Spec resubmitted", Instant.now(), null, Instant.now().plusSeconds(1800),
                type, "## spec", "MARKDOWN", UUID.randomUUID(), null, null, null,
                null, null, null, null, null);
    }

    private ToolContext context(ActorPrincipal actor) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpActorContext.transportContext(actor));
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }

    @Test
    void listApprovals_filtersPendingSpecReviews() {
        UUID id = UUID.randomUUID();
        when(approvalQueryService.list(ApprovalStatus.PENDING))
                .thenReturn(List.of(detail(id, "SPEC_REVIEW", "PENDING")));

        String json = tools.listApprovals("PENDING");

        assertThat(json).contains("SPEC_REVIEW").contains(id.toString());
    }

    @Test
    void workerDecide_isForbiddenAndNeverReachesTheCoordinator() {
        UUID id = UUID.randomUUID();
        ActorPrincipal worker = ActorPrincipal.worker(RUN_ID, Instant.parse("2026-09-22T12:10:00Z"));

        String json = tools.decideApproval(id, true, "lgtm", context(worker));

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Operator authority required\"");
        verifyNoInteractions(permissionCoordinator);
    }

    @Test
    void workerOfAnotherRunDecide_isForbiddenToo() {
        UUID id = UUID.randomUUID();
        ActorPrincipal worker = ActorPrincipal.worker(OTHER_RUN, Instant.parse("2026-09-22T12:10:00Z"));

        String json = tools.decideApproval(id, false, "no", context(worker));

        assertThat(json).contains("\"ok\":false").contains("\"errorType\":\"FORBIDDEN\"");
        verifyNoInteractions(permissionCoordinator);
    }

    @Test
    void aCallWithoutATransportActorIsRefused() {
        UUID id = UUID.randomUUID();

        String json = tools.decideApproval(id, true, "lgtm", new ToolContext(Map.of()));

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"FORBIDDEN\"")
                .contains("\"message\":\"Missing MCP exchange\"");
        verifyNoInteractions(permissionCoordinator);
    }

    @Test
    void operatorDecide_routesAllowOnceToTheCoordinatorWithTheOperatorPrincipal() {
        UUID id = UUID.randomUUID();
        ToolContext context = context(ActorPrincipal.operator(null));

        String json = tools.decideApproval(id, true, "lgtm", context);

        ArgumentCaptor<ActorPrincipal> actor = ArgumentCaptor.forClass(ActorPrincipal.class);
        verify(permissionCoordinator).decide(org.mockito.ArgumentMatchers.eq(id),
                org.mockito.ArgumentMatchers.eq(PermissionChoice.ALLOW_ONCE), actor.capture());
        assertThat(actor.getValue()).isEqualTo(ActorPrincipal.operator(null));
        assertThat(json).contains("\"ok\":true")
                .contains("\"approvalId\":\"" + id + "\"")
                .contains("\"approved\":true")
                .contains("\"status\":\"processed\"");
    }

    @Test
    void operatorDeny_routesDenyToTheCoordinator() {
        UUID id = UUID.randomUUID();
        ActorPrincipal operator = ActorPrincipal.operator(Instant.parse("2026-09-22T20:00:00Z"));

        String json = tools.decideApproval(id, false, "too risky", context(operator));

        verify(permissionCoordinator).decide(id, PermissionChoice.DENY, operator);
        assertThat(json).contains("\"ok\":true").contains("\"approved\":false");
    }

    @Test
    void aSettledRequestIsReportedAsAConflict() {
        UUID id = UUID.randomUUID();
        ActorPrincipal operator = ActorPrincipal.operator(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("Approval " + id + " is already APPROVED; a decision on a settled request is refused"))
                .when(permissionCoordinator).decide(id, PermissionChoice.ALLOW_ONCE, operator);

        String json = tools.decideApproval(id, true, "again", context(operator));

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"CONFLICT\"")
                .contains("a decision on a settled request is refused");
    }

    @Test
    void aLegacyApprovalWithoutCorrelationIsReportedAsNotFound() {
        UUID id = UUID.randomUUID();
        ActorPrincipal operator = ActorPrincipal.operator(null);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Approval " + id
                        + " is not a native permission request; it must be decided through the approval gate"))
                .when(permissionCoordinator).decide(id, PermissionChoice.DENY, operator);

        String json = tools.decideApproval(id, false, "nope", context(operator));

        assertThat(json).contains("\"ok\":false")
                .contains("\"errorType\":\"NOT_FOUND\"")
                .contains("is not a native permission request");
    }
}
