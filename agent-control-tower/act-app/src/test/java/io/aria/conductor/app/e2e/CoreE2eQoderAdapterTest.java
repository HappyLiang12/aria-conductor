package io.aria.conductor.app.e2e;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract of the harness Qoder core executable resolution
 * ({@link CoreE2eQoderAdapter#resolveAbsoluteCoreExecutable}, Task 19 fix
 * round 2): the committed bridge accepts only an <em>absolute existing</em>
 * executable for {@code --cli} and refuses a bare name with {@code E_CONFIG}
 * before it binds its endpoint -- the exact refusal that made every qoder run
 * die at the readiness gate. The harness therefore resolves the configured node
 * name to an absolute file before the launch profile is built, and refuses a
 * name with no absolute answer loudly at boot.
 *
 * <p>The cases drive the resolution with explicit {@code PATH}/{@code PATHEXT}
 * inputs (the seam the harness boot uses with its own environment), so the
 * contract is pinned without requiring a node installation.
 */
class CoreE2eQoderAdapterTest {

    @Test
    void aBareNameResolvesToTheAbsoluteFileOnPath() throws IOException {
        Path directory = Files.createTempDirectory("aria-e2e-node-path-");
        Path executable = Files.createFile(directory.resolve("node.exe"));

        String resolved = CoreE2eQoderAdapter.resolveAbsoluteCoreExecutable(
                "node", directory.toString(), List.of(".exe"));

        assertThat(resolved).isEqualTo(executable.toAbsolutePath().normalize().toString());
        assertThat(Path.of(resolved).isAbsolute()).as("the bridge accepts only an absolute --cli").isTrue();
        assertThat(Files.isRegularFile(Path.of(resolved))).isTrue();
    }

    @Test
    void anAbsoluteExistingExecutableIsReturnedNormalizedUnchanged() throws IOException {
        Path directory = Files.createTempDirectory("aria-e2e-node-absolute-");
        Path executable = Files.createFile(directory.resolve("node.exe"));
        String configured = directory.resolve(".").resolve("node.exe").toString();

        String resolved = CoreE2eQoderAdapter.resolveAbsoluteCoreExecutable(
                configured, "an-unused-path-entry", List.of(".exe"));

        assertThat(resolved).isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void aNameWithNoAbsoluteAnswerOnPathIsRefusedWithTheNameAndPath() throws IOException {
        Path empty = Files.createTempDirectory("aria-e2e-node-missing-");

        assertThatThrownBy(() -> CoreE2eQoderAdapter.resolveAbsoluteCoreExecutable(
                "aria-e2e-no-such-node-executable", empty.toString(), List.of(".exe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute existing core executable")
                .hasMessageContaining("aria-e2e-no-such-node-executable")
                .hasMessageContaining(empty.toString());
    }

    @Test
    void anAbsoluteExecutableThatDoesNotExistIsRefused() throws IOException {
        Path missing = Files.createTempDirectory("aria-e2e-node-absent-").resolve("node.exe");

        assertThatThrownBy(() -> CoreE2eQoderAdapter.resolveAbsoluteCoreExecutable(
                missing.toString(), "an-unused-path-entry", List.of(".exe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute existing core executable")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void theLaunchProfileCarriesTheResolvedAbsoluteExecutableAsTheBridgesCli() throws Exception {
        // A bare configured name (the boot default) with an explicit absolute
        // resolution: the RED state this test pins is the profile carrying the
        // bare name, which the bridge refuses with E_CONFIG before it binds.
        String configured = "node";
        Path executable = Files.createTempFile("aria-e2e-node-wire-", ".exe");
        Path bridgeEntry = Files.createTempFile("aria-e2e-bridge-wire-", ".js");
        Path peerScript = Files.createTempFile("aria-e2e-peer-wire-", ".mjs");
        CoreE2eScenarios scenarios = new CoreE2eScenarios(
                Path.of(getClass().getResource("/e2e/scenarios.json").toURI()), "fixture-control-token-0123456789");
        CoreE2eProcessBackend.State state = new CoreE2eProcessBackend.State(
                io.aria.conductor.execution.runtime.host.OwnedProcessController.forCurrentPlatform(),
                new CoreE2eWorkerTokens(new io.aria.conductor.execution.security.ActorTokenService()));
        UUID agentId = UUID.randomUUID();
        scenarios.select(agentId, "reported-usage");
        CoreE2eQoderAdapter adapter = new CoreE2eQoderAdapter(scenarios, state, configured,
                executable.toString(), bridgeEntry, peerScript);
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, agentId, "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, WorkspaceMode.DIRECT, "/tmp/aria-e2e", null),
                null, null, null);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST, "e2e-host-" + runId,
                Files.createTempDirectory("aria-e2e-ws-").toString(),
                Files.createTempDirectory("aria-e2e-cfg-").toString(),
                URI.create("http://127.0.0.1:45678/"));

        LaunchProfile profile = adapter.launchProfile(spec, environment, new SecretBundle(null, Map.of()));

        assertThat(profile.argv().get(0)).as("the bridge process itself is still launched by name")
                .isEqualTo(configured);
        int cliFlag = profile.argv().indexOf("--cli");
        assertThat(cliFlag).as("the bridge argv must carry --cli").isNotNegative();
        String cli = profile.argv().get(cliFlag + 1);
        assertThat(cli).as("the committed bridge refuses a bare --cli name with E_CONFIG before it binds")
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
        assertThat(Path.of(cli).isAbsolute()).isTrue();
        assertThat(Files.isRegularFile(Path.of(cli))).isTrue();
        assertThat(profile.argv()).containsSubsequence("--cli-arg", peerScript.toString());
        assertThat(profile.argv()).contains("--child-env", "ARIA_PEER_SCENARIO=reported-usage");
    }

    @Test
    void aNonAbsoluteCoreExecutableIsRefusedByTheAdapterConstructor() throws Exception {
        Path bridgeEntry = Files.createTempFile("aria-e2e-bridge-guard-", ".js");
        Path peerScript = Files.createTempFile("aria-e2e-peer-guard-", ".mjs");
        CoreE2eScenarios scenarios = new CoreE2eScenarios(
                Path.of(getClass().getResource("/e2e/scenarios.json").toURI()), "fixture-control-token-0123456789");
        CoreE2eProcessBackend.State state = new CoreE2eProcessBackend.State(
                io.aria.conductor.execution.runtime.host.OwnedProcessController.forCurrentPlatform(),
                new CoreE2eWorkerTokens(new io.aria.conductor.execution.security.ActorTokenService()));

        assertThatThrownBy(() -> new CoreE2eQoderAdapter(scenarios, state, "node", "node",
                bridgeEntry, peerScript))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute existing file")
                .hasMessageContaining("node");
    }
}
