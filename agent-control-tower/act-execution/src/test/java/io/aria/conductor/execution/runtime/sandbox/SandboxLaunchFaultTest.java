package io.aria.conductor.execution.runtime.sandbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionHandlers;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import io.aria.conductor.common.model.RunErrorText;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.LaunchRefusal;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.core.OpenCodeCoreAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * S-1 and S-2 at the seam where they actually happened: the production
 * {@link SandboxLifecycle} and {@link SandboxExecutionBackend} over a scripted SDK that
 * delivers the fixed image launcher's output the way the OpenSandbox SDK does, and —
 * for the readiness half — the production {@link OpenCodeCoreAdapter} against an endpoint
 * that never answers.
 *
 * <p>The scripts are the two real container outcomes the Lead measured on
 * {@code opencode-sandbox:1.1}: a launcher that refused before its child existed, and a
 * launcher that spawned and then faulted. Which of those two a run was is exactly the
 * knowledge the launch path used to throw away, and the whole design of these tests is
 * that the destructive decision may only ever be taken on the first one.
 */
class SandboxLaunchFaultTest {

    private static final UUID RUN_ID = UUID.fromString("0e75d7f4-0000-0000-0000-000000000004");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final int CORE_PORT = 4096;
    private static final String IMAGE = "opencode-sandbox:1.1";
    private static final List<String> SERVE_ARGV =
            List.of("opencode", "serve", "--hostname", "0.0.0.0", "--port", String.valueOf(CORE_PORT));

    /** The refusal the Lead reproduced by hand inside the leaked container (launch.mjs:331-338). */
    private static final String PRE_SPAWN_REFUSAL =
            "{\"event\":\"supervisor.fault\",\"name\":\"LaunchFault\",\"message\":"
                    + "\"Entrypoint name is not allowlisted by this image: "
                    + "C:\\\\Users\\\\operator\\\\opencode.exe\"}\n";
    /** launch.mjs:301: the record write that can only fail once the child already exists. */
    private static final String POST_SPAWN_SPAWN_LINE =
            "{\"event\":\"supervisor.started\",\"runId\":\"0e75d7f4\",\"childPid\":41}\n";
    private static final String POST_SPAWN_REFUSAL =
            "{\"event\":\"supervisor.fault\",\"name\":\"Error\",\"message\":"
                    + "\"EACCES: permission denied, rename 'launch-record.json.tmp'\"}\n";

    @TempDir
    Path temp;

    // ---------------------------------------------------------------- S-1: the verdict

    /**
     * The image refused the launch and said why; the operator must see that reason instead
     * of the readiness window running out. The wait is the production one
     * ({@code OpenCodeCoreAdapter.open} polling {@code GET /global/health} of an endpoint
     * that never answers), bounded here by the run's own deadline.
     */
    @Test
    void aRefusedLaunchFailsWithTheLaunchersOwnReasonInsteadOfTheReadinessTimeout() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            assertThatThrownBy(fixture::launchThenOpen)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Entrypoint name is not allowlisted by this image")
                    .hasMessageContaining("supervisor.fault");

            assertThat(Duration.between(core.started(), Instant.now()))
                    .as("the readiness window must not be burned on a launch the image already refused")
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(sdk.operations).doesNotContain("stop-writers");
        }
    }

    /**
     * The launcher's verdict is its own words, event name included, so the operator can act
     * on the reason rather than on a symptom of it.
     */
    @Test
    void theRefusalCarriesTheEventNameAndMessageTheLauncherPrinted() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            assertThatThrownBy(fixture::launchOnly)
                    .isInstanceOf(SandboxLaunchRejectedException.class)
                    .hasMessageContaining("supervisor.fault")
                    .hasMessageContaining("LaunchFault")
                    .hasMessageContaining("Entrypoint name is not allowlisted by this image: "
                            + "C:\\Users\\operator\\opencode.exe");
        }
    }

    /**
     * The other half of the same knowledge: a launch that reached its child is not a
     * refusal, and the launch path must not invent one — that is the difference between
     * destroying and not destroying a live core.
     */
    @Test
    void aLauncherThatReportedASpawnIsNotRefused() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.POST_SPAWN_SPAWN_ONLY);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            RuntimeHandle handle = fixture.launchOnly();

            assertThat(handle).isNotNull();
            assertThat(sdk.operations).contains("launch");
        }
    }

    /** A capture that broke is an unknown outcome: today's behaviour, which is the safe one. */
    @Test
    void aBrokenLauncherCaptureIsSilenceNotARefusal() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.BROKEN_CAPTURE);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            assertThat(fixture.launchOnly()).isNotNull();
            assertThat(sdk.operations).contains("launch");
        }
    }

    /** A launcher that says nothing at all stays exactly as uninformative as it is today. */
    @Test
    void aSilentLauncherDoesNotRefuseTheLaunch() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.SILENT);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            assertThat(fixture.launchOnly()).isNotNull();
            assertThat(sdk.operations).contains("launch");
        }
    }

    /**
     * The difference between a fix and a 5 s tax on every run, measured at the launch itself
     * with the <em>production</em> verdict window: a healthy image spawns its child and
     * reports it (launch.mjs:289 then :302), and that report — delivered while the launcher
     * goes on supervising the child, and without the newline this SDK build happens to frame
     * it with — ends the wait. A launch that reaches its child must pay nothing for the
     * observation the refused launch needs.
     */
    @Test
    void aSuccessfulLaunchDoesNotPayTheVerdictWindow() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.POST_SPAWN_SPAWN_UNTERMINATED);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core, Duration.ofSeconds(5));

            long started = System.nanoTime();
            RuntimeHandle handle = fixture.launchOnly();

            assertThat(handle).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("the wait ends on supervisor.started, not on the 5 s window")
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    // ---------------------------------------------------------------- S-2: the cleanup

    /**
     * A launch the image refused before spawning has no writer to stop: {@code stop-writers}
     * cannot find a launch record because the launcher never reached the spawn that writes
     * one (launch.mjs:289 then :301), so asking it is a guarantee of a refusal — and that
     * refusal is what leaked the sandbox. The proven verdict answers it truthfully.
     *
     * <p>The refusal here arrives <em>after</em> the launch's own verdict window, which is
     * the shape of the run the Lead reproduced: the handle exists, the run is controllable,
     * and only the launcher's late message says that nothing was ever spawned.
     */
    @Test
    void stopWritersOfAProvenNeverSpawnedLaunchIsVerifiedWithoutAskingTheScript() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.LATE_PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();
            sdk.refuseLate();

            StopProof proof = fixture.lifecycle.stopWriters(RUN_ID, Instant.now().plusSeconds(30));

            assertThat(proof).isEqualTo(new StopProof(RUN_ID, true));
            assertThat(sdk.operations).doesNotContain("stop-writers");
        }
    }

    /**
     * The boundary's negative: a launcher that reported a spawn still goes through
     * {@code stop-writers}, and an unverified report from it is still not a stop proof.
     * A live core is never destroyed on the strength of a script that was not asked.
     */
    @Test
    void stopWritersStillAsksTheScriptWhenTheLauncherReportedASpawn() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.POST_SPAWN_REFUSAL);
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false,\"remaining\":[41]}\n";
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();

            StopProof proof = fixture.lifecycle.stopWriters(RUN_ID, Instant.now().plusSeconds(30));

            assertThat(sdk.operations).contains("stop-writers");
            assertThat(proof.allWritersStopped()).isFalse();
        }
    }

    /** The second negative: an unreadable launcher outcome keeps today's refusal behaviour. */
    @Test
    void stopWritersStillAsksTheScriptWhenTheLauncherOutcomeIsUnreadable() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.BROKEN_CAPTURE);
        sdk.stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":false}\n";
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();

            StopProof proof = fixture.lifecycle.stopWriters(RUN_ID, Instant.now().plusSeconds(30));

            assertThat(sdk.operations).contains("stop-writers");
            assertThat(proof.allWritersStopped()).isFalse();
        }
    }

    /**
     * The never-spawned proof is a stop answer for cleanup, not an export licence: the
     * export gate stays exactly as strict as it is today.
     */
    @Test
    void theNeverSpawnedProofDoesNotArmTheExportGate() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.LATE_PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();
            sdk.refuseLate();
            StopProof proof = fixture.lifecycle.stopWriters(RUN_ID, Instant.now().plusSeconds(30));
            Path destination = Files.createDirectories(temp.resolve("results"));

            assertThatThrownBy(() -> fixture.lifecycle.export(RUN_ID, destination, proof))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No current verified stop for run " + RUN_ID);

            assertThat(sdk.operations).doesNotContain("export");
        }
    }

    /**
     * A refused launch destroys the environment where it is detected: the sandbox the Lead
     * found still {@code Up} nine minutes after its run ended FAILED is the leak this is for.
     */
    @Test
    void aRefusedLaunchDestroysTheEnvironmentInsteadOfLeakingIt() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);

            assertThatThrownBy(fixture::launchOnly).isInstanceOf(SandboxLaunchRejectedException.class);

            assertThat(sdk.operations).contains("kill");
            assertThat(sdk.operations).doesNotContain("stop-writers");
            assertThat(sdk.killedSandboxIds).containsExactly(sdk.createdSandboxId);
        }
    }

    // ------------------------------------------- the production SDK adapter itself

    /**
     * The link the {@code SandboxSdk} seam cannot make: {@link SandboxLifecycle.OpenSandboxSdk}
     * is what actually hands the launcher's output to the observation, and the whole design
     * rests on it. Over a real {@code RunCommandRequest}, this asserts that the request carries
     * handlers, that what execd reports through them reaches the record, and that the fixed
     * launcher command is issued unchanged — the one thing here that is not a scripted seam.
     */
    @Test
    void theProductionSdkAdapterFeedsTheObservationFromTheCommandOutput() {
        SandboxLifecycle.OpenSandboxSdk sdk = new SandboxLifecycle.OpenSandboxSdk("http://127.0.0.1:8080", null);
        LaunchObservation observation = new LaunchObservation();
        List<String> commands = new CopyOnWriteArrayList<>();
        Sandbox sandbox = trackedSandbox(sdk, streamingLauncherAnswer(PRE_SPAWN_REFUSAL, 78, commands));

        sdk.launch(sandboxId(sandbox), "/home/aria/run/0e75d7f4", Duration.ofSeconds(30), observation);

        assertThat(observation.awaitVerdict(Duration.ofSeconds(5))).as("the launcher's verdict arrives").isTrue();
        assertThat(waitUntil(observation::outcomeKnown, Duration.ofSeconds(5)))
                .as("the command's own outcome arrives when run() returns").isTrue();
        assertThat(commands).singleElement()
                .isEqualTo("node /opt/aria/launch.mjs --manifest /home/aria/run/0e75d7f4/launch-manifest.json");
        assertThat(observation.neverSpawnedProven()).isTrue();
    }

    /**
     * The same adapter's failure branch: when the SDK call itself breaks, the launcher's outcome
     * is unknown, and unknown is never "no writers exist". The comment in the production adapter
     * claims this; a mocked answer that throws is what pins it.
     */
    @Test
    void theProductionSdkAdapterReportsABrokenCallAsUnknownNotAsAbsence() {
        SandboxLifecycle.OpenSandboxSdk sdk = new SandboxLifecycle.OpenSandboxSdk("http://127.0.0.1:8080", null);
        LaunchObservation observation = new LaunchObservation();
        Sandbox sandbox = trackedSandbox(sdk, invocation -> {
            throw new IllegalStateException("execd stream lost");
        });

        sdk.launch(sandboxId(sandbox), "/home/aria/run/0e75d7f4", Duration.ofSeconds(30), observation);

        assertThat(observation.awaitVerdict(Duration.ofSeconds(5)))
                .as("a broken capture is itself a verdict to stop waiting for").isTrue();
        assertThat(observation.faultReported()).isFalse();
        assertThat(observation.outcomeKnown()).isFalse();
        assertThat(observation.neverSpawnedProven()).isFalse();
    }

    // ------------------------------------------- S-1's other half: the readiness wait

    /**
     * The run the Lead reproduced on a real container: the launcher refused before spawning
     * and said why, but only <em>after</em> the launch's own verdict window had already
     * closed over a silent stream — so the launch returned a handle and the readiness wait
     * took over. The wait is where the verdict has to be consulted, and on the code the Lead
     * measured it never was: the run waited its whole window and failed as
     * <em>"opencode serve did not become ready within 90000ms"</em> while the image's own
     * sentence was already in the observation.
     *
     * <p>The message thrown here is also what the run persists: the engine records the
     * failure's text bounded by {@link RunErrorText#bound(String)}, so an operator reading
     * {@code runs.error_message} reads the reason they can act on.
     */
    @Test
    void theReadinessWaitStopsOnARefusalThatLandsAfterTheLaunchVerdictWindow() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.LATE_PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            RuntimeHandle handle = fixture.launchOnly();
            assertThat(fixture.placementRefusal().reason())
                    .as("while the launcher is silent nothing is proven, and the wait keeps waiting")
                    .isNull();

            sdk.refuseLate();

            Instant started = Instant.now();
            TaskExecutionException failure = catchThrowableOfType(
                    () -> fixture.openWithPlacementRefusal(handle), TaskExecutionException.class);

            assertThat(failure).as("the open fails on the launcher's verdict, not on its own timeout")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .contains("Entrypoint name is not allowlisted by this image: "
                            + "C:\\Users\\operator\\opencode.exe")
                    .contains("supervisor.fault")
                    .contains("LaunchFault")
                    .doesNotContain("did not become ready");
            assertThat(Duration.between(started, Instant.now()))
                    .as("the readiness window must not be burned on a launch the image already refused")
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(RunErrorText.bound(failure.getMessage()))
                    .as("the persisted run error carries the image's own sentence")
                    .contains("Entrypoint name is not allowlisted by this image");
        }
    }

    /**
     * The measurement the next person needs and the parent change could not give them: one
     * INFO line for the moment the refusal first lands in the observation, naming the run and
     * the time since the launch started. It is written once per <em>launch attempt</em> — the
     * arrival is a fact about the transport, not about how many consumers read it — and a
     * second attempt of the same run gets its own.
     */
    @Test
    void theArrivalOfAProvenRefusalIsLoggedOncePerLaunchAttempt() throws IOException {
        ListAppender<ILoggingEvent> logs = captureLifecycleLogs();
        ScriptedSdk sdk = new ScriptedSdk(Script.LATE_PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();

            sdk.refuseLate();
            String first = fixture.lifecycle.launchRefusalReason(RUN_ID);
            String second = fixture.lifecycle.launchRefusalReason(RUN_ID);
            String third = fixture.placementRefusal().reason();

            assertThat(first).as("the refusal is the launcher's own verdict").isEqualTo(second).isEqualTo(third);
            assertThat(arrivalLines(logs))
                    .as("one arrival line per launch attempt, however many consumers read the verdict")
                    .hasSize(1);
            assertThat(arrivalLines(logs).get(0))
                    .contains("Launcher refusal for run " + RUN_ID)
                    .contains("ms after its launch started")
                    .contains("Entrypoint name is not allowlisted by this image");

            fixture.lifecycle.launch(RUN_ID);
            assertThat(fixture.lifecycle.launchRefusalReason(RUN_ID))
                    .as("the fresh attempt has its own observation, and it has said nothing yet")
                    .isNull();
            sdk.refuseLate();
            assertThat(fixture.lifecycle.launchRefusalReason(RUN_ID))
                    .as("the new attempt's own verdict, read again")
                    .isNotNull();
            assertThat(arrivalLines(logs))
                    .as("the second launch attempt gets its own arrival line")
                    .hasSize(2);
        } finally {
            ((Logger) LoggerFactory.getLogger(SandboxLifecycle.class)).detachAppender(logs);
        }
    }

    /**
     * The fail-closed rule of the parent change, restated at the seam the readiness wait
     * consumes: a launcher that said nothing, a capture that broke, and a launcher that
     * reported its spawn and only then faulted all answer {@code null} — nothing proven — so
     * the wait runs its budget, today's error stands and the verified-stop rules decide the
     * cleanup exactly as they did before. A slow-but-live core keeps its chance to come up.
     */
    @Test
    void anUnprovenVerdictIsNeverARefusalTheWaitMayStopOn() throws IOException {
        for (Script script : List.of(Script.SILENT, Script.BROKEN_CAPTURE, Script.POST_SPAWN_REFUSAL)) {
            ScriptedSdk sdk = new ScriptedSdk(script);
            try (NeverReadyCore core = new NeverReadyCore()) {
                core.wire(sdk);
                Fixture fixture = new Fixture(sdk, core);
                fixture.launchOnly();

                assertThat(fixture.lifecycle.launchRefusalReason(RUN_ID))
                        .as("%s proves nothing about whether a writer exists", script)
                        .isNull();
            }
        }
    }

    /**
     * The boundary the fix must never cross: a spawn report that arrives late, followed by
     * the fault on the record write (launch.mjs:301), describes a live child. Nothing may be
     * concluded from it, so the refusal source stays silent and the readiness wait runs.
     */
    @Test
    void aLateSpawnReportFollowedByAFaultIsNotARefusal() throws IOException {
        ScriptedSdk sdk = new ScriptedSdk(Script.LATE_PRE_SPAWN_REFUSAL);
        try (NeverReadyCore core = new NeverReadyCore()) {
            core.wire(sdk);
            Fixture fixture = new Fixture(sdk, core);
            fixture.launchOnly();

            sdk.spawnThenRefuseLate();

            assertThat(fixture.lifecycle.launchRefusalReason(RUN_ID))
                    .as("a launcher that reported its spawn never refuses a launch that never spawned")
                    .isNull();
        }
    }

    // ------------------------------------------------------------------ fixture

    /** The lifecycle and backend under test, over a scripted SDK and a never-ready endpoint. */
    private final class Fixture {
        private final SandboxLifecycle lifecycle;
        private final SandboxExecutionBackend backend;
        private final OpenCodeCoreAdapter adapter;
        private final PreparedEnvironment environment;

        Fixture(ScriptedSdk sdk, NeverReadyCore core) throws IOException {
            this(sdk, core, Duration.ofMillis(150));
        }

        Fixture(ScriptedSdk sdk, NeverReadyCore core, Duration verdictWindow) throws IOException {
            lifecycle = new SandboxLifecycle(sdk);
            // A silent launcher must not make every unrelated launch pay a window: the
            // production default is bounded and the tests set their own. One test keeps the
            // production 5 s default precisely to measure what a healthy launch pays.
            lifecycle.setLaunchVerdictWindow(verdictWindow);
            backend = new SandboxExecutionBackend(lifecycle,
                    coreId -> coreId.equals("opencode")
                            ? new SandboxExecutionBackend.SandboxProfile(IMAGE, CORE_PORT) : null);
            adapter = adapter(core);
            WorkspaceLease lease = lease();
            environment = backend.prepare(spec(), lease);
        }

        RuntimeHandle launchOnly() {
            return backend.launch(environment, new LaunchProfile(SERVE_ARGV, Map.of(), "/workspace"));
        }

        void launchThenOpen() {
            RuntimeHandle handle = backend.launch(environment,
                    new LaunchProfile(SERVE_ARGV, Map.of(), "/workspace"));
            adapter.open(handle, spec(), new SecretBundle(null, Map.of()));
        }

        /** The session open as the coordinator performs it: with the placement's refusal source. */
        void openWithPlacementRefusal(RuntimeHandle handle) {
            adapter.open(handle, spec(), new SecretBundle(null, Map.of()), backend.launchRefusal(RUN_ID));
        }

        /** The refusal source the placement hands the core's readiness wait. */
        LaunchRefusal placementRefusal() {
            return backend.launchRefusal(RUN_ID);
        }

        private OpenCodeCoreAdapter adapter(NeverReadyCore core) {
            RunMcpWiring runMcp = mock(RunMcpWiring.class);
            when(runMcp.forRun(any(ExecutionSpec.class), any(PreparedEnvironment.class)))
                    .thenReturn(java.util.Optional.empty());
            return new OpenCodeCoreAdapter(new OpenCodeCoreAdapter.OpenCodeProfile(
                    "opencode", List.of(), Map.of(), "1.18.15", "efficient"), runMcp);
        }

        private WorkspaceLease lease() throws IOException {
            Path localRoot = Files.createDirectories(temp.resolve("workspace"));
            Files.writeString(localRoot.resolve("notes.md"), "snapshot bytes\n");
            Path runtimeRoot = Files.createDirectories(temp.resolve("runtime"));
            return new WorkspaceLease(UUID.randomUUID(), RUN_ID, WorkspaceKind.SANDBOX_SNAPSHOT,
                    localRoot, null, runtimeRoot.toString(), null);
        }

        private ExecutionSpec spec() {
            // The production 90 s window, bounded here by the run's own deadline: the point
            // of the fix is that a refused launch never reaches that wait at all.
            return new ExecutionSpec(RUN_ID, AGENT_ID, "opencode", ExecutionMode.SANDBOX,
                    new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, WorkspaceMode.DIRECT,
                            null, null), null, null, Instant.now().plusSeconds(6));
        }
    }

    /** The scripted launcher outcomes, delivered through the SDK seam the production code feeds. */
    private enum Script {
        /** Refused before the spawn: the fault line on stderr, the command exits 78. */
        PRE_SPAWN_REFUSAL,
        /** Said nothing while the launch was waiting, and refuses afterwards. */
        LATE_PRE_SPAWN_REFUSAL,
        /** Spawned and reported it (launch.mjs:302), then the command keeps supervising. */
        POST_SPAWN_SPAWN_ONLY,
        /** The same report as one execd chunk that carries no newline of its own. */
        POST_SPAWN_SPAWN_UNTERMINATED,
        /** Spawned, then faulted on the record write (launch.mjs:301) — a live child. */
        POST_SPAWN_REFUSAL,
        /** The command never reported anything. */
        SILENT,
        /** The SDK call itself broke: the outcome is unknown, not absent. */
        BROKEN_CAPTURE
    }

    /**
     * The SDK seam as {@link SandboxLifecycle.OpenSandboxSdk} uses it: the launcher command
     * runs on its own thread, its output is fed to the observation as it arrives, and only
     * the completion (or its failure) is scripted here.
     */
    private static final class ScriptedSdk implements SandboxLifecycle.SandboxSdk {

        final List<String> operations = new CopyOnWriteArrayList<>();
        final List<String> killedSandboxIds = new CopyOnWriteArrayList<>();
        final Script script;
        volatile String stopWritersOutput = "{\"action\":\"stop\",\"writersStopped\":true,\"remaining\":[]}\n";
        volatile String createdSandboxId;
        volatile String endpoint = "127.0.0.1:1";
        volatile LaunchObservation lastObservation;

        ScriptedSdk(Script script) {
            this.script = script;
        }

        @Override
        public String create(String image, Map<String, String> env) {
            operations.add("create");
            createdSandboxId = "sandbox-" + RUN_ID;
            return createdSandboxId;
        }

        @Override
        public void upload(String sandboxId, List<WriteEntry> entries) {
            operations.add("upload");
        }

        @Override
        public String endpoint(String sandboxId, int port) {
            return endpoint;
        }

        @Override
        public void launch(String sandboxId, String runDirectory, Duration timeout, LaunchObservation observation) {
            operations.add("launch");
            lastObservation = observation;
            switch (script) {
                case PRE_SPAWN_REFUSAL -> {
                    observation.recordStderr(PRE_SPAWN_REFUSAL);
                    observation.commandCompleted(78);
                }
                case LATE_PRE_SPAWN_REFUSAL -> {
                    // Silent while the launch waits for a verdict; refuse afterwards,
                    // the way a launcher whose command output arrives late does.
                }
                case POST_SPAWN_SPAWN_ONLY -> observation.recordStdout(POST_SPAWN_SPAWN_LINE);
                case POST_SPAWN_SPAWN_UNTERMINATED ->
                        observation.recordStdout(POST_SPAWN_SPAWN_LINE.stripTrailing());
                case POST_SPAWN_REFUSAL -> {
                    observation.recordStdout(POST_SPAWN_SPAWN_LINE);
                    observation.recordStderr(POST_SPAWN_REFUSAL);
                    observation.commandCompleted(70);
                }
                case BROKEN_CAPTURE -> observation.commandAborted();
                case SILENT -> {
                    // The launcher says nothing: the command is still running its child.
                }
            }
        }

        /** Delivers the refusal the launch's own verdict window had not seen yet. */
        void refuseLate() {
            LaunchObservation observation = lastObservation;
            assertThat(observation).as("the lifecycle handed the SDK an observation").isNotNull();
            observation.recordStderr(PRE_SPAWN_REFUSAL);
            observation.commandCompleted(78);
        }

        /** The late pair that must never read as a refusal: the spawn report, then its fault. */
        void spawnThenRefuseLate() {
            LaunchObservation observation = lastObservation;
            assertThat(observation).as("the lifecycle handed the SDK an observation").isNotNull();
            observation.recordStdout(POST_SPAWN_SPAWN_LINE);
            observation.recordStderr(POST_SPAWN_REFUSAL);
            observation.commandCompleted(70);
        }

        @Override
        public String pauseWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("pause-writers");
            return "{\"action\":\"suspend\",\"suspended\":true}\n";
        }

        @Override
        public String resumeWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("resume-writers");
            return "{\"action\":\"resume\",\"resumed\":true}\n";
        }

        @Override
        public String stopWriters(String sandboxId, String runDirectory, Duration timeout) {
            operations.add("stop-writers");
            return stopWritersOutput;
        }

        @Override
        public void renew(String sandboxId, Duration extension) {
            operations.add("renew");
        }

        @Override
        public List<SandboxLifecycle.SandboxSdk.RemoteFile> export(String sandboxId, String remoteRoot) {
            operations.add("export");
            return new ArrayList<>();
        }

        @Override
        public void kill(String sandboxId) {
            operations.add("kill");
            killedSandboxIds.add(sandboxId);
        }
    }

    /** A core endpoint that answers every health probe with the execd proxy's 502. */
    private static final class NeverReadyCore implements AutoCloseable {

        private final HttpServer server;
        private final Instant started = Instant.now();

        NeverReadyCore() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/global/health", exchange -> {
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
            });
            server.createContext("/session", exchange -> {
                byte[] body = "{\"id\":\"s1\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        Instant started() {
            return started;
        }

        /** The core's address, as the SDK's endpoint lookup would report it. */
        void wire(ScriptedSdk sdk) {
            sdk.endpoint = authority();
        }

        String authority() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ------------------------------------------- production-adapter helpers

    /** The id {@link SandboxLifecycle.OpenSandboxSdk} files the sandbox under. */
    private static final String PROD_SANDBOX_ID = "sandbox-6fd4dcfa";

    /**
     * A sandbox created through the <em>production</em> adapter, so {@code launch} finds it in
     * the adapter's own id map and drives the SDK's {@code commands()} — the path the scripted
     * seam replaces.
     */
    private static Sandbox trackedSandbox(SandboxLifecycle.OpenSandboxSdk sdk,
            org.mockito.stubbing.Answer<Execution> answer) {
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any(Duration.class))).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            Sandbox sandbox = mock(Sandbox.class);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn(PROD_SANDBOX_ID);
            Commands commands = mock(Commands.class);
            when(sandbox.commands()).thenReturn(commands);
            when(commands.run(any(RunCommandRequest.class))).thenAnswer(answer);
            sdk.create(IMAGE, Map.of());
            return sandbox;
        }
    }

    private static String sandboxId(Sandbox sandbox) {
        return sandbox.getId();
    }

    /**
     * What {@code CommandsAdapter.run} really does with a streaming request: it invokes the
     * request's own handlers as execd reports each chunk, while the command still runs, and
     * only returns the execution — with its exit code — when the stream ends. The event is
     * delivered without a newline because the SDK neither adds nor strips one.
     */
    private static org.mockito.stubbing.Answer<Execution> streamingLauncherAnswer(String event, Integer exitCode,
            List<String> commands) {
        return invocation -> {
            RunCommandRequest request = invocation.getArgument(0);
            commands.add(request.getCommand());
            ExecutionHandlers handlers = request.getHandlers();
            if (handlers == null || handlers.getOnStderr() == null) {
                throw new IllegalStateException("the launch request carries no handlers to feed the record");
            }
            handlers.getOnStderr().handle(new OutputMessage(event.stripTrailing(), 0L, true));
            Execution exec = mock(Execution.class);
            when(exec.getExitCode()).thenReturn(exitCode);
            return exec;
        };
    }

    /** Bounded join of the launcher's background virtual thread. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition, Duration window) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** Captures what {@link SandboxLifecycle} logs for the duration of one test. */
    private static ListAppender<ILoggingEvent> captureLifecycleLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SandboxLifecycle.class)).addAppender(appender);
        return appender;
    }

    /** The INFO lines reporting that a launcher's proven refusal landed in an observation. */
    private static List<String> arrivalLines(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(text -> text.startsWith("Launcher refusal for run "))
                .toList();
    }
}
