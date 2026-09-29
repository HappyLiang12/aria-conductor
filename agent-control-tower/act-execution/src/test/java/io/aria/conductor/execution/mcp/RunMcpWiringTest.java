package io.aria.conductor.execution.mcp;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run-scoped platform MCP wiring: the legacy gate (enabled configuration
 * and the Aria assistant agent only), the loopback/configured-host resolution
 * and the never-guess rule of the sandbox placement.
 */
class RunMcpWiringTest {

    private static final Instant DEADLINE = Instant.now().plusSeconds(2700);

    private final McpProperties mcp = new McpProperties();
    private final ActorTokenService actorTokens = new ActorTokenService();
    private final RunMcpWiring wiring = new RunMcpWiring(mcp, actorTokens);

    @Test
    void aDisabledEndpointLeavesEvenTheAriaRunUnwired() {
        mcp.setEnabled(false);

        assertThat(wiring.forRun(spec(AriaConstants.ARIA_AGENT_ID, ExecutionMode.HOST), environment(ExecutionMode.HOST)))
                .isEmpty();
    }

    @Test
    void aNonAriaAgentNeverGetsThePlatformMcp() {
        assertThat(wiring.forRun(spec(UUID.randomUUID(), ExecutionMode.HOST), environment(ExecutionMode.HOST)))
                .isEmpty();
    }

    @Test
    void theLegacyStaticTokenModeLeavesTheRunUnwired() {
        mcp.setAuthMode("token");
        mcp.setToken("static-platform-bearer");

        assertThat(wiring.forRun(spec(AriaConstants.ARIA_AGENT_ID, ExecutionMode.HOST),
                environment(ExecutionMode.HOST)))
                .as("a run is never handed the platform's long-lived token")
                .isEmpty();
    }

    @Test
    void theAriaHostRunGetsTheLoopbackEndpointAndAResolvableRunBearer() {
        mcp.setPort(4711);
        ExecutionSpec spec = spec(AriaConstants.ARIA_AGENT_ID, ExecutionMode.HOST);

        RunMcpWiring.Endpoint endpoint = wiring.forRun(spec, environment(ExecutionMode.HOST)).orElseThrow();

        assertThat(endpoint.url()).isEqualTo("http://127.0.0.1:4711/mcp");
        assertThat(actorTokens.resolveBearer("Bearer " + endpoint.token()))
                .as("the run-scoped bearer the endpoint will verify")
                .get()
                .satisfies(actor -> assertThat(actor.runId()).isEqualTo(spec.runId()));
    }

    @Test
    void theAriaSandboxRunIsLeftUnwiredWithoutAConfiguredSandboxHostAddress() {
        assertThat(wiring.forRun(spec(AriaConstants.ARIA_AGENT_ID, ExecutionMode.SANDBOX),
                environment(ExecutionMode.SANDBOX)))
                .as("a sandbox-reachable address is never guessed")
                .isEmpty();
    }

    @Test
    void theAriaSandboxRunUsesTheConfiguredSandboxHostAddress() {
        mcp.setPort(9000);
        mcp.setSandboxHostAddress("host.containers.internal");

        RunMcpWiring.Endpoint endpoint = wiring.forRun(spec(AriaConstants.ARIA_AGENT_ID, ExecutionMode.SANDBOX),
                environment(ExecutionMode.SANDBOX)).orElseThrow();

        assertThat(endpoint.url()).isEqualTo("http://host.containers.internal:9000/mcp");
    }

    private static ExecutionSpec spec(UUID agentId, ExecutionMode mode) {
        return new ExecutionSpec(UUID.randomUUID(), agentId, "qoder", mode,
                new AgentExecutionSettings("qoder", mode, null, null, null),
                "openid:operator", "rev-1", DEADLINE);
    }

    private static PreparedEnvironment environment(ExecutionMode mode) {
        UUID runId = UUID.randomUUID();
        URI endpoint = mode == ExecutionMode.SANDBOX
                ? URI.create("http://localhost:46321/proxy/9310")
                : URI.create("http://127.0.0.1:46321/");
        return new PreparedEnvironment(runId, mode, mode.name().toLowerCase(java.util.Locale.ROOT) + "-" + runId,
                "workspace", "configuration", endpoint);
    }
}
