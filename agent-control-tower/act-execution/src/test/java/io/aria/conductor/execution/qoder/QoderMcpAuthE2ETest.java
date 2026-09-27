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
 * Slice A gate (Task A3, the first authenticated gate): inside a real OpenSandbox sandbox
 * created from the pinned qoder-sandbox image, prove an MCP round-trip over HTTP with a
 * real {@code Authorization} header against a header-validating stub.
 *
 * <p>The probe logic lives in {@code e2e/qoder/slice-a/03-mcp-auth.mjs} (a Node ESM script,
 * executed in-sandbox via {@code OpenCodeSandboxManager#runCommand}); this test boots the
 * sandbox, injects the Qoder PAT as the {@code QODER_PERSONAL_ACCESS_TOKEN} sandbox
 * environment variable (environment only, never argv — see
 * {@link QoderSandboxHarness#boot(String, String, Map)}), uploads the script plus the pinned
 * plugin bundle, runs the script and fails unless the script reports PASS.
 *
 * <p>The script (single probe implementation, three cases against one in-sandbox stub):
 * <ul>
 *   <li>positive: {@code session/new.mcpServers[].headers} carries
 *       {@code Authorization: Bearer test-worker-token}; the CLI's {@code mcp_call} succeeds
 *       and the agent replies with the stub's exact text;</li>
 *   <li>negative (header omitted) and negative (wrong header): the stub answers HTTP 401 on
 *       every JSON-RPC request, records the refusal, and the CLI's call fails — proving the
 *       header path is load-bearing rather than the stub accepting everything.</li>
 * </ul>
 * The script asserts its own criteria (markers {@code A3-MCP-AUTH-RESULT: PASS/FAIL},
 * exit code, JSON summary {@code A3-SUMMARY-JSON}) and exits non-zero on any failure; this
 * test asserts the script's exit marker and result line, not prose.
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
 * mvn clean verify -pl act-execution -Dit.test=QoderMcpAuthE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
 * </pre>
 * {@code clean} is required: {@code jacoco:check} is bound to the {@code test} phase and
 * is only skipped by {@code -Dskip.unit.tests=true}, so a stale
 * {@code act-execution/target/jacoco.exec} fails the build before Failsafe runs.
 *
 * <p>Evidence: {@code e2e/qoder/slice-a/03-mcp-auth.md} (raw output, JSON summary, usage
 * fields).
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderMcpAuthE2ETest {

    /** Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile} (Task A1). */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    /** Probe script, uploaded to {@code /workspace/03-mcp-auth.mjs}. */
    private static final String PROBE_SCRIPT = "e2e/qoder/slice-a/03-mcp-auth.mjs";

    /** Pinned plugin bundle root, uploaded to {@code /workspace/plugin/} (A2/this task). */
    private static final String PLUGIN_BUNDLE_DIR = "agent-control-tower/qoder-sandbox/plugin";

    /** Host-side credential variable holding the Qoder PAT (never an argv entry). */
    private static final String HOST_CREDENTIAL_ENV = "QODER_E2E_PAT";

    /** Sandbox/CLI-side variable the Qoder CLI reads the PAT from (verified in the pinned binary). */
    private static final String SANDBOX_TOKEN_ENV = "QODER_PERSONAL_ACCESS_TOKEN";

    /** Script result markers (see 03-mcp-auth.mjs). */
    private static final String PASS_MARKER = "A3-MCP-AUTH-RESULT: PASS";
    private static final String FAIL_MARKER = "A3-MCP-AUTH-RESULT: FAIL";

    /** Script JSON summary marker (required by the task brief's evidence contract). */
    private static final String SUMMARY_MARKER = "A3-SUMMARY-JSON:";

    private static final int REPO_ROOT_SEARCH_DEPTH = 6;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void authenticatedMcpRoundTripHonoursTheAuthorizationHeader() throws IOException {
        String pat = System.getenv(HOST_CREDENTIAL_ENV);
        Assumptions.assumeTrue(pat != null && !pat.isBlank(),
                HOST_CREDENTIAL_ENV + " is not set: this authenticated Slice A gate needs the Qoder PAT "
                        + "in the host environment (credential-dependent cases are skipped without it; "
                        + "no PAT in CI)");

        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve(PROBE_SCRIPT);
        Path bundle = repoRoot.resolve(PLUGIN_BUNDLE_DIR);
        assertThat(script).as("A3 MCP auth probe script %s", script).isRegularFile();
        assertThat(bundle.resolve(".qoder-plugin/plugin.json"))
                .as("pinned plugin manifest under %s", bundle).isRegularFile();

        Path staging = buildStagingDirectory(script, bundle);
        try (QoderSandboxHarness harness = QoderSandboxHarness.boot(
                SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE, Map.of(SANDBOX_TOKEN_ENV, pat))) {
            harness.upload(staging);
            // Plan Global Constraint: pin every local E2E Qoder run to a zero-credit model.
            // The harness validated the value fail-closed at boot; the script pins the ACP
            // session with `-m <model>` plus session/set_model. The PAT is NOT on this command
            // line: it reached the sandbox only through the container environment.
            String output = harness.run("QODER_E2E_MODEL=" + harness.e2eModel()
                    + " QODER_E2E_PLUGIN_DIR=/workspace/plugin node /workspace/03-mcp-auth.mjs 2>&1"
                    + "; echo A3_SCRIPT_EXIT=$?");

            // Raw evidence for e2e/qoder/slice-a/03-mcp-auth.md (captured in the Failsafe
            // report). The output contains no credential material: the synthetic MCP bearer
            // token is a placeholder and the script never prints the Qoder PAT.
            System.out.println("=== [A3] sandbox " + harness.sandboxId() + " raw output begin ===");
            System.out.println(output);
            System.out.println("=== [A3] sandbox " + harness.sandboxId() + " raw output end ===");
            Path evidence = Path.of("target", "qoder-a3-sandbox-output.txt");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, output, StandardCharsets.UTF_8);
            System.out.println("[A3] un-folded sandbox output written to " + evidence.toAbsolutePath());

            assertThat(output)
                    .as("MCP auth script must exit 0 and report PASS in sandbox %s", harness.sandboxId())
                    .contains("A3_SCRIPT_EXIT=0")
                    .contains(PASS_MARKER)
                    .contains(SUMMARY_MARKER)
                    .doesNotContain(FAIL_MARKER)
                    .doesNotContain("[A3] FAIL");
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Stage the deliverables in the exact layout they must have inside the sandbox:
     * {@code 03-mcp-auth.mjs} at the workspace root and the pinned plugin bundle under
     * {@code plugin/} (same layout A2/B4 use).
     */
    private static Path buildStagingDirectory(Path script, Path bundle) throws IOException {
        Path staging = Files.createTempDirectory("a3-mcp-auth-staging-");
        Files.copy(script, staging.resolve("03-mcp-auth.mjs"));
        Path pluginTarget = staging.resolve("plugin");
        try (Stream<Path> walk = Files.walk(bundle)) {
            for (Path source : walk.toList()) {
                Path relative = bundle.relativize(source);
                Path target = pluginTarget.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
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
