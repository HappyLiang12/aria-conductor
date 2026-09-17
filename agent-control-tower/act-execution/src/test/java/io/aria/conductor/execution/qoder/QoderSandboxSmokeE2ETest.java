package io.aria.conductor.execution.qoder;

import io.aria.conductor.common.event.RunProgressEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.qoder.QoderAdkProvider;
import io.aria.conductor.execution.adk.qoder.QoderProperties;
import io.aria.conductor.execution.credential.RuntimeCredentialService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B6 real-sandbox smoke: drives {@link QoderAdkProvider#executeTask} end to end against a
 * real OpenSandbox sandbox built from the pinned {@code qoder-sandbox} image — the provider
 * creates the sandbox, starts the bridge (verify-then-start), creates a bridge session on the
 * zero-credit {@code efficient} model and runs the prompt {@code Reply with exactly: ok}.
 *
 * <p>Assertions (the B6 acceptance criteria):
 * <ul>
 *   <li>the run completes with an explicit terminal event and the {@link TaskResult} output
 *       is exactly {@code ok} (whitespace-trimmed; the raw text is printed and written to
 *       {@code target/qoder-b6-smoke-output.txt} as evidence);</li>
 *   <li>the bridge reported the pinned model: a {@code qoder.model=<model>} STATUS progress
 *       event (the bridge's {@code session_started.model}) with {@code model == efficient};</li>
 *   <li>progress events carry the bridge's monotonic sequence as their {@code seq}.</li>
 * </ul>
 *
 * <p>Zero-credit gate (plan Global Constraint): the only accessible enforcement is
 * {@link QoderSandboxHarness#boot}, which fails closed on a non-zero-credit pin — the smoke
 * boots it once as a gate, asserts the validated model is {@code efficient} and kills it
 * again before the provider creates its own sandbox (the provider has no knowledge of the
 * harness; the gate keeps the smoke honest for local runs).
 *
 * <p>Credential handling: the PAT is read from the host environment variable
 * {@code QODER_E2E_PAT} and travels only through {@link RuntimeCredentialService} into the
 * sandbox container environment as {@code QODER_PERSONAL_ACCESS_TOKEN} — never argv, never a
 * file, never a log. The credential store is mocked here because this smoke exercises the
 * sandbox/bridge/CLI path, not the encrypted-at-rest store (B7 covers that separately).
 *
 * <p>Gated by {@code -Dqoder.e2e.enabled=true} so the default unit/integration lanes skip it
 * (the local OpenSandbox server, the built image and the local PAT are prerequisites):
 * <pre>
 * cd agent-control-tower
 * export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)"   # local only, never echoed
 * mvn verify -pl act-execution -Dspring.profiles.active=h2 -Dqoder.e2e.enabled=true \
 *     -Dit.test=QoderSandboxSmokeE2ETest
 * </pre>
 * {@code *E2ETest} classes run under Failsafe ({@code mvn verify}) — plain {@code mvn test}
 * runs none of them.
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderSandboxSmokeE2ETest {

    /** Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile} (Task A1/B4). */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    /** Host-side credential variable holding the Qoder PAT (never an argv entry). */
    private static final String HOST_CREDENTIAL_ENV = "QODER_E2E_PAT";

    /** The B6 smoke prompt (the answer must be exactly the token). */
    private static final String PROMPT = "Reply with exactly: ok";

    /** The zero-credit model this smoke must run on (plan Global Constraint). */
    private static final String EXPECTED_MODEL = "efficient";

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void efficientModel_repliesOk_throughTheProvider() throws Exception {
        String pat = System.getenv(HOST_CREDENTIAL_ENV);
        Assumptions.assumeTrue(pat != null && !pat.isBlank(),
                HOST_CREDENTIAL_ENV + " is not set: this real-sandbox smoke needs the Qoder PAT "
                        + "in the host environment (no PAT in CI)");

        // Zero-credit gate: the harness validates QODER_E2E_MODEL fail-closed at boot; hold it
        // only long enough to learn the validated model, then kill the gate sandbox.
        String model;
        try (QoderSandboxHarness gate = QoderSandboxHarness.boot(SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE, Map.of())) {
            model = gate.e2eModel();
        }
        assertThat(model).as("B6 smoke must run on the zero-credit '%s' model", EXPECTED_MODEL)
                .isEqualTo(EXPECTED_MODEL);

        // The production credential contract, with the real PAT supplied by the host env.
        // Mocked deliberately: this smoke validates the sandbox/bridge/CLI path, not B7's store.
        RuntimeCredentialService credentials = mock(RuntimeCredentialService.class);
        when(credentials.read("qoder")).thenReturn(pat);

        List<RunProgressEvent> progress = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof RunProgressEvent progressEvent) {
                progress.add(progressEvent);
            }
        };

        QoderProperties properties = new QoderProperties();
        properties.setSandboxServerUrl(SANDBOX_SERVER_URL);
        properties.setImage(QODER_SANDBOX_IMAGE);
        properties.setPort(4097);
        properties.setModel(model);
        properties.setMaxTaskMinutes(5);

        QoderAdkProvider provider = new QoderAdkProvider(properties, credentials, publisher);
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder()
                .id(agentId)
                .name("qoder-b6-smoke")
                .role("tester")
                .description("B6 provider real-sandbox smoke")
                .build();
        try {
            TaskResult result = provider.executeTask(agent, UUID.randomUUID(), PROMPT,
                    new TaskContext(1, Duration.ofMinutes(4)));

            RunProgressEvent modelEvent = progress.stream()
                    .filter(e -> e.getKind() == RunProgressEvent.Kind.STATUS)
                    .filter(e -> e.getContent().startsWith("qoder.model="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no qoder.model progress event published: " + describe(progress)));

            String evidence = renderEvidence(properties, result, modelEvent, progress);
            System.out.println("=== [B6] raw smoke evidence begin ===");
            System.out.println(evidence);
            System.out.println("=== [B6] raw smoke evidence end ===");
            Path evidenceFile = Path.of("target", "qoder-b6-smoke-output.txt");
            Files.createDirectories(evidenceFile.getParent());
            Files.writeString(evidenceFile, evidence, StandardCharsets.UTF_8);
            System.out.println("[B6] raw smoke evidence written to " + evidenceFile.toAbsolutePath());

            assertThat(result.aborted()).as("smoke run must not be reported as aborted").isFalse();
            assertThat(result.sessionId()).as("bridge session id").isNotBlank();
            assertThat(result.finalOutput().trim())
                    .as("the model must answer the prompt 'Reply with exactly: ok' with exactly 'ok'"
                            + " (raw output: %s)", quote(result.finalOutput()))
                    .isEqualTo("ok");
            // The bridge only reports the CLI's effective model through session_started; the
            // smoke asserts the pinned model reached the session.
            assertThat(modelEvent.getContent()).isEqualTo("qoder.model=" + EXPECTED_MODEL);
            assertThat(modelEvent.getSeq()).as("bridge event sequence").isPositive();
            assertThat(progress).extracting(RunProgressEvent::getSeq).isSorted();
        } catch (TaskExecutionException e) {
            // Surface the typed failure plus the progress trail (the provider logs the rest).
            throw new AssertionError("Qoder smoke run failed (" + e.cause() + "): " + e.getMessage()
                    + " — progress: " + describe(progress), e);
        } finally {
            provider.shutdownAll();
        }
    }

    private static String renderEvidence(QoderProperties properties, TaskResult result,
                                         RunProgressEvent modelEvent, List<RunProgressEvent> progress) {
        StringBuilder sb = new StringBuilder();
        sb.append("[B6] image=").append(properties.getImage())
                .append(" model=").append(properties.getModel())
                .append(" prompt=\"").append(PROMPT).append("\"\n");
        sb.append("[B6] TaskResult sessionId=").append(result.sessionId())
                .append(" inputTokens=").append(result.inputTokens())
                .append(" outputTokens=").append(result.outputTokens())
                .append(" aborted=").append(result.aborted()).append("\n");
        sb.append("[B6] session_started model event: content=\"").append(modelEvent.getContent())
                .append("\" seq=").append(modelEvent.getSeq()).append("\n");
        sb.append("[B6] raw finalOutput begin\n").append(result.finalOutput())
                .append("\n[B6] raw finalOutput end\n");
        sb.append("[B6] progress events (").append(progress.size()).append("):\n");
        for (RunProgressEvent event : progress) {
            sb.append("  seq=").append(event.getSeq())
                    .append(" kind=").append(event.getKind())
                    .append(" tool=").append(event.getToolName())
                    .append(" content=").append(quote(event.getContent())).append('\n');
        }
        return sb.toString();
    }

    private static String describe(List<RunProgressEvent> events) {
        return events.stream()
                .map(e -> e.getKind() + ":" + e.getSeq() + ":" + e.getContent())
                .toList()
                .toString();
    }

    private static String quote(String value) {
        return value == null ? "null" : '"' + value + '"';
    }
}
