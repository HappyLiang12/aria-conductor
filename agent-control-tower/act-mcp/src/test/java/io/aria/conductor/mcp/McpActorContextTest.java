package io.aria.conductor.mcp;

import io.aria.conductor.common.security.ActorPrincipal;
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
 * fix round 1, m1). {@code ToolResponses.requireOperator} can name an action and a
 * surface because it starts from an actor; this guard runs before an actor exists, so
 * it has to speak the same sentence shape itself — otherwise a call that carried no
 * credential is the one refusal on the surface with no next step in it.
 */
class McpActorContextTest {

    private static final String REFUSAL = "Operator authority required: calling a tool without an "
            + "authenticated credential is an operator decision. Reconnect the MCP client with an "
            + "authenticated credential: the operator bearer credential "
            + "(aria.operator.bearer-token) or a run-scoped worker token issued to the run.";

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

    private static ToolContext contextWith(McpTransportContext transportContext) {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(transportContext);
        return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, exchange));
    }
}
