package io.aria.conductor.mcp;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.mcp.tools.ToolResponses;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The unauthenticated refusal is part of the one operator-authority voice (task-11
 * fix round 1, m1): it keeps the named surface, so a call that carried no credential
 * is not the one refusal on the surface with no next step in it.
 *
 * <p>T48 (re-review rev-mcp m3) corrects the other half. The refusal used the
 * operator-only template, whose tail reads "is an operator decision" — but this path
 * runs before an actor exists, so no authority decision was made and the caller was
 * never identified. The sentence is now its own ({@code ToolResponses.unauthenticated}),
 * and the worker refusal keeps the clause because THAT one really is a decision about
 * authority.
 */
class McpActorContextTest {

    private static final String REFUSAL = "MCP authentication required: this call carried no "
            + "verifiable credential, so its caller was not identified and the tool refused it "
            + "before any authority decision. Reconnect the MCP client with an authenticated "
            + "credential: the operator bearer credential (aria.operator.bearer-token) or a "
            + "run-scoped worker token issued to the run.";

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");

    /** A call that reached the tool with no MCP exchange at all. */
    @Test
    void aCallWithNoExchangeIsRefusedInTheActionableForm() {
        assertThatThrownBy(() -> McpActorContext.require(new ToolContext(Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessage(REFUSAL);
    }

    /** An exchange that resolved no actor: the credential did not verify, or none was sent. */
    @Test
    void aCallWhoseExchangeCarriesNoActorIsRefusedInTheSameActionableForm() {
        ToolContext context = contextWith(McpTransportContext.EMPTY);

        assertThatThrownBy(() -> McpActorContext.require(context))
                .isInstanceOf(SecurityException.class)
                .hasMessage(REFUSAL);
    }

    /** The positive control: a transport-resolved actor is returned untouched. */
    @Test
    void aTransportResolvedActorPassesThrough() {
        ActorPrincipal worker = ActorPrincipal.worker(RUN_ID, Instant.parse("2026-11-09T12:10:00Z"));

        ActorPrincipal resolved = McpActorContext.require(contextWith(McpActorContext.transportContext(worker)));

        assertThat(resolved).isEqualTo(worker);
    }

    /**
     * T48 (rev-mcp m3): an authentication failure must not be dressed as an authority
     * decision. The negative here needs its positive control — the shape it rejects is
     * still correct for the caller it was written for, an identified but non-operator
     * caller — otherwise this test would pass by the template having no such clause at all.
     */
    @Test
    void theUnauthenticatedRefusalDoesNotClaimAnAuthorityDecision() {
        assertThatThrownBy(() -> McpActorContext.require(new ToolContext(Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageNotContaining("is an operator decision")
                .hasMessageNotContaining("Operator authority required");

        // Positive control: the worker refusal keeps the clause, because there the
        // platform did identify the caller and did decide about authority.
        assertThat(ToolResponses.operatorOnly("retiring an agent", "Retire it on the Crew page."))
                .contains("Operator authority required")
                .contains("is an operator decision");
    }

    private static ToolContext contextWith(McpTransportContext transportContext) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transportContext);
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }
}
