package io.aria.conductor.execution.qoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice A gate (Task A2): inside a real OpenSandbox sandbox created from the pinned
 * qoder-sandbox image, prove that the Qoder CLI configuration is isolated — a hostile
 * workspace {@code .mcp.json} and hostile user/project settings cannot add MCP servers
 * or relax the permission mode, and {@code --plugin-dir} is the only plugin source.
 *
 * <p>The probe logic lives in {@code e2e/qoder/slice-a/02-isolation.sh}; this test only
 * boots the sandbox, uploads the script plus the repository plugin bundle
 * ({@code agent-control-tower/qoder-sandbox/plugin/}), runs the script through
 * {@code OpenCodeSandboxManager#runCommand} and fails when the script reports a leak
 * (non-zero exit / {@code A2-ISOLATION-RESULT: FAIL}).
 *
 * <p>No credentials are involved: the script observes the CLI's own startup log
 * (permission mode, effective MCP servers) and the CLI's config surfaces
 * ({@code plugins}, {@code agents}, {@code skills}) only.
 *
 * <p>Gated by {@code -Dqoder.e2e.enabled=true} so the default unit/integration lanes
 * skip it (the local OpenSandbox server and the built image are prerequisites, not CI
 * services):
 * <pre>
 * cd agent-control-tower
 * mvn clean verify -pl act-execution -Dit.test=QoderConfigIsolationE2ETest -Dqoder.e2e.enabled=true -Djacoco.skip=true
 * </pre>
 * {@code clean} is required: {@code jacoco:check} is bound to the {@code test} phase and
 * is only skipped by {@code -Dskip.unit.tests=true}, so a stale
 * {@code act-execution/target/jacoco.exec} fails the build before Failsafe runs.
 *
 * <p>Evidence: {@code e2e/qoder/slice-a/02-isolation.md} (raw output, chosen flags).
 */
@EnabledIfSystemProperty(named = "qoder.e2e.enabled", matches = "true")
class QoderConfigIsolationE2ETest {

    /** Image built from {@code agent-control-tower/qoder-sandbox/Dockerfile} (Task A1). */
    private static final String QODER_SANDBOX_IMAGE = "aria-conductor/qoder-sandbox:0.1";

    /** Local OpenSandbox server: docker-compose service {@code opensandbox-server}, host port 8090. */
    private static final String SANDBOX_SERVER_URL = "http://localhost:8090";

    /** Probe script, uploaded to {@code /workspace/02-isolation.sh}. */
    private static final String ISOLATION_SCRIPT = "e2e/qoder/slice-a/02-isolation.sh";

    /** Pinned plugin bundle root, uploaded to {@code /workspace/plugin/}. */
    private static final String PLUGIN_BUNDLE_DIR = "agent-control-tower/qoder-sandbox/plugin";

    /** Script result markers (see 02-isolation.sh). */
    private static final String PASS_MARKER = "A2-ISOLATION-RESULT: PASS";
    private static final String FAIL_MARKER = "A2-ISOLATION-RESULT: FAIL";

    private static final int REPO_ROOT_SEARCH_DEPTH = 6;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void hostileConfigCannotAddMcpServersRelaxPermissionsOrInjectPlugins() throws IOException {
        Path repoRoot = findRepoRoot();
        Path script = repoRoot.resolve(ISOLATION_SCRIPT);
        Path bundle = repoRoot.resolve(PLUGIN_BUNDLE_DIR);
        assertThat(script).as("A2 isolation script %s", script).isRegularFile();
        assertThat(bundle.resolve(".qoder-plugin/plugin.json"))
                .as("pinned plugin manifest under %s", bundle).isRegularFile();

        Path staging = buildStagingDirectory(script, bundle);
        try (QoderSandboxHarness harness = QoderSandboxHarness.boot(SANDBOX_SERVER_URL, QODER_SANDBOX_IMAGE)) {
            harness.upload(staging);
            // Plan Global Constraint: pin every local E2E Qoder run to a zero-credit model.
            // The harness validated the value fail-closed at boot; the script pins every
            // qodercli invocation with -m <model>.
            String output = harness.run("QODER_E2E_MODEL=" + harness.e2eModel()
                    + " bash /workspace/02-isolation.sh 2>&1; echo A2_SCRIPT_EXIT=$?");

            // Raw evidence for e2e/qoder/slice-a/02-isolation.md (captured in the Failsafe report).
            System.out.println("=== [A2] sandbox " + harness.sandboxId() + " raw output begin ===");
            System.out.println(output);
            System.out.println("=== [A2] sandbox " + harness.sandboxId() + " raw output end ===");
            // Failsafe folds long output in the console/report; keep an un-folded copy for the
            // evidence document (regenerate with this test; the file lives under target/).
            Path evidence = Path.of("target", "qoder-a2-sandbox-output.txt");
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, output, StandardCharsets.UTF_8);
            System.out.println("[A2] un-folded sandbox output written to " + evidence.toAbsolutePath());

            assertThat(output)
                    .as("isolation script must exit 0 and report PASS in sandbox %s", harness.sandboxId())
                    .contains("A2_SCRIPT_EXIT=0")
                    .contains(PASS_MARKER)
                    .doesNotContain(FAIL_MARKER)
                    .doesNotContain("[A2] FAIL");
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Stage the deliverables in the exact layout they must have inside the sandbox:
     * {@code 02-isolation.sh} at the workspace root and the plugin bundle under
     * {@code plugin/} (the same layout B4 copies into the final image).
     */
    private static Path buildStagingDirectory(Path script, Path bundle) throws IOException {
        Path staging = Files.createTempDirectory("a2-isolation-staging-");
        Files.copy(script, staging.resolve("02-isolation.sh"));
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
            if (Files.isRegularFile(dir.resolve(ISOLATION_SCRIPT))) {
                return dir;
            }
        }
        throw new IllegalStateException("Repository root not found (searched "
                + REPO_ROOT_SEARCH_DEPTH + " parent directories for " + ISOLATION_SCRIPT
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
