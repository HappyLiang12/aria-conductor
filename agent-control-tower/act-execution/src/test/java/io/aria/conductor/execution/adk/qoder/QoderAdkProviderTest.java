package io.aria.conductor.execution.adk.qoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionConstraints;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.credential.RuntimeCredentialException;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.sandbox.SandboxLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
    private static final String PROMPT_TEXT = "do the thing";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock SandboxLifecycle sandboxLifecycle;
    @Mock QoderBridgeClient client;
    @Mock QoderBridgeClient.EventStream eventStream;
    @Mock RuntimeCredentialService credentialService;

    QoderProperties properties;
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
                });
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

        // One sandbox per agent, one bridge session per run, empty MCP list in slice B.
        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().runId()).isEqualTo("run-" + runId);
        assertThat(request.getValue().agentId()).isEqualTo(agentId.toString());
        assertThat(request.getValue().cwd()).isEqualTo("/workspace");
        assertThat(request.getValue().model()).isEqualTo("efficient");
        assertThat(request.getValue().mcpServers()).isEmpty();
        assertThat(request.getValue().deadlineSeconds()).isEqualTo(120L);
        verify(client).prompt(eq(BRIDGE_SESSION), startsWith(PROMPT_TEXT));
        // The client was pointed at the resolved sandbox endpoint with a non-PAT bridge token.
        assertThat(clientUrl.get()).isEqualTo(BRIDGE_URL);
        assertThat(clientToken.get()).isNotBlank().isNotEqualTo(PAT);
    }

    @Test
    void executeTask_nullMaxDuration_fallsBackToQoderMaxTaskMinutes() {
        properties.setMaxTaskMinutes(7);
        UUID runId = UUID.randomUUID();
        streamPlays(sessionStarted(1, "efficient"), completed(2, "end_turn"));

        provider.executeTask(agent(UUID.randomUUID()), runId, PROMPT_TEXT, new TaskContext(1, null));

        ArgumentCaptor<QoderBridgeClient.CreateSessionRequest> request =
                ArgumentCaptor.forClass(QoderBridgeClient.CreateSessionRequest.class);
        verify(client).createSession(request.capture());
        assertThat(request.getValue().deadlineSeconds()).isEqualTo(7 * 60L);
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

        InOrder order = inOrder(client, sandboxLifecycle);
        order.verify(client).cancel(BRIDGE_SESSION);
        order.verify(sandboxLifecycle).killSandbox(SANDBOX_ID);
        verify(sandboxLifecycle, never()).renewSandbox(anyString(), any());
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

    @Test
    void executeTask_secondConcurrentRunForOneAgent_failsWithTypedBusyError() throws Exception {
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

        verify(client).cancel(BRIDGE_SESSION);
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
}
