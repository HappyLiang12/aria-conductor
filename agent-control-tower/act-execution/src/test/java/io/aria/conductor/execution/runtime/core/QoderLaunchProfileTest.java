package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.SecretBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 20 fix round 1: the generated Qoder bridge launch profile must be
 * launchable as shipped -- every path the bridge is told exists at launch time,
 * and the values the bridge refuses (a bare {@code --cli}, a relative bridge
 * entry, a missing control-secret/credential file) are resolved or refused
 * loudly here instead of failing the launch gate 30 seconds later.
 */
class QoderLaunchProfileTest {

    private static final String CLI_VARIABLE = "QODER_PERSONAL_ACCESS_TOKEN";
    private static final String CREDENTIAL_VALUE = "test-personal-access-token-value";

    private Path root;

    @AfterEach
    void tearDown() throws IOException {
        if (root != null) {
            deleteTree(root);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private record Fixture(Path root, Path workspace, Path configuration, UUID runId,
            ExecutionSpec spec, PreparedEnvironment environment) {
    }

    private Fixture fixture() throws IOException {
        root = Files.createTempDirectory("qoder-launch-profile");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("host"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null),
                "qoder:operator", "rev-1", null);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST, "host-" + runId,
                workspace.toString(), configuration.toString(), URI.create("http://127.0.0.1:46321/"));
        return new Fixture(root, workspace, configuration, runId, spec, environment);
    }

    /** A stub core executable on disk: the bridge only accepts an absolute existing file for {@code --cli}. */
    private static Path executable(Path directory, String name) throws IOException {
        Path executable = Files.createDirectories(directory).resolve(name);
        Files.writeString(executable, "stub", StandardCharsets.UTF_8);
        return executable;
    }

    private static Path bridgeEntry(Path directory) throws IOException {
        Path entry = Files.createDirectories(directory).resolve("main.js");
        Files.writeString(entry, "// stub bridge", StandardCharsets.UTF_8);
        return entry;
    }

    // ------------------------------------------------------------------ the profile

    /**
     * The generated profile of a real launch: the argv is explicit, every path it
     * names is absolute and existing at launch time, the run-owned credential file
     * is materialized from the credential bundle, and the control-secret file is
     * declared for the placement (which alone holds the minted secret).
     */
    @Test
    void theGeneratedProfileNamesAbsoluteExistingPathsAndWritesTheCredentialFile() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"));

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("qoder:operator", Map.of(CLI_VARIABLE, CREDENTIAL_VALUE)));

        Path credentialFile = QoderCoreAdapter.credentialFile(fixture.environment());
        Path controlSecretFile = QoderCoreAdapter.controlSecretFile(fixture.environment());
        assertThat(profile.argv()).isEqualTo(List.of(
                "node", bridge.toString(),
                "--run-id", fixture.runId().toString(),
                "--workspace", fixture.workspace().toString(),
                "--model", "efficient",
                "--cli", cli.toString(),
                "--credential-env", CLI_VARIABLE,
                "--credential-file", credentialFile.toString(),
                "--control-secret-file", controlSecretFile.toString(),
                "--host", "127.0.0.1",
                "--port", "46321"));
        assertThat(profile.workingDirectory()).isEqualTo(fixture.workspace().toString());
        assertThat(profile.controlSecretFile())
                .as("the placement writes the minted control secret into exactly this file")
                .isEqualTo(controlSecretFile.toString());
        assertThat(credentialFile).as("every file the bridge is told must exist at launch time").isRegularFile();
        assertThat(Files.readString(credentialFile)).isEqualTo(CREDENTIAL_VALUE + System.lineSeparator());
        for (Path path : List.of(credentialFile, controlSecretFile)) {
            assertThat(path.getParent()).isEqualTo(fixture.configuration());
        }
    }

    /** The reviewed model pin is passed through verbatim, from configuration. */
    @Test
    void theProfilePassesTheConfiguredModelThrough() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61",
                "operator-pinned-model"));

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        int model = profile.argv().indexOf("--model");
        assertThat(profile.argv().get(model + 1)).isEqualTo("operator-pinned-model");
        assertThat(profile.argv()).as("no credential bundle entry means no credential-file pair")
                .doesNotContain("--credential-file");
        assertThat(profile.controlSecretFile()).isEqualTo(
                QoderCoreAdapter.controlSecretFile(fixture.environment()).toString());
    }

    /** A bare configured CLI name resolves on PATH to the absolute existing file the bridge demands. */
    @Test
    void aBareCliNameResolvesOnPathToAnAbsoluteExistingPath() throws Exception {
        Fixture fixture = fixture();
        Path bin = fixture.root().resolve("bin");
        Path cli = executable(bin, "qodercli");

        String resolved = QoderCoreAdapter.resolveExecutable("qodercli", "aria.cores.qoder.executable",
                bin.toString(), "");

        assertThat(resolved).isEqualTo(cli.toAbsolutePath().normalize().toString());
    }

    /** The production lookup entry applies the same resolution to the configured value. */
    @Test
    void theProductionLookupAcceptsAnAbsoluteExistingExecutable() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");

        assertThat(QoderCoreAdapter.resolveExecutable(cli.toString(), "aria.cores.qoder.executable"))
                .isEqualTo(cli.toAbsolutePath().normalize().toString());
        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable(cli.resolveSibling("absent.exe").toString(),
                "aria.cores.qoder.executable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable");
    }

    /** An unresolvable bare name is refused loudly by property name, before any process is started. */
    @Test
    void anUnresolvableCliNameIsRefusedByPropertyName() throws Exception {
        Fixture fixture = fixture();
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), "no-such-qoder-cli-executable-xyz", List.of(), Map.of(), Map.of(),
                "1.1.61", "efficient"));

        assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable")
                .hasMessageContaining("absolute existing executable");
    }

    /** A missing bridge entry is refused loudly by property name: the node child could never load it. */
    @Test
    void aMissingBridgeEntryIsRefusedByPropertyName() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path missing = fixture.root().resolve("not-built").resolve("main.js");
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", missing.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"));

        assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.bridge-entry")
                .hasMessageContaining("existing file");
    }

    @Test
    void theProfilePassesTheRecordedAcpInvocationToTheCli() throws Exception {
        // The pinned qodercli speaks the bridge's newline-delimited JSON-RPC only
        // in its recorded ACP mode (Task 1's probe launched it with --acp). A
        // profile without it leaves the bridge waiting for `initialize` until its
        // window elapses and it exits before binding -- the live acceptance failed
        // exactly that way, so the pass-through is pinned here.
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of("--acp"), Map.of(), Map.of(), "1.1.61",
                "efficient"));

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        assertThat(profile.argv())
                .as("the CLI invocation carries the recorded ACP mode")
                .containsSubsequence("--cli", cli.toString(), "--cli-arg", "--acp");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
