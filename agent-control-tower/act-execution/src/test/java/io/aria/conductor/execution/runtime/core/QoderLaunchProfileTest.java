package io.aria.conductor.execution.runtime.core;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.CoreLaunchEnvironment;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.security.ActorTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
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

    private final McpProperties mcp = new McpProperties();
    private final ActorTokenService actorTokens = new ActorTokenService();
    private final RunMcpWiring wiring = new RunMcpWiring(mcp, actorTokens);

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
        return fixture(UUID.randomUUID(), null);
    }

    private Fixture fixture(UUID agentId, Instant deadline) throws IOException {
        root = Files.createTempDirectory("qoder-launch-profile");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("host"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, agentId, "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null),
                "qoder:operator", "rev-1", deadline);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.HOST, "host-" + runId,
                workspace.toString(), configuration.toString(), URI.create("http://127.0.0.1:46321/"));
        return new Fixture(root, workspace, configuration, runId, spec, environment);
    }

    /**
     * A SANDBOX placement: the working directory is a sandbox path used verbatim,
     * and the endpoint is the server's proxy URL for the in-sandbox port.
     */
    private Fixture sandboxFixture() throws IOException {
        root = Files.createTempDirectory("qoder-launch-profile");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path configuration = Files.createDirectories(root.resolve("runtime").resolve("sandbox"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), "qoder", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("qoder", ExecutionMode.SANDBOX, null, null, null),
                "qoder:operator", "rev-1", null);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.SANDBOX,
                "sandbox-" + runId, "/workspace", configuration.toString(),
                URI.create("http://localhost:8080/proxy/46321/"));
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
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"), wiring);

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
        assertThat(profile.secretValues())
                .as("the credential value this launch writes to the run-owned file is a declared secret"
                        + " of the launch, so the log masks it by value")
                .containsExactly(CREDENTIAL_VALUE);
        assertThat(profile.controlSecretFile())
                .as("the placement writes the minted control secret into exactly this file")
                .isEqualTo(controlSecretFile.toString());
        assertThat(credentialFile).as("every file the bridge is told must exist at launch time").isRegularFile();
        // The framing byte is the protocol: the bridge reads newline-delimited JSON, so the
        // credential file must end in "\n" on every platform -- pinned literally here (the
        // production constant is what the writer uses; this is what it must be).
        assertThat(Files.readString(credentialFile)).isEqualTo(CREDENTIAL_VALUE + "\n");
        for (Path path : List.of(credentialFile, controlSecretFile)) {
            assertThat(path.getParent()).isEqualTo(fixture.configuration());
        }
    }

    /**
     * The restored platform-MCP wiring: only the Aria assistant's run carries
     * the bridge's worker-MCP flags, and the run-owned token file they name is
     * materialized next to the credential file with the fixed LF framing.
     */
    @Test
    void theAriaProfileCarriesThePlatformMcpFlagsAndWritesTheTokenFile() throws Exception {
        Fixture fixture = fixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));
        mcp.setPort(4711);
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"),
                wiring);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("qoder:operator", Map.of(CLI_VARIABLE, CREDENTIAL_VALUE)));

        Path tokenFile = QoderCoreAdapter.workerMcpTokenFile(fixture.environment());
        assertThat(profile.argv()).containsSubsequence(
                "--worker-mcp-name", "aria-conductor",
                "--worker-mcp-url", "http://127.0.0.1:4711/mcp",
                "--worker-mcp-token-file", tokenFile.toString());
        assertThat(tokenFile)
                .as("the bridge reads the token file at startup, so it must exist at launch time")
                .isRegularFile();
        assertThat(tokenFile.getParent()).isEqualTo(fixture.configuration());
        String token = Files.readString(tokenFile);
        // Same framing byte as the credential file: the bridge trims the file,
        // but the LF is the pinned run-owned-file contract on every platform.
        assertThat(token).endsWith("\n");
        assertThat(actorTokens.resolveBearer("Bearer " + token.strip()))
                .as("the file carries the run-scoped worker bearer minted for this run")
                .get()
                .satisfies(actor -> assertThat(actor.runId()).isEqualTo(fixture.runId()));
        assertThat(profile.secretValues())
                .as("the run bearer written to the token file is a declared secret of the launch")
                .contains(CREDENTIAL_VALUE, token.strip());
        for (String argument : profile.argv()) {
            assertThat(argument)
                    .as("a token value must never appear in the launch argv")
                    .doesNotContain(token.strip());
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
                "operator-pinned-model"), wiring);

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
                "1.1.61", "efficient"), wiring);

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
                "node", missing.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"),
                wiring);

        assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.bridge-entry")
                .hasMessageContaining("existing file");
    }

    /**
     * F3: the shipped default {@code packages/qoder-acp-bridge/dist/main.js} is a
     * repository-relative path, and the backend is started from {@code act-app/}
     * ({@code mvn spring-boot:run -pl act-app}). Resolving it against the process
     * working directory made the default fail from the documented start path, and
     * the old hint ("leave the property unset to use the default") recommended the
     * value that had just failed.
     */
    @Test
    void aRepositoryRelativeBridgeEntryIsResolvedAgainstTheRepositoryRoot() throws Exception {
        Fixture fixture = fixture();
        Path repo = Files.createDirectories(fixture.root().resolve("repo"));
        Path bridge = bridgeEntry(repo.resolve("packages/qoder-acp-bridge/dist"));
        Path actApp = Files.createDirectories(repo.resolve("agent-control-tower/act-app"));
        Files.writeString(repo.resolve("agent-control-tower/pom.xml"), "<project/>", StandardCharsets.UTF_8);

        String resolved = QoderCoreAdapter.resolveBridgeEntry(
                "packages/qoder-acp-bridge/dist/main.js", actApp, QoderCoreAdapter.repositoryRoot(actApp));

        assertThat(resolved).isEqualTo(bridge.toAbsolutePath().normalize().toString());
    }

    /** The hint names why the value failed; it no longer recommends the default that just failed. */
    @Test
    void theMissingBridgeEntryHintDoesNotRecommendTheFailingDefaultAgain() {
        assertThatThrownBy(() -> QoderCoreAdapter.resolveBridgeEntry(
                "packages/qoder-acp-bridge/dist/main.js", Path.of("absent-working-directory"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("packages/qoder-acp-bridge/dist/main.js")
                .hasMessageNotContaining("leave the property unset");
    }

    /**
     * F4: the released Windows CLI is the versioned executable under
     * {@code ~/.qoder/bin/qodercli}, so the bare default name {@code qoder} does
     * not resolve. A bare name that misses PATH falls back to that directory and
     * picks the highest version instead of refusing the run.
     */
    @Test
    void theVersionedCliIsDiscoveredWhenTheBareNameMissesPath() throws Exception {
        Fixture fixture = fixture();
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        Path newest = executable(cliRoot, "qodercli-1.1.61.exe");
        executable(cliRoot, "qodercli-1.1.9.exe");

        String resolved = QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", "", "",
                List.of(fixture.root().resolve(".qoder/bin"), cliRoot));

        assertThat(resolved).isEqualTo(newest.toAbsolutePath().normalize().toString());
    }

    /** A PATH hit still wins over discovery: the configured resolution order is unchanged. */
    @Test
    void aPathHitWinsOverTheVersionedDiscovery() throws Exception {
        Fixture fixture = fixture();
        Path onPath = executable(fixture.root().resolve("on-path"), "qoder.exe");
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        executable(cliRoot, "qodercli-1.1.61.exe");

        String resolved = QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", fixture.root().resolve("on-path").toString(), ".EXE;.CMD",
                List.of(cliRoot));

        assertThat(resolved).isEqualTo(onPath.toAbsolutePath().normalize().toString());
    }

    /**
     * R10: the committed bridge spawns the CLI without a shell, so the
     * extensionless Unix shim that ships on {@code PATH} beside the Windows CLI is
     * not a launch candidate there -- {@code spawn .../qoder ENOENT}. On Windows a
     * configured bare name must therefore resolve to the spawnable image, even
     * when the shim is written first in the same directory.
     */
    @Test
    void aBareNameOnWindowsPrefersTheSpawnableExeOverAnExtensionlessShim() throws Exception {
        Fixture fixture = fixture();
        Path bin = fixture.root().resolve("bin");
        Path shim = executable(bin, "qoder");
        Path image = executable(bin, "qoder.exe");

        String resolved = QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", bin.toString(), ".COM;.EXE;.BAT;.CMD", List.of());

        assertThat(shim).as("the reproduction needs the extensionless shim on PATH").isRegularFile();
        assertThat(resolved).isEqualTo(image.toAbsolutePath().normalize().toString());
    }

    /**
     * R10: a directory that carries only the Unix shim and a {@code .cmd} wrapper
     * (neither spawnable without a shell) contributes no candidate, so the
     * existing versioned-CLI discovery still resolves the released executable.
     */
    @Test
    void aBareNameOnWindowsSkipsAnExtensionlessShimAndFallsThroughToDiscovery() throws Exception {
        Fixture fixture = fixture();
        Path bin = fixture.root().resolve("bin");
        executable(bin, "qoder");
        executable(bin, "qoder.cmd");
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        Path newest = executable(cliRoot, "qodercli-1.1.61.exe");
        executable(cliRoot, "qodercli-1.1.9.exe");

        String resolved = QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", bin.toString(), ".COM;.EXE;.BAT;.CMD", List.of(cliRoot));

        assertThat(resolved).isEqualTo(newest.toAbsolutePath().normalize().toString());
    }

    /**
     * R10: {@code .cmd}/{@code .bat} cannot be spawned without a shell, so a
     * Windows name that only a batch wrapper answers is refused loudly by property
     * name instead of handing the bridge a value it can only fail to spawn.
     */
    @Test
    void aBareNameOnWindowsRefusesACmdOnlyCandidate() throws Exception {
        Fixture fixture = fixture();
        Path bin = fixture.root().resolve("bin");
        executable(bin, "qoder.cmd");

        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", bin.toString(), ".COM;.EXE;.BAT;.CMD", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable")
                .hasMessageContaining("absolute existing executable")
                .as("the refusal says why a .cmd wrapper is not an answer")
                .hasMessageContaining("only .exe/.com");
    }

    /**
     * R10 review: discovery must apply the same spawnable-image rule as the
     * {@code PATH} lookup. A {@code .cmd} wrapper is not something the bridge can
     * start without a shell, so a higher-versioned wrapper must never win over the
     * {@code .exe} the bridge can actually run.
     */
    @Test
    void windowsDiscoverySkipsAnUnspawnableCmdWrapperAndPicksTheSpawnableExe() throws Exception {
        Fixture fixture = fixture();
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        Path spawnable = executable(cliRoot, "qodercli-1.1.9.exe");
        Path wrapper = executable(cliRoot, "qodercli-1.1.61.cmd");

        String resolved = QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", "", ".COM;.EXE;.BAT;.CMD", List.of(cliRoot));

        assertThat(wrapper).as("the reproduction needs the newer .cmd wrapper on disk").isRegularFile();
        assertThat(resolved).isEqualTo(spawnable.toAbsolutePath().normalize().toString());
    }

    /**
     * R10 review: a discovery directory that carries only a {@code .cmd} wrapper
     * contributes no candidate at all, so the launch is refused by property name
     * instead of handing the bridge the wrapper the refusal itself calls
     * unspawnable.
     */
    @Test
    void windowsDiscoveryRefusesACmdOnlyDirectoryByPropertyName() throws Exception {
        Fixture fixture = fixture();
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        executable(cliRoot, "qodercli-1.1.61.cmd");

        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable("qoder",
                "aria.cores.qoder.executable", "", ".COM;.EXE;.BAT;.CMD", List.of(cliRoot)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable")
                .hasMessageContaining("only .exe/.com");
    }

    /**
     * POSIX carries no extension set, so discovery keeps the population it always
     * had: the existing name rule (a bare name or one of .exe/.cmd/.bat) still
     * matches, because the spawnable-image filter is a Windows rule.
     */
    @Test
    void posixDiscoveryKeepsItsExistingCandidatePopulation() throws Exception {
        Fixture fixture = fixture();
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        Path wrapper = executable(cliRoot, "qodercli-1.1.61.cmd");

        assertThat(QoderCoreAdapter.resolveExecutable("qoder", "aria.cores.qoder.executable",
                "", "", List.of(cliRoot)))
                .isEqualTo(wrapper.toAbsolutePath().normalize().toString());
    }

    /**
     * R10 review: the absolute-path branch is not a bypass of the spawnable rule.
     * A configured {@code .cmd} passes {@code Files.isRegularFile} but is exactly
     * the wrapper the bridge cannot start without a shell, so it is refused by
     * property name (Windows only: an extension set is present).
     */
    @Test
    void aWindowsAbsoluteCmdPathIsRefusedByPropertyName() throws Exception {
        Fixture fixture = fixture();
        Path wrapper = executable(fixture.root().resolve("bin"), "qodercli.cmd");

        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable(wrapper.toString(),
                "aria.cores.qoder.executable", "", ".COM;.EXE;.BAT;.CMD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable")
                .hasMessageContaining("only .exe/.com");
    }

    /** R10 review: the same absolute branch refuses an extensionless Windows shim. */
    @Test
    void aWindowsAbsoluteExtensionlessShimIsRefusedByPropertyName() throws Exception {
        Fixture fixture = fixture();
        Path shim = executable(fixture.root().resolve("bin"), "qodercli");

        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable(shim.toString(),
                "aria.cores.qoder.executable", "", ".COM;.EXE;.BAT;.CMD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable");
    }

    /**
     * The Windows absolute rule is exactly a Windows rule: with no extension set
     * (POSIX) an absolute existing file keeps resolving as before, and a
     * {@code .com} image is accepted on Windows too.
     */
    @Test
    void theAbsoluteImageRuleAppliesOnlyWhereAnExtensionSetExists() throws Exception {
        Fixture fixture = fixture();
        Path extensionless = executable(fixture.root().resolve("bin"), "qodercli");
        Path command = executable(fixture.root().resolve("com"), "qodercli.com");

        assertThat(QoderCoreAdapter.resolveExecutable(extensionless.toString(),
                "aria.cores.qoder.executable", "", ""))
                .isEqualTo(extensionless.toAbsolutePath().normalize().toString());
        assertThat(QoderCoreAdapter.resolveExecutable(command.toString(),
                "aria.cores.qoder.executable", "", ".COM;.EXE;.BAT;.CMD"))
                .isEqualTo(command.toAbsolutePath().normalize().toString());
    }

    /** Discovery never rescues an explicitly configured absolute path that is missing. */
    @Test
    void anExplicitAbsoluteCliPathIsNeverReplacedByDiscovery() throws Exception {
        Fixture fixture = fixture();
        Path cliRoot = fixture.root().resolve(".qoder/bin/qodercli");
        executable(cliRoot, "qodercli-1.1.61.exe");

        assertThatThrownBy(() -> QoderCoreAdapter.resolveExecutable(
                fixture.root().resolve("absent/qodercli.exe").toString(),
                "aria.cores.qoder.executable", "", "", List.of(cliRoot)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable");
    }

    // ------------------------------------------------ run-owned launch environment

    /**
     * F13/F14/F12/F20: the launch carries the built environment to both sides of
     * the bridge. The bridge process gets the effective PATH (it builds the CLI
     * child's environment from that one variable only), the CLI child gets the
     * resolved git credential and the git isolation/long-path settings, and the
     * credential value is declared a secret so the run-owned core log masks it.
     */
    @Test
    void theProfileCarriesTheLaunchEnvironmentOnTheBridgeAndTheCliChild() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        CoreLaunchEnvironment launchEnvironment = CoreLaunchEnvironment.forHost(
                "C:\\Windows\\system32;C:\\Program Files\\GitHub CLI", ";",
                List.of("C:/Users/User/tools/apache-maven-3.9.6/bin"),
                () -> "ghp_resolved_pack_credential", true);
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"),
                wiring, launchEnvironment);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("qoder:operator", Map.of(CLI_VARIABLE, CREDENTIAL_VALUE)));

        assertThat(profile.env())
                .as("the bridge's PATH is what the committed bridge forwards to its CLI child")
                .containsEntry("PATH", "/c/Windows/system32:/c/Program Files/GitHub CLI"
                        + ":/c/Users/User/tools/apache-maven-3.9.6/bin")
                // F13: the bridge reads this from its own environment and prepends it
                // to the child PATH; publishing it on the child was inert.
                .containsEntry("ARIA_CORE_EXTRA_PATH", "/c/Users/User/tools/apache-maven-3.9.6/bin");
        String ceiling = CoreLaunchEnvironment.renderDirectory(
                CoreLaunchEnvironment.parentDirectory(fixture.workspace().toString()),
                CoreLaunchEnvironment.PathStyle.POSIX);
        for (String pair : List.of(
                "GIT_CEILING_DIRECTORIES=" + ceiling,
                "GIT_CONFIG_COUNT=1",
                "GIT_CONFIG_KEY_0=core.longpaths",
                "GIT_CONFIG_VALUE_0=true")) {
            int index = profile.argv().indexOf(pair);
            assertThat(index).as("%s must reach the CLI child as a declared child environment pair", pair)
                    .isGreaterThan(0);
            assertThat(profile.argv().get(index - 1)).isEqualTo("--child-env");
        }
        // F14: the resolved git credential is secret-bearing, so it travels in a
        // run-owned file rather than argv (the process table is world-readable
        // to the same user, including the agent's own Bash tool).
        assertThat(profile.argv()).noneMatch(argument -> argument.contains("ghp_resolved_pack_credential"));
        int fileFlag = profile.argv().indexOf("--child-env-file");
        assertThat(fileFlag).isGreaterThan(0);
        Path childEnvironmentFile = Path.of(profile.argv().get(fileFlag + 1));
        assertThat(childEnvironmentFile).isEqualTo(QoderCoreAdapter.childEnvironmentFile(fixture.environment()));
        List<String> lines = java.nio.file.Files.readAllLines(childEnvironmentFile);
        assertThat(lines).containsExactly(
                "GH_TOKEN=ghp_resolved_pack_credential",
                "GITHUB_TOKEN=ghp_resolved_pack_credential");
        assertThat(profile.secretValues())
                .as("both the model credential and the injected git credential are launch secrets")
                .contains(CREDENTIAL_VALUE, "ghp_resolved_pack_credential");
    }

    /** A reviewed profile value is operator configuration and wins over the derived one. */
    @Test
    void theReviewedProfileEnvironmentWinsOverTheDerivedOne() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        CoreLaunchEnvironment launchEnvironment = CoreLaunchEnvironment.forHost(
                "C:\\Windows\\system32", ";", List.of(), null, true);
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(),
                Map.of("PATH", "C:\\reviewed-toolchain"), "1.1.61", "efficient"),
                wiring, launchEnvironment);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        assertThat(profile.env()).containsEntry("PATH", "C:\\reviewed-toolchain");
    }

    /**
     * A profile constructed without a launch environment (the legacy two-argument
     * constructor) adds none of it: the existing reviewed launches stay exactly as
     * they were, and nothing ambient is invented.
     */
    @Test
    void aProfileWithoutALaunchEnvironmentAddsNoDerivedValues() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"),
                wiring);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        assertThat(profile.env()).isEmpty();
        assertThat(profile.argv()).doesNotContain("GH_TOKEN=null");
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
                "efficient"), wiring);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle(null, Map.of()));

        assertThat(profile.argv())
                .as("the CLI invocation carries the recorded ACP mode")
                .containsSubsequence("--cli", cli.toString(), "--cli-arg", "--acp");
    }

    /**
     * The merge guard is enforced where the bridge environment is copied, so a
     * profile constructed directly cannot set a variable the launcher owns. The
     * spelling variants matter because the ambient lookup it protects is
     * case-insensitive.
     */
    @Test
    void aDirectlyConstructedProfileCannotSetALauncherOwnedVariable() throws Exception {
        Fixture fixture = fixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        for (String name : List.of("TMP", "tmp", "XDG_data_home", "ARIA_MCP_TOKEN")) {
            QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                    "node", bridge.toString(), cli.toString(), List.of(), Map.of(),
                    Map.of(name, "synthetic-operator-value"), "1.1.61", "efficient"), wiring);

            assertThatThrownBy(() -> adapter.launchProfile(fixture.spec(), fixture.environment(),
                    new SecretBundle(null, Map.of())))
                    .as("a directly constructed profile must not carry %s into the launch", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .hasMessageContaining("launcher owns");
        }
    }

    /**
     * Every run-owned file that carries a secret is readable by its owner only
     * where POSIX modes exist (the Linux CI leg). Windows has no POSIX mode; the
     * platform default applies there and the assertion is skipped.
     */
    @Test
    void theRunOwnedSecretFilesAreOwnerOnlyWherePosixModesExist() throws Exception {
        Fixture fixture = fixture(AriaConstants.ARIA_AGENT_ID, Instant.now().plusSeconds(2700));
        mcp.setPort(4711);
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        CoreLaunchEnvironment launchEnvironment = CoreLaunchEnvironment.forHost(
                "C:\\Windows\\system32", ";", List.of(), () -> "ghp_owner_only_probe", true);
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(), Map.of(), Map.of(), "1.1.61", "efficient"),
                wiring, launchEnvironment);

        adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("qoder:operator", Map.of(CLI_VARIABLE, CREDENTIAL_VALUE)));

        for (Path path : List.of(
                QoderCoreAdapter.credentialFile(fixture.environment()),
                QoderCoreAdapter.workerMcpTokenFile(fixture.environment()),
                QoderCoreAdapter.childEnvironmentFile(fixture.environment()))) {
            assertThat(path).as("the secret file is materialized").isRegularFile();
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
                continue;
            }
            assertThat(Files.getPosixFilePermissions(path))
                    .as("%s carries a secret and must not be readable by anyone else", path.getFileName())
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    /**
     * A SANDBOX bridge runs inside the sandbox and cannot read a host-owned file,
     * so the host-file routing of the git credential is HOST-only. A reviewed
     * profile token pair keeps its prior inline {@code --child-env} form there.
     */
    @Test
    void aSandboxLaunchKeepsTheReviewedTokenPairsInline() throws Exception {
        Fixture fixture = sandboxFixture();
        Path cli = executable(fixture.root().resolve("bin"), "qodercli.exe");
        Path bridge = bridgeEntry(fixture.root().resolve("bridge"));
        QoderCoreAdapter adapter = new QoderCoreAdapter(new QoderCoreAdapter.QoderProfile(
                "node", bridge.toString(), cli.toString(), List.of(),
                Map.of("GH_TOKEN", "ghp_reviewed_sandbox_token"), Map.of(), "1.1.61", "efficient"), wiring);

        LaunchProfile profile = adapter.launchProfile(fixture.spec(), fixture.environment(),
                new SecretBundle("qoder:operator", Map.of(CLI_VARIABLE, CREDENTIAL_VALUE)));

        int index = profile.argv().indexOf("GH_TOKEN=ghp_reviewed_sandbox_token");
        assertThat(index).as("the sandbox bridge reads the pair from its own command line")
                .isGreaterThan(0);
        assertThat(profile.argv().get(index - 1)).isEqualTo("--child-env");
        assertThat(profile.argv())
                .as("a host-owned file path would be unreadable inside the sandbox")
                .doesNotContain("--child-env-file");
        assertThat(profile.argv()).containsSubsequence(
                "--workspace", "/workspace", "--host", "127.0.0.1", "--port", "46321");
        assertThat(profile.env()).as("no host-derived PATH reaches a sandbox bridge").isEmpty();
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
