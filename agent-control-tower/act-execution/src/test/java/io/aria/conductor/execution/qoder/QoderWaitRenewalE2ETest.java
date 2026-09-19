package io.aria.conductor.execution.qoder;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice A gate (Task A5, the final Slice A task): in a real OpenSandbox sandbox created
 * from the pinned qoder-sandbox image and driven by a real authenticated Qoder ACP session
 * (model pinned to {@code efficient}), pin the facts B6/C2 need for run deadlines and TTL
 * renewal:
 * <ol>
 *   <li><b>production renewal path</b>: {@code OpenCodeSandboxManager#renewSandbox(id, 30m)}
 *       is exercised through {@link QoderSandboxHarness#renew(Duration)} and the sandbox's
 *       {@code expiresAt} is read back with a real SDK round trip (a {@code Sandbox.connector()}
 *       handle + {@code getInfo().getExpiresAt()}; {@code Sandbox.resumer()} is attempted first
 *       but a server-side resume of a running sandbox is rejected with HTTP 409, so in practice
 *       every read attaches through {@code connector()}) before and after — the manager method
 *       returns {@code void} and only logs, so the server value is the observable;</li>
 *   <li><b>long wait + renewal interleaving</b>: the in-sandbox probe
 *       ({@code e2e/qoder/slice-a/05-wait-renewal.mjs}) holds a write permission request
 *       pending for 5 minutes; this test, while the blocking probe run is in flight on a
 *       virtual thread, renews the sandbox TTL in the middle of that window. The probe writes
 *       its state (sandbox clock, epoch ms) to {@code /tmp/a5/state} at PENDING and ANSWER
 *       and records the host renewal bracket written to {@code /tmp/a5/host-renewal}; this
 *       test asserts the bracket lies inside {@code [t_pending, t_answer]}. After the hold the
 *       request is answered with the offered {@code allow_once} option and the CLI must still
 *       execute (target file written, turn completed) — the evidence that a long approval wait
 *       survives across a renewal window;</li>
 *   <li><b>effective-model observability</b> (recorded, not asserted by this test): the probe
 *       scans every inbound ACP message for model / usage / credit fields and pins the exact
 *       event and field path that carries the effective model — recorded verbatim in the
 *       evidence file {@code e2e/qoder/slice-a/05-wait-renewal.md};</li>
 *   <li><b>deadline anchors</b> (recorded): the probe records the {@code session/prompt}
 *       request epoch and the prompt response epoch, from which a hard deadline is derived
 *       (start + maxDuration, closed by the response).</li>
 * </ol>
 *
 * <p>The probe logic lives in {@code e2e/qoder/slice-a/05-wait-renewal.mjs} (a Node ESM script
 * executed in-sandbox via {@code OpenCodeSandboxManager#runCommand}); this test boots the
 * sandbox, injects the Qoder PAT as the {@code QODER_PERSONAL_ACCESS_TOKEN} sandbox
 * environment variable (environment only, never argv — see
 * {@link QoderSandboxHarness#boot(String, String, Map)}), uploads the script, runs it and
 * fails unless the script reports PASS (markers {@code A5-WAIT-RENEWAL-RESULT: PASS/FAIL},
 * {@code A5-SUMMARY-JSON}, exit code 0).
 *
 * <p>Credential handling is A3/A4's: the PAT is read from the host environment variable
 * {@code QODER_E2E_PAT}, travels only into the sandbox container environment, is never an
 * argument of any process, never written to a file and never printed; when the variable is
 * absent the test is skipped with the credential named (no PAT in CI).
 *
 * <p>Gated by {@code -Dqoder.e2e.enabled=true} so the default unit/integration lanes skip it
 * (the local OpenSandbox server, the built image and the local PAT are prerequisites, not CI
 * services):
 * <pre>
 * cd agent-control-tower
 * export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)"   # local only, never echoed
 * mvn clean verify -pl act-execution -Dit.test=QoderWaitRenewalE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
 * </pre>
 * {@code clean} is required: {@code jacoco:check} is bound to the {@code test} phase and is
 * only skipped by {@code -Dskip.unit.tests=true}, so a stale
 * {@code act-execution/target/jacoco.exec} fails the build before Failsafe runs.
 *
 * <p>Evidence: {@code e2e/qoder/slice-a/05-wait-renewal.md} (raw output, both expiresAt
 * readings, the interleaving timestamps from both sides, the model-observability record and
 * the prompt deadline anchors).
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderWaitRenewalE2ETest {

    /** Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile} (Task A1). */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    /** Probe script, uploaded to {@code /workspace/05-wait-renewal.mjs}. */
    private static final String PROBE_SCRIPT = "e2e/qoder/slice-a/05-wait-renewal.mjs";

    /** Host-side credential variable holding the Qoder PAT (never an argv entry). */
    private static final String HOST_CREDENTIAL_ENV = "QODER_E2E_PAT";

    /** Sandbox/CLI-side variable the Qoder CLI reads the PAT from (verified in the pinned binary). */
    private static final String SANDBOX_TOKEN_ENV = "QODER_PERSONAL_ACCESS_TOKEN";

    /** Script result markers (see 05-wait-renewal.mjs). */
    private static final String PASS_MARKER = "A5-WAIT-RENEWAL-RESULT: PASS";
    private static final String FAIL_MARKER = "A5-WAIT-RENEWAL-RESULT: FAIL";

    /** Script JSON summary marker (evidence contract of the task brief). */
    private static final String SUMMARY_MARKER = "A5-SUMMARY-JSON:";

    /** TTL extension requested on both renewals (Step 1: the production path assertion). */
    private static final Duration RENEWAL_EXTENSION = Duration.ofMinutes(30);

    /** The probe's bounded pending window — asserted from the state file (task brief: 5 minutes). */
    private static final long HOLD_MS = Duration.ofMinutes(5).toMillis();

    /** Sandbox-side state file written by the probe (single-line key=value, sandbox clock). */
    private static final String STATE_FILE = "/tmp/a5/state";

    /** File the prompted Write tool must create after the hold (also checked by the probe itself). */
    private static final String PROBE_TARGET_FILE = "/tmp/a5/answer-under-renewal/written.txt";

    /** Exact content the prompt requires (kept in sync with 05-wait-renewal.mjs FILE_CONTENT). */
    private static final String PROBE_TARGET_CONTENT = "renewed";

    /** How long to wait for the probe to report PENDING after the run starts. */
    private static final Duration PENDING_WAIT = Duration.ofSeconds(150);

    /** How long to wait for the whole probe command (prompt + 5-min hold + turn + exit). */
    private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(12);

    /** How long to wait for a single state-file read to observe a phase token. */
    private static final Duration STATE_POLL_INTERVAL = Duration.ofSeconds(1);

    /** Connection config for the test's own SDK attach handles (connector() reads; mirrors the manager). */
    private static final ConnectionConfig SANDBOX_CONNECTION_CONFIG = ConnectionConfig.builder()
            .protocol("http")
            .domain("localhost:8090")
            .build();

    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\b(\\d{13})\\b");

    private static final int REPO_ROOT_SEARCH_DEPTH = 6;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void longPendingWaitSurvivesRenewalAndTheTurnCompletesAfterwards() throws Exception {
        String pat = System.getenv(HOST_CREDENTIAL_ENV);
        Assumptions.assumeTrue(pat != null && !pat.isBlank(),
                HOST_CREDENTIAL_ENV + " is not set: this authenticated Slice A gate needs the Qoder PAT "
                        + "in the host environment (credential-dependent cases are skipped without it; "
                        + "no PAT in CI)");

        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve(PROBE_SCRIPT);
        assertThat(script).as("A5 wait/renewal probe script %s", script).isRegularFile();

        Path staging = buildStagingDirectory(script);
        try (QoderSandboxHarness harness = QoderSandboxHarness.boot(
                SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE, Map.of(SANDBOX_TOKEN_ENV, pat))) {
            harness.upload(staging);
            String sandboxId = harness.sandboxId();
            System.out.println("[A5] sandbox " + sandboxId + " ready (model pin " + harness.e2eModel() + ")");

            // ---- Step 1: the production renewal path, with a real SDK read of the effect ----
            // OpenCodeSandboxManager#renewSandbox returns void and only logs, so the observable
            // is the server-side expiresAt, read back through an attached SDK handle
            // (resumer() is tried first and 409s on a running sandbox; connector() is the read path).
            OffsetDateTime expiresBefore = readExpiresAt(sandboxId);
            long hostRenew1Started = System.currentTimeMillis();
            harness.renew(RENEWAL_EXTENSION);
            long hostRenew1Finished = System.currentTimeMillis();
            OffsetDateTime expiresAfter = readExpiresAt(sandboxId);
            System.out.println("[A5] STEP1 renewal: extension=" + RENEWAL_EXTENSION
                    + " expiresBefore=" + expiresBefore + " expiresAfter=" + expiresAfter
                    + " expiresForward=" + forwardMillis(expiresBefore, expiresAfter) + "ms"
                    + " hostRenew=" + (hostRenew1Finished - hostRenew1Started) + "ms");
            assertThat(expiresBefore).as("sandbox %s must report an expiresAt before renewal", sandboxId).isNotNull();
            assertThat(expiresAfter).as("sandbox %s must report an expiresAt after renewal", sandboxId).isNotNull();
            assertThat(expiresAfter)
                    .as("renewSandbox(%s) must move the sandbox expiresAt forward (%s -> %s)",
                            RENEWAL_EXTENSION, expiresBefore, expiresAfter)
                    .isAfter(expiresBefore);

            // ---- Step 2: 5-minute pending hold with the renewal in the middle of it --------
            // The blocking probe run is started on a virtual thread; this thread observes the
            // probe's PENDING state file, renews the TTL through the production path, verifies
            // the request is still pending after the renewal, then waits for the probe to
            // answer, finish the turn and exit.
            String probeCommand = "QODER_E2E_MODEL=" + harness.e2eModel()
                    + " node /workspace/05-wait-renewal.mjs 2>&1"
                    + "; echo A5_SCRIPT_EXIT=$?";
            FutureTask<String> probeTask = new FutureTask<>(() -> harness.run(probeCommand));
            Thread.ofVirtual().name("a5-wait-renewal-probe").start(probeTask);

            String stateAtPending;
            try {
                stateAtPending = awaitState(harness, "phase=PENDING", PENDING_WAIT);
            } catch (RuntimeException e) {
                probeTask.cancel(true);
                throw e;
            }
            long pendingSeenAtHost = System.currentTimeMillis();
            System.out.println("[A5] STEP2 pending observed on host at epochMs=" + pendingSeenAtHost
                    + " state=\"" + stateAtPending + "\"");

            long sandboxBeforeRenew = sandboxEpochMillis(harness);
            OffsetDateTime expiresBeforeMidRenewal = readExpiresAt(sandboxId);
            long hostRenew2Started = System.currentTimeMillis();
            harness.renew(RENEWAL_EXTENSION);
            long hostRenew2Finished = System.currentTimeMillis();
            OffsetDateTime expiresAfterMidRenewal = readExpiresAt(sandboxId);
            long sandboxAfterRenew = sandboxEpochMillis(harness);
            // Host-side renewal bracket, written into the sandbox so the probe can lift it
            // into its summary: host clock readings plus the sandbox clock readings taken
            // around the production renewSandbox call.
            String hostRenewalLine = "host_renew host_before=" + hostRenew2Started
                    + " host_after=" + hostRenew2Finished
                    + " sandbox_before=" + sandboxBeforeRenew
                    + " sandbox_after=" + sandboxAfterRenew
                    + " expires_before=" + expiresBeforeMidRenewal
                    + " expires_after=" + expiresAfterMidRenewal;
            harness.run("printf '%s\\n' '" + hostRenewalLine + "' > /tmp/a5/host-renewal");
            String stateAfterRenewal = readState(harness);
            System.out.println("[A5] STEP2 mid-window renewal: hostRenew=[" + hostRenew2Started + ".."
                    + hostRenew2Finished + "] (" + (hostRenew2Finished - hostRenew2Started) + "ms)"
                    + " sandboxClockBracket=[" + sandboxBeforeRenew + ".." + sandboxAfterRenew + "]"
                    + " expiresBefore=" + expiresBeforeMidRenewal + " expiresAfter=" + expiresAfterMidRenewal
                    + " expiresForward=" + forwardMillis(expiresBeforeMidRenewal, expiresAfterMidRenewal) + "ms"
                    + " stateAfterRenewal=\"" + stateAfterRenewal + "\"");
            assertThat(stateAfterRenewal)
                    .as("the mid-window renewal must happen while the permission request is still pending")
                    .contains("phase=PENDING");
            assertThat(expiresAfterMidRenewal)
                    .as("the mid-window renewSandbox(%s) must move expiresAt forward (%s -> %s)",
                            RENEWAL_EXTENSION, expiresBeforeMidRenewal, expiresAfterMidRenewal)
                    .isAfter(expiresBeforeMidRenewal);

            // The post-probe section is wrapped so that a failure before the evidence writes
            // (probe timeout, sandbox death, state parse) still leaves a durable diagnosis:
            // the probe mirrors its redacted console into /tmp/a5/console.log inside the
            // sandbox, which this catch prints best-effort before rethrowing.
            try {
                String output = awaitProbe(probeTask, harness);
                long completedSeenAtHost = System.currentTimeMillis();
                String finalState = readState(harness);
                long tPending = stateField(finalState, "t_pending");
                long tAnswer = stateField(finalState, "t_answer");
                long heldMs = stateField(finalState, "held_ms");
                System.out.println("[A5] STEP2 final state=\"" + finalState + "\"");
                System.out.println("[A5] STEP2 completed observed on host at epochMs=" + completedSeenAtHost
                        + " hostProbeWallClockMs=" + (completedSeenAtHost - pendingSeenAtHost));
                System.out.println("[A5] STEP2 interleaving (sandbox clock): t_pending=" + tPending
                        + " sandbox_before=" + sandboxBeforeRenew
                        + " sandbox_after=" + sandboxAfterRenew
                        + " t_answer=" + tAnswer + " held_ms=" + heldMs);

                // Host-side proof that the CLI still executed: read the file the CLI was supposed
                // to write from the driver itself, instead of resting on the probe's self-report
                // (the probe's own fileChecks remain in the summary — this is a second, independent
                // observation, and the host-side read is part of the evidence in the .md).
                String hostFileRead = harness.run("cat " + PROBE_TARGET_FILE + " 2>/dev/null || echo '<missing>'").trim();
                System.out.println("[A5] STEP2 host-side read of " + PROBE_TARGET_FILE + " -> \""
                        + hostFileRead + "\"");

                // Raw evidence for e2e/qoder/slice-a/05-wait-renewal.md (captured in the Failsafe
                // report). Written BEFORE the first assertion that consumes the probe verdict, so
                // a red gate leaves the same evidence a green one does. No credential material:
                // the PAT reached the sandbox only through the container environment and the
                // probe redacts its value in every console line.
                System.out.println("=== [A5] sandbox " + sandboxId + " raw output begin ===");
                System.out.println(output);
                System.out.println("=== [A5] sandbox " + sandboxId + " raw output end ===");
                Path evidence = Path.of("target", "qoder-a5-sandbox-output.txt");
                Files.createDirectories(evidence.getParent());
                Files.writeString(evidence, output, StandardCharsets.UTF_8);
                System.out.println("[A5] un-folded sandbox output written to " + evidence.toAbsolutePath());
                Path hostEvidence = Path.of("target", "qoder-a5-host-evidence.txt");
                Files.writeString(hostEvidence,
                        "sandboxId=" + sandboxId + "\n"
                                + "step1.expiresBefore=" + expiresBefore + "\n"
                                + "step1.expiresAfter=" + expiresAfter + "\n"
                                + "step2.pendingSeenAtHostEpochMs=" + pendingSeenAtHost + "\n"
                                + "step2.hostRenew=[" + hostRenew2Started + ".." + hostRenew2Finished + "]\n"
                                + "step2.sandboxClockBracket=[" + sandboxBeforeRenew + ".." + sandboxAfterRenew + "]\n"
                                + "step2.expiresBefore=" + expiresBeforeMidRenewal + "\n"
                                + "step2.expiresAfter=" + expiresAfterMidRenewal + "\n"
                                + "step2.stateAtPending=" + stateAtPending + "\n"
                                + "step2.stateAfterRenewal=" + stateAfterRenewal + "\n"
                                + "step2.finalState=" + finalState + "\n"
                                + "step2.hostFileRead=" + hostFileRead + "\n",
                        StandardCharsets.UTF_8);
                System.out.println("[A5] host evidence written to " + hostEvidence.toAbsolutePath());

                assertThat(finalState)
                        .as("the probe must finish the turn after the hold (sandbox %s)", sandboxId)
                        .contains("phase=COMPLETED")
                        .contains("file_ok=1");
                assertThat(heldMs)
                        .as("the pending wait must be the full 5-minute evidence window")
                        .isGreaterThanOrEqualTo(HOLD_MS);
                assertThat(sandboxBeforeRenew)
                        .as("host renewal bracket must start at/after the probe recorded the pending request")
                        .isGreaterThanOrEqualTo(tPending);
                assertThat(sandboxAfterRenew)
                        .as("host renewal bracket must end before the probe answered the pending request")
                        .isLessThan(tAnswer);
                assertThat(hostFileRead)
                        .as("the driver must observe the exact content the CLI wrote at %s (sandbox %s)",
                                PROBE_TARGET_FILE, sandboxId)
                        .isEqualTo(PROBE_TARGET_CONTENT);
                assertThat(output)
                        .as("wait/renewal script must exit 0 and report PASS in sandbox %s", sandboxId)
                        .contains("A5_SCRIPT_EXIT=0")
                        .contains(PASS_MARKER)
                        .contains(SUMMARY_MARKER)
                        .doesNotContain(FAIL_MARKER)
                        .doesNotContain("[A5] FAIL");
            } catch (Throwable failure) {
                // Evidence-preservation backstop: if the failure happened before the capture
                // writes above, the probe's own stdout is not on disk. Read the durable
                // redacted console mirror the probe keeps inside the sandbox (best effort;
                // a dead sandbox must not mask the original failure) and print it with a
                // clear marker before rethrowing.
                try {
                    String console = harness.run("cat /tmp/a5/console.log 2>/dev/null || echo '<no /tmp/a5/console.log>'");
                    System.out.println("=== [A5] sandbox " + harness.sandboxId()
                            + " durable console /tmp/a5/console.log begin ===");
                    System.out.println(console);
                    System.out.println("=== [A5] sandbox " + harness.sandboxId() + " durable console end ===");
                } catch (Throwable consoleFailure) {
                    System.out.println("[A5] could not read /tmp/a5/console.log after failure: " + consoleFailure);
                }
                throw failure;
            }
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Wait for the probe's blocking run, distinguishing a command failure from a timeout.
     * The probe's own hard case timeout (10 minutes) fires well inside
     * {@link #PROBE_TIMEOUT}, so a timeout here means the sandbox/tooling is stuck.
     */
    private static String awaitProbe(FutureTask<String> probeTask, QoderSandboxHarness harness) throws Exception {
        try {
            return probeTask.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("the in-sandbox probe command failed in sandbox "
                    + harness.sandboxId() + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the A5 probe", e);
        } finally {
            probeTask.cancel(true);
        }
    }

    /**
     * Read the sandbox {@code expiresAt} with a real SDK round trip: attach a handle and call
     * {@code getInfo()} (the {@code Sandbox} constructor is not usable as API for this). The
     * handle is attached with {@code Sandbox.resumer()} first, but a server-side resume of a
     * <em>running</em> sandbox is rejected with HTTP 409 Conflict, so the read falls back to
     * {@code Sandbox.connector()} (attach without asking the server to resume) — the path that
     * produced all four readings recorded in {@code e2e/qoder/slice-a/05-wait-renewal.md}. The
     * handle is closed afterwards: {@code Sandbox#close()} only releases the SDK HTTP client,
     * it does not kill the sandbox.
     */
    private static OffsetDateTime readExpiresAt(String sandboxId) {
        try {
            OffsetDateTime expiresAt = readExpiresAtVia(sandboxId, true);
            System.out.println("[A5] expiresAt read: sandbox " + sandboxId + " via resumer handle -> " + expiresAt);
            return expiresAt;
        } catch (RuntimeException resumeFailure) {
            OffsetDateTime expiresAt = readExpiresAtVia(sandboxId, false);
            System.out.println("[A5] expiresAt read: sandbox " + sandboxId + " via connector handle -> " + expiresAt
                    + " (resumer path failed: " + resumeFailure.getMessage() + ")");
            return expiresAt;
        }
    }

    private static OffsetDateTime readExpiresAtVia(String sandboxId, boolean resumer) {
        Sandbox handle = resumer
                ? Sandbox.resumer()
                        .sandboxId(sandboxId)
                        .connectionConfig(SANDBOX_CONNECTION_CONFIG)
                        .skipHealthCheck(true)
                        .resume()
                : Sandbox.connector()
                        .sandboxId(sandboxId)
                        .connectionConfig(SANDBOX_CONNECTION_CONFIG)
                        .skipHealthCheck(true)
                        .connect();
        try {
            return handle.getInfo().getExpiresAt();
        } finally {
            handle.close();
        }
    }

    /** Read the probe's state file; never fails, so callers can poll it. */
    private static String readState(QoderSandboxHarness harness) {
        return harness.run("cat " + STATE_FILE + " 2>/dev/null || echo phase=ABSENT");
    }

    /** Poll the state file until it contains {@code token}, or fail after {@code timeout}. */
    private static String awaitState(QoderSandboxHarness harness, String token, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = readState(harness);
            if (last.contains(token)) {
                return last;
            }
            try {
                Thread.sleep(STATE_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling " + STATE_FILE, e);
            }
        }
        throw new IllegalStateException("state file " + STATE_FILE + " never contained '" + token
                + "' within " + timeout + "; last read: " + last);
    }

    /**
     * Read the sandbox's own clock (Node's {@code Date.now()} — the same clock the probe
     * stamps its state with), so the host renewal bracket can be compared with the probe's
     * pending/answer timestamps without cross-clock guessing.
     */
    private static long sandboxEpochMillis(QoderSandboxHarness harness) {
        String output = harness.run("node -e \"console.log(Date.now())\"");
        Matcher matcher = EPOCH_MILLIS.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("could not read the sandbox clock, output: " + output);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Read a numeric {@code key=value} field from the probe's single-line state file. */
    private static long stateField(String state, String key) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(key) + "=(\\d+)").matcher(state);
        if (!matcher.find()) {
            throw new IllegalStateException("state field '" + key + "' not present in: " + state);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** Milliseconds between two server-reported expiry instants ({@code null}-safe). */
    private static Long forwardMillis(OffsetDateTime before, OffsetDateTime after) {
        return before == null || after == null ? null : Duration.between(before, after).toMillis();
    }

    /** Stage the probe script at the workspace root (the layout it must have in the sandbox). */
    private static Path buildStagingDirectory(Path script) throws IOException {
        Path staging = Files.createTempDirectory("a5-wait-renewal-staging-");
        Files.copy(script, staging.resolve("05-wait-renewal.mjs"));
        return staging;
    }

    /** Walk up from the Maven working directory until the repository root is found. */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < REPO_ROOT_SEARCH_DEPTH && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(PROBE_SCRIPT))) {
                return dir;
            }
        }
        throw new IllegalStateException("Repository root not found (searched "
                + REPO_ROOT_SEARCH_DEPTH + " parent directories for " + PROBE_SCRIPT
                + " from " + Path.of("").toAbsolutePath() + ")");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
