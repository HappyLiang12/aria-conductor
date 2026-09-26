package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.opencode.OpenCodeHttpClient;
import io.aria.conductor.execution.approval.PermissionReply;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.ControlStrategy;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreEvent;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test of the native core sessions behind the shared ports (task 11).
 *
 * <p>Every assertion runs against a REAL peer process. The Qoder core is the
 * committed run-bound bridge ({@code packages/qoder-acp-bridge/dist/main.js},
 * built by {@code pnpm build}) launched through the adapter's own
 * {@code launchProfile} with the committed mock Qoder CLI
 * ({@code mock-qoder.mjs}) as its child; the OpenCode core is the committed
 * mock OpenCode service ({@code mock-opencode.mjs}) launched through the
 * adapter's own launch profile. Nothing here substitutes a hand-rolled core:
 * the only fixture is the deterministic committed peer, and the exercised chain
 * (adapter -&gt; native protocol -&gt; peer) is real.
 *
 * <p>The recorded wire authority is Task 1's fixture set
 * ({@code e2e/agent-core/fixtures/}) with the {@code efficient} model pin; the
 * peers are its executable reproduction. Scenarios whose shapes the committed
 * recordings do not contain ({@code reported-usage}, {@code unknown-usage},
 * {@code two-turn-nonce}) are declared in {@code e2e/scenarios.json} and
 * implemented by both peers; they reuse the recorded envelopes and pin only
 * fixture-defined values.
 */
@Timeout(600)
class CoreAdapterContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PEER_CONTROL_TOKEN = "core-adapter-contract-peer-token-0001";
    private static final String MODEL_PIN = "efficient";
    /** The reviewed pinned core versions of the committed capability matrix rows. */
    private static final String QODER_CORE_VERSION = "1.1.61";
    private static final String OPENCODE_CORE_VERSION = "1.14.31";
    private static final String QODER_CREDENTIAL_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";
    private static final String FIXTURE_CREDENTIAL = "fixture-personal-access-token-value";

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable cleanup : cleanups) {
            try {
                cleanup.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a failed test
            }
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------ Qoder: completion contract

    /**
     * Step 1's exact contract block, driven by the real bridge with the
     * committed mock CLI child in the {@code reported-usage} scenario. The
     * session id is captured from the bridge's own authenticated session
     * response, independently of the adapter under test.
     */
    @Test
    void qoderSessionReportsTheRecordedCompletionContract() throws Exception {
        try (QoderRun run = new QoderRun("reported-usage")) {
            String recordedSessionId = run.recordedSessionId();
            List<CoreEvent> events = new CopyOnWriteArrayList<>();

            CoreResult result = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), events::add)
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            assertThat(result.sessionId()).isEqualTo(recordedSessionId);
            assertThat(result.finalOutput()).isEqualTo("fixture-complete");
            assertThat(result.usage().inputTokens()).isEqualTo(12L);
            assertThat(result.usage().outputTokens()).isEqualTo(7L);
            assertThat(result.usage().observedModel()).isEqualTo("efficient");
            assertThat(result.usage().credits()).as("no credit accounting is reported natively").isNull();
            assertThat(result.cancelled()).isFalse();

            // The exact native prompt body: the bridge echoes the received text
            // in prompt.started, so a single-turn task with no system material
            // and no history must arrive verbatim.
            JsonNode started = firstFrameOfType(events, "prompt.started");
            assertThat(started.path("text").asText()).isEqualTo("Reply with the fixture completion");
            assertThat(started.path("sessionId").asText()).isEqualTo(recordedSessionId);

            // Streamed payloads: the bridge's session.update frames reach the
            // consumer, so the exact chunk text stays observable.
            JsonNode messageChunk = events.stream()
                    .filter(event -> "session.update".equals(event.type()))
                    .map(CoreAdapterContractTest::payload)
                    .filter(payload -> "agent_message_chunk"
                            .equals(payload.path("update").path("sessionUpdate").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no agent_message_chunk event arrived"));
            assertThat(messageChunk.path("update").path("content").path("text").asText())
                    .isEqualTo("fixture-complete");

            // The terminal frame is forwarded too, with the exact members of the
            // recorded prompt-result envelope.
            JsonNode terminal = firstFrameOfType(events, "prompt.result");
            assertThat(terminal.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(terminal.path("usage").path("inputTokens").asLong()).isEqualTo(12L);
            assertThat(terminal.path("observedModel").asText()).isEqualTo("efficient");

            for (CoreEvent event : events) {
                assertThat(event.runId()).isEqualTo(run.runId());
                assertThat(event.sessionId()).isEqualTo(recordedSessionId);
            }

            // The reviewed pin is the requested model; the observed model above
            // came from the core's own report.
            assertThat(run.adapter().model()).isEqualTo(MODEL_PIN);
        }
    }

    /**
     * Unknown usage is null, never zero -- and the requested pin is never
     * relabelled as an observed model.
     */
    @Test
    void qoderUnknownUsageStaysNullInsteadOfZero() throws Exception {
        try (QoderRun run = new QoderRun("unknown-usage")) {
            CoreResult result = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            assertThat(result.finalOutput()).isEqualTo("fixture-usage-unknown");
            assertThat(result.usage().inputTokens()).isNull();
            assertThat(result.usage().outputTokens()).isNull();
            assertThat(result.usage().credits()).isNull();
            assertThat(result.usage().observedModel())
                    .as("an unreported observed model must not become the requested pin")
                    .isNull();
            assertThat(run.adapter().model())
                    .as("the requested pin is still what the reviewed profile asks for")
                    .isEqualTo(MODEL_PIN);
        }
    }

    /**
     * Two turns of one session: the second turn must carry the exact prior user
     * and assistant messages as context. The peer echoes the exact text it
     * received, so the assertion is on what the core really saw.
     */
    @Test
    void qoderSecondTurnCarriesTheExactPriorMessagesAsContext() throws Exception {
        try (QoderRun run = new QoderRun("two-turn-nonce")) {
            String nonceFirst = "nonce-first-" + UUID.randomUUID();
            String nonceSecond = "nonce-second-" + UUID.randomUUID();
            String system = "fixture system material";

            CoreResult first = run.session()
                    .prompt(new CoreTask(null, List.of(), nonceFirst), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertThat(first.finalOutput())
                    .as("a single-turn task with no history is sent verbatim")
                    .isEqualTo(nonceFirst);

            List<CoreEvent> secondEvents = new CopyOnWriteArrayList<>();
            CoreResult second = run.session()
                    .prompt(new CoreTask(system, List.of(LlmMessage.user(nonceFirst),
                            LlmMessage.assistant(nonceFirst)), nonceSecond), secondEvents::add)
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            String expected = system + "\n\n"
                    + "user: " + nonceFirst + "\n"
                    + "assistant: " + nonceFirst + "\n\n"
                    + nonceSecond;
            assertThat(second.finalOutput())
                    .as("the core must receive system input, ordered history and the current request")
                    .isEqualTo(expected);
            assertThat(firstFrameOfType(secondEvents, "prompt.started").path("text").asText())
                    .as("the exact composed prompt text is what reached the bridge")
                    .isEqualTo(expected);

            assertThat(Files.list(run.workspace()).toList())
                    .as("neither turn may execute a fixture action")
                    .isEmpty();
        }
    }

    /**
     * Native permission option ids and the reply mapping: an allow-once reply
     * writes exactly the granted bytes, a rejection leaves no side effect, and
     * allow-always is never selectable as an allow-once substitute.
     */
    @Test
    void qoderPermissionRepliesUseTheOfferedNativeOptionIds() throws Exception {
        try (QoderRun run = new QoderRun("write-twice")) {
            BlockingQueue<CoreEvent> permissions = new LinkedBlockingQueue<>();
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Create the fixture file, then run the fixture command"),
                            event -> {
                                if ("permission.request".equals(event.type())) {
                                    permissions.add(event);
                                }
                            });

            CoreEvent first = permissions.poll(60, TimeUnit.SECONDS);
            assertThat(first).as("the peer must request the edit permission").isNotNull();
            JsonNode editRequest = JSON.readTree(first.payloadJson());
            assertThat(editRequest.path("toolName").asText()).isEqualTo("Write");
            assertThat(editRequest.path("toolKind").asText()).isEqualTo("edit");
            assertThat(editRequest.path("options")).isEqualTo(recordedEditOptions());
            assertThat(first.requestId()).isEqualTo(editRequest.path("requestId").asText());
            int editRequestId = editRequest.path("requestId").asInt();

            // allow-always is offered by the core but is never an allow-once substitute.
            assertThatThrownBy(() -> run.session()
                    .decide(new PermissionReply(run.runId(), run.session().sessionId(),
                            String.valueOf(editRequestId), "proceed_always"))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS))
                    .hasMessageContaining("proceed_always")
                    .hasMessageContaining("allow_always");

            run.session().decide(new PermissionReply(run.runId(), run.session().sessionId(),
                    String.valueOf(editRequestId), "proceed_once")).toCompletableFuture().get(30, TimeUnit.SECONDS);

            CoreEvent second = permissions.poll(60, TimeUnit.SECONDS);
            assertThat(second).as("the peer must re-ask with a distinct request id").isNotNull();
            JsonNode executeRequest = JSON.readTree(second.payloadJson());
            assertThat(executeRequest.path("toolKind").asText()).isEqualTo("execute");
            assertThat(executeRequest.path("options")).isEqualTo(recordedExecuteOptions());
            int executeRequestId = executeRequest.path("requestId").asInt();
            assertThat(executeRequestId).isNotEqualTo(editRequestId);

            run.session().decide(new PermissionReply(run.runId(), run.session().sessionId(),
                    String.valueOf(executeRequestId), "cancel")).toCompletableFuture().get(30, TimeUnit.SECONDS);

            CoreResult result = prompting.toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertThat(result.finalOutput())
                    .as("the committed peer's write flows finish without an assistant message"
                            + " (Task 7's declared unpinned-completion gap, carried to the harness task);"
                            + " the granted and denied side effects above are the permission contract")
                    .isEmpty();
            assertThat(Files.readString(run.workspace().resolve("probe-allow-once.txt")))
                    .as("a granted allow-once reply writes exactly the recorded bytes")
                    .isEqualTo("alpha-allow-once");
            assertThat(Files.exists(run.workspace().resolve("probe-write-twice-2.txt")))
                    .as("a rejected permission leaves no side effect")
                    .isFalse();

            // An option id the core never offered is refused instead of guessed.
            assertThatThrownBy(() -> run.session()
                    .decide(new PermissionReply(run.runId(), run.session().sessionId(),
                            String.valueOf(editRequestId), "not-offered-option"))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS))
                    .hasMessageContaining("not-offered-option");
        }
    }

    /** Cancellation is per-prompt and its acknowledgement is the bridge's truthful one. */
    @Test
    void qoderCancelIsVerifiedByTheCoreAndWritesNothing() throws Exception {
        try (QoderRun run = new QoderRun("cancel-pending")) {
            BlockingQueue<CoreEvent> permissions = new LinkedBlockingQueue<>();
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Create probe-cancel-pending.txt with the fixture text"),
                            event -> {
                                if ("permission.request".equals(event.type())) {
                                    permissions.add(event);
                                }
                            });
            assertThat(permissions.poll(60, TimeUnit.SECONDS)).isNotNull();

            ControlAck ack = run.session().cancel(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);

            assertThat(ack.state()).isEqualTo(ControlState.STOPPED);
            assertThat(ack.verified()).as("the core confirmed stopReason=cancelled").isTrue();
            CoreResult result = prompting.toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertThat(result.cancelled()).as("a cancelled prompt is reported as cancelled").isTrue();
            assertThat(Files.exists(run.workspace().resolve("probe-cancel-pending.txt")))
                    .as("cancelling before a decision must not write")
                    .isFalse();
        }
    }

    /**
     * Native pause is unavailable for this core: the session reports the
     * unchanged, unverified state and falls back to nothing by itself, while
     * the separately verified BACKEND_SUSPEND strategy is what the capability
     * snapshot names.
     */
    @Test
    void qoderNativePauseIsUnavailableAndNeverFallsBack() throws Exception {
        try (QoderRun run = new QoderRun("reported-usage")) {
            List<CoreEvent> events = new CopyOnWriteArrayList<>();
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), events::add);

            ControlAck paused = run.session().pause(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(paused.state()).as("no pause happened, so no state change may be reported")
                    .isEqualTo(ControlState.RUNNING);
            assertThat(paused.verified()).isFalse();

            ControlAck resumed = run.session().resume(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(resumed.state()).isEqualTo(ControlState.RUNNING);
            assertThat(resumed.verified()).isFalse();

            CoreResult result = prompting.toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertThat(result.finalOutput())
                    .as("an unverified pause must not disturb the running prompt")
                    .isEqualTo("fixture-complete");
            assertThat(events.stream().map(CoreEvent::type).filter(type -> type.startsWith("control.")).toList())
                    .as("a refused pause provably emits no control frame")
                    .isEmpty();

            assertThat(run.adapter().capabilities(ExecutionMode.HOST))
                    .isEqualTo(new CoreCapabilities(ControlStrategy.BACKEND_SUSPEND, true, false, true));
            assertThat(run.adapter().capabilities(ExecutionMode.SANDBOX))
                    .as("the recorded sandbox row is blocked: every value stays unverified")
                    .isEqualTo(new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false));
        }
    }

    /** A core that disconnects mid-prompt is an explicit failure, never a completion. */
    @Test
    void qoderDisconnectFailsThePromptExplicitly() throws Exception {
        try (QoderRun run = new QoderRun("disconnect")) {
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture acknowledgment"), event -> { });

            assertThatThrownBy(() -> prompting.toCompletableFuture().get(120, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TaskExecutionException.class);
            TaskExecutionException failure = (TaskExecutionException) causeOf(prompting);
            assertThat(failure.cause()).isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR);
            assertThat(failure.getMessage())
                    .as("the bridge's exact child-exit diagnosis must survive translation")
                    .isEqualTo("Qoder bridge refused the prompt (E_CHILD_EXITED): Qoder child exited with code 7");
        }
    }

    /**
     * A stream-open failure is reported as a failed stage, never thrown out of a
     * {@code CompletionStage}-returning method, and never latches the session: the
     * prompt state is cleared, so the next prompt reports the bridge failure
     * again instead of a phantom "prompt already in flight".
     */
    @Test
    void qoderStreamOpenFailureFailsTheStageAndNeverLatchesTheSession() throws Exception {
        try (QoderRun run = new QoderRun("reported-usage")) {
            run.stopBridge();

            CompletionStage<CoreResult> first = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { });
            assertThatThrownBy(() -> first.toCompletableFuture().get(30, TimeUnit.SECONDS))
                    .as("an unreachable bridge must surface as a failed stage, not as a synchronous throw")
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be reached for its event stream");

            assertThatThrownBy(() -> run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                    .toCompletableFuture().get(30, TimeUnit.SECONDS))
                    .as("the session must stay usable: the bridge failure must be reported again,"
                            + " never a phantom prompt already in flight")
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be reached for its event stream")
                    .hasMessageNotContaining("A prompt is already in flight");
        }
    }

    /**
     * A run without its frozen deadline is a programming error, not a licence to
     * run unbounded: the prompt is refused explicitly instead of being scheduled
     * without any deadline at all.
     */
    @Test
    void qoderNullDeadlineIsRefusedInsteadOfRunningUnbounded() throws Exception {
        try (QoderRun run = new QoderRun("reported-usage")) {
            QoderBridgeClient client = new QoderBridgeClient(run.environment().endpoint(), run.secret());
            try (QoderCoreSession session = new QoderCoreSession(run.specWithoutDeadline(), client, MODEL_PIN)) {
                assertThatThrownBy(() -> session
                        .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                        .toCompletableFuture().get(30, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("The Qoder session of run " + run.runId() + " carries no run deadline;"
                                + " every run freezes a 45-minute deadline, so a prompt without one is refused");
            }
        }
    }

    /**
     * An unsupported model is refused at launch, never substituted: the bridge
     * validates the reviewed pin against the core's offered list before it can
     * serve a session.
     */
    @Test
    void qoderUnsupportedModelPinIsRefusedWithoutFallback() throws Exception {
        try (QoderRun unsupported = new QoderRun("complete", "fixture-not-offered-model")) {
            assertThat(unsupported.readyLine()).isNull();
            JsonNode error = unsupported.bridgeErrorLine();
            assertThat(error.path("code").asText()).isEqualTo("E_UNSUPPORTED_MODEL");
            assertThat(error.path("message").asText()).isEqualTo(
                    "Model \"fixture-not-offered-model\" is not offered by the pinned core; offered: "
                            + "[\"efficient\",\"qmodel_38max\",\"qfmodel\"]");
            assertThat(unsupported.exitCode()).as("the bridge's handshake exit code").isEqualTo(70);
        }
    }

    // ------------------------------------------------------------------ Qoder: bridge client gaps

    /** The launch profile names the run's secret and credential files and never carries a secret value. */
    @Test
    void qoderLaunchProfileNamesTheSecretFilesAndLeaksNoSecretValue() throws Exception {
        try (QoderRun run = new QoderRun("complete")) {
            LaunchProfile profile = run.launchProfile();

            assertThat(profile.workingDirectory()).isEqualTo(run.workspace().toString());
            assertThat(profile.argv()).isEqualTo(List.of(
                    nodeExecutable(),
                    bridgeEntry().toString(),
                    "--run-id", run.runId().toString(),
                    "--workspace", run.workspace().toString(),
                    "--model", MODEL_PIN,
                    "--cli", nodeExecutable(),
                    "--cli-arg", qoderPeerScript().toString(),
                    "--child-env", "ARIA_PEER_CONTROL_TOKEN=" + PEER_CONTROL_TOKEN,
                    "--child-env", "ARIA_PEER_SCENARIO=complete",
                    "--child-env", "ARIA_PEER_WORKSPACE=" + run.workspace(),
                    "--credential-env", QODER_CREDENTIAL_VARIABLE,
                    "--credential-file", QoderCoreAdapter.credentialFile(run.environment()).toString(),
                    "--control-secret-file", QoderCoreAdapter.controlSecretFile(run.environment()).toString(),
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(run.environment().endpoint().getPort())));
            assertThat(profile.env()).isEqualTo(Map.of());
            for (String argument : profile.argv()) {
                assertThat(argument)
                        .as("a secret value must never appear in the launch argv")
                        .doesNotContain(run.secret())
                        .doesNotContain(FIXTURE_CREDENTIAL);
            }
        }
    }

    /** The client authenticates exactly as the bridge's contract demands. */
    @Test
    void qoderBridgeClientRefusesAWrongSecretAndAForeignBinding() throws Exception {
        try (QoderRun run = new QoderRun("complete")) {
            try (QoderBridgeClient wrong = new QoderBridgeClient(run.environment().endpoint(),
                    "wrong-control-secret-000000000000")) {
                assertThatThrownBy(wrong::session)
                        .isInstanceOf(QoderBridgeClient.BridgeFailure.class)
                        .satisfies(error -> {
                            assertThat(((QoderBridgeClient.BridgeFailure) error).code()).isEqualTo("E_UNAUTHORIZED");
                            assertThat(((QoderBridgeClient.BridgeFailure) error).status()).isEqualTo(401);
                        });
            }

            try (QoderBridgeClient right = new QoderBridgeClient(run.environment().endpoint(), run.secret())) {
                QoderBridgeClient.SessionView view = right.session();
                assertThat(view.runId()).isEqualTo(run.runId().toString());
                assertThat(view.workspace()).isEqualTo(run.workspace().toString());
                assertThat(view.model()).isEqualTo(MODEL_PIN);
                assertThat(view.sessionId()).isEqualTo(run.recordedSessionId());
                assertThat(view.state()).isEqualTo("ready");

                assertThatThrownBy(() -> right.sessionBoundTo(UUID.randomUUID()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("does not name run");
                assertThat(right.sessionBoundTo(run.runId()).sessionId()).isEqualTo(run.recordedSessionId());
            }
        }
    }

    /**
     * The committed bridge accepts only its own two loopback literals for
     * {@code --host} ({@code packages/qoder-acp-bridge/src/main.ts} refuses
     * {@code localhost}), so the adapter refuses a localhost endpoint up front
     * instead of launching a bridge process that can only fail its usage check.
     */
    @Test
    void qoderAdapterRefusesALocalhostEndpointTheBridgeItselfRejects() throws Exception {
        RunFixture fixture = fixture(QoderCoreAdapter.CORE_ID);
        try {
            int port = freeLoopbackPort();
            PreparedEnvironment localhost = new PreparedEnvironment(fixture.runId(), ExecutionMode.HOST,
                    "host-" + fixture.runId(), fixture.workspace().toString(),
                    fixture.runtimeRoot().resolve("host").toString(),
                    URI.create("http://localhost:" + port + "/"));
            QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                    nodeExecutable(), bridgeEntry().toString(), nodeExecutable(),
                    List.of(qoderPeerScript().toString()), Map.of(), Map.of(),
                    QODER_CORE_VERSION, MODEL_PIN));
            SecretBundle credentials = new SecretBundle("fixture-credential-ref",
                    Map.of(QoderCoreAdapter.CONTROL_SECRET_ENVIRONMENT,
                            "core-adapter-contract-localhost-check"));

            assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), localhost, credentials))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The Qoder bridge endpoint must be a loopback host:port, got: "
                            + "http://localhost:" + port + "/");
        } finally {
            deleteTree(fixture.root());
        }
    }

    /**
     * The alignment premise, executed against the committed bridge: it really
     * refuses {@code --host localhost} with its usage refusal (and never serves),
     * so a launch profile that passed the name on could not come up.
     */
    @Test
    void qoderBridgeRefusesTheLocalhostHostFlagTheAdapterMustNotPassThrough() throws Exception {
        Process process = new ProcessBuilder(nodeExecutable(), bridgeEntry().toString(),
                "--host", "localhost").start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("the committed bridge must exit on the refused --host value")
                    .isTrue();
            assertThat(process.exitValue()).isEqualTo(64);
            assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip())
                    .isEqualTo("{\"type\":\"bridge.error\",\"code\":\"E_USAGE\","
                            + "\"message\":\"--host must be a loopback address, got localhost\"}");
        } finally {
            process.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ OpenCode: completion contract

    @Test
    void openCodeSessionReportsTheRecordedCompletionContract() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("reported-usage")) {
            String recordedSessionId = run.recordedSessionId();
            List<CoreEvent> events = new CopyOnWriteArrayList<>();

            CoreResult result = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), events::add)
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            assertThat(result.sessionId()).isEqualTo(recordedSessionId);
            assertThat(result.finalOutput()).isEqualTo("fixture-complete");
            assertThat(result.usage().inputTokens()).isEqualTo(12L);
            assertThat(result.usage().outputTokens()).isEqualTo(7L);
            assertThat(result.usage().observedModel()).isEqualTo("efficient");
            assertThat(result.usage().credits()).isNull();
            assertThat(result.cancelled()).isFalse();

            assertThat(events).hasSize(1);
            CoreEvent message = events.get(0);
            assertThat(message.type()).isEqualTo("opencode.message");
            assertThat(message.runId()).isEqualTo(run.runId());
            assertThat(message.sessionId()).isEqualTo(recordedSessionId);
            JsonNode envelope = JSON.readTree(message.payloadJson());
            assertThat(envelope.path("info").path("id").asText()).isEqualTo(run.recordedMessageId());
            assertThat(envelope.path("info").path("tokens").path("input").asLong()).isEqualTo(12L);
            assertThat(envelope.path("parts").toString())
                    .isEqualTo("[{\"type\":\"text\",\"text\":\"fixture-complete\"}]");
            assertThat(run.adapter().model()).isEqualTo(MODEL_PIN);
        }
    }

    @Test
    void openCodeUnknownUsageStaysNullInsteadOfZero() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("unknown-usage")) {
            CoreResult result = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            assertThat(result.finalOutput()).isEqualTo("fixture-usage-unknown");
            assertThat(result.usage().inputTokens()).isNull();
            assertThat(result.usage().outputTokens()).isNull();
            assertThat(result.usage().credits()).isNull();
            assertThat(result.usage().observedModel())
                    .as("an unreported modelID must not become the requested pin")
                    .isNull();
            assertThat(run.adapter().model()).isEqualTo(MODEL_PIN);
        }
    }

    /**
     * The exact request bodies of both turns: the system material reaches the
     * native {@code system} member, the ordered history is a leading text part
     * and the current user request is the last part.
     */
    @Test
    void openCodeSecondTurnCarriesTheExactPriorMessagesAsContext() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("two-turn-nonce")) {
            String nonceFirst = "nonce-first-" + UUID.randomUUID();
            String nonceSecond = "nonce-second-" + UUID.randomUUID();
            String system = "fixture system material";

            CoreResult first = run.session()
                    .prompt(new CoreTask(null, List.of(), nonceFirst), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertThat(first.finalOutput()).isEqualTo(nonceFirst);

            CoreResult second = run.session()
                    .prompt(new CoreTask(system, List.of(LlmMessage.user(nonceFirst),
                            LlmMessage.assistant(nonceFirst)), nonceSecond), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);

            String expectedHistory = "user: " + nonceFirst + "\nassistant: " + nonceFirst;
            assertThat(second.finalOutput()).isEqualTo(expectedHistory + "\n" + nonceSecond);

            List<JsonNode> requests = run.messageRequests();
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0)).isEqualTo(JSON.valueToTree(Map.of(
                    "model", MODEL_PIN,
                    "parts", List.of(Map.of("type", "text", "text", nonceFirst)))));
            assertThat(requests.get(1)).isEqualTo(JSON.valueToTree(Map.of(
                    "model", MODEL_PIN,
                    "system", system,
                    "parts", List.of(
                            Map.of("type", "text", "text", expectedHistory),
                            Map.of("type", "text", "text", nonceSecond)))));

            assertThat(Files.list(run.workspace()).toList())
                    .as("neither turn may execute a fixture action")
                    .isEmpty();
        }
    }

    /**
     * The OpenCode surface has no native permission reply channel: the session
     * refuses a decision explicitly instead of inventing a route, while the
     * harness write gate the approval coordinator agrees on stays visible on
     * the peer with its exact recorded option ids.
     *
     * <p>The gate is a HOLD: the peer withholds the message response until the
     * decision resolves (fix round 5), so the pending request is observable
     * while the prompt is in flight and the abort is what releases the turn --
     * the resulting completion carries the fixture's cancelled outcome.
     */
    @Test
    void openCodePermissionDecisionsAreRefusedAndCancelUsesTheAbortRoute() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("cancel-pending")) {
            CompletionStage<CoreResult> stage = run.session()
                    .prompt(new CoreTask(null, List.of(), "Create probe-cancel-pending.txt with the fixture text"),
                            event -> { });

            JsonNode pending = run.awaitPendingDecision(30_000);
            assertThat(pending.path("pending")).hasSize(1);
            assertThat(pending.path("pending").get(0).path("kind").asText()).isEqualTo("edit");
            assertThat(pending.path("pending").get(0).path("options")).isEqualTo(recordedEditOptions());

            assertThatThrownBy(() -> run.session()
                    .decide(new PermissionReply(run.runId(), run.session().sessionId(), "0", "proceed_once"))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS))
                    .hasMessageContaining("no native permission reply channel");

            ControlAck ack = run.session().cancel(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(ack.state())
                    .as("an abort whose outcome the core does not confirm is never a state change")
                    .isEqualTo(ControlState.RUNNING);
            assertThat(ack.verified()).isFalse();

            JsonNode cancelRecord = run.awaitRecord("peer.cancel", 30_000);
            assertThat(cancelRecord.path("pending").asText()).isEqualTo("decision");
            assertThat(cancelRecord.path("pendingRequestIds")).hasSize(1);
            assertThat(cancelRecord.path("pendingRequestIds").get(0).asInt()).isEqualTo(0);
            assertThat(Files.exists(run.workspace().resolve("probe-cancel-pending.txt")))
                    .as("cancelling before a decision must not write")
                    .isFalse();

            CoreResult result = stage.toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertThat(result.finalOutput())
                    .as("the abort released the held turn with the fixture's cancelled outcome")
                    .isEqualTo("fixture write not applied: the run was cancelled");
        }
    }

    /**
     * Native pause is not part of the verified OpenCode HTTP subset (the
     * recorded unsupported-mode evidence answers the route with 404), so the
     * session refuses locally without inventing a call and names no strategy.
     */
    @Test
    void openCodePauseIsRefusedLocallyAndTheRouteDoesNotExist() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("reported-usage")) {
            CoreResult result = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                    .toCompletableFuture().get(120, TimeUnit.SECONDS);
            assertThat(result.finalOutput()).isEqualTo("fixture-complete");

            HttpResponse<String> probe = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            run.environment().endpoint().resolve("session/" + run.recordedSessionId() + "/pause"))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("content-type", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(probe.statusCode())
                    .as("the recorded evidence that no pause RPC exists")
                    .isEqualTo(404);

            ControlAck paused = run.session().pause(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(paused.state()).isEqualTo(ControlState.RUNNING);
            assertThat(paused.verified()).isFalse();
            ControlAck resumed = run.session().resume(Instant.now().plusSeconds(30))
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertThat(resumed.state()).isEqualTo(ControlState.RUNNING);
            assertThat(resumed.verified()).isFalse();

            assertThat(run.adapter().capabilities(ExecutionMode.HOST))
                    .as("the recorded opencode/HOST row is blocked: nothing is verified")
                    .isEqualTo(new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false));
            assertThat(run.adapter().capabilities(ExecutionMode.SANDBOX))
                    .isEqualTo(new CoreCapabilities(ControlStrategy.UNVERIFIED, false, false, false));
        }
    }

    /** A provider refusal and a truncated response are explicit failures, never silent successes. */
    @Test
    void openCodeRefusalsAreExplicitAndNeverASilentSuccess() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("unsupported-model", "fixture-unknown-model")) {
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture acknowledgment"), event -> { });

            assertThatThrownBy(() -> prompting.toCompletableFuture().get(120, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TaskExecutionException.class);
            TaskExecutionException refused = (TaskExecutionException) causeOf(prompting);
            assertThat(refused.cause()).isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR);
            assertThat(refused.getMessage()).isEqualTo("OpenCode refused POST /session/"
                    + run.recordedSessionId() + "/message (status 400): Unsupported model id: fixture-unknown-model");
        }

        try (OpenCodeRun run = new OpenCodeRun("malformed-frame")) {
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture acknowledgment"), event -> { });

            assertThatThrownBy(() -> prompting.toCompletableFuture().get(120, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            TaskExecutionException failure = (TaskExecutionException) causeOf(prompting);
            assertThat(failure.cause())
                    .as("a malformed native body may never be reported as a completed prompt")
                    .isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR);
        }
    }

    /**
     * The peer's disconnect scenario closes the connection mid-response: the
     * single-shot transport's IOException branch (not the parse branch) surfaces
     * as an explicit PROVIDER_ERROR carrying the exact transport diagnosis, and
     * the peer's own record proves which scenario was exercised.
     */
    @Test
    void openCodeDisconnectMidPromptFailsThroughTheTransportBranch() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("disconnect")) {
            CompletionStage<CoreResult> prompting = run.session()
                    .prompt(new CoreTask(null, List.of(), "Reply with the fixture acknowledgment"), event -> { });

            assertThatThrownBy(() -> prompting.toCompletableFuture().get(120, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            TaskExecutionException failure = (TaskExecutionException) causeOf(prompting);
            assertThat(failure.cause()).isEqualTo(TaskExecutionException.Cause.PROVIDER_ERROR);
            assertThat(failure.getMessage()).isEqualTo("OpenCode request failed: POST /session/"
                    + run.recordedSessionId() + "/message \u2014 "
                    + "chunked transfer encoding, state: READING_LENGTH");

            JsonNode disconnect = run.awaitRecord("peer.disconnect", 30_000);
            assertThat(disconnect.path("scenario").asText()).isEqualTo("disconnect");
        }
    }

    /**
     * A run without its frozen deadline is a programming error, not a licence to
     * fall back: the prompt is refused explicitly instead of silently becoming
     * the HTTP client's five-minute default timeout.
     */
    @Test
    void openCodeNullDeadlineIsRefusedInsteadOfTheClientDefault() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("reported-usage")) {
            OpenCodeHttpClient client = new OpenCodeHttpClient(run.environment().endpoint().toString());
            try (OpenCodeCoreSession session = new OpenCodeCoreSession(run.specWithoutDeadline(), client,
                    run.recordedSessionId(), MODEL_PIN)) {
                assertThatThrownBy(() -> session
                        .prompt(new CoreTask(null, List.of(), "Reply with the fixture completion"), event -> { })
                        .toCompletableFuture().get(120, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("The OpenCode session of run " + run.runId() + " carries no run deadline;"
                                + " every run freezes a 45-minute deadline, so a prompt without one is refused");
            }
        }
    }

    /**
     * The governed OpenCode profile: the run-owned XDG configuration carries a
     * deny-by-default permission policy, the legacy allow-all default never
     * survives into it, and the profile really starts the committed peer.
     */
    @Test
    void openCodeGovernedProfileNeverCarriesTheLegacyAllowAllDefault() throws Exception {
        try (OpenCodeRun run = new OpenCodeRun("reported-usage")) {
            Path governed = OpenCodeCoreAdapter.governedConfigurationFile(run.environment());
            assertThat(governed).isEqualTo(Path.of(run.environment().configurationDirectory())
                    .resolve("config").resolve("opencode").resolve("opencode.json"));
            assertThat(Files.readString(governed)).isEqualTo(OpenCodeCoreAdapter.governedConfigurationJson());
            assertThat(OpenCodeCoreAdapter.governedConfigurationJson())
                    .as("the legacy allow-all default must not survive into the governed profile")
                    .doesNotContain("\"*\": \"allow\"")
                    .doesNotContain("\"*\":\"allow\"");

            LaunchProfile profile = run.launchProfile();
            assertThat(profile.argv()).isEqualTo(List.of(
                    nodeExecutable(),
                    openCodePeerScript().toString(),
                    "serve",
                    "--port", String.valueOf(run.environment().endpoint().getPort()),
                    "--hostname", "127.0.0.1"));
            Map<String, String> expectedEnvironment = new LinkedHashMap<>();
            expectedEnvironment.put("ARIA_PEER_CONTROL_TOKEN", PEER_CONTROL_TOKEN);
            expectedEnvironment.put("ARIA_PEER_SCENARIO", "reported-usage");
            expectedEnvironment.put("ARIA_PEER_WORKSPACE", run.workspace().toString());
            expectedEnvironment.put("XDG_CONFIG_HOME", Path.of(run.environment().configurationDirectory())
                    .resolve("config").toString());
            expectedEnvironment.put("XDG_DATA_HOME", Path.of(run.environment().configurationDirectory())
                    .resolve("data").toString());
            expectedEnvironment.put("XDG_CACHE_HOME", Path.of(run.environment().configurationDirectory())
                    .resolve("cache").toString());
            expectedEnvironment.put("DEEPSEEK_API_KEY", "fixture-provider-key");
            assertThat(profile.env()).isEqualTo(expectedEnvironment);
            assertThat(profile.workingDirectory()).isEqualTo(run.workspace().toString());
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record RunFixture(Path root, Path workspace, Path runtimeRoot, UUID runId,
            ExecutionSpec spec, PreparedEnvironment environment) {
    }

    private static RunFixture fixture(String coreId) throws IOException {
        Path root = Files.createTempDirectory("core-adapter-contract").toRealPath();
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runtimeRoot = Files.createDirectories(root.resolve("runtime"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), coreId,
                ExecutionMode.HOST, new AgentExecutionSettings(coreId, ExecutionMode.HOST,
                        WorkspaceMode.DIRECT, workspace.toString(), null),
                "fixture-credential-ref", "fixture-config-revision",
                Instant.now().plus(Duration.ofMinutes(10)));
        Path configuration = runtimeRoot.resolve("host");
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST,
                "host-" + runId, workspace.toString(), configuration.toString(),
                URI.create("http://127.0.0.1:" + freeLoopbackPort() + "/"));
        return new RunFixture(root, workspace, runtimeRoot, runId, spec, environment);
    }

    /** One real Qoder run: the committed bridge launched through the adapter's own launch profile. */
    private final class QoderRun implements AutoCloseable {

        private final RunFixture fixture;
        private final String secret;
        private final QoderCoreAdapter adapter;
        private final LaunchProfile launchProfile;
        private final Process process;
        private final List<String> stdoutLines = new CopyOnWriteArrayList<>();
        private final StringBuilder stderr = new StringBuilder();
        private final String endpoint;
        private final JsonNode readyLine;
        private final JsonNode bridgeErrorLine;
        private final int exitCode;
        private QoderCoreSession session;
        private String recordedSessionId;

        QoderRun(String scenario) throws IOException, InterruptedException {
            this(scenario, MODEL_PIN);
        }

        QoderRun(String scenario, String model) throws IOException, InterruptedException {
            fixture = fixture(QoderCoreAdapter.CORE_ID);
            secret = "core-adapter-contract-secret-" + UUID.randomUUID();
            adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                    nodeExecutable(),
                    bridgeEntry().toString(),
                    nodeExecutable(),
                    List.of(qoderPeerScript().toString()),
                    Map.of(
                            "ARIA_PEER_CONTROL_TOKEN", PEER_CONTROL_TOKEN,
                            "ARIA_PEER_SCENARIO", scenario,
                            "ARIA_PEER_WORKSPACE", fixture.workspace().toString()),
                    Map.of(),
                    QODER_CORE_VERSION,
                    model));
            SecretBundle credentials = new SecretBundle("fixture-credential-ref", Map.of(
                    QODER_CREDENTIAL_VARIABLE, FIXTURE_CREDENTIAL,
                    QoderCoreAdapter.CONTROL_SECRET_ENVIRONMENT, secret));
            launchProfile = adapter.launchProfile(fixture.spec(), fixture.environment(), credentials);

            // The trusted launcher's side of the contract: it writes the run-owned
            // files the profile names, then starts exactly the profile it was given.
            Path secretFile = QoderCoreAdapter.controlSecretFile(fixture.environment());
            Files.createDirectories(secretFile.getParent());
            Files.writeString(secretFile, secret + "\n");
            Files.writeString(QoderCoreAdapter.credentialFile(fixture.environment()), FIXTURE_CREDENTIAL + "\n");

            ProcessBuilder builder = new ProcessBuilder(launchProfile.argv());
            builder.directory(Path.of(launchProfile.workingDirectory()).toFile());
            builder.environment().clear();
            builder.environment().putAll(launchProfile.env());
            process = builder.start();
            startStdoutReader();
            startStderrReader();

            Instant deadline = Instant.now().plusSeconds(60);
            JsonNode ready = null;
            JsonNode error = null;
            int cursor = 0;
            while (Instant.now().isBefore(deadline)) {
                if (stdoutLines.size() > cursor) {
                    JsonNode parsed = JSON.readTree(stdoutLines.get(cursor));
                    cursor += 1;
                    if ("bridge.ready".equals(parsed.path("type").asText())) {
                        ready = parsed;
                        break;
                    }
                    if ("bridge.error".equals(parsed.path("type").asText())) {
                        error = parsed;
                        break;
                    }
                } else if (!process.isAlive()) {
                    break;
                } else {
                    Thread.sleep(25);
                }
            }
            readyLine = ready;
            if (ready != null) {
                endpoint = ready.path("endpoint").asText();
                bridgeErrorLine = null;
                exitCode = Integer.MIN_VALUE;
                URI expected = fixture.environment().endpoint();
                assertThat(endpoint)
                        .as("the bridge must serve the endpoint the backend allocated")
                        .isEqualTo(expected.getScheme() + "://" + expected.getAuthority());
                assertThat(ready.path("runId").asText()).isEqualTo(fixture.runId().toString());
                assertThat(ready.path("model").asText()).isEqualTo(model);
                session = (QoderCoreSession) adapter.open(
                        new RuntimeHandle(fixture.runId(), ExecutionMode.HOST, "host-" + fixture.runId(),
                                "core-adapter-contract-fixture", fixture.environment().endpoint()),
                        fixture.spec(), credentials);
                recordedSessionId = session.sessionId();
                assertThat(recordedSessionId).isEqualTo(bridgeSessionId());
            } else {
                endpoint = null;
                bridgeErrorLine = error;
                exitCode = process.waitFor();
            }
            cleanups.add(this);
        }

        UUID runId() {
            return fixture.runId();
        }

        Path workspace() {
            return fixture.workspace();
        }

        PreparedEnvironment environment() {
            return fixture.environment();
        }

        QoderCoreAdapter adapter() {
            return adapter;
        }

        LaunchProfile launchProfile() {
            return launchProfile;
        }

        String secret() {
            return secret;
        }

        JsonNode readyLine() {
            return readyLine;
        }

        JsonNode bridgeErrorLine() {
            return bridgeErrorLine;
        }

        int exitCode() {
            return exitCode;
        }

        QoderCoreSession session() {
            return session;
        }

        String recordedSessionId() {
            return recordedSessionId;
        }

        /** The same frozen spec with the run deadline removed (a programming-error input). */
        ExecutionSpec specWithoutDeadline() {
            ExecutionSpec spec = fixture.spec();
            return new ExecutionSpec(spec.runId(), spec.agentId(), spec.coreId(), spec.mode(),
                    spec.settings(), spec.credentialRef(), spec.configurationRevision(), null);
        }

        /** The native session id from the bridge's own authenticated session response. */
        private String bridgeSessionId() throws IOException, InterruptedException {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            fixture.environment().endpoint().resolve("session"))
                    .timeout(Duration.ofSeconds(10))
                    .header(QoderBridgeClient.CONTROL_SECRET_HEADER, secret)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body()).path("sessionId").asText();
        }

        private void startStdoutReader() {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutLines.add(line);
                    }
                } catch (IOException ignored) {
                    // the stream closes with the process
                }
            }, "bridge-stdout");
            thread.setDaemon(true);
            thread.start();
        }

        private void startStderrReader() {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderr) {
                            stderr.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // the stream closes with the process
                }
            }, "bridge-stderr");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            stopBridge();
            if (session != null) {
                session.close();
            }
            try {
                deleteTree(fixture.root());
            } catch (IOException ignored) {
                // best effort: a fixture writer may still hold a handle for a moment
            }
        }

        /** Stops the bridge process and its descendants and waits for its port to close. */
        void stopBridge() {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (ProcessHandle descendant : descendants) {
                descendant.destroyForcibly();
            }
            process.destroyForcibly();
            try {
                process.waitFor(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One real OpenCode run: the committed mock service launched through the adapter's own profile. */
    private final class OpenCodeRun implements AutoCloseable {

        private final RunFixture fixture;
        private final OpenCodeCoreAdapter adapter;
        private final LaunchProfile launchProfile;
        private final Process process;
        private final List<JsonNode> records = new CopyOnWriteArrayList<>();
        private final List<JsonNode> messageRequests = new CopyOnWriteArrayList<>();
        private final BlockingQueue<JsonNode> recordQueue = new LinkedBlockingQueue<>();
        private OpenCodeCoreSession session;
        private String recordedSessionId;

        OpenCodeRun(String scenario) throws IOException, InterruptedException {
            this(scenario, MODEL_PIN);
        }

        OpenCodeRun(String scenario, String model) throws IOException, InterruptedException {
            fixture = fixture(OpenCodeCoreAdapter.CORE_ID);
            adapter = new OpenCodeCoreAdapter(new OpenCodeCoreAdapter.OpenCodeProfile(
                    nodeExecutable(),
                    List.of(openCodePeerScript().toString()),
                    Map.of(
                            "ARIA_PEER_CONTROL_TOKEN", PEER_CONTROL_TOKEN,
                            "ARIA_PEER_SCENARIO", scenario,
                            "ARIA_PEER_WORKSPACE", fixture.workspace().toString()),
                    OPENCODE_CORE_VERSION,
                    model));
            SecretBundle credentials = new SecretBundle("fixture-credential-ref",
                    Map.of("DEEPSEEK_API_KEY", "fixture-provider-key"));
            launchProfile = adapter.launchProfile(fixture.spec(), fixture.environment(), credentials);

            ProcessBuilder builder = new ProcessBuilder(launchProfile.argv());
            builder.directory(Path.of(launchProfile.workingDirectory()).toFile());
            builder.environment().clear();
            builder.environment().putAll(launchProfile.env());
            process = builder.start();
            startStderrReader();

            awaitRecord("peer.listening", 30_000);
            session = (OpenCodeCoreSession) adapter.open(
                    new RuntimeHandle(fixture.runId(), ExecutionMode.HOST, "host-" + fixture.runId(),
                            "core-adapter-contract-fixture", fixture.environment().endpoint()),
                    fixture.spec(), credentials);
            // The session identity is captured from the peer's own record of the
            // session the adapter created, never from the adapter's claim.
            recordedSessionId = peerState().path("sessionId").asText(null);
            assertThat(recordedSessionId).startsWith("ses_");
            assertThat(session.sessionId()).isEqualTo(recordedSessionId);
            cleanups.add(this);
        }

        UUID runId() {
            return fixture.runId();
        }

        Path workspace() {
            return fixture.workspace();
        }

        PreparedEnvironment environment() {
            return fixture.environment();
        }

        OpenCodeCoreAdapter adapter() {
            return adapter;
        }

        LaunchProfile launchProfile() {
            return launchProfile;
        }

        OpenCodeCoreSession session() {
            return session;
        }

        String recordedSessionId() {
            return recordedSessionId;
        }

        /** The same frozen spec with the run deadline removed (a programming-error input). */
        ExecutionSpec specWithoutDeadline() {
            ExecutionSpec spec = fixture.spec();
            return new ExecutionSpec(spec.runId(), spec.agentId(), spec.coreId(), spec.mode(),
                    spec.settings(), spec.credentialRef(), spec.configurationRevision(), null);
        }

        /** The assistant message id the peer itself served, read back through the native list route. */
        String recordedMessageId() throws IOException, InterruptedException {
            HttpResponse<String> listed = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            fixture.environment().endpoint().resolve("session/" + recordedSessionId + "/message"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(listed.statusCode()).isEqualTo(200);
            JsonNode messages = JSON.readTree(listed.body());
            assertThat(messages).hasSize(1);
            return messages.get(0).path("info").path("id").asText();
        }

        List<JsonNode> messageRequests() {
            return List.copyOf(messageRequests);
        }

        JsonNode pendingDecisions() throws IOException, InterruptedException {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            fixture.environment().endpoint().resolve("__peer/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("x-peer-control-token", PEER_CONTROL_TOKEN)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        }

        /**
         * The first pending fixture decision, polled while a HELD prompt is in
         * flight: a decision-gated turn withholds the message response until its
         * decision resolves, so the ask must be awaited, not assumed.
         */
        JsonNode awaitPendingDecision(long timeoutMillis) throws IOException, InterruptedException {
            Instant limit = Instant.now().plusMillis(timeoutMillis);
            JsonNode pending = null;
            while (Instant.now().isBefore(limit)) {
                pending = pendingDecisions();
                if (pending.path("pending").size() > 0) {
                    return pending;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("no pending fixture decision arrived; last: " + pending);
        }

        /** The peer's own state view (harness-authenticated), including its current session id. */
        JsonNode peerState() throws IOException, InterruptedException {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            fixture.environment().endpoint().resolve("__peer/state"))
                    .timeout(Duration.ofSeconds(10))
                    .header("x-peer-control-token", PEER_CONTROL_TOKEN)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        }

        JsonNode awaitRecord(String type, long timeoutMillis) throws InterruptedException {
            Instant limit = Instant.now().plusMillis(timeoutMillis);
            while (Instant.now().isBefore(limit)) {
                JsonNode record = recordQueue.poll(Math.max(1, Duration.between(Instant.now(), limit).toMillis()),
                        TimeUnit.MILLISECONDS);
                if (record == null) {
                    break;
                }
                if (type.equals(record.path("type").asText())) {
                    return record;
                }
            }
            throw new AssertionError("no '" + type + "' record arrived; recorded: " + records);
        }

        private void startStderrReader() {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        JsonNode record;
                        try {
                            record = JSON.readTree(line);
                        } catch (IOException ignored) {
                            continue;
                        }
                        records.add(record);
                        if ("peer.message_request".equals(record.path("type").asText())) {
                            messageRequests.add(record.path("body"));
                        }
                        recordQueue.add(record);
                    }
                } catch (IOException ignored) {
                    // the stream closes with the process
                }
            }, "opencode-stderr");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (ProcessHandle descendant : descendants) {
                descendant.destroyForcibly();
            }
            process.destroyForcibly();
            try {
                process.waitFor(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (session != null) {
                session.close();
            }
            try {
                deleteTree(fixture.root());
            } catch (IOException ignored) {
                // best effort: a fixture writer may still hold a handle for a moment
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode payload(CoreEvent event) {
        try {
            return JSON.readTree(event.payloadJson());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode firstFrameOfType(List<CoreEvent> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .map(CoreAdapterContractTest::payload)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + type + "' event arrived; saw: "
                        + events.stream().map(CoreEvent::type).toList()));
    }

    private static Throwable causeOf(CompletionStage<CoreResult> stage) throws InterruptedException {
        try {
            ((java.util.concurrent.CompletableFuture<CoreResult>) stage).get(5, TimeUnit.SECONDS);
            throw new AssertionError("the stage was expected to fail");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the stage did not fail within 5s", e);
        }
    }

    private static JsonNode recordedEditOptions() {
        return JSON.valueToTree(List.of(
                Map.of("optionId", "proceed_always", "name", "Allow for this session", "kind", "allow_always"),
                Map.of("optionId", "proceed_once", "name", "Allow", "kind", "allow_once"),
                Map.of("optionId", "cancel", "name", "Reject", "kind", "reject_once")));
    }

    private static JsonNode recordedExecuteOptions() {
        return JSON.valueToTree(List.of(
                Map.of("optionId", "proceed_always_and_save", "name", "Always allow \"printf\"",
                        "kind", "allow_always"),
                Map.of("optionId", "proceed_once", "name", "Allow", "kind", "allow_once"),
                Map.of("optionId", "cancel", "name", "Reject", "kind", "reject_once")));
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Pinned Node executable as an absolute path (the bridge requires one for --cli). */
    private static String nodeExecutable() {
        String configured = System.getenv("NODE_BIN");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().toString();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) {
                    continue;
                }
                for (String name : List.of("node.exe", "node")) {
                    Path candidate = Path.of(directory).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toAbsolutePath().toString();
                    }
                }
            }
        }
        throw new IllegalStateException("no Node executable was found on PATH (set NODE_BIN to pin one)");
    }

    /** The committed bridge entry (git-ignored build output; the lane builds it first). */
    private static Path bridgeEntry() {
        String configured = System.getProperty("core.bridge.entry");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        Path found = resolveBelowUserDir(Path.of("packages", "qoder-acp-bridge", "dist", "main.js"),
                "core.bridge.entry");
        if (!Files.isRegularFile(found)) {
            throw new IllegalStateException("the committed bridge entry is not built: " + found
                    + " (run `pnpm build` in packages/qoder-acp-bridge)");
        }
        return found;
    }

    private static Path qoderPeerScript() {
        return resolvePeerScript("mock-qoder.mjs", "core.qoder.peer.script");
    }

    private static Path openCodePeerScript() {
        return resolvePeerScript("mock-opencode.mjs", "core.opencode.peer.script");
    }

    private static Path resolvePeerScript(String name, String property) {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("the committed peer " + name + " is missing at " + path);
            }
            return path;
        }
        Path found = resolveBelowUserDir(Path.of("agent-control-tower", "act-app", "src", "test",
                "resources", "e2e", "peers", name), property);
        if (!Files.isRegularFile(found)) {
            throw new IllegalStateException("the committed peer " + name + " is missing at " + found);
        }
        return found;
    }

    /** Walk up from {@code user.dir} (the module directory in a Maven lane) to the repository root. */
    private static Path resolveBelowUserDir(Path relative, String property) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && directory != null; depth++) {
            Path candidate = directory.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return candidate.toRealPath();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("nothing found below " + System.getProperty("user.dir")
                + " for " + relative + " (set -D" + property + "=<path>)");
    }
}
