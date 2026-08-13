package io.aria.conductor.execution.adk.opencode;

import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider.OpenCodeInstance;
import io.aria.conductor.execution.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenCodeAdkProvider} with mocked
 * {@link OpenCodeSandboxManager} and {@link OpenCodeHttpClient}.
 */
@ExtendWith(MockitoExtension.class)
class OpenCodeAdkProviderTest {

    private static final String IMAGE = "test-image";

    @Mock OpenCodeSandboxManager sandboxManager;
    @Mock OpenCodeHttpClient httpClient;

    @TempDir Path tempDir;

    OpenCodeProperties properties;
    OpenCodeAdkProvider provider;

    @BeforeEach
    void setUp() {
        properties = new OpenCodeProperties();
        properties.setSandboxServerUrl("http://localhost:8080");
        properties.setSandboxApiKey("test-key");
        properties.setImage(IMAGE);
        properties.setPort(4096);
        properties.setMaxTaskMinutes(30);
        provider = new OpenCodeAdkProvider(properties, sandboxManager, httpClient);
        provider.setWorkspaceBaseForTest(tempDir);
    }

    private Agent agent(UUID agentId) {
        return Agent.builder().id(agentId).name("test-agent").role("coder").description("desc").build();
    }

    @Test
    void providerId_returnsOpencode() {
        assertThat(provider.providerId()).isEqualTo("opencode");
    }

    @Test
    void supportsTaskExecution_returnsTrue() {
        assertThat(provider.supportsTaskExecution()).isTrue();
    }

    @Test
    void call_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> provider.call(UUID.randomUUID(), List.of(LlmMessage.user("hi")), List.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("turn-level");
    }

    @Test
    void prepareAgent_success_createsSandboxUploadsStartsServeAndWaitsHealth() {
        UUID agentId = UUID.randomUUID();
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-1");
        when(sandboxManager.getSandboxUrl("sb-1", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxManager).createSandbox(eq(agentId), eq(IMAGE), any());
        verify(sandboxManager).uploadWorkspace(eq(agentId), eq(tempDir.resolve(agentId.toString())));
        verify(sandboxManager).runServeCommand("sb-1", 4096);
        verify(httpClient, atLeastOnce()).isHealthy();
        assertThat(provider.instancesForTest()).containsKey(agentId);
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-1");
        assertThat(provider.instancesForTest().get(agentId).healthy()).isTrue();
    }

    @Test
    void prepareAgent_sandboxCreationFailure_throwsSandboxUnavailable_withoutKill() {
        UUID agentId = UUID.randomUUID();
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any()))
                .thenThrow(new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE, "server down"));

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE))
                .hasMessageContaining("server down");

        verify(sandboxManager, never()).killSandbox(any());
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);
    }

    @Test
    void prepareAgent_serveNeverReady_destroysSandboxAndThrowsSandboxUnavailable() {
        UUID agentId = UUID.randomUUID();
        provider.setReadyTimeoutForTest(Duration.ofMillis(250));
        provider.setReadyPollIntervalForTest(Duration.ofMillis(50));
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-1");
        when(sandboxManager.getSandboxUrl("sb-1", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(false);

        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE))
                .hasMessageContaining("did not become ready");

        verify(sandboxManager).killSandbox("sb-1");
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);
    }

    @Test
    void prepareAgent_healthProbeSlowerThanPollInterval_stillHonorsWallClockBudget() {
        UUID agentId = UUID.randomUUID();
        provider.setReadyTimeoutForTest(Duration.ofMillis(300));
        provider.setReadyPollIntervalForTest(Duration.ofMillis(50));
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-slow");
        when(sandboxManager.getSandboxUrl("sb-slow", 4096)).thenReturn("http://127.0.0.1:4096");
        // Each probe blocks 80ms (slower than the 50ms poll interval). The old
        // attempt-count budget (300/50 = 6 tries) would wall-clock to ~6*(80+50)
        // = 780ms instead of the declared 300ms budget.
        when(httpClient.isHealthy()).thenAnswer(inv -> {
            Thread.sleep(80);
            return false;
        });

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider.prepareAgent(agentId, agent(agentId)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE))
                .hasMessageContaining("did not become ready");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Wall clock stays near the 300ms budget (old code: ~780ms, new: ~350ms).
        assertThat(elapsedMs).as("waitForHealth must honor the wall-clock budget").isLessThan(500);
        verify(sandboxManager).killSandbox("sb-slow");
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);
    }

    @Test
    void prepareAgent_passesSandboxEnvToManager() {
        UUID agentId = UUID.randomUUID();
        Map<String, String> env = Map.of("DEEPSEEK_API_KEY", "secret-key");
        properties.setSandboxEnv(env);
        when(sandboxManager.createSandbox(agentId, IMAGE, env)).thenReturn("sb-env");
        when(sandboxManager.getSandboxUrl("sb-env", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxManager).createSandbox(agentId, IMAGE, env);
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-env");
    }

    @Test
    void executeTask_success_returnsTaskResult() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession("run-" + runId)).thenReturn("sess-1");
        when(httpClient.sendMessage(eq("sess-1"), anyString(), eq("do the task"), any()))
                .thenReturn(new OpenCodeHttpClient.MessageResponse("msg-1", "task done", 120, 45));

        TaskResult result = provider.executeTask(agent(agentId), runId, "do the task",
                new TaskContext(50, Duration.ofMinutes(5)));

        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.sessionId()).isEqualTo("sess-1");
        assertThat(result.finalOutput()).isEqualTo("task done");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(45);
        assertThat(result.aborted()).isFalse();
        verify(httpClient).createSession("run-" + runId);
    }

    @Test
    void executeTask_timeout_abortsSession() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession(anyString())).thenReturn("sess-1");
        when(httpClient.sendMessage(any(), any(), any(), any()))
                .thenThrow(new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT, "deadline exceeded"));

        assertThatThrownBy(() -> provider.executeTask(agent(agentId), runId, "task",
                new TaskContext(50, Duration.ofSeconds(1))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.TIMEOUT));

        verify(httpClient).abortSession("sess-1");
    }

    @Test
    void isHealthy_afterThreeFailures_destroysInstanceAndRebuildsOnNextPrepare() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-old", false, Instant.now(), 2, httpClient));
        when(httpClient.isHealthy()).thenReturn(false);

        assertThat(provider.isHealthy(agentId)).isFalse();

        verify(sandboxManager).killSandbox("sb-old");
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);

        // Next prepareAgent rebuilds the sandbox
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-new");
        when(sandboxManager.getSandboxUrl("sb-new", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxManager).createSandbox(eq(agentId), eq(IMAGE), any());
        assertThat(provider.instancesForTest()).containsKey(agentId);
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-new");
    }

    @Test
    void isHealthy_belowThreshold_keepsInstance() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", false, Instant.now(), 1, httpClient));
        when(httpClient.isHealthy()).thenReturn(false);

        assertThat(provider.isHealthy(agentId)).isFalse();

        verify(sandboxManager, never()).killSandbox(any());
        assertThat(provider.instancesForTest()).containsKey(agentId);
        assertThat(provider.instancesForTest().get(agentId).failureCount()).isEqualTo(2);
    }

    @Test
    void shutdownAgent_killsSandboxAndClearsMapping() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));

        provider.shutdownAgent(agentId);

        verify(sandboxManager).killSandbox("sb-1");
        assertThat(provider.instancesForTest()).doesNotContainKey(agentId);
    }

    // ---- #15 per-instance OpenCodeHttpClient resources must be closed on shutdown ----

    @Test
    void shutdownAgent_closesPerInstanceHttpClient() {
        UUID agentId = UUID.randomUUID();
        OpenCodeHttpClient instanceClient = mock(OpenCodeHttpClient.class);
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, instanceClient));

        provider.shutdownAgent(agentId);

        // The provider owns per-instance clients: they must be closed so their
        // executor / HttpClient resources are released.
        verify(instanceClient).close();
    }

    @Test
    void shutdownAgent_doesNotCloseFixedHttpClient() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));

        provider.shutdownAgent(agentId);

        // The fixed test client is injected and owned by the test — the provider
        // must not close it.
        verify(httpClient, never()).close();
    }

    // ---- #1 cancel race: pending abort recorded before session creation ----

    @Test
    void abortTask_beforeSessionCreated_recordsPendingAbort_executeTaskAbortsAndThrowsAborted() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        // Simulate a cancel landing in the session-creation window: runClients is
        // already registered (executeTask registers it before createSession) but
        // runSessions is not yet populated — abortTask must record a pending abort.
        when(httpClient.createSession("run-" + runId)).thenAnswer(inv -> {
            provider.abortTask(runId);
            return "sess-1";
        });

        assertThatThrownBy(() -> provider.executeTask(agent(agentId), runId, "task",
                new TaskContext(0, Duration.ofMinutes(5))))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.ABORTED))
                .hasMessageContaining("cancelled");

        // The pending abort is honored right after createSession: session aborted.
        verify(httpClient).abortSession("sess-1");
    }

    // ---- #7 maxRounds must not be a dead parameter ----

    @Test
    void executeTask_withMaxRounds_injectsRoundLimitIntoSystemPrompt() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession("run-" + runId)).thenReturn("sess-1");
        when(httpClient.sendMessage(eq("sess-1"), anyString(), eq("do the task"), any()))
                .thenReturn(new OpenCodeHttpClient.MessageResponse("msg-1", "task done", 120, 45));

        provider.executeTask(agent(agentId), runId, "do the task", new TaskContext(7, Duration.ofMinutes(30)));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).sendMessage(eq("sess-1"), promptCaptor.capture(), eq("do the task"), any());
        assertThat(promptCaptor.getValue())
                .as("system prompt must carry the maxRounds constraint")
                .contains("You have at most 7 assistant turns");
    }

    @Test
    void executeTask_withoutMaxRounds_omitsRoundLimitFromSystemPrompt() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession("run-" + runId)).thenReturn("sess-1");
        when(httpClient.sendMessage(eq("sess-1"), anyString(), eq("do the task"), any()))
                .thenReturn(new OpenCodeHttpClient.MessageResponse("msg-1", "task done", 120, 45));

        provider.executeTask(agent(agentId), runId, "do the task", new TaskContext(0, Duration.ofMinutes(30)));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(httpClient).sendMessage(eq("sess-1"), promptCaptor.capture(), eq("do the task"), any());
        assertThat(promptCaptor.getValue())
                .as("no maxRounds means no round-limit injection")
                .doesNotContain("assistant turns");
    }

    @Test
    void executeTask_maxRoundsBudget_capsDeadlineBelowMaxDuration() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession("run-" + runId)).thenReturn("sess-1");
        when(httpClient.sendMessage(eq("sess-1"), anyString(), eq("do the task"), any()))
                .thenReturn(new OpenCodeHttpClient.MessageResponse("msg-1", "task done", 120, 45));

        // 2 rounds × 2 min = 4 min budget, tighter than the 30 min max duration.
        provider.executeTask(agent(agentId), runId, "do the task", new TaskContext(2, Duration.ofMinutes(30)));

        verify(httpClient).sendMessage(eq("sess-1"), anyString(), eq("do the task"), eq(Duration.ofMinutes(4)));
    }

    // ---- #10 concurrent runs for the same agent share one sandbox preparation ----

    @Test
    void concurrentExecuteTask_sameAgent_createsSandboxOnlyOnce() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-1");
        when(sandboxManager.getSandboxUrl("sb-1", 4096)).thenReturn("http://127.0.0.1:4096");
        when(httpClient.isHealthy()).thenReturn(true);
        when(httpClient.createSession(anyString())).thenAnswer(inv -> "sess-" + UUID.randomUUID());
        when(httpClient.sendMessage(any(), any(), any(), any()))
                .thenReturn(new OpenCodeHttpClient.MessageResponse("msg-1", "done", 1, 1));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TaskResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID runId = UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return provider.executeTask(agent(agentId), runId, "task-" + runId,
                        new TaskContext(0, Duration.ofMinutes(5)));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads must reach the barrier").isTrue();
        go.countDown();
        for (Future<TaskResult> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS).finalOutput()).isEqualTo("done");
        }
        pool.shutdownNow();

        verify(sandboxManager, times(1)).createSandbox(eq(agentId), eq(IMAGE), any());
        assertThat(provider.instancesForTest()).containsKey(agentId);
    }

    // ---- #F12 fresh sandbox health probe before reuse ----

    @Test
    void getOrPrepareInstance_staleHealthyCachedInstance_destroysAndRebuilds() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-old", true, Instant.now(), 0, httpClient));
        // Cached instance reports healthy, but a fresh probe reports false.
        when(httpClient.isHealthy()).thenReturn(false, true);
        when(sandboxManager.createSandbox(eq(agentId), eq(IMAGE), any())).thenReturn("sb-new");
        when(sandboxManager.getSandboxUrl("sb-new", 4096)).thenReturn("http://127.0.0.1:4096");

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxManager).killSandbox("sb-old");
        verify(sandboxManager).createSandbox(eq(agentId), eq(IMAGE), any());
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-new");
    }

    @Test
    void getOrPrepareInstance_healthyInstance_isReused() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);

        provider.prepareAgent(agentId, agent(agentId));

        verify(sandboxManager, never()).createSandbox(any(), any(), any());
        verify(sandboxManager, never()).killSandbox(any());
        assertThat(provider.instancesForTest().get(agentId).sandboxId()).isEqualTo("sb-1");
    }

    @Test
    void getOrPrepareInstance_probesHealthOnEveryInvocation() {
        UUID agentId = UUID.randomUUID();
        provider.putInstanceForTest(agentId, new OpenCodeInstance("sb-1", true, Instant.now(), 0, httpClient));
        when(httpClient.isHealthy()).thenReturn(true);

        provider.prepareAgent(agentId, agent(agentId));
        provider.prepareAgent(agentId, agent(agentId));

        verify(httpClient, times(2)).isHealthy();
    }
}
