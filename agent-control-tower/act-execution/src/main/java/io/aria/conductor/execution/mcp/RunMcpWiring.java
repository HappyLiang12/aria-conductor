package io.aria.conductor.execution.mcp;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.sandbox.SandboxBind;
import io.aria.conductor.execution.security.ActorTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * The platform's own MCP endpoint as one run sees it: the run-scoped wiring of
 * {@code mcp.aria-conductor} into a core launch.
 *
 * <p>The gate is the legacy one: only the Aria assistant agent receives the
 * platform MCP, and only while {@code aria.mcp} is enabled. A HOST run dials the
 * backend on loopback; a SANDBOX run may only use the operator's configured
 * sandbox-reachable host — when it is blank the run is left without the platform
 * MCP (a guessed address is never emitted).
 */
@Component
public class RunMcpWiring {

    private static final Logger log = LoggerFactory.getLogger(RunMcpWiring.class);

    private final McpProperties mcp;
    private final ActorTokenService actorTokens;

    public RunMcpWiring(McpProperties mcp, ActorTokenService actorTokens) {
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.actorTokens = Objects.requireNonNull(actorTokens, "actorTokens");
    }

    /** The run's MCP endpoint URL and the run-scoped worker bearer minted with it. */
    public record Endpoint(String url, String token) {
    }

    /**
     * The platform MCP endpoint of {@code spec}'s run, or empty when the endpoint
     * is disabled, the run's agent is not the Aria assistant, or the configured auth
     * mode expects the static platform token (which a run is never handed). The
     * bearer is minted per run and expires with the run's frozen deadline.
     */
    public Optional<Endpoint> forRun(ExecutionSpec spec, PreparedEnvironment environment) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        if (!mcp.isEnabled() || !AriaConstants.ARIA_AGENT_ID.equals(spec.agentId())) {
            return Optional.empty();
        }
        if (mcp.isTokenMode()) {
            // auth-mode=token authenticates with the long-lived platform token: handing
            // that to a run would outlive the run and the run's own scope. The run-scoped
            // mode meant for runs is `actor`; until the operator picks it, no wiring beats
            // a bearer the endpoint must refuse.
            log.warn("aria.mcp.auth-mode=token expects the static platform token, which is never given to a run:"
                    + " run {} is left without the platform MCP (use auth-mode=actor for run-scoped access)",
                    spec.runId());
            return Optional.empty();
        }
        String host;
        if (SandboxBind.isSandboxProxy(environment)) {
            host = mcp.getSandboxHostAddress();
            if (host == null || host.isBlank()) {
                log.warn("Run {} is a sandbox placement but aria.mcp.sandbox-host-address is blank: the sandbox"
                        + " host address not configured; run left without the platform MCP (the address is"
                        + " never guessed)", spec.runId());
                return Optional.empty();
            }
        } else {
            host = SandboxBind.loopbackHost();
        }
        String url = "http://" + host + ":" + mcp.getPort() + "/mcp";
        return Optional.of(new Endpoint(url, actorTokens.issueWorker(spec.runId(), spec.deadline())));
    }
}
