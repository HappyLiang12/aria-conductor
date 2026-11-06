package io.aria.conductor.app.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreSession;
import io.aria.conductor.execution.security.ActorTokenService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Harness OpenCode core adapter (Task 19 peer-launch wiring): the production
 * {@code opencode} core served by the committed mock OpenCode peer
 * ({@code peers/mock-opencode.mjs}) instead of a real {@code opencode serve}
 * binary.
 *
 * <p>The launch profile is the peer's real process launch: {@code node
 * <peers>/mock-opencode.mjs --scenario <s> --workspace <w> serve --port <p>
 * --hostname <h>} -- the same recorded {@code serve} argv shape the production
 * adapter produces, with the harness scenario (selected through
 * {@code POST /api/v1/maintenance/core-e2e/scenario} for this agent) forwarded
 * as a real argv/env fixture selection. The session is opened against the peer's
 * endpoint through the production {@link OpenCodeCoreAdapter} (real native
 * message envelope, usage normalization and abort route) and wrapped in
 * {@link PeerSession}, which bridges the peer's harness-only decision surface
 * ({@code GET /__peer/pending} / {@code POST /__peer/decision}) into the run
 * policy's {@code permission.request} events and replies, and answers
 * pause/resume with verified process suspension of the owned peer tree.
 *
 * <p>The peer refuses to start without the scenario, the admitted workspace and
 * the control token (peer-actions.mjs bootPeer), so a harness run cannot reach a
 * fixture-less peer.
 */
final class CoreE2eOpenCodeAdapter implements CoreAdapter {

    static final String CORE_ID = "opencode";

    /** Fixture model pin: {@code fixtures.models.default} of the committed manifest. */
    static final String FIXTURE_MODEL = "efficient";
    /** Reviewed pinned core version of the committed peer. */
    static final String FIXTURE_VERSION = "1.14.31";

    private final CoreE2eScenarios scenarios;
    private final CoreE2eProcessBackend.State peers;
    private final String nodeExecutable;
    private final Path peerScript;

    CoreE2eOpenCodeAdapter(CoreE2eScenarios scenarios, CoreE2eProcessBackend.State peers,
            String nodeExecutable, Path peerScript) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.nodeExecutable = Objects.requireNonNull(nodeExecutable, "nodeExecutable");
        this.peerScript = Objects.requireNonNull(peerScript, "peerScript");
    }

    @Override
    public String coreId() {
        return CORE_ID;
    }

    /**
     * The harness core's verified control surface: pause/resume are verified
     * process-tree suspensions (the recorded control technique), the abort route
     * is only an acknowledgement (the committed non-cooperative fixture answers
     * it while ignoring it), and the reported-usage scenario proves observable
     * usage.
     */
    @Override
    public CoreCapabilities capabilities(ExecutionMode mode) {
        Objects.requireNonNull(mode, "mode");
        return new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, false, false, true);
    }

    @Override
    public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(environment, "environment");
        requireCore(spec);
        String scenario = scenarios.requireScenario(spec.agentId());
        Path workingDirectory = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();
        if (environment.endpoint() == null || environment.endpoint().getPort() < 1) {
            throw new IllegalStateException("The harness OpenCode launch requires the prepared endpoint");
        }
        List<String> argv = new ArrayList<>(List.of(
                nodeExecutable,
                peerScript.toString(),
                "--scenario", scenario,
                "--workspace", workingDirectory.toString(),
                "serve",
                "--port", String.valueOf(environment.endpoint().getPort()),
                "--hostname", environment.endpoint().getHost()));
        return new LaunchProfile(argv, peerEnvironment(scenario, workingDirectory), workingDirectory.toString());
    }

    @Override
    public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(spec, "spec");
        requireCore(spec);
        // Launch returns as soon as the peer process is owned; the peer has not
        // necessarily bound its port yet. Wait for the authenticated control
        // route -- the proof only the peer launched with this run's harness
        // control token answers -- before opening the session, so a session open
        // can never race the peer's bind (the production host backend waits the
        // same way for its bridge).
        CoreE2eEndpointReadiness.awaitOpenCodePeer(handle.endpoint(), scenarios.peerControlToken());
        // The production adapter opens the native session (single-shot create) on
        // the endpoint the peer launch produced; only the executable differs. Its
        // platform-MCP wiring is disabled: this harness never exercises it (it
        // builds its own launch).
        McpProperties harnessMcp = new McpProperties();
        harnessMcp.setEnabled(false);
        OpenCodeCoreAdapter delegate = new OpenCodeCoreAdapter(new OpenCodeCoreAdapter.OpenCodeProfile(
                nodeExecutable, List.of(peerScript.toString()), Map.of(), FIXTURE_VERSION, FIXTURE_MODEL),
                new RunMcpWiring(harnessMcp, new ActorTokenService()));
        CoreSession nativeSession = delegate.open(handle, spec, credentials);
        return new PeerSession(spec, nativeSession, handle.endpoint(), scenarios.peerControlToken(), peers);
    }

    private Map<String, String> peerEnvironment(String scenario, Path workingDirectory) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(CoreE2eScenarios.PEER_CONTROL_TOKEN_ENV, scenarios.peerControlToken());
        env.put("ARIA_PEER_SCENARIO", scenario);
        env.put("ARIA_PEER_WORKSPACE", workingDirectory.toString());
        // The child env replaces the parent's, and the peer shells out (git push
        // fixtures): without PATH its spawned tools are ENOENT. The production
        // launch profile passes PATH for the same reason.
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            env.put("PATH", path);
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            env.put("SystemRoot", systemRoot);
        }
        return env;
    }

    private void requireCore(ExecutionSpec spec) {
        if (!CORE_ID.equals(spec.coreId())) {
            throw new IllegalArgumentException("The harness OpenCode adapter does not serve core " + spec.coreId());
        }
    }

    /**
     * Peer-aware wrapper around the production OpenCode session. It adds exactly
     * the harness control the mock peer exposes over its own {@code /__peer/*}
     * surface, and nothing else: the native prompt/usage/abort semantics stay the
     * production session's.
     *
     * <ul>
     *   <li>while a prompt is in flight, the peer's pending fixture decision is
     *       forwarded as the run policy's {@code permission.request} event (the
     *       exact payload the coordinator correlates: requestId, toolName,
     *       options), so the ask surfaces through the real permission
     *       coordinator and the operator decides it like any native ask;</li>
     *   <li>{@code decide} is delivered through the peer's own decision route with
     *       the selected option's id — the peer applies the fixture effect only
     *       after a genuine allow-once decision;</li>
     *   <li>pause/resume are the verified process-tree suspension operations of
     *       the harness backend (a truthfully frozen peer), not invented RPCs:
     *       the committed OpenCode subset has no native pause route.</li>
     * </ul>
     */
    static final class PeerSession implements CoreSession {

        private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
        private static final Duration PEER_REQUEST_TIMEOUT = Duration.ofSeconds(10);
        /** How long the session keeps polling the peer past the run deadline. */
        private static final Duration POLL_GRACE = Duration.ofSeconds(60);
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final ScheduledExecutorService POLLERS = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "core-e2e-peer-pending");
            thread.setDaemon(true);
            return thread;
        });

        private final ExecutionSpec spec;
        private final CoreSession nativeSession;
        private final URI endpoint;
        private final String controlToken;
        private final CoreE2eProcessBackend.State peers;
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        private final Set<String> forwardedRequests = ConcurrentHashMap.newKeySet();
        private volatile Consumer<CoreEvent> eventSink;
        private volatile ScheduledFuture<?> pendingPoller;

        PeerSession(ExecutionSpec spec, CoreSession nativeSession, URI endpoint, String controlToken,
                CoreE2eProcessBackend.State peers) {
            this.spec = Objects.requireNonNull(spec, "spec");
            this.nativeSession = Objects.requireNonNull(nativeSession, "nativeSession");
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.controlToken = Objects.requireNonNull(controlToken, "controlToken");
            this.peers = Objects.requireNonNull(peers, "peers");
        }

        @Override
        public String sessionId() {
            return nativeSession.sessionId();
        }

        @Override
        public CompletionStage<CoreResult> prompt(CoreTask task, Consumer<CoreEvent> events) {
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(events, "events");
            eventSink = events;
            startPendingPoller();
            CompletionStage<CoreResult> stage = nativeSession.prompt(task, events);
            return stage.whenComplete((result, failure) -> {
                // One last observation at the prompt result; the poller itself keeps
                // running (see startPendingPoller): a governed write decision is a
                // gate on the WRITE, not on the prompt, so the peer may offer it just
                // after the message was answered and the run must still see the ask.
                pollPending(events);
            });
        }

        /**
         * Starts the session-lived pending poller once. Cancelling it at the prompt
         * result (the earlier shape) lost every decision whose offer landed after
         * the message was answered -- exactly the fixtures' async write gate -- so a
         * run completed with no ask and the permission specs had nothing to decide.
         * The poller stops on its own a bounded grace window past the run deadline,
         * which also ends it for a session nobody closes explicitly.
         */
        private void startPendingPoller() {
            ScheduledFuture<?> current = pendingPoller;
            if (current != null && !current.isCancelled() && !current.isDone()) {
                return;
            }
            pendingPoller = POLLERS.scheduleWithFixedDelay(this::pollPendingWhileLive, 0,
                    POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void pollPendingWhileLive() {
            Consumer<CoreEvent> sink = eventSink;
            if (sink == null) {
                return;
            }
            Instant deadline = spec.deadline();
            if (deadline != null && Instant.now().isAfter(deadline.plus(POLL_GRACE))) {
                ScheduledFuture<?> current = pendingPoller;
                if (current != null) {
                    current.cancel(false);
                }
                return;
            }
            pollPending(sink);
        }

        /**
         * One poll of the peer's pending fixture decision. A newly observed
         * decision is forwarded once as a {@code permission.request} event; the
         * forwarded {@code requestId} is the peer's own request id (the value the
         * decision must be correlated back to).
         */
        private void pollPending(Consumer<CoreEvent> events) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("__peer/pending"))
                        .header("x-peer-control-token", controlToken)
                        .timeout(PEER_REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return;
                }
                JsonNode pending = JSON.readTree(response.body()).path("pending");
                for (JsonNode decision : pending) {
                    String requestId = decision.path("id").asText("");
                    if (requestId.isBlank() || !forwardedRequests.add(requestId)) {
                        continue;
                    }
                    String toolName = decision.path("toolName").asText("");
                    if (toolName.isBlank()) {
                        toolName = decision.path("kind").asText("unknown");
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", requestId);
                    payload.put("toolName", toolName);
                    payload.put("target", decision.path("path").asText(""));
                    payload.put("arguments", decision.path("command").asText(""));
                    List<Map<String, Object>> options = new ArrayList<>();
                    for (JsonNode option : decision.path("options")) {
                        options.add(Map.of(
                                "optionId", option.path("optionId").asText(""),
                                "name", option.path("name").asText(""),
                                "kind", option.path("kind").asText("")));
                    }
                    payload.put("options", options);
                    long expiresInMs = decision.path("expiresInMs").asLong(0);
                    if (expiresInMs > 0) {
                        // The peer declared a decision window: the recorded ask must
                        // never stay decidable past it.
                        payload.put("expiresInMs", expiresInMs);
                    }
                    events.accept(new CoreEvent("permission.request", spec.runId(), sessionId(), requestId,
                            JSON.writeValueAsString(payload)));
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // The peer may not be listening yet, or may be gone: the next poll
                // retries; a real refusal surfaces through the prompt result.
            }
        }

        @Override
        public CompletionStage<Void> decide(PermissionReply reply) {
            Objects.requireNonNull(reply, "reply");
            try {
                String body = JSON.writeValueAsString(Map.of("optionId", reply.optionId()));
                HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("__peer/decision"))
                        .header("x-peer-control-token", controlToken)
                        .header("Content-Type", "application/json")
                        .timeout(PEER_REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "The mock OpenCode peer refused the decision for request " + reply.requestId()
                                    + " (optionId=" + reply.optionId() + "): HTTP " + response.statusCode()
                                    + " " + response.body()));
                }
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletionStage<ControlAck> pause(Instant deadline) {
            return peers.pausePeer(spec.runId(), deadline);
        }

        @Override
        public CompletionStage<ControlAck> resume(Instant deadline) {
            return peers.resumePeer(spec.runId(), deadline);
        }

        @Override
        public CompletionStage<ControlAck> cancel(Instant deadline) {
            return nativeSession.cancel(deadline);
        }
    }
}
