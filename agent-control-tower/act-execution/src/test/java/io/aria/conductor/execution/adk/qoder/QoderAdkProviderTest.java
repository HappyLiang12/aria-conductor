package io.aria.conductor.execution.adk.qoder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionConstraints;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.approval.AcpPermissionCoordinator;
import io.aria.conductor.execution.approval.RunScopedCredentialService;
import io.aria.conductor.execution.approval.WriteGrantService;
import io.aria.conductor.execution.credential.RuntimeCredentialException;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.SandboxHostResolver;
import io.aria.conductor.execution.sandbox.SandboxLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QoderAdkProvider} and {@link QoderProgressPump} with a mocked
 * {@link SandboxLifecycle}, {@link QoderBridgeClient} and {@link RuntimeCredentialService}.
 *
 * <p>Every test exercises the real provider/pump code paths; only the sandbox lifecycle, the
 * bridge HTTP/SSE surface and the credential store are mocked. The bridge event streams are
 * played through a mocked {@code EventStream} so terminal semantics (explicit
 * {@code completed}/{@code failed} versus a clean EOF) are asserted exactly as the bridge
 * contract defines them.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class QoderAdkProviderTest {

    private static final String IMAGE = "aria-conductor/qoder-sandbox:0.1";
    private static final String SANDBOX_ID = "sb-1";
    private static final String BRIDGE_URL = "http://127.0.0.1:40369/proxy/4097";
    private static final String BRIDGE_SESSION = "bridge-session-1";
    private static final String PAT = "synthetic-pat-value";
    private static final String ROTATED_PAT = "synthetic-pat-rotated-value";
    private static final String PROMPT_TEXT = "do the thing";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock SandboxLifecycle sandboxLifecycle;
    @Mock QoderBridgeClient client;
    @Mock QoderBridgeClient.EventStream eventStream;
    /** Pump double for the deterministic stop-proof pins (no real reader thread). */
    @Mock QoderProgressPump pump;
    @Mock RuntimeCredentialService credentialService;
    @Mock RunScopedCredentialService runScopedCredentialService;
    @Mock WriteGrantService writeGrantService;
    @Mock AcpPermissionCoordinator permissionCoordinator;
    @Mock RunRepository runRepository;

    QoderProperties properties;
    McpProperties mcpProperties;
    QoderAdkProvider provider;
    ApplicationEventPublisher publisher;
    List<RunProgressEvent> published;
    AtomicReference<String> clientUrl;
    AtomicReference<String> clientToken;

    @BeforeEach
    void setUp() {
        properties = new QoderProperties();
        properties.setSandboxServerUrl("http://localhost:8090");
        properties.setImage(IMAGE);
        properties.setPort(4097);
        properties.setModel("efficient");
        properties.setMaxTaskMinutes(45);
        properties.setSandboxRenewInterval(Duration.ofMinutes(5));
        // The worker/MCP wiring is opt-in per test: disabled here keeps the legacy tests on
        // the MCP-less path.
        mcpProperties = new McpProperties();
        mcpProperties.setEnabled(false);

        published = new CopyOnWriteArrayList<>();
        publisher = event -> {
            if (event instanceof RunProgressEvent progress) {
                published.add(progress);
            }
        };
        clientUrl = new AtomicReference<>();
        clientToken = new AtomicReference<>();

        provider = new QoderAdkProvider(properties, sandboxLifecycle, credentialService, publisher,
                (url, token) -> {
                    clientUrl.set(url);
                    clientToken.set(token);
                    return client;
                },
                mcpProperties, runScopedCredentialService, writeGrantService, permissionCoordinator,
                runRepository);
        provider.setBridgeReadyTimeoutForTest(Duration.ofMillis(400));
        provider.setBridgeReadyPollIntervalForTest(Duration.ofMillis(10));
        provider.setStopGraceForTest(Duration.ofMillis(50));

        lenient().when(credentialService.read("qoder")).thenReturn(PAT);
        lenient().when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap())).thenReturn(SANDBOX_ID);
        lenient().when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenReturn(BRIDGE_URL);
        lenient().when(client.health()).thenReturn(new QoderBridgeClient.Health("ok", "1.1.41"));
        lenient().when(client.createSession(any())).thenReturn(BRIDGE_SESSION);
        lenient().when(client.openEventStream(anyString())).thenReturn(eventStream);
    }

    // ---- helpers -------------------------------------------------------------

    private Agent agent(UUID agentId) {
        return Agent.builder().id(agentId).name("test-agent").role("coder").description("desc").build();
    }

    private static QoderBridgeClient.BridgeEvent event(long sequence, String type, String json) {
        try {
            JsonNode payload = MAPPER.readTree(json);
            return new QoderBridgeClient.BridgeEvent(sequence, type, payload);
        } catch (Exception e) {
            throw new IllegalStateException("Bad test event JSON", e);
        }
    }

    /** The event stream plays the events, then ends (server-side end of stream). */
    private void streamPlays(QoderBridgeClient.BridgeEvent... events) {
        doAnswer(invocation -> {
            Consumer<QoderBridgeClient.BridgeEvent> consumer = invocation.getArgument(0);
            for (QoderBridgeClient.BridgeEvent e : events) {
                consumer.accept(e);
            }
            return null;
        }).when(eventStream).read(any());
    }

    /** The event stream plays the events after a delay, then ends. */
    private void streamPlaysAfter(Duration delay, QoderBridgeClient.BridgeEvent... events) {
        doAnswer(invocation -> {
            Thread.sleep(delay.toMillis());
            Consumer<QoderBridgeClient.BridgeEvent> consumer = invocation.getArgument(0);
            for (QoderBridgeClient.BridgeEvent e : events) {
                consumer.accept(e);
            }
            return null;
        }).when(eventStream).read(any());
    }

    /** The event stream stays open (no events, no end) until {@link QoderBridgeClient.EventStream#close()}. */
    private void streamStaysOpen() {
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(invocation -> {
            released.await(30, TimeUnit.SECONDS);
            return null;
        }).when(eventStream).read(any());
        doAnswer(invocation -> {
            released.countDown();
            return null;
        }).when(eventStream).close();
    }

    private static QoderBridgeClient.BridgeEvent sessionStarted(long seq, String model) {
        return event(seq, "session_started", "{\"model\":\"" + model + "\"}");
    }

    private static QoderBridgeClient.BridgeEvent completed(long seq, String stopReason) {
        return event(seq, "completed", "{\"stopReason\":\"" + stopReason + "\"}");
    }

    private TaskResult executeHappyRun(UUID runId, TaskContext context) {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                event(3, "usage", "{\"credits\":null,\"inputTokens\":7,\"outputTokens\":3}"),
                completed(4, "end_turn"));
        return provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT, context);
    }

    // ---- identity / capabilities --------------------------------------------

    @Test
    void providerId_returnsQoder() {
        assertThat(provider.providerId()).isEqualTo("qoder");
    }

    @Test
    void supportsTaskExecution_returnsTrue() {
        assertThat(provider.supportsTaskExecution()).isTrue();
    }

    @Test
    void taskConstraints_resolvesQoderMaxTaskMinutes() {
        properties.setMaxTaskMinutes(30);
        TaskExecutionConstraints constraints = provider.taskConstraints();
        assertThat(constraints).isNotNull();
        assertThat(constraints.maxTaskDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void call_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> provider.call(UUID.randomUUID(), List.of(LlmMessage.user("hi")), List.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("turn-level");
    }

    // ---- happy path ----------------------------------------------------------

    @Test
    void executeTask_completesOnExplicitTerminalEvent_andReturnsOutputAndUsage() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        // G8 pinning note: a controlled preparation delay makes the remaining-window arithmetic
        // deterministic AND drift-detecting. The pre-G7 code sent the full caller window (120 s);
        // sending what is LEFT of it (120 s minus ~1.0 s of preparation) yields exactly 119 s for
        // any preparation time in the documented (1..2) s band, so the exact assertion below
        // fails on the full-window arithmetic instead of accepting both.
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return BRIDGE_URL;
        });
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                event(3, "usage", "{\"credits\":null,\"inputTokens\":7,\"outputTokens\":3}"),
                completed(4, "end_turn"));

        TaskResult result = provider.executeTask(agent(agentId), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.sessionId()).isEqualTo(BRIDGE_SESSION);
        assertThat(result.finalOutput()).isEqualTo("ok");
        assertThat(result.inputTokens()).isEqualTo(7);
        assertThat(result.outputTokens()).isEqualTo(3);
        assertThat(result.aborted()).isFalse();
        // Both counters arrived as measured numbers: the pair counts as reported usage.
        assertThat(result.usageReported()).isTrue();

        // One sandbox per agent, one bridge session per run; MCP is disabled in this test.
        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().runId()).isEqualTo("run-" + runId);
        assertThat(request.getValue().agentId()).isEqualTo(agentId.toString());
        assertThat(request.getValue().cwd()).isEqualTo("/workspace");
        assertThat(request.getValue().model()).isEqualTo("efficient");
        assertThat(request.getValue().mcpServers()).isEmpty();
        // G7/G8: the bridge receives what is LEFT of the caller's 2-minute window (it anchors the
        // deadline at receipt), pinned to the exact remaining-window value the 1.0 s preparation
        // yields; the pre-G7 full-window code sent 120 s and fails here.
        assertThat(request.getValue().deadlineSeconds()).isEqualTo(119L);
        verify(client).prompt(eq(BRIDGE_SESSION), startsWith(PROMPT_TEXT));
        // The client was pointed at the resolved sandbox endpoint with a non-PAT bridge token.
        assertThat(clientUrl.get()).isEqualTo(BRIDGE_URL);
        assertThat(clientToken.get()).isNotBlank().isNotEqualTo(PAT);
    }

    // ---- MCP worker wiring and the permission sink (R11) ---------------------

    private void enableMcp() {
        mcpProperties.setEnabled(true);
        mcpProperties.setPort(8080);
        // The governed provider only runs with MCP enabled in token mode (F1): the worker
        // entry is the authenticated channel, while a non-token mode would admit the
        // sandbox's header-less callers as the operator identity.
        mcpProperties.setAuthMode("token");
    }

    /** Pin the host-candidate source so the probe order is deterministic. */
    private void hostCandidates(String... addresses) {
        List<SandboxHostResolver.Candidate> fixed = java.util.Arrays.stream(addresses)
                .map(address -> new SandboxHostResolver.Candidate("nic-" + address, address))
                .toList();
        provider.setHostResolverFactoryForTest(override -> SandboxHostResolver.over(fixed, override));
    }

    @Test
    void mcpEnabled_probesCandidates_andPassesTheAriaServerWithTheWorkerToken() {
        enableMcp();
        hostCandidates("172.30.112.1", "10.0.0.5");
        UUID runId = UUID.randomUUID();
        when(runScopedCredentialService.issue(eq(runId), any(Instant.class))).thenReturn("wcp_test_worker_token");
        when(client.probe(eq("http://172.30.112.1:8080/mcp"), anyList()))
                .thenReturn(new QoderBridgeClient.ProbeResult(true, 503, "http 503 without a json-rpc result"));
        when(client.probe(eq("http://10.0.0.5:8080/mcp"), anyList()))
                .thenReturn(new QoderBridgeClient.ProbeResult(true, 200, "json-rpc result"));

        executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().mcpServers()).hasSize(1);
        QoderBridgeClient.McpServer server = request.getValue().mcpServers().get(0);
        assertThat(server.name()).isEqualTo("aria");
        // The first candidate answered non-2xx and was skipped; the second one wins.
        assertThat(server.url()).isEqualTo("http://10.0.0.5:8080/mcp");
        assertThat(server.headers()).containsExactly(
                new QoderBridgeClient.Header("Authorization", "Bearer wcp_test_worker_token"));
        verify(client).probe(eq("http://172.30.112.1:8080/mcp"), anyList());
        verify(client).probe(eq("http://10.0.0.5:8080/mcp"), anyList());
    }

    @Test
    void mcpEnabled_noReachableCandidate_leavesTheSessionEmpty_andRevokesTheCredential() {
        enableMcp();
        hostCandidates("172.30.112.1");
        UUID runId = UUID.randomUUID();
        when(runScopedCredentialService.issue(eq(runId), any(Instant.class))).thenReturn("wcp_test_unused");
        when(client.probe(anyString(), anyList()))
                .thenReturn(new QoderBridgeClient.ProbeResult(false, null, "probe timed out after 3000ms"));

        executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        // Fail closed: never an anonymous MCP entry, and the unused credential is revoked.
        assertThat(request.getValue().mcpServers()).isEmpty();
        verify(runScopedCredentialService).revoke(runId);
    }

    @Test
    void mcpDisabled_neverProbes_andPassesNoServers() {
        UUID runId = UUID.randomUUID();

        executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().mcpServers()).isEmpty();
        verify(client, never()).probe(anyString(), anyList());
        verify(runScopedCredentialService, never()).issue(any(), any());
    }

    @Test
    void mcpEnabledWithoutAUsablePort_warnsAndPassesNoServers() {
        // Enabled but unwireable (no usable port): the misconfiguration must be operator-visible
        // instead of silently degrading to an MCP-less run. Token mode is required to reach this
        // path — in a non-token mode the provider refuses the run outright (F1).
        mcpProperties.setEnabled(true);
        mcpProperties.setAuthMode("token");
        mcpProperties.setPort(0);
        UUID runId = UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(QoderAdkProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));
        } finally {
            logger.detachAppender(appender);
        }

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().mcpServers()).isEmpty();
        verify(client, never()).probe(anyString(), anyList());
        verify(runScopedCredentialService, never()).issue(any(), any());
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("MCP is enabled but the worker entry cannot be wired"));
    }

    @Test
    void runEnd_revokesTheWorkerCredentialAndTheRunGrants() {
        UUID runId = UUID.randomUUID();

        executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));

        verify(runScopedCredentialService).revoke(runId);
        verify(writeGrantService).revoke(runId);
    }

    @Test
    void permissionAsk_reachesTheCoordinator_withTheRunAgentAndSessionIdentity() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        String permissionPayload = "{\"requestId\":\"req-1\",\"toolCallId\":\"call_1\","
                + "\"toolName\":\"mcp__aria__write_file\",\"rawInput\":\"{}\",\"rawInputTruncated\":false,"
                + "\"options\":[{\"optionId\":\"a\",\"kind\":\"allow_once\",\"name\":\"Allow\"}]}";
        streamPlays(sessionStarted(1, "efficient"),
                event(2, "permission_request", permissionPayload),
                completed(3, "end_turn"));

        provider.executeTask(agent(agentId), runId, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(permissionCoordinator).handlePermissionEvent(eq(runId), eq(agentId), eq(BRIDGE_SESSION),
                payload.capture());
        assertThat(payload.getValue().path("requestId").asText()).isEqualTo("req-1");
    }

    @Test
    void aThrowingCoordinator_neverAffectsTheRun() {
        doThrow(new IllegalStateException("coordinator boom")).when(permissionCoordinator)
                .handlePermissionEvent(any(), any(), anyString(), any());
        String permissionPayload = "{\"requestId\":\"req-1\",\"toolCallId\":\"call_1\","
                + "\"toolName\":\"mcp__aria__write_file\",\"rawInput\":\"{}\",\"rawInputTruncated\":false,"
                + "\"options\":[{\"optionId\":\"a\",\"kind\":\"allow_once\",\"name\":\"Allow\"}]}";
        streamPlays(sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                event(3, "permission_request", permissionPayload),
                completed(4, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.finalOutput()).isEqualTo("ok");
    }

    @Test
    void executeTask_nullMaxDuration_fallsBackToQoderMaxTaskMinutes() {
        properties.setMaxTaskMinutes(7);
        UUID runId = UUID.randomUUID();
        // G8 pinning note: same controlled preparation delay as the caller-window pin above; the
        // pre-G7 full-window code sent 420 s and fails the exact assertion below.
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return BRIDGE_URL;
        });
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT, new TaskContext(1, null));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        // G7/G8: the remaining part of the 7-minute fallback window, not a restarted one.
        assertThat(request.getValue().deadlineSeconds()).isEqualTo(419L);
    }

    @Test
    void executeTask_callerMaxDuration_isHonored_notSilentlyIgnored() {
        // The context deadline (300 ms) must be the effective one: max-task-minutes is 45.
        properties.setMaxTaskMinutes(45);
        streamStaysOpen();
        UUID runId = UUID.randomUUID();
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMillis(300))))
                .isInstanceOf(TaskExecutionException.class)
                .extracting(e -> ((TaskExecutionException) e).cause())
                .isEqualTo(TaskExecutionException.Cause.TIMEOUT);

        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        assertThat(elapsedMillis)
                .as("the 300 ms caller deadline must fire instead of the 45-minute property fallback")
                .isLessThan(10_000L);
    }

    // ---- deadline abort path -------------------------------------------------

    @Test
    void executeTask_deadlineAbort_cancelsSessionThenKillsSandbox() throws InterruptedException {
        streamStaysOpen();
        UUID runId = UUID.randomUUID();
        when(client.cancel(BRIDGE_SESSION)).thenReturn(true);

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMillis(300))))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("deadline");

        // Cancel is issued by the timeout path and again (idempotently) by the run-end
        // cleanup in runTask's finally: call-count assertions deliberately do not depend
        // on which path issued the cancel first.
        verify(client, atLeastOnce()).cancel(BRIDGE_SESSION);
        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        verify(sandboxLifecycle, never()).renewSandbox(anyString(), any());
        // Design §5.3 ordering: the session cancel must precede the sandbox kill (the kill is
        // only the last bounded fallback). The idempotent cancel issued by the run-end cleanup
        // lands after the kill and does not disturb this order; the times(1) form is required
        // because an InOrder check with atLeastOnce consumes the trailing cancel of that mock
        // and then finds no kill after it (probe: b6fx-inorder-probe-atleastonce.log).
        InOrder order = inOrder(client, sandboxLifecycle);
        order.verify(client).cancel(BRIDGE_SESSION);
        order.verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
    }

    @Test
    void executeTask_deadlineAbort_whenStopProven_doesNotKillSandbox() {
        // The bridge ends the stream in reaction to cancel: the run's stop is proven, so the
        // prepared sandbox survives (kill is only the last bounded fallback).
        CountDownLatch cancelled = new CountDownLatch(1);
        doAnswer(invocation -> {
            cancelled.await(10, TimeUnit.SECONDS);
            return null;
        }).when(eventStream).read(any());
        when(client.cancel(BRIDGE_SESSION)).thenAnswer(invocation -> {
            cancelled.countDown();
            return true;
        });

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMillis(300))))
                .isInstanceOf(TaskExecutionException.class);

        verify(sandboxLifecycle, never()).killSandbox(anyString());
    }

    // ---- busy rejection ------------------------------------------------------

    /**
     * The live negative of the parameterized hand-over pin: every non-terminal status — the
     * complement of the guard's terminal set (PENDING / INITIALIZING / RUNNING / PAUSED) — keeps
     * the typed busy rejection, never a handover. The EnumSource excludes the four terminal
     * statuses, so a newly added non-terminal status is covered as soon as it exists.
     */
    @ParameterizedTest(name = "previous run live as {0}")
    @EnumSource(value = RunStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"COMPLETED", "FAILED", "CANCELLED", "ABORTED"})
    void executeTask_secondConcurrentRunForOneAgent_failsWithTypedBusyError(RunStatus liveStatus) throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID firstRun = UUID.randomUUID();
        UUID secondRun = UUID.randomUUID();
        CountDownLatch inSessionCreation = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            inSessionCreation.countDown();
            releaseFirstRun.await(10, TimeUnit.SECONDS);
            return BRIDGE_SESSION;
        }).when(client).createSession(any());
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));
        // The live run's row proves it is not terminal: the terminal-aware slot guard added for
        // the kanban re-dispatch must still reject here, never hand the slot over.
        lenient().when(runRepository.findById(firstRun)).thenReturn(Optional.of(
                Run.builder().id(firstRun).agentId(agentId).status(liveStatus).build()));

        Thread first = new Thread(() -> provider.executeTask(agent(agentId), firstRun, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))), "first-run");
        first.start();
        assertThat(inSessionCreation.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> provider.executeTask(agent(agentId), secondRun, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("active run");
        // The rejection changed nothing: the live run still owns the slot.
        assertThat(provider.activeRunsForTest()).containsEntry(agentId, firstRun);

        releaseFirstRun.countDown();
        first.join(10_000);
        assertThat(first.isAlive()).isFalse();
        // The rejected run never reached session creation, and only one sandbox was prepared.
        verify(client, times(1)).createSession(any());
        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
    }

    // ---- pending abort -------------------------------------------------------

    @Test
    void executeTask_abortDuringSessionCreation_isHonoredBeforePrompt() {
        UUID runId = UUID.randomUUID();
        doAnswer(invocation -> {
            // The cancel lands in the sandbox-prep / session-creation window.
            provider.abortTask(runId);
            return BRIDGE_SESSION;
        }).when(client).createSession(any());

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED));

        verify(client).cancel(BRIDGE_SESSION);
        verify(client, never()).prompt(anyString(), anyString());
        // The pre-execution abort throw must still run the run-end cleanup (no leaked registrations).
        assertThat(provider.runSessionsForTest()).doesNotContainKey(runId);
        assertThat(provider.runClientsForTest()).doesNotContainKey(runId);
        assertThat(provider.runInstancesForTest()).doesNotContainKey(runId);
        assertThat(provider.runPumpsForTest()).doesNotContainKey(runId);
    }

    @Test
    void abortTask_noInFlightRun_isANoOp() {
        provider.abortTask(UUID.randomUUID());
        verifyNoInteractions(client);
    }

    @Test
    void abortTask_inFlightRun_cancelsTheBridgeSession() throws Exception {
        UUID runId = UUID.randomUUID();
        CountDownLatch inSession = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(invocation -> {
            inSession.countDown();
            return BRIDGE_SESSION;
        }).when(client).createSession(any());
        doAnswer(invocation -> {
            released.await(30, TimeUnit.SECONDS);
            return null;
        }).when(eventStream).read(any());
        doAnswer(invocation -> {
            released.countDown();
            return null;
        }).when(eventStream).close();
        doAnswer(invocation -> {
            released.countDown();
            return null;
        }).when(sandboxLifecycle).killSandbox(anyString());
        when(client.cancel(BRIDGE_SESSION)).thenReturn(true);

        Thread run = new Thread(() -> {
            try {
                provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                        new TaskContext(1, Duration.ofSeconds(5)));
            } catch (RuntimeException ignored) {
                // The engine discards the provider result of a cancelled run; the test only
                // asserts the cancel was delivered to the bridge.
            }
        }, "abort-run");
        run.start();
        assertThat(inSession.await(10, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> provider.runSessionsForTest().containsKey(runId));

        provider.abortTask(runId);

        // Idempotent: the abort path cancels once and the run-end cleanup cancels again.
        verify(client, atLeastOnce()).cancel(BRIDGE_SESSION);
        run.join(10_000);
        assertThat(run.isAlive()).isFalse();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            Thread.sleep(20);
        }
    }

    // ---- bridge startup / health --------------------------------------------

    @Test
    void prepareAgent_startBridge_whenImageCmdDidNotStartIt() {
        when(client.health())
                .thenThrow(new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "connection refused"))
                .thenReturn(new QoderBridgeClient.Health("ok", "1.1.41"));
        UUID agentId = UUID.randomUUID();

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxLifecycle).runBackgroundCommand(eq(SANDBOX_ID),
                eq(QoderAdkProvider.BRIDGE_START_COMMAND), anyMap());
        verify(sandboxLifecycle, atLeastOnce()).getSandboxUrl(SANDBOX_ID, 4097);
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    @Test
    void prepareAgent_bridgeNeverBecomesReady_failsSandboxUnavailable() {
        when(client.health()).thenThrow(
                new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "connection refused"));
        UUID agentId = UUID.randomUUID();

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE))
                .hasMessageContaining("did not become ready");
        // A failed preparation must not leak a sandbox.
        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
    }

    @Test
    void prepareAgent_passesThePatAndPerSandboxBridgeTokenThroughTheSandboxEnvironment() {
        UUID agentId = UUID.randomUUID();

        provider.prepareAgent(agentId, agent(agentId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(sandboxLifecycle).createSandbox(eq(agentId), eq(IMAGE), env.capture());
        assertThat(env.getValue())
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", PAT)
                .containsEntry("PORT", "4097");
        assertThat(env.getValue().get("BRIDGE_TOKEN"))
                .as("a per-sandbox random bridge token, never the PAT")
                .isNotBlank()
                .isNotEqualTo(PAT);
    }

    @Test
    void prepareAgent_missingCredential_failsWithProviderErrorBeforeCreatingASandbox() {
        when(credentialService.read("qoder")).thenThrow(new RuntimeCredentialException(
                RuntimeCredentialException.Cause.NOT_CONFIGURED,
                "No runtime credential configured for provider qoder"));
        UUID agentId = UUID.randomUUID();

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("credential");
        verify(sandboxLifecycle, never()).createSandbox(any(), anyString(), anyMap());
    }

    // ---- terminal failure paths ---------------------------------------------

    @Test
    void executeTask_unknownModelFromBridge_isReportedAsATypedProviderError() {
        when(client.createSession(any())).thenThrow(new QoderBridgeException(
                QoderBridgeException.Cause.INVALID_REQUEST,
                "Qoder bridge POST /sessions failed: HTTP 400 UNKNOWN_MODEL — model 'nope' is not among"
                        + " the CLI's advertised models [auto, efficient]"));

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("UNKNOWN_MODEL")
                .hasMessageContaining("efficient");
        verify(client, never()).prompt(anyString(), anyString());
    }

    @Test
    void executeTask_governanceStop_failsTheRunAsAGovernanceError() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "failed", "{\"reason\":\"run stopped by governance: mode escalation\","
                        + "\"code\":\"GOVERNANCE_STOP\"}"));

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("governance");
    }

    @Test
    void executeTask_failedEvent_failsTheRunWithTheReportedReason() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "failed", "{\"reason\":\"the CLI died\",\"code\":\"ACP_PROCESS_EXITED\"}"));

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("ACP_PROCESS_EXITED")
                .hasMessageContaining("the CLI died");
    }

    @Test
    void executeTask_cleanStreamEndWithoutTerminalSignal_isNotATaskCompletion() {
        // Only non-terminal events, then a clean server-side EOF: must NOT become a result.
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"partial\"}"));

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("without an explicit terminal event");
    }

    @Test
    void executeTask_cancelledStopReason_isReportedAsAnAbortedResult() {
        streamPlays(sessionStarted(1, "efficient"), event(2, "agent_message", "{\"text\":\"partial\"}"),
                completed(3, "cancelled"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.aborted()).isTrue();
        assertThat(result.finalOutput()).isEqualTo("partial");
    }

    // ---- progress events -----------------------------------------------------

    @Test
    void executeTask_progressEvents_carryBridgeSequenceAndTheQoderModel() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"ok\"}"),
                completed(3, "end_turn"));

        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        provider.executeTask(agent(agentId), runId, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        RunProgressEvent model = published.stream()
                .filter(e -> e.getKind() == RunProgressEvent.Kind.STATUS)
                .filter(e -> e.getContent().contains("qoder.model="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no qoder.model progress event published: " + describe(published)));
        assertThat(model.getContent()).isEqualTo("qoder.model=efficient");
        assertThat(model.getSeq()).as("bridge event sequence").isEqualTo(1L);
        assertThat(model.getRunId()).isEqualTo(runId);
        assertThat(model.getAgentId()).isEqualTo(agentId);

        RunProgressEvent message = published.stream()
                .filter(e -> e.getKind() == RunProgressEvent.Kind.THINKING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no text progress event published: " + describe(published)));
        assertThat(message.getContent()).isEqualTo("ok");
        assertThat(message.getSeq()).isEqualTo(2L);
    }

    @Test
    void executeTask_mapsEveryBridgeEventTypeToProgressEvents() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "agent_message", "{\"text\":\"working\"}"),
                event(3, "tool_call", "{\"toolCallId\":\"t1\",\"toolName\":\"Write\",\"kind\":\"edit\",\"status\":\"in_progress\"}"),
                event(4, "tool_call_update", "{\"toolCallId\":\"t1\",\"status\":\"completed\"}"),
                event(5, "tool_call_update", "{\"toolCallId\":\"t2\",\"status\":\"in_progress\"}"),
                event(6, "permission_request", "{\"requestId\":\"p1\",\"toolCallId\":\"t2\",\"toolName\":\"Bash\","
                        + "\"title\":\"run a command\",\"inputDigest\":\"abc\",\"expiresAt\":\"2026-01-01T00:00:00Z\"}"),
                event(7, "mode_changed", "{\"currentModeId\":\"default\"}"),
                event(8, "future_bridge_event", "{\"anything\":true}"),
                event(9, "usage", "{\"credits\":null,\"inputTokens\":4,\"outputTokens\":0}"),
                completed(10, "end_turn"));

        provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(published).extracting(RunProgressEvent::getKind)
                .contains(RunProgressEvent.Kind.THINKING,
                        RunProgressEvent.Kind.TOOL_CALL,
                        RunProgressEvent.Kind.TOOL_RESULT,
                        RunProgressEvent.Kind.STATUS);
        assertThat(published).extracting(RunProgressEvent::getToolName)
                .contains("Write");
        assertThat(published).extracting(RunProgressEvent::getContent)
                .anyMatch(text -> text.contains("permission_request") && text.contains("Bash"))
                .anyMatch(text -> text.contains("mode_changed"))
                .doesNotContain("future_bridge_event");
        // Sequence numbers mirror the bridge's monotonic event sequence, never restart.
        assertThat(published).extracting(RunProgressEvent::getSeq).isSorted();
    }

    private static String describe(List<RunProgressEvent> events) {
        return events.stream()
                .map(e -> e.getKind() + ":" + e.getSeq() + ":" + e.getContent())
                .toList()
                .toString();
    }

    @Test
    void executeTask_renewHeartbeat_extendsTheSandboxWhileTheRunIsInFlight() {
        properties.setSandboxRenewInterval(Duration.ofMillis(40));
        streamPlaysAfter(Duration.ofMillis(250), sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        verify(sandboxLifecycle, atLeastOnce()).renewSandbox(eq(SANDBOX_ID), any(Duration.class));
    }

    // ---- health / lifecycle --------------------------------------------------

    @Test
    void isHealthy_noInstance_isFalse() {
        assertThat(provider.isHealthy(UUID.randomUUID())).isFalse();
    }

    @Test
    void isHealthy_probesTheBridgeAndReportsFalseWhenItThrows() {
        UUID agentId = UUID.randomUUID();
        provider.prepareAgent(agentId, agent(agentId));
        when(client.health()).thenThrow(
                new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "gone"));

        assertThat(provider.isHealthy(agentId)).isFalse();
    }

    @Test
    void isHealthy_healthyBridge_isTrue() {
        UUID agentId = UUID.randomUUID();
        provider.prepareAgent(agentId, agent(agentId));

        assertThat(provider.isHealthy(agentId)).isTrue();
    }

    @Test
    void probeRuntimeHealth_isSideEffectFree() {
        UUID agentId = UUID.randomUUID();
        assertThat(provider.probeRuntimeHealth(agentId)).isEqualTo(io.aria.conductor.execution.adk.AdkProvider.RuntimeHealth.NOT_STARTED);
        provider.prepareAgent(agentId, agent(agentId));
        assertThat(provider.probeRuntimeHealth(agentId)).isEqualTo(io.aria.conductor.execution.adk.AdkProvider.RuntimeHealth.REACHABLE);
        when(client.health()).thenThrow(
                new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "gone"));
        assertThat(provider.probeRuntimeHealth(agentId)).isEqualTo(io.aria.conductor.execution.adk.AdkProvider.RuntimeHealth.UNREACHABLE);
        // A read-path probe must never tear a live sandbox down.
        verify(sandboxLifecycle, never()).killSandbox(anyString());
    }

    @Test
    void isServiceHealthy_delegatesToTheSandboxLifecycle() {
        when(sandboxLifecycle.isServerHealthy()).thenReturn(true);
        assertThat(provider.isServiceHealthy()).isTrue();
        verify(sandboxLifecycle).isServerHealthy();
    }

    @Test
    void executeTask_reusesThePreparedSandboxForTheSameAgent() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent);
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent, UUID.randomUUID(), PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        verify(sandboxLifecycle, times(1)).createSandbox(any(), anyString(), anyMap());
    }

    @Test
    void shutdownAgent_killsSandboxAndForgetsTheInstance() {
        UUID agentId = UUID.randomUUID();
        provider.prepareAgent(agentId, agent(agentId));

        provider.shutdownAgent(agentId);

        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);
        // The injected/client from the factory is owned by the caller in tests: never closed here.
        verify(client, never()).close();
    }

    @Test
    void shutdownAll_tearsDownEveryPreparedSandbox() {
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap())).thenReturn("sb-1", "sb-2");
        when(sandboxLifecycle.getSandboxUrl("sb-1", 4097)).thenReturn(BRIDGE_URL);
        when(sandboxLifecycle.getSandboxUrl("sb-2", 4097)).thenReturn(BRIDGE_URL);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        provider.prepareAgent(first, agent(first));
        provider.prepareAgent(second, agent(second));

        provider.shutdownAll();

        verify(sandboxLifecycle).killSandbox("sb-1");
        verify(sandboxLifecycle).killSandbox("sb-2");
        assertThat(provider.instancesForTest()).isEmpty();
    }

    @Test
    void shutdownAgent_withoutAnInstance_isANoOp() {
        provider.shutdownAgent(UUID.randomUUID());
        verify(sandboxLifecycle, never()).killSandbox(anyString());
    }

    // ---- fix-round 1, item 1: the run's end terminates its bridge session ----------

    @Test
    void executeTask_completedRun_endsTheBridgeSessionOnce() {
        UUID runId = UUID.randomUUID();
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.aborted()).isFalse();
        // The bridge keeps the session's qodercli child alive after prompt_result: the run's
        // end must terminate it (cancel is idempotent on the bridge side).
        verify(client, times(1)).cancel(BRIDGE_SESSION);
        assertThat(provider.runSessionsForTest()).doesNotContainKey(runId);
    }

    @Test
    void executeTask_cancelledStopReason_stillEndsTheSession() {
        streamPlays(sessionStarted(1, "efficient"), completed(2, "cancelled"));

        provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        verify(client, atLeastOnce()).cancel(BRIDGE_SESSION);
    }

    // ---- fix-round 1, item 3: run registrations never leak on failure paths --------

    @Test
    void executeTask_createSessionFailure_doesNotLeakRunRegistrations_andNeverCancels() {
        UUID runId = UUID.randomUUID();
        when(client.createSession(any())).thenThrow(new QoderBridgeException(
                QoderBridgeException.Cause.UNREACHABLE, "bridge connection refused"));

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));

        assertThat(provider.runSessionsForTest()).doesNotContainKey(runId);
        assertThat(provider.runClientsForTest()).doesNotContainKey(runId);
        assertThat(provider.runInstancesForTest()).doesNotContainKey(runId);
        assertThat(provider.runPumpsForTest()).doesNotContainKey(runId);
        // No session ever existed: nothing may be cancelled for a null session.
        verify(client, never()).cancel(anyString());
    }

    // ---- fix-round 1, item 2: unknown usage stays unknown --------------------------

    @Test
    void executeTask_usageWithZeroPlaceholderCounters_reportsUnknownUsageNotZero() {
        // A5 reality: the CLI reports literal zeros over ACP — unavailable accounting,
        // never a measured "0 tokens" result (design §4.2, acceptance item 10).
        UUID runId = UUID.randomUUID();
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "usage", "{\"credits\":null,\"inputTokens\":0,\"outputTokens\":0}"),
                completed(3, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.usageReported()).isFalse();
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
        assertThat(usagePulse()).isEqualTo("qoder.usage input=n/a output=n/a credits=n/a");
    }

    @Test
    void executeTask_usageEventWithoutCounters_rendersNa_andKeepsTheOutcomeUnknown() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "usage", "{\"credits\":null}"),
                completed(3, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.usageReported()).isFalse();
        assertThat(usagePulse()).isEqualTo("qoder.usage input=n/a output=n/a credits=n/a");
    }

    @Test
    void executeTask_partialUsageReport_keepsUsageUnknownButThePulseShowsTheKnownValue() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "usage", "{\"credits\":null,\"inputTokens\":7}"),
                completed(3, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        // A partial report must not be half-fabricated into the budget: no measured pair, no sum.
        assertThat(result.usageReported()).isFalse();
        assertThat(result.inputTokens()).isZero();
        assertThat(result.outputTokens()).isZero();
        assertThat(usagePulse()).isEqualTo("qoder.usage input=7 output=n/a credits=n/a");
    }

    @Test
    void executeTask_usageEventsAccumulate_reportOnlyMeasuredCounters() {
        streamPlays(
                sessionStarted(1, "efficient"),
                event(2, "usage", "{\"credits\":null,\"inputTokens\":4,\"outputTokens\":0}"),
                event(3, "usage", "{\"credits\":null,\"inputTokens\":6,\"outputTokens\":5}"),
                completed(4, "end_turn"));

        TaskResult result = provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)));

        // The placeholder output zero of the first frame is unknown, not a 0 to add.
        assertThat(result.usageReported()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(10);
        assertThat(result.outputTokens()).isEqualTo(5);
    }

    @Test
    void terminalResult_logsReportedNumbers_whenMeasured_andTheNotReportedMarkerOtherwise() {
        Logger logger = (Logger) LoggerFactory.getLogger(QoderAdkProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Unknown case: the CLI's placeholder zeros must never be logged as "0 input / 0 output".
            streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));
            provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                    new TaskContext(1, Duration.ofMinutes(2)));

            // Reported case: the measured numbers are logged.
            streamPlays(sessionStarted(1, "efficient"),
                    event(2, "usage", "{\"credits\":null,\"inputTokens\":7,\"outputTokens\":3}"),
                    completed(3, "end_turn"));
            provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(), PROMPT_TEXT,
                    new TaskContext(1, Duration.ofMinutes(2)));

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("token usage not reported by the provider"))
                    .anyMatch(message -> message.contains("7 input / 3 output tokens"))
                    .noneMatch(message -> message.contains("0 input / 0 output"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- fix-round 1, item 4: execd / bridge-start retry halves --------------------

    @Test
    void prepareAgent_execProbeRetriesUntilTheExecChannelAccepts() {
        UUID agentId = UUID.randomUUID();
        when(sandboxLifecycle.runCommand(SANDBOX_ID, "true"))
                .thenThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Command execution failed in sandbox sb-1: connection refused"))
                .thenThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Command execution failed in sandbox sb-1: connection refused"))
                .thenReturn("");

        provider.prepareAgent(agentId, agent(agentId));

        // The exec channel lags sandbox creation: the probe must retry, then preparation proceeds.
        verify(sandboxLifecycle, times(3)).runCommand(SANDBOX_ID, "true");
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    @Test
    void prepareAgent_reissuesBridgeStart_untilTheSecondStartBringsItUp() {
        UUID agentId = UUID.randomUUID();
        provider.setBridgeStartRetryIntervalForTest(Duration.ofMillis(100));
        provider.setBridgeReadyTimeoutForTest(Duration.ofSeconds(3));
        provider.setBridgeReadyPollIntervalForTest(Duration.ofMillis(20));
        AtomicInteger starts = new AtomicInteger();
        doAnswer(invocation -> {
            starts.incrementAndGet();
            return null;
        }).when(sandboxLifecycle).runBackgroundCommand(eq(SANDBOX_ID), anyString(), anyMap());
        when(client.health()).thenAnswer(invocation -> {
            if (starts.get() >= 2) {
                return new QoderBridgeClient.Health("ok", "1.1.41");
            }
            throw new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "connection refused");
        });

        provider.prepareAgent(agentId, agent(agentId));

        // The fire-and-forget launch issued before the bridge could answer is lost: the bounded
        // re-issue is what brings the bridge up (exactly twice — the third would be a runaway).
        verify(sandboxLifecycle, times(2)).runBackgroundCommand(eq(SANDBOX_ID),
                eq(QoderAdkProvider.BRIDGE_START_COMMAND), anyMap());
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    // ---- fix-round 1, item 5: one sandbox preparation per agent, no second owner ----

    @Test
    void getOrPrepareInstance_concurrentCallers_shareOneSandboxPreparation() throws Exception {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        CountDownLatch inCreate = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap())).thenAnswer(invocation -> {
            inCreate.countDown();
            releaseCreate.await(10, TimeUnit.SECONDS);
            return SANDBOX_ID;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> provider.prepareAgent(agentId, agent));
            assertThat(inCreate.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> provider.prepareAgent(agentId, agent));
            Thread.sleep(150); // let the second caller join the in-flight preparation
            releaseCreate.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    @Test
    void getOrPrepareInstance_afterTheOwnerFailed_aLaterCallerRetries() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap()))
                .thenThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "docker daemon hiccup"))
                .thenReturn(SANDBOX_ID);

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));

        // A later caller must not adopt the failed preparation.
        provider.prepareAgent(agentId, agent);

        assertThat(provider.instancesForTest()).containsKey(agentId);
        verify(sandboxLifecycle, times(2)).createSandbox(any(), eq(IMAGE), anyMap());
    }

    @Test
    void getOrPrepareInstance_keepsTheCompletedPreparation_forLateCallers() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent);

        // The completed future stays in the preparation map: a late caller must find a
        // mapping (never null) and adopt it instead of racing to become a second owner.
        assertThat(provider.preparingForTest()).containsKey(agentId);
        assertThat(provider.preparingForTest().get(agentId)).isDone();

        provider.prepareAgent(agentId, agent); // late caller: adopts, no second sandbox
        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
    }

    @Test
    void getOrPrepareInstance_afterTheInstanceWasRemoved_aLateCallerReprepares() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent);
        // The instance is killed and forgotten, but its completed future stays in `preparing`.
        provider.shutdownAgent(agentId);

        clearInvocations(sandboxLifecycle);
        lenient().when(sandboxLifecycle.getSandboxUrl(anyString(), eq(4097))).thenReturn(BRIDGE_URL);
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap())).thenReturn("sb-2");

        // The kept future is done but its instance is gone: a late caller must re-prepare
        // (never adopt the killed sandbox) and the next late caller must adopt the fresh
        // preparation instead of becoming a second owner.
        provider.prepareAgent(agentId, agent);
        provider.prepareAgent(agentId, agent);

        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-2");
    }

    @Test
    void getOrPrepareInstance_lateCallerThatSawAStaleInstance_neverBecomesASecondOwner() throws Exception {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent); // inst1 registered (sandbox sb-1)

        CountDownLatch bProbeEntered = new CountDownLatch(1);
        CountDownLatch releaseBProbe = new CountDownLatch(1);
        // Starts at 1: the initial preparation above already consumed the setUp stub (sb-1).
        AtomicInteger sandboxCounter = new AtomicInteger(1);
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap()))
                .thenAnswer(invocation -> "sb-" + sandboxCounter.incrementAndGet());
        lenient().when(sandboxLifecycle.getSandboxUrl(anyString(), eq(4097))).thenReturn(BRIDGE_URL);
        Map<String, AtomicInteger> probesByThread = new ConcurrentHashMap<>();
        when(client.health()).thenAnswer(invocation -> {
            String thread = Thread.currentThread().getName();
            int probe = probesByThread.computeIfAbsent(thread, key -> new AtomicInteger()).getAndIncrement();
            if ("caller-B".equals(thread) && probe == 0) {
                // caller-B reads inst1, then freezes inside its reachability probe.
                bProbeEntered.countDown();
                releaseBProbe.await(10, TimeUnit.SECONDS);
                throw new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "stale instance");
            }
            if ("caller-C".equals(thread) && probe == 0) {
                // caller-C finds inst1 unreachable and rebuilds while caller-B is frozen.
                throw new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE, "stale instance");
            }
            return new QoderBridgeClient.Health("ok", "1.1.41");
        });

        AtomicReference<Throwable> callerBFailure = new AtomicReference<>();
        Thread callerB = new Thread(() -> {
            try {
                provider.prepareAgent(agentId, agent);
            } catch (Throwable t) {
                callerBFailure.set(t);
            }
        }, "caller-B");
        callerB.start();
        assertThat(bProbeEntered.await(10, TimeUnit.SECONDS))
                .as("caller-B must enter its stale-instance probe").isTrue();

        Thread callerC = new Thread(() -> provider.prepareAgent(agentId, agent), "caller-C");
        callerC.start();
        callerC.join(10_000);
        assertThat(callerC.isAlive()).as("caller-C must finish its rebuild").isFalse();

        releaseBProbe.countDown();
        callerB.join(10_000);
        assertThat(callerB.isAlive()).as("caller-B must finish").isFalse();

        // Exactly two sandboxes: inst1 (killed and replaced by caller-C) and C's rebuild.
        // A third create means caller-B became a second owner and leaked caller-C's sandbox.
        verify(sandboxLifecycle, times(2)).createSandbox(any(), eq(IMAGE), anyMap());
        assertThat(provider.instancesForTest().get(agentId).sandboxId())
                .as("caller-B must adopt the live instance, never overwrite it with a second sandbox")
                .isEqualTo("sb-2");
        assertThat(callerBFailure.get()).isNull();
    }

    // ---- fix-round 1, item 6: the bridge token never leaks through toString ---------

    @Test
    void qoderInstance_toString_doesNotLeakTheBridgeToken() {
        UUID agentId = UUID.randomUUID();
        provider.prepareAgent(agentId, agent(agentId));

        QoderAdkProvider.QoderInstance instance = provider.instancesForTest().get(agentId);
        assertThat(instance.bridgeToken()).isNotBlank();
        assertThat(instance.toString())
                .contains("QoderInstance[")
                .contains("bridgeToken=<redacted>")
                .doesNotContain(instance.bridgeToken());
    }

    // ---- fix-round 1, item 7: timing windows ---------------------------------------

    @Test
    void executeTask_prepTime_countsAgainstTheCallerDeadline() {
        // Sandbox prep (here: endpoint resolution) takes at least the whole caller window: the
        // 1 s stub sleep alone equals it, and the surrounding prep work makes it strictly longer.
        streamStaysOpen();
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return BRIDGE_URL;
        });
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), UUID.randomUUID(),
                PROMPT_TEXT, new TaskContext(1, Duration.ofMillis(1000))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.TIMEOUT));

        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        assertThat(elapsedMillis)
                .as("prep time must count against the caller's window (window 1000ms + prep 1000ms)")
                .isLessThan(2000L);
    }

    @Test
    void abortTask_betweenSessionRegistrationAndPumpStart_doesNotKillTheSandbox() throws Exception {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent);
        UUID runId = UUID.randomUUID();

        CountDownLatch pumpConstructionEntered = new CountDownLatch(1);
        CountDownLatch releasePumpConstruction = new CountDownLatch(1);
        doAnswer(invocation -> {
            // Freeze the runner between runSessions.put and runPumps.put: the pump does not
            // exist yet while the session is already registered.
            pumpConstructionEntered.countDown();
            releasePumpConstruction.await(10, TimeUnit.SECONDS);
            return eventStream;
        }).when(client).openEventStream(anyString());
        streamPlays(); // clean stream end once the pump starts

        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                provider.executeTask(agent, runId, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));
            } catch (Throwable t) {
                runFailure.set(t);
            }
        }, "in-flight-run");
        run.start();
        assertThat(pumpConstructionEntered.await(10, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> provider.runSessionsForTest().containsKey(runId));

        // The abort lands after runSessions.put but before runPumps.put: the pump cannot prove
        // the stop, yet the accepted cancel already terminated the session — no kill needed.
        provider.abortTask(runId);
        releasePumpConstruction.countDown();
        run.join(10_000);
        assertThat(run.isAlive()).isFalse();

        assertThat(runFailure.get()).isInstanceOf(TaskExecutionException.class)
                .satisfies(t -> assertThat(((TaskExecutionException) t).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED));
        verify(sandboxLifecycle, never()).killSandbox(anyString());
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    /** The first {@code qoder.usage} pulse content published during the last run. */
    private String usagePulse() {
        return published.stream()
                .map(RunProgressEvent::getContent)
                .filter(content -> content != null && content.startsWith("qoder.usage"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no qoder.usage progress event published: " + describe(published)));
    }

    // ---- G1b F1: the governed provider refuses a non-token MCP configuration ----------
    //
    // Design lines 309-311: the unauthenticated operator mode must be unreachable from Qoder
    // sandboxes. With MCP enabled in a non-token auth mode the /mcp endpoint admits a
    // header-less caller as the operator identity, so the governed provider refuses the run
    // outright instead of letting its sandbox hold an operator-equivalent route.

    @Test
    void executeTask_mcpEnabledWithANonTokenAuthMode_refusesWithATypedErrorAndStartsNothing() {
        // mcpProperties keeps its deployment default authMode "none" here. A resolvable MCP
        // candidate is irrelevant: the refusal is about the endpoint's operator admittance.
        mcpProperties.setEnabled(true);
        mcpProperties.setPort(8080);
        hostCandidates("172.30.112.1");
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("aria.mcp.auth-mode=token");

        // The refusal sits at the earliest boundary: no sandbox, no CLI process, no session,
        // no prompt and no run-scoped credential ever existed for this configuration.
        verify(sandboxLifecycle, never()).createSandbox(any(), anyString(), anyMap());
        verifyNoInteractions(client);
        verify(runScopedCredentialService, never()).issue(any(), any());
        assertThat(provider.instancesForTest()).isEmpty();
    }

    @Test
    void prepareAgent_mcpEnabledWithANonTokenAuthMode_refusesBeforeCreatingASandbox() {
        mcpProperties.setEnabled(true);
        mcpProperties.setPort(8080);
        UUID agentId = UUID.randomUUID();

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("aria.mcp.auth-mode=token");

        // Refusing at the prepare entry means the sandbox, its bridge process and its CLI never start.
        verify(sandboxLifecycle, never()).createSandbox(any(), anyString(), anyMap());
        assertThat(provider.instancesForTest()).isEmpty();
    }

    @Test
    void executeTask_mcpEnabledWithTokenAuthMode_startsTheRunNormally() {
        enableMcp();
        hostCandidates("10.0.0.5");
        UUID runId = UUID.randomUUID();
        when(runScopedCredentialService.issue(eq(runId), any(Instant.class))).thenReturn("wcp_test_worker_token");
        when(client.probe(anyString(), anyList()))
                .thenReturn(new QoderBridgeClient.ProbeResult(true, 200, "json-rpc result"));

        TaskResult result = executeHappyRun(runId, new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(result.aborted()).isFalse();
        assertThat(result.finalOutput()).isEqualTo("ok");
        verify(client).createSession(any());
    }

    @Test
    void executeTask_mcpDisabledWithANonTokenAuthMode_stillStartsTheRun() {
        // The refusal stays narrow: with MCP disabled there is no worker channel to protect,
        // so the auth mode is irrelevant and the run proceeds.
        mcpProperties.setEnabled(false);
        TaskResult result = executeHappyRun(UUID.randomUUID(), new TaskContext(1, Duration.ofMinutes(2)));
        assertThat(result.finalOutput()).isEqualTo("ok");
        verify(client, never()).probe(anyString(), anyList());
    }

    // ---- G1b F5: a cancel landing during sandbox preparation ---------------------------

    @Test
    void executeTask_abortDuringSandboxPreparation_isHonored_andNeverCreatesASession() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Agent agent = agent(agentId);
        CountDownLatch prepEntered = new CountDownLatch(1);
        CountDownLatch releasePrep = new CountDownLatch(1);
        // Hold the sandbox preparation (endpoint resolution) so the abort lands inside it.
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            prepEntered.countDown();
            releasePrep.await(10, TimeUnit.SECONDS);
            return BRIDGE_URL;
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                provider.executeTask(agent, runId, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "preparing-run");
        run.start();
        assertThat(prepEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // The engine's abort lands while the runner is still blocked in preparation.
        provider.abortTask(runId);
        releasePrep.countDown();
        run.join(10_000);
        assertThat(run.isAlive()).isFalse();

        assertThat(failure.get()).isInstanceOf(TaskExecutionException.class)
                .satisfies(t -> assertThat(((TaskExecutionException) t).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED));
        // A run cancelled during preparation must never reach the bridge session or the prompt.
        verify(client, never()).createSession(any());
        verify(client, never()).prompt(anyString(), anyString());
        verify(client, never()).cancel(anyString());
        verify(sandboxLifecycle, never()).killSandbox(anyString());
        // No registration survives the aborted run.
        assertThat(provider.runRegistrationsForTest()).doesNotContainKey(runId);
        assertThat(provider.runSessionsForTest()).doesNotContainKey(runId);
        assertThat(provider.runClientsForTest()).doesNotContainKey(runId);
    }

    @Test
    void executeTask_registersTheRunForCancellationBeforePreparation_andCleansUpOnExit() throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Agent agent = agent(agentId);
        CountDownLatch prepEntered = new CountDownLatch(1);
        CountDownLatch releasePrep = new CountDownLatch(1);
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            prepEntered.countDown();
            releasePrep.await(10, TimeUnit.SECONDS);
            return BRIDGE_URL;
        });
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        AtomicReference<TaskResult> result = new AtomicReference<>();
        Thread run = new Thread(() -> result.set(provider.executeTask(agent, runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2)))), "registered-run");
        run.start();
        assertThat(prepEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // While preparation is still in flight the run is already registered for cancellation.
        assertThat(provider.runRegistrationsForTest()).containsKey(runId);

        releasePrep.countDown();
        run.join(10_000);
        assertThat(run.isAlive()).isFalse();
        assertThat(result.get()).isNotNull();
        // The run completed normally and its cancellation registration was cleaned up.
        assertThat(provider.runRegistrationsForTest()).doesNotContainKey(runId);
        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
    }

    // ---- G1b F6: credential identity of a reused sandbox -------------------------------

    @Test
    void executeTask_deletedCredential_refusesTheNextRunWithoutCreatingASession() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent); // sandbox prepared while the PAT existed
        when(credentialService.read("qoder")).thenThrow(new RuntimeCredentialException(
                RuntimeCredentialException.Cause.NOT_CONFIGURED,
                "No runtime credential configured for provider qoder"));
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> provider.executeTask(agent, runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("credential");

        // Remove revokes future launches: the reused sandbox is not re-entered, and no new
        // session, prompt or sandbox is created for the refused run.
        verify(client, never()).createSession(any());
        verify(client, never()).prompt(anyString(), anyString());
        verify(sandboxLifecycle, times(1)).createSandbox(any(), anyString(), anyMap());
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    @Test
    void executeTask_rotatedCredential_recreatesTheIdleSandboxBeforeTheNextRun() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent); // sandbox sb-1 built with the original PAT
        when(credentialService.read("qoder")).thenReturn(ROTATED_PAT);
        when(sandboxLifecycle.createSandbox(any(), eq(IMAGE), anyMap())).thenReturn("sb-2");
        when(sandboxLifecycle.getSandboxUrl("sb-2", 4097)).thenReturn(BRIDGE_URL);
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent, UUID.randomUUID(), PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        // The idle sandbox built with the stale credential is destroyed before the new run.
        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(sandboxLifecycle, times(2)).createSandbox(eq(agentId), eq(IMAGE), env.capture());
        assertThat(env.getAllValues().get(0))
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", PAT);
        assertThat(env.getAllValues().get(1))
                .as("the new run's sandbox must be created with the rotated credential")
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", ROTATED_PAT);
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-2");
    }

    @Test
    void executeTask_unchangedCredential_reusesTheHealthySandboxForTheNextRun() {
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId);
        provider.prepareAgent(agentId, agent);
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent, UUID.randomUUID(), PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));
        provider.executeTask(agent, UUID.randomUUID(), PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        // The credential re-validation must not regress the sanctioned one-sandbox reuse.
        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());
        verify(sandboxLifecycle, never()).killSandbox(anyString());
    }

    @Test
    void preparedInstance_stampsTheCredentialIdentity_asAOneWayHash_neverThePat() {
        UUID agentId = UUID.randomUUID();
        provider.prepareAgent(agentId, agent(agentId));

        QoderAdkProvider.QoderInstance instance = provider.instancesForTest().get(agentId);
        assertThat(instance.patHash())
                .as("a SHA-256 hex digest of the PAT — never the PAT itself")
                .hasSize(64)
                .isNotEqualTo(PAT)
                .doesNotContain(PAT);
        assertThat(instance.toString())
                .contains("patHash=<redacted>")
                .doesNotContain(instance.patHash());
    }

    // ---- G1b deadline: the provider deadline is the host-granted absolute deadline -----

    @Test
    void executeTask_runDeadline_isTheHostGrantedDeadline_notRestartedAfterPreparation() {
        // Preparation consumes ~600ms of the 2000ms window: the deadline handed to the
        // coordinator must be the absolute grant (entry + 2000ms), not a clock restarted
        // after preparation (which would lag the host's granted deadline by the prep time).
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            Thread.sleep(600);
            return BRIDGE_URL;
        });
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));
        UUID runId = UUID.randomUUID();
        Instant entered = Instant.now();

        provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMillis(2000)));

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(permissionCoordinator).bindRun(eq(runId), eq(client), deadline.capture());
        long declaredMillis = Duration.between(entered, deadline.getValue()).toMillis();
        assertThat(declaredMillis)
                .as("the provider deadline must not restart the granted window after preparation")
                .isLessThan(2200L)
                .isGreaterThan(1500L);
    }

    // ---- G4 item 1: the per-agent slot guard is terminal-aware --------------------------------

    /**
     * F9 acceptance on the qoder path: a terminal predecessor's provider call keeps the agent's
     * single-run slot until its abort finishes (async engine abort, ~1s poll, then up to the 15s
     * stop grace). A re-dispatched run landing in that window must take the slot over instead of
     * failing with the busy error, and the winding-down run's teardown must not evict the
     * successor's registration.
     *
     * <p>Parameterized over the guard's whole terminal set, so dropping any one of
     * COMPLETED / FAILED / CANCELLED / ABORTED from it fails its parameter.
     */
    @ParameterizedTest(name = "previous run terminal as {0}")
    @EnumSource(value = RunStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED", "ABORTED"})
    void executeTask_previousRunTerminalButStillWindingDown_takesOverTheSlotInsteadOfRejecting(
            RunStatus terminalStatus) throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        // The previous run's provider call has not been torn down yet, so it still holds the slot
        // while its row goes terminal. The row flips AFTER the run passed its launch boundaries —
        // the real sequence (the kanban gives up on a run that is already executing); a row that
        // were terminal from the start would now abort the run at its own boundary checks
        // (G8 finding 2), which is a different scenario.
        AtomicReference<RunStatus> oldStatus = new AtomicReference<>(RunStatus.RUNNING);
        lenient().when(runRepository.findById(oldRun)).thenAnswer(invocation -> Optional.of(
                Run.builder().id(oldRun).agentId(agentId).status(oldStatus.get()).build()));
        CountDownLatch oldStreamOpen = new CountDownLatch(1);
        CountDownLatch oldPromptDelivered = new CountDownLatch(1);
        CountDownLatch releaseOldStream = new CountDownLatch(1);
        CountDownLatch releaseNewStream = new CountDownLatch(1);
        AtomicInteger streamReads = new AtomicInteger();
        // The prompt is the run's last launch boundary: waiting for it makes the status flip below
        // deterministic (the pump's reader thread may open the stream before the runner crossed
        // its pre-prompt row re-read, so the stream alone is not proof the boundaries were passed).
        doAnswer(invocation -> {
            oldPromptDelivered.countDown();
            return null;
        }).when(client).prompt(anyString(), anyString());
        doAnswer(invocation -> {
            Consumer<QoderBridgeClient.BridgeEvent> consumer = invocation.getArgument(0);
            switch (streamReads.getAndIncrement()) {
                case 0 -> {
                    // The old run's pump stays open: its executeTask has not returned.
                    oldStreamOpen.countDown();
                    releaseOldStream.await(10, TimeUnit.SECONDS);
                }
                case 1 -> {
                    consumer.accept(sessionStarted(1, "efficient"));
                    releaseNewStream.await(10, TimeUnit.SECONDS);
                    consumer.accept(completed(2, "end_turn"));
                }
                default -> { }
            }
            return null;
        }).when(eventStream).read(any());

        AtomicReference<Throwable> oldFailure = new AtomicReference<>();
        Thread old = new Thread(() -> {
            try {
                provider.executeTask(agent(agentId), oldRun, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));
            } catch (Throwable t) {
                oldFailure.set(t);
            }
        }, "winding-down-run");
        old.start();
        assertThat(oldStreamOpen.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(oldPromptDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        // The kanban's stop flips the row to a terminal status while the run winds down — after the
        // run passed every launch boundary, so the flip cannot abort the run itself.
        oldStatus.set(terminalStatus);

        // The re-dispatched run lands while the old one is still winding down.
        AtomicReference<TaskResult> fresh = new AtomicReference<>();
        AtomicReference<Throwable> freshFailure = new AtomicReference<>();
        Thread reDispatched = new Thread(() -> {
            try {
                fresh.set(provider.executeTask(agent(agentId), newRun, PROMPT_TEXT,
                        new TaskContext(1, Duration.ofMinutes(2))));
            } catch (Throwable t) {
                freshFailure.set(t);
            }
        }, "re-dispatched-run");
        reDispatched.start();
        waitUntil(() -> freshFailure.get() != null || provider.runSessionsForTest().containsKey(newRun));

        assertThat(freshFailure.get())
                .as("the re-dispatched run must take over the terminal run's slot, not lose the race")
                .isNull();
        assertThat(provider.activeRunsForTest()).containsEntry(agentId, newRun);

        // The old run's teardown (its executeTask returns once its stream ends) removes only its
        // own slot entry: the successor's registration survives.
        releaseOldStream.countDown();
        old.join(10_000);
        assertThat(old.isAlive()).isFalse();
        assertThat(oldFailure.get()).isInstanceOf(TaskExecutionException.class)
                .satisfies(t -> assertThat(((TaskExecutionException) t).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR));
        assertThat(provider.activeRunsForTest())
                .as("the old run's teardown removes its own entry by value, never the successor's")
                .containsEntry(agentId, newRun);

        // The new run finishes normally and releases the slot.
        releaseNewStream.countDown();
        reDispatched.join(10_000);
        assertThat(reDispatched.isAlive()).isFalse();
        assertThat(freshFailure.get()).isNull();
        assertThat(fresh.get()).isNotNull();
        assertThat(fresh.get().runId()).isEqualTo(newRun);
        assertThat(provider.activeRunsForTest()).doesNotContainKey(agentId);
    }

    // ---- G6 item 1: the last-resort kill is guarded by slot ownership --------------------------

    /**
     * G4 review Minor 1: after a terminal predecessor hands the agent's slot over, both runs hold
     * the same {@link QoderAdkProvider.QoderInstance}, so an unproven stop in the predecessor must
     * not run its last-resort kill — destroying the shared sandbox and closing the shared bridge
     * client would end the successor's stream and lose its sandbox. The agent's slot is the
     * ownership proof: it now belongs to the successor, so the kill is skipped.
     */
    @Test
    void abortTask_supersededRunWithAnUnprovenStop_doesNotKillTheSandboxOrCloseTheSharedClient() {
        QoderAdkProvider owning = owningClientsProvider();
        UUID agentId = UUID.randomUUID();
        UUID supersededRun = UUID.randomUUID();
        UUID successorRun = UUID.randomUUID();
        QoderAdkProvider.QoderInstance inst = instance(agentId);
        owning.instancesForTest().put(agentId, inst);
        owning.runInstancesForTest().put(supersededRun, inst);
        owning.runClientsForTest().put(supersededRun, client);
        owning.runSessionsForTest().put(supersededRun, BRIDGE_SESSION);
        owning.runPumpsForTest().put(supersededRun, pump);
        // The slot already belongs to the successor; both runs share the instance.
        owning.activeRunsForTest().put(agentId, successorRun);
        // The stop proof fails: this is exactly the last-resort boundary.
        when(pump.awaitStopped(any(Duration.class))).thenReturn(false);

        owning.abortTask(supersededRun);

        // The cancel is still delivered (idempotent), but the shared sandbox, bridge client and
        // instance registration survive for the successor that owns the slot.
        verify(client).cancel(BRIDGE_SESSION);
        verify(sandboxLifecycle, never()).killSandbox(anyString());
        verify(client, never()).close();
        assertThat(owning.instancesForTest()).containsEntry(agentId, inst);
    }

    /**
     * The complementary direction: the guard protects a successor, it never disables the bounded
     * fallback — a run that still owns the slot keeps killing the sandbox when its stop cannot be
     * proven within the grace (design §5.3).
     */
    @Test
    void abortTask_runThatStillOwnsTheSlotWithAnUnprovenStop_stillKillsTheSandbox() {
        QoderAdkProvider owning = owningClientsProvider();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        QoderAdkProvider.QoderInstance inst = instance(agentId);
        owning.instancesForTest().put(agentId, inst);
        owning.runInstancesForTest().put(runId, inst);
        owning.runClientsForTest().put(runId, client);
        owning.runSessionsForTest().put(runId, BRIDGE_SESSION);
        owning.runPumpsForTest().put(runId, pump);
        owning.activeRunsForTest().put(agentId, runId);
        when(pump.awaitStopped(any(Duration.class))).thenReturn(false);

        owning.abortTask(runId);

        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        verify(client).close();
    }

    /** A provider that owns its bridge clients, so {@code closeIfOwned} is observable here. */
    private QoderAdkProvider owningClientsProvider() {
        return new QoderAdkProvider(properties, sandboxLifecycle, credentialService, publisher, mcpProperties);
    }

    /** A hand-built instance for the seeded-map pins (synthetic values only). */
    private QoderAdkProvider.QoderInstance instance(UUID agentId) {
        return new QoderAdkProvider.QoderInstance(agentId, SANDBOX_ID, BRIDGE_URL,
                "synthetic-bridge-token", "synthetic-pat-hash", client);
    }

    // ---- G4 item 2: the pending-abort record is atomic with the run's registration ------------

    /**
     * The check-then-write of the pending-abort record has a window: the run's finally may remove
     * the records before the write lands, leaking the entry for the provider's lifetime — and in
     * the sibling ordering the record can outlive the runner's last launch boundary and report a
     * spurious ABORTED whose stop was never sent. The pin is the trapping-map witness (the
     * `WriteGrantServiceTest` F2 pattern): the write must run inside the registration's own
     * per-key map operation, the one critical section the run's cleanup also enters.
     */
    @Test
    void abortTask_beforeAnySession_recordsThePendingAbortInsideTheRegistrationsPerKeyOperation() throws Exception {
        AbortRecordingWitness witness = installAbortRecordingWitness();
        UUID runId = UUID.randomUUID();
        provider.runRegistrationsForTest().put(runId, Boolean.TRUE); // the run is preparing (F5)

        provider.abortTask(runId);

        assertThat(witness.recordsInsideTheirRegistrationOperation())
                .as("the pending-abort record must be written inside the registration's per-key operation;"
                        + " a write outside it is the window where the run's cleanup removes it first")
                .containsExactly(Boolean.TRUE);
    }

    @Test
    void abortTask_withAClientButNoSession_recordsThePendingAbortInsideTheRegistrationOperation() throws Exception {
        AbortRecordingWitness witness = installAbortRecordingWitness();
        UUID runId = UUID.randomUUID();
        provider.runRegistrationsForTest().put(runId, Boolean.TRUE);
        provider.runClientsForTest().put(runId, client); // the bridge call is in flight, no session yet

        provider.abortTask(runId);

        assertThat(witness.recordsInsideTheirRegistrationOperation())
                .as("the pre-existing session-creation branch must apply the same discipline")
                .containsExactly(Boolean.TRUE);
    }

    /**
     * The sibling ordering of the same window: the record lands while the launch boundary is
     * crossing, so the runner's next check never sees it. The abort must deliver the stop itself
     * instead of settling for a record the runner has already passed (which would later report
     * ABORTED without any stop having reached the bridge).
     */
    @Test
    void abortTask_whenTheSessionAppearsWhileTheAbortIsRecorded_deliversTheStop() throws Exception {
        UUID runId = UUID.randomUUID();
        provider.runRegistrationsForTest().put(runId, Boolean.TRUE);
        provider.runClientsForTest().put(runId, client);
        Map<UUID, String> sessions = provider.runSessionsForTest();
        sessions.put(runId, BRIDGE_SESSION);
        // The abort's first read of the session map misses the session once: the record is then
        // written while the runner has already crossed its post-createSession boundary.
        installMissFirstSessionGet(runId, sessions);

        provider.abortTask(runId);

        verify(client).cancel(BRIDGE_SESSION);
    }

    /**
     * Witness for the item-2 atomicity pins (the trapping-map pattern of the F2 pin in
     * {@code WriteGrantServiceTest}): the registration map double marks the per-key operation that
     * is active while a {@code computeIfPresent} on the registration runs, and the pending-abort
     * map double records for every write whether it ran inside the registration's own operation
     * on the same key.
     */
    private static final class AbortRecordingWitness {

        private final Deque<UUID> activeRegistrationOperations = new ArrayDeque<>();
        private final List<Boolean> abortRecords = new ArrayList<>();

        Map<UUID, Boolean> registrationMap() {
            return new ConcurrentHashMap<>() {
                @Override
                public Boolean computeIfPresent(UUID key,
                                                BiFunction<? super UUID, ? super Boolean,
                                                        ? extends Boolean> remappingFunction) {
                    activeRegistrationOperations.push(key);
                    try {
                        return super.computeIfPresent(key, remappingFunction);
                    } finally {
                        activeRegistrationOperations.pop();
                    }
                }
            };
        }

        Map<UUID, Boolean> abortMap() {
            return new ConcurrentHashMap<>() {
                @Override
                public Boolean put(UUID key, Boolean value) {
                    abortRecords.add(key.equals(activeRegistrationOperations.peek()));
                    return super.put(key, value);
                }
            };
        }

        List<Boolean> recordsInsideTheirRegistrationOperation() {
            return List.copyOf(abortRecords);
        }
    }

    /** Install the witness doubles over the provider's registration / pending-abort maps. */
    private AbortRecordingWitness installAbortRecordingWitness() throws Exception {
        AbortRecordingWitness witness = new AbortRecordingWitness();
        setProviderField("registeredRuns", witness.registrationMap());
        setProviderField("runAborted", witness.abortMap());
        return witness;
    }

    /**
     * Replace the provider's session map with a double whose first read of {@code missKey} answers
     * "no session yet" and delegates everything else: the deterministic stand-in for the runner's
     * {@code runSessions.put} landing between the abort's two reads.
     */
    private void installMissFirstSessionGet(UUID missKey, Map<UUID, String> sessions) throws Exception {
        Map<UUID, String> doubleMap = new ConcurrentHashMap<>() {
            private final AtomicBoolean missed = new AtomicBoolean();

            @Override
            public String get(Object key) {
                if (missKey.equals(key) && missed.compareAndSet(false, true)) {
                    return null;
                }
                return sessions.get(key);
            }

            @Override
            public String put(UUID key, String value) {
                return sessions.put(key, value);
            }

            @Override
            public String remove(Object key) {
                return sessions.remove(key);
            }

            @Override
            public boolean containsKey(Object key) {
                return sessions.containsKey(key);
            }
        };
        setProviderField("runSessions", doubleMap);
    }

    private void setProviderField(String name, Object value) throws Exception {
        setProviderField(provider, name, value);
    }

    private static void setProviderField(QoderAdkProvider target, String name, Object value) throws Exception {
        Field field = QoderAdkProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ---- G4 item 3: an unstated MCP configuration cannot run silently -------------------------

    /**
     * F1 for manual wirings: a provider constructed without {@code McpProperties} cannot be
     * checked against the none-mode hazard, so it refuses at both entries instead of letting its
     * sandbox run against an unknown host configuration.
     */
    @Test
    void executeTask_withoutAnExplicitMcpConfiguration_isRefusedBeforeAnythingStarts() {
        QoderAdkProvider unstated = new QoderAdkProvider(properties, sandboxLifecycle, credentialService,
                publisher, (url, token) -> client, null, runScopedCredentialService, writeGrantService,
                permissionCoordinator, runRepository);
        UUID agentId = UUID.randomUUID();

        assertThatThrownBy(() -> unstated.executeTask(agent(agentId), UUID.randomUUID(), PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR))
                .hasMessageContaining("MCP configuration");

        assertThatThrownBy(() -> unstated.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("MCP configuration");

        // The refusal sits at the earliest boundary: no sandbox, no bridge, no session starts.
        verify(sandboxLifecycle, never()).createSandbox(any(), anyString(), anyMap());
        assertThat(unstated.instancesForTest()).isEmpty();
    }

    // ---- G7 finding 1: the take-over stops the predecessor before the successor enters --------

    /**
     * G7 Major 1: a terminal run ROW is not proof that the predecessor left the shared sandbox —
     * its bridge session and its {@code qodercli} child are stopped only once the engine's
     * asynchronous abort reaches the provider (about a poll interval after the terminal commit).
     * The successor must therefore terminate the predecessor before it creates a session of its
     * own; otherwise two CLI children share one {@code /workspace} for the abort window, which is
     * exactly what the one-run-per-agent guard exists to prevent.
     *
     * <p>The predecessor's event stream is held open on a latch: while its pump is unstopped the
     * successor must not create a session, and once the stream ends the successor proceeds in the
     * same — now exclusive — sandbox (the stop was proven, so no kill).
     */
    @Test
    void executeTask_takeOver_doesNotCreateTheSuccessorsSessionBeforeThePredecessorIsStopped()
            throws Exception {
        UUID agentId = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        // The predecessor is executing (the stream below is held open) when its row goes terminal:
        // the row flips only after the run passed its launch boundaries, exactly the real sequence
        // (a row terminal from the start would abort the predecessor at its own boundary checks now).
        AtomicReference<RunStatus> oldStatus = new AtomicReference<>(RunStatus.RUNNING);
        lenient().when(runRepository.findById(oldRun)).thenAnswer(invocation -> Optional.of(
                Run.builder().id(oldRun).agentId(agentId).status(oldStatus.get()).build()));
        // The stop proof must be awaited: the class-wide 50 ms test grace would turn the latch
        // hold into the last-resort kill, which is a different pin (see the sibling tests).
        provider.setStopGraceForTest(Duration.ofSeconds(5));
        CountDownLatch oldStreamOpen = new CountDownLatch(1);
        CountDownLatch oldPromptDelivered = new CountDownLatch(1);
        CountDownLatch releaseOldStream = new CountDownLatch(1);
        CountDownLatch predecessorCancelDelivered = new CountDownLatch(1);
        AtomicInteger sessionCreations = new AtomicInteger();
        AtomicInteger streamReads = new AtomicInteger();
        when(client.createSession(any())).thenAnswer(invocation -> {
            sessionCreations.incrementAndGet();
            return BRIDGE_SESSION;
        });
        when(client.cancel(BRIDGE_SESSION)).thenAnswer(invocation -> {
            predecessorCancelDelivered.countDown();
            return true;
        });
        // The prompt is the predecessor's last launch boundary; waiting for it makes the row flip
        // below deterministic (the pump's reader can open the stream before the runner crossed its
        // pre-prompt row re-read, so the open stream alone is not proof the boundaries were passed).
        doAnswer(invocation -> {
            oldPromptDelivered.countDown();
            return null;
        }).when(client).prompt(anyString(), anyString());
        doAnswer(invocation -> {
            Consumer<QoderBridgeClient.BridgeEvent> consumer = invocation.getArgument(0);
            switch (streamReads.getAndIncrement()) {
                case 0 -> {
                    // The predecessor is still executing: its pump has not been stopped.
                    oldStreamOpen.countDown();
                    releaseOldStream.await(30, TimeUnit.SECONDS);
                }
                case 1 -> {
                    consumer.accept(sessionStarted(1, "efficient"));
                    consumer.accept(completed(2, "end_turn"));
                }
                default -> { }
            }
            return null;
        }).when(eventStream).read(any());

        AtomicReference<Throwable> oldFailure = new AtomicReference<>();
        Thread predecessor = new Thread(() -> {
            try {
                provider.executeTask(agent(agentId), oldRun, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));
            } catch (Throwable t) {
                oldFailure.set(t);
            }
        }, "g7-predecessor");
        predecessor.start();
        assertThat(oldStreamOpen.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(oldPromptDelivered.await(10, TimeUnit.SECONDS)).isTrue();
        // The kanban gives up on the executing predecessor: its row goes terminal now (after every
        // launch boundary, so the flip cannot abort the predecessor itself).
        oldStatus.set(RunStatus.CANCELLED);

        AtomicReference<TaskResult> successorResult = new AtomicReference<>();
        AtomicReference<Throwable> successorFailure = new AtomicReference<>();
        Thread successor = new Thread(() -> {
            try {
                successorResult.set(provider.executeTask(agent(agentId), newRun, PROMPT_TEXT,
                        new TaskContext(1, Duration.ofMinutes(2))));
            } catch (Throwable t) {
                successorFailure.set(t);
            }
        }, "g7-successor");
        successor.start();

        // Bounded settle: the successor had every chance to enter the sandbox while the
        // predecessor's pump is still running (it does — one session before the fix).
        Thread.sleep(250);
        assertThat(sessionCreations.get())
                .as("the successor must not create a second session/CLI while the predecessor's pump"
                        + " is still unstopped — one Qoder CLI per workspace")
                .isEqualTo(1);
        assertThat(predecessorCancelDelivered.await(10, TimeUnit.SECONDS))
                .as("the take-over must deliver the predecessor's idempotent cancel")
                .isTrue();

        // The predecessor's stream ends: its stop is proven and the successor proceeds.
        releaseOldStream.countDown();
        waitUntil(() -> sessionCreations.get() == 2);
        successor.join(10_000);
        assertThat(successor.isAlive()).isFalse();
        assertThat(successorFailure.get()).isNull();
        assertThat(successorResult.get()).isNotNull();
        assertThat(successorResult.get().runId()).isEqualTo(newRun);
        // The stop was proven, so the successor re-entered the same sandbox: no kill was needed.
        verify(sandboxLifecycle, never()).killSandbox(anyString());
        verify(sandboxLifecycle, times(1)).createSandbox(any(), eq(IMAGE), anyMap());

        predecessor.join(10_000);
        assertThat(predecessor.isAlive()).isFalse();
        assertThat(oldFailure.get()).isInstanceOf(TaskExecutionException.class);
        assertThat(provider.activeRunsForTest()).doesNotContainKey(agentId);
    }

    // ---- G7 finding 3 / G8 finding 1: ownership, detach and slot release are ONE step ---------

    /**
     * G7 Minor 3, tightened by G8 finding 1: the G6 guard used to read {@code activeRuns.get(...)}
     * and then kill, so a successor claiming the slot between that read and the kill lost the
     * sandbox it had just taken. The G7 fix answered the ownership question with the
     * value-conditional removal, but released the slot BEFORE detaching the instance registry, so a
     * re-dispatch claiming the freed slot could still read the registered instance in
     * {@code getOrPrepareInstance}, pass the reachability probe and reuse the sandbox that was
     * being destroyed.
     *
     * <p>Ownership, the instance-registry detach and the slot release are therefore ONE per-key
     * critical section: the slot map proves the slot still names this run, detaches the registry
     * inside it and gives the slot up only afterwards; the destroy/close work stays outside. The
     * pin is a trapping pair of maps that makes the interleaving observable without racing threads:
     * the registry records the slot state at the detach, the slot map records the registry state at
     * the release, and a bare {@code get} on the ownership path is recorded as its own event. The
     * expected trace is exactly (1) detach while the slot still names the run, then (2) release
     * with the instance already detached; the pre-G8 order records the reverse and fails here.
     */
    @Test
    void abortTask_lastResortKill_detachesTheInstanceInsideTheOwnershipCriticalSection() throws Exception {
        QoderAdkProvider owning = owningClientsProvider();
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        QoderAdkProvider.QoderInstance inst = instance(agentId);
        LastResortOrderWitness witness = new LastResortOrderWitness(agentId, runId);
        setProviderField(owning, "activeRuns", witness.slot());
        setProviderField(owning, "instances", witness.instances());
        owning.instancesForTest().put(agentId, inst);
        owning.runInstancesForTest().put(runId, inst);
        owning.runClientsForTest().put(runId, client);
        owning.runSessionsForTest().put(runId, BRIDGE_SESSION);
        owning.runPumpsForTest().put(runId, pump);
        when(pump.awaitStopped(any(Duration.class))).thenReturn(false);

        owning.abortTask(runId);

        assertThat(witness.events())
                .as("the detach must happen inside the slot's critical section: the instance is"
                        + " detached while the slot still names the run, and the slot is released only"
                        + " with the instance already detached — any other order leaves a window where a"
                        + " re-dispatch can reuse the sandbox being destroyed")
                .containsExactly(
                        "slot.ownership",
                        "instance.remove:slot=run",
                        "slot.release:instance=detached");
        // The kill still runs for the run the slot names, and the slot and the registry are both
        // given up in that same step.
        verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        verify(client).close();
        assertThat(owning.activeRunsForTest()).doesNotContainKey(agentId);
        assertThat(owning.instancesForTest()).doesNotContainKey(agentId);
    }

    /**
     * Witness for the G8 finding-1 pin (the trapping-map pattern of the G4 item-2 and G7 item-3
     * pins): the slot map records the ownership operation and, at the moment the slot is given up,
     * the instance-registry state; the registry records its detach together with the slot state at
     * that instant. A bare {@code get} on the ownership path is recorded as its own event, so it
     * fails the exact trace instead of hiding inside it.
     */
    private static final class LastResortOrderWitness {

        private final UUID agentId;
        private final UUID runId;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean released = new AtomicBoolean();
        /** Tracked instead of read back: a registry read would itself be a witness event. */
        private final AtomicBoolean instanceDetached = new AtomicBoolean();

        private final Map<UUID, UUID> slot = new ConcurrentHashMap<>() {
            @Override
            public UUID get(Object key) {
                if (agentId.equals(key)) {
                    events.add("slot.get");
                }
                return super.get(key);
            }

            @Override
            public UUID compute(UUID key,
                                BiFunction<? super UUID, ? super UUID, ? extends UUID> remappingFunction) {
                if (!agentId.equals(key)) {
                    return super.compute(key, remappingFunction);
                }
                events.add("slot.ownership");
                return super.compute(key, (owner, current) -> {
                    UUID next = remappingFunction.apply(owner, current);
                    if (next == null) {
                        released.set(true);
                        events.add("slot.release:instance="
                                + (instanceDetached.get() ? "detached" : "registered"));
                    }
                    return next;
                });
            }

            @Override
            public boolean remove(Object key, Object value) {
                boolean removed = super.remove(key, value);
                if (agentId.equals(key) && removed) {
                    events.add("slot.remove");
                    released.set(true);
                }
                return removed;
            }
        };

        private final Map<UUID, QoderAdkProvider.QoderInstance> instances = new ConcurrentHashMap<>() {
            @Override
            public boolean remove(Object key, Object value) {
                boolean removed = super.remove(key, value);
                if (removed) {
                    instanceDetached.set(true);
                }
                if (agentId.equals(key)) {
                    events.add("instance.remove:slot=" + (released.get() ? "released" : "run"));
                }
                return removed;
            }

            @Override
            public QoderAdkProvider.QoderInstance get(Object key) {
                QoderAdkProvider.QoderInstance value = super.get(key);
                if (agentId.equals(key)) {
                    events.add("instance.get:" + (value == null ? "absent" : "registered"));
                }
                return value;
            }
        };

        LastResortOrderWitness(UUID agentId, UUID runId) {
            this.agentId = agentId;
            this.runId = runId;
            slot.put(agentId, runId);
        }

        Map<UUID, UUID> slot() {
            return slot;
        }

        Map<UUID, QoderAdkProvider.QoderInstance> instances() {
            return instances;
        }

        List<String> events() {
            return List.copyOf(events);
        }
    }

    // ---- G7 finding 2: one approval window for the bridge and the host ------------------------

    /**
     * G7 Major 2 with the G8 finding-3 margin: the host expires every ask at
     * {@code min(now + approvals.timeout-ms, runDeadline)} anchored when it PERSISTS the ask,
     * while the bridge anchors its local deadline at frame handling — strictly earlier — so the
     * raw window let the bridge deny an ask the host still held (the APPROVED-then-{@code expired}
     * retry loop). The provider therefore forwards the host's window PLUS the documented margin
     * ({@code APPROVAL_WINDOW_SLACK_MS} = 5000 ms): the bridge's local deadline is then
     * {@code handleTime + window + margin} against the host's {@code persistTime + window}, i.e.
     * never earlier than the host's expiry as long as the delivery-plus-commit transit and the
     * sandbox/host clock skew stay inside the margin. The exact forwarded value is pinned here.
     */
    @Test
    void prepareAgent_passesTheHostsApprovalWindowPlusTheDocumentedMarginIntoTheSandboxEnvironment() {
        UUID agentId = UUID.randomUUID();

        provider.prepareAgent(agentId, agent(agentId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(sandboxLifecycle).createSandbox(eq(agentId), eq(IMAGE), env.capture());
        assertThat(env.getValue())
                .as("the bridge clamps every per-ask deadline to the host window PADDED by the"
                        + " documented margin, so the host (anchoring at persist time) always expires"
                        + " before the bridge's local deadline fires")
                .containsEntry("APPROVAL_TIMEOUT_MS", "1805000");
    }

    /**
     * The configured value — never only the documented fallback — plus the same margin is what the
     * bridge receives: the property is the single source of the window, the margin is the fixed
     * transit/skew allowance on top of it.
     */
    @Test
    void prepareAgent_configuredApprovalWindow_replacesTheDocumentedDefault() throws Exception {
        setProviderField(provider, "approvalWindowMs", 1_234_567L);
        UUID agentId = UUID.randomUUID();

        provider.prepareAgent(agentId, agent(agentId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(sandboxLifecycle).createSandbox(eq(agentId), eq(IMAGE), env.capture());
        assertThat(env.getValue()).containsEntry("APPROVAL_TIMEOUT_MS", "1239567");
    }

    /**
     * G7 Major 2, second half: the provider used to hand the bridge the FULL run window and the
     * bridge anchors it at session creation, so the session deadline drifted by the whole
     * preparation time. The bridge must receive the REMAINING window instead: anchored at receipt
     * it lands on the host's absolute run deadline, and the bridge's session clamp then agrees
     * with the host's ask clamp.
     */
    @Test
    void executeTask_sessionDeadline_isTheRemainingWindow_neverRestartedAtSessionCreation() {
        // Preparation consumes 1.2 s of the 2 s window: the bridge must be told 1 s (the ceiling
        // of the 0.8 s left), never the original 2 s.
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            Thread.sleep(1200);
            return BRIDGE_URL;
        });
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));
        UUID runId = UUID.randomUUID();

        provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMillis(2000)));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().deadlineSeconds())
                .as("the bridge must anchor its session deadline to the host's absolute run deadline")
                .isEqualTo(1L);
    }

    // ---- G8 finding 2: the silent take-over and the launch-boundary row re-reads --------------

    /**
     * G8 finding 2a — the silent interleaving: a terminal predecessor whose pending abort cannot be
     * RECORDED — it sits in the gap between claiming its slot and reaching
     * {@code registeredRuns.put}, so there is no registration to write the record into — used to be
     * neither recorded nor announced. The successor proceeded silently while that predecessor could
     * still create a session in the sandbox it was about to enter. The take-over log is
     * unconditional; what stops the unregistered predecessor is its own launch-boundary row re-read
     * (the pins below), which the logged message states.
     */
    @Test
    void executeTask_takeOver_ofAPredecessorWithoutASession_logsTheUnrecordedHandOver() {
        UUID agentId = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID newRun = UUID.randomUUID();
        lenient().when(runRepository.findById(oldRun)).thenReturn(Optional.of(
                Run.builder().id(oldRun).agentId(agentId).status(RunStatus.CANCELLED).build()));
        // The predecessor claimed the slot but has not reached its cancellation registration yet:
        // the pending abort cannot be recorded, and the take-over must still say so.
        provider.activeRunsForTest().put(agentId, oldRun);
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));
        Logger logger = (Logger) LoggerFactory.getLogger(QoderAdkProvider.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TaskResult result = provider.executeTask(agent(agentId), newRun, PROMPT_TEXT,
                    new TaskContext(1, Duration.ofMinutes(2)));

            assertThat(result.runId()).isEqualTo(newRun);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .as("the take-over of an unregistered terminal predecessor must never be silent")
                .anyMatch(message -> message.contains("has no session yet")
                        && message.contains("has not registered for cancellation"));
        assertThat(provider.activeRunsForTest()).doesNotContainKey(agentId);
    }

    /**
     * G8 finding 2b — the first launch boundary: a run whose row went terminal while its sandbox
     * was being prepared must abort there and never create a session (or a prompt): the terminal
     * row is the predecessor's own second chance when no pending-abort record could be delivered.
     */
    @Test
    void executeTask_rowWentTerminalDuringPreparation_abortsBeforeCreatingTheSession() {
        UUID runId = UUID.randomUUID();
        when(sandboxLifecycle.getSandboxUrl(SANDBOX_ID, 4097)).thenAnswer(invocation -> {
            when(runRepository.findById(runId)).thenReturn(Optional.of(
                    Run.builder().id(runId).status(RunStatus.CANCELLED).build()));
            return BRIDGE_URL;
        });

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED))
                .hasMessageContaining("launch boundary");

        verify(client, never()).createSession(any());
        verify(client, never()).prompt(anyString(), anyString());
        verify(client, never()).cancel(anyString());
        verify(sandboxLifecycle, never()).killSandbox(anyString());
        assertThat(provider.runRegistrationsForTest()).doesNotContainKey(runId);
    }

    /**
     * G8 finding 2c — the second launch boundary: a row that went terminal while the bridge session
     * was being created must abort before any prompt. The session already exists, so the run-end
     * cleanup cancels it (nothing executes in the sandbox).
     */
    @Test
    void executeTask_rowWentTerminalDuringSessionCreation_abortsBeforeThePrompt() {
        UUID runId = UUID.randomUUID();
        doAnswer(invocation -> {
            when(runRepository.findById(runId)).thenReturn(Optional.of(
                    Run.builder().id(runId).status(RunStatus.CANCELLED).build()));
            return BRIDGE_SESSION;
        }).when(client).createSession(any());

        assertThatThrownBy(() -> provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT,
                new TaskContext(1, Duration.ofMinutes(2))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED))
                .hasMessageContaining("launch boundary");

        verify(client, never()).prompt(anyString(), anyString());
        verify(client).cancel(BRIDGE_SESSION);
        assertThat(provider.runSessionsForTest()).doesNotContainKey(runId);
    }

    /**
     * G8 finding 2d — the boundary SITES: the run's own row is re-read at every launch boundary
     * (after preparation, after session creation, immediately before the prompt), so a prompt can
     * never be issued without the row having been checked right before it. The behavioural pins
     * above prove the two synchronously reachable reads have teeth; this pin records all three
     * sites so a prompt without its preceding re-read fails deterministically.
     */
    @Test
    void executeTask_reReadsTheRunRowAtEveryLaunchBoundary_beforeThePrompt() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<String> order = new CopyOnWriteArrayList<>();
        when(runRepository.findById(runId)).thenAnswer(invocation -> {
            order.add("runRow");
            return Optional.empty();
        });
        doAnswer(invocation -> {
            order.add("prompt");
            return null;
        }).when(client).prompt(anyString(), anyString());
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent(agentId), runId, PROMPT_TEXT, new TaskContext(1, Duration.ofMinutes(2)));

        assertThat(order).containsExactly("runRow", "runRow", "runRow", "prompt");
    }
}
