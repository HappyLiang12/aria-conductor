package io.aria.conductor.execution.qoder;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice A gate (Task A4): inside a real OpenSandbox sandbox created from the pinned
 * qoder-sandbox image, pin the permission semantics the frozen contracts C0.3 (ACP
 * sequence) and C0.4 (option selection) depend on:
 * <ul>
 *   <li><b>allow-once</b>: a write permission request answered with the offered option
 *       whose {@code kind} is {@code allow_once} creates the file, does NOT escalate the
 *       session mode (no {@code acceptEdits}), and a second write in the same session
 *       asks again (no session-wide grant);</li>
 *   <li><b>deny</b>: {@code reject_once} (or {@code cancelled}) leaves the file absent;</li>
 *   <li><b>cancel while a request is pending</b>: probe whether {@code session/cancel}
 *       exists; when it does not, terminate the CLI and assert no orphan process remains
 *       ({@code ps} inside the sandbox). The path that worked is reported in the stable
 *       {@code CANCEL-METHOD-DECISION:} line consumed by C0.3/B3a.</li>
 * </ul>
 *
 * <p>The probe logic lives in {@code e2e/qoder/slice-a/04-permissions.mjs} (a Node ESM
 * script, executed in-sandbox via {@code OpenCodeSandboxManager#runCommand}); this test
 * boots the sandbox, injects the Qoder PAT as the {@code QODER_PERSONAL_ACCESS_TOKEN}
 * sandbox environment variable (environment only, never argv — see
 * {@link QoderSandboxHarness#boot(String, String, Map)}), uploads the script, runs it and
 * fails unless the script reports PASS. The script asserts its own criteria (markers
 * {@code A4-PERMISSIONS-RESULT: PASS/FAIL}, exit code, JSON summary
 * {@code A4-SUMMARY-JSON}) and exits non-zero on any failure; this test asserts the
 * script's exit marker, verdict lines and summary marker, not prose.
 *
 * <p>Hard constraints asserted by the script, not merely observed: {@code allow_always}
 * is never selected, and no {@code current_mode_update} to {@code acceptEdits} ever
 * appears (spec constraint; see the ACP spike Section 4).
 *
 * <p>Credentials: the PAT is read from the host environment variable {@code QODER_E2E_PAT}
 * and travels only into the sandbox container environment (the plan Global Constraint).
 * It is never an argument of any process, never written to a file and never printed; when
 * the variable is absent the test is skipped with the credential named (no PAT in CI).
 *
 * <p>Gated by {@code -Dqoder.e2e.enabled=true} so the default unit/integration lanes
 * skip it (the local OpenSandbox server, the built image and the local PAT are
 * prerequisites, not CI services):
 * <pre>
 * cd agent-control-tower
 * export QODER_E2E_PAT="$(cat /c/Users/User/.qoder/qoder-pat.txt)"   # local only, never echoed
 * mvn clean verify -pl act-execution -Dit.test=QoderPermissionsE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
 * </pre>
 * {@code clean} is required: {@code jacoco:check} is bound to the {@code test} phase and
 * is only skipped by {@code -Dskip.unit.tests=true}, so a stale
 * {@code act-execution/target/jacoco.exec} fails the build before Failsafe runs.
 *
 * <p>Evidence: {@code e2e/qoder/slice-a/04-permissions.md} (raw output, JSON summary,
 * option-kind table, CANCEL-METHOD-DECISION, recorded usage).
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderPermissionsE2ETest {

    /** Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile} (Task A1). */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    /** Probe script, uploaded to {@code /workspace/04-permissions.mjs}. */
    private static final String PROBE_SCRIPT = "e2e/qoder/slice-a/04-permissions.mjs";

    /** Host-side credential variable holding the Qoder PAT (never an argv entry). */
    private static final String HOST_CREDENTIAL_ENV = "QODER_E2E_PAT";

    /** Sandbox/CLI-side variable the Qoder CLI reads the PAT from (verified in the pinned binary). */
    private static final String SANDBOX_TOKEN_ENV = "QODER_PERSONAL_ACCESS_TOKEN";

    /** Script result markers (see 04-permissions.mjs). */
    private static final String PASS_MARKER = "A4-PERMISSIONS-RESULT: PASS";
    private static final String FAIL_MARKER = "A4-PERMISSIONS-RESULT: FAIL";

    /** Script JSON summary marker (evidence contract of the task brief). */
    private static final String SUMMARY_MARKER = "A4-SUMMARY-JSON:";

    /** Stable cancel-method decision line consumed by C0.3/B3a (task A4 deliverable). */
    private static final String CANCEL_DECISION_MARKER = "CANCEL-METHOD-DECISION:";

    private static final int REPO_ROOT_SEARCH_DEPTH = 6;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void permissionSemanticsAllowOnceDenyAndCancelArePinned() throws IOException {
        String pat = System.getenv(HOST_CREDENTIAL_ENV);
        Assumptions.assumeTrue(pat != null && !pat.isBlank(),
                HOST_CREDENTIAL_ENV + " is not set: this authenticated Slice A gate needs the Qoder PAT "
                        + "in the host environment (credential-dependent cases are skipped without it; "
                        + "no PAT in CI)");

        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve(PROBE_SCRIPT);
        assertThat(script).as("A4 permissions probe script %s", script).isRegularFile();

        Path staging = buildStagingDirectory(script);
        try (QoderSandboxHarness harness = QoderSandboxHarness.boot(
                SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE, Map.of(SANDBOX_TOKEN_ENV, pat))) {
            harness.upload(staging);
            // Plan Global Constraint: pin every local E2E Qoder run to a zero-credit model.
            // The harness validated the value fail-closed at boot; the script pins the ACP
            // session with `-m <model>` plus session/set_model. The PAT is NOT on this command
            // line: it reached the sandbox only through the container environment.
            String output = harness.run("QODER_E2E_MODEL=" + harness.e2eModel()
                    + " node /workspace/04-permissions.mjs 2>&1"
                    + "; echo A4_SCRIPT_EXIT=$?");

            // Raw evidence for e2e/qoder/slice-a/04-permissions.md (captured in the Failsafe
            // report). The output contains no credential material: the script wraps console.log
            // in a redactor that replaces the PAT value with [redacted], and the write/deny
            // fixtures use no tokens at all.
            System.out.println("=== [A4] sandbox " + harness.sandboxId() + " raw output begin ===");
            System.out.println(output);
            System.out.println("=== [A4] sandbox " + harness.sandboxId() + " raw output end ===");
            Path evidence = Path.of("target", "qoder-a4-sandbox-output.txt");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, output, StandardCharsets.UTF_8);
            System.out.println("[A4] un-folded sandbox output written to " + evidence.toAbsolutePath());

            assertThat(output)
                    .as("permissions script must exit 0 and report PASS in sandbox %s", harness.sandboxId())
                    .contains("A4_SCRIPT_EXIT=0")
                    .contains(PASS_MARKER)
                    .contains(SUMMARY_MARKER)
                    .contains(CANCEL_DECISION_MARKER)
                    .doesNotContain(FAIL_MARKER)
                    .doesNotContain("[A4] FAIL");
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Stage the deliverable in the exact layout it must have inside the sandbox:
     * {@code 04-permissions.mjs} at the workspace root (no plugin bundle: permission
     * semantics do not depend on plugin loading, which A2 pinned separately).
     */
    private static Path buildStagingDirectory(Path script) throws IOException {
        Path staging = Files.createTempDirectory("a4-permissions-staging-");
        Files.copy(script, staging.resolve("04-permissions.mjs"));
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
