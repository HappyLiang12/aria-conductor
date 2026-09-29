package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the cross-core Host/Sandbox runtime ledger types (plan
 * section 2.1): exact record components, defensive copies for collection-bearing
 * records, toString redaction for the two memory-only secret carriers, and
 * nullable (never zero) unknown usage.
 */
class RuntimeLedgerTypesTest {

    private static final String SECRET = "fixture-secret-value";

    @Test
    void secretBundleDefensivelyCopiesEnvironmentAndRedactsToString() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("QODER_PAT", SECRET);
        SecretBundle bundle = new SecretBundle("qoder:operator", environment);
        environment.put("LATE", "added-after-construction");

        assertThat(bundle.reference()).isEqualTo("qoder:operator");
        assertThat(bundle.environment()).containsExactly(Map.entry("QODER_PAT", SECRET));
        assertThatThrownBy(() -> bundle.environment().put("X", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bundle.toString())
                .isEqualTo("SecretBundle[redacted]")
                .doesNotContain(SECRET);
    }

    @Test
    void launchProfileDefensivelyCopiesArgvAndEnvAndRedactsToString() {
        List<String> argv = new ArrayList<>(List.of("qodercli", "--acp"));
        Map<String, String> environment = new LinkedHashMap<>(Map.of("QODER_PAT", SECRET));
        LaunchProfile profile = new LaunchProfile(argv, environment, "C:/work/run-1");
        argv.add("--late");
        environment.put("LATE", "added-after-construction");

        assertThat(profile.argv()).containsExactly("qodercli", "--acp");
        assertThat(profile.env()).containsExactly(Map.entry("QODER_PAT", SECRET));
        assertThat(profile.workingDirectory()).isEqualTo("C:/work/run-1");
        assertThat(profile.toString())
                .isEqualTo("LaunchProfile[redacted]")
                .doesNotContain(SECRET);
    }

    @Test
    void coreTaskDefensivelyCopiesHistory() {
        List<LlmMessage> history = new ArrayList<>(List.of(LlmMessage.user("first")));
        CoreTask task = new CoreTask("system prompt", history, "current request");
        history.add(LlmMessage.assistant("appended-after-construction"));

        assertThat(task.systemPrompt()).isEqualTo("system prompt");
        assertThat(task.history()).containsExactly(LlmMessage.user("first"));
        assertThat(task.userPrompt()).isEqualTo("current request");
    }

    @Test
    void executionSpecCarriesTheFrozenBindingFields() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-09-22T12:45:00Z");
        var settings = new AgentExecutionSettings("qoder", ExecutionMode.HOST,
                WorkspaceMode.WORKTREE, "C:/projects/example", "main");
        var spec = new ExecutionSpec(runId, agentId, "qoder", ExecutionMode.HOST,
                settings, "qoder:operator", "rev-7", deadline);

        assertThat(spec.runId()).isEqualTo(runId);
        assertThat(spec.agentId()).isEqualTo(agentId);
        assertThat(spec.coreId()).isEqualTo("qoder");
        assertThat(spec.mode()).isEqualTo(ExecutionMode.HOST);
        assertThat(spec.settings()).isEqualTo(settings);
        assertThat(spec.credentialRef()).isEqualTo("qoder:operator");
        assertThat(spec.configurationRevision()).isEqualTo("rev-7");
        assertThat(spec.deadline()).isEqualTo(deadline);
    }

    @Test
    void workspaceEnvironmentAndRuntimeRecordsCarryTheirExactComponents() {
        UUID runId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        var lease = new WorkspaceLease(leaseId, runId, io.aria.conductor.common.runtime.WorkspaceKind.WORKTREE,
                Path.of("C:/work/run-1"), Path.of("C:/projects/example"), "runtime-root", "abc123");
        var environment = new PreparedEnvironment(runId, ExecutionMode.HOST, "env-1",
                "C:/work/run-1", "C:/work/run-1/config", URI.create("http://127.0.0.1:9301"));
        var handle = new RuntimeHandle(runId, ExecutionMode.HOST, "env-1", "run-1-owner",
                URI.create("http://127.0.0.1:9301"));
        var proof = new StopProof(runId, true);

        assertThat(lease.leaseId()).isEqualTo(leaseId);
        assertThat(lease.runId()).isEqualTo(runId);
        assertThat(lease.localRoot()).isEqualTo(Path.of("C:/work/run-1"));
        assertThat(lease.sourceRoot()).isEqualTo(Path.of("C:/projects/example"));
        assertThat(lease.runtimeRoot()).isEqualTo("runtime-root");
        assertThat(lease.baseCommit()).isEqualTo("abc123");
        assertThat(environment.mode()).isEqualTo(ExecutionMode.HOST);
        assertThat(environment.endpoint()).isEqualTo(URI.create("http://127.0.0.1:9301"));
        assertThat(handle.ownershipIdentity()).isEqualTo("run-1-owner");
        assertThat(proof.runId()).isEqualTo(runId);
        assertThat(proof.allWritersStopped()).isTrue();
    }

    @Test
    void controlAndOutcomeRecordsCarryTheirExactComponents() {
        UUID runId = UUID.randomUUID();
        var ack = new ControlAck(ControlState.PAUSED, true);
        var capabilities = new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true);
        var result = new CoreResult("session-1", "final output",
                new UsageSnapshot(10L, 20L, new BigDecimal("1.25"), "efficient"), false);
        var event = new CoreEvent("progress", runId, "session-1", "req-1", "{}");
        var artifacts = new ArtifactBundle(Path.of("C:/work/run-1/artifacts"), "sha256-value", true);

        assertThat(ack.state()).isEqualTo(ControlState.PAUSED);
        assertThat(ack.verified()).isTrue();
        assertThat(ControlState.values())
                .containsExactly(ControlState.RUNNING, ControlState.PAUSED, ControlState.STOPPED);
        assertThat(ControlStrategy.values())
                .containsExactly(ControlStrategy.NATIVE_CHECKPOINT, ControlStrategy.BACKEND_SUSPEND,
                        ControlStrategy.UNVERIFIED);
        assertThat(capabilities.pauseStrategy()).isEqualTo(ControlStrategy.BACKEND_SUSPEND);
        assertThat(capabilities.nativeCancel()).isTrue();
        assertThat(capabilities.enforcedRoundLimit()).isFalse();
        assertThat(capabilities.usageObservable()).isTrue();
        assertThat(result.sessionId()).isEqualTo("session-1");
        assertThat(result.finalOutput()).isEqualTo("final output");
        assertThat(result.cancelled()).isFalse();
        assertThat(event.type()).isEqualTo("progress");
        assertThat(event.runId()).isEqualTo(runId);
        assertThat(event.payloadJson()).isEqualTo("{}");
        assertThat(artifacts.manifestSha256()).isEqualTo("sha256-value");
        assertThat(artifacts.complete()).isTrue();
    }

    @Test
    void unknownUsageStaysNullRatherThanZero() {
        var unknown = new UsageSnapshot(null, null, null, null);

        assertThat(unknown.inputTokens()).isNull();
        assertThat(unknown.outputTokens()).isNull();
        assertThat(unknown.credits()).isNull();
        assertThat(unknown.observedModel()).isNull();
    }

    @Test
    void observedUsageKeepsItsExactValues() {
        var observed = new UsageSnapshot(1200L, 340L, new BigDecimal("0.42"), "efficient");

        assertThat(observed.inputTokens()).isEqualTo(1200L);
        assertThat(observed.outputTokens()).isEqualTo(340L);
        assertThat(observed.credits()).isEqualByComparingTo("0.42");
        assertThat(observed.observedModel()).isEqualTo("efficient");
    }
}
