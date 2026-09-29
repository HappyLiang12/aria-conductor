package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour tests for {@link CoreConfigurationService} (spec 6.1/6.2): the
 * frozen per-core invocation, an explicit environment allowlist with run-owned
 * HOME/TMP roots, generated files outside a Direct user repository, refusal of
 * SDK-entrypoint/ambient configuration, and redaction of the launch carriers.
 */
class CoreConfigurationServiceTest {

    private static final String SECRET = "synthetic-secret-42";
    private static final String QODER_EXE = "qodercli-test";
    private static final String OPENCODE_EXE = "opencode-test";

    /** The exact frozen OS allowlist of the service; the child inherits nothing else. */
    private static final Set<String> ALLOWED_SYSTEM_VARIABLES = Set.of(
            "PATH", "PATHEXT", "SystemRoot", "SystemDrive", "windir", "COMSPEC",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "LANG", "LC_ALL", "TZ");

    private static final Map<String, String> OPENCODE_PROVIDER_CREDENTIALS =
            Map.of("DEEPSEEK_API_KEY", "test-provider-key");

    @TempDir
    Path tempDir;

    private final CoreConfigurationService service = new CoreConfigurationService(
            QODER_EXE, OPENCODE_EXE, OPENCODE_PROVIDER_CREDENTIALS);

    // ------------------------------------------------------------------ brief step 1 guard

    @Test
    void launchObjectsNeverRevealTheirSecretEnvironmentInToString() {
        var bundle = new SecretBundle("qoder:test", java.util.Map.of("RUNTIME_SECRET", "synthetic-secret-42"));
        assertThat(bundle.toString()).isEqualTo("SecretBundle[redacted]");
        assertThat(bundle.environment().get("RUNTIME_SECRET")).isEqualTo("synthetic-secret-42");
    }

    // ------------------------------------------------------------------ qoder profile

    @Test
    void qoderProfileFreezesTheRecordedInvocationAndRunOwnedRoots() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path configurationDirectory = tempDir.resolve("run-config");
        ExecutionSpec spec = spec("qoder", workspace);
        PreparedEnvironment environment =
                environment(spec.runId(), workspace, configurationDirectory, 9301);

        LaunchProfile profile = service.prepare(spec, environment,
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET)));

        assertThat(profile.argv()).containsExactly(
                QODER_EXE, "--acp", "--config-dir", configurationDirectory.resolve("config").toString(),
                "--setting-sources", "project", "--strict-mcp-config",
                "--mcp-config", "{\"mcpServers\":{}}");
        assertThat(profile.argv()).noneMatch(argument -> argument.contains(SECRET));
        assertThat(profile.workingDirectory()).isEqualTo(workspace.toString());
        assertThat(profile.env())
                .containsEntry("QODER_PERSONAL_ACCESS_TOKEN", SECRET)
                .containsEntry("HOME", configurationDirectory.resolve("home").toString())
                .containsEntry("USERPROFILE", configurationDirectory.resolve("home").toString())
                .containsEntry("TMP", configurationDirectory.resolve("tmp").toString())
                .containsEntry("TEMP", configurationDirectory.resolve("tmp").toString());
        assertThat(profile.toString())
                .isEqualTo("LaunchProfile[redacted]")
                .doesNotContain(SECRET);
        assertThat(Files.isDirectory(configurationDirectory.resolve("home"))).isTrue();
        assertThat(Files.isDirectory(configurationDirectory.resolve("tmp"))).isTrue();
        assertThat(Files.isDirectory(configurationDirectory.resolve("config"))).isTrue();
    }

    @Test
    void environmentIsExplicitlyAllowlistedAndPersonalHomeIsNotInherited() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path configurationDirectory = tempDir.resolve("run-config");
        ExecutionSpec spec = spec("qoder", workspace);

        LaunchProfile profile = service.prepare(spec,
                environment(spec.runId(), workspace, configurationDirectory, 9301),
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET)));

        Set<String> permitted = new TreeSet<>(ALLOWED_SYSTEM_VARIABLES);
        permitted.addAll(Set.of("HOME", "USERPROFILE", "TMP", "TEMP", "QODER_PERSONAL_ACCESS_TOKEN"));
        assertThat(profile.env().keySet()).isSubsetOf(permitted);

        for (String name : List.of("HOME", "USERPROFILE")) {
            String ambient = System.getenv(name);
            if (ambient != null) {
                assertThat(profile.env().get(name))
                        .as("personal %s must not be inherited", name)
                        .isNotEqualTo(ambient);
            }
        }

        // An ambient variable that is neither allowlisted nor computed never reaches the child.
        Optional<String> nonAllowlisted = System.getenv().keySet().stream()
                .filter(name -> !permitted.contains(name))
                .findFirst();
        assertThat(nonAllowlisted)
                .as("ambient test environment must carry at least one non-allowlisted variable")
                .isPresent();
        assertThat(profile.env()).doesNotContainKey(nonAllowlisted.get());
        assertThat(profile.env()).doesNotContainKey("NODE_OPTIONS");
    }

    @Test
    void hostileRepositoryConfigurationNeitherChangesTheProfileNorItsOwnBytes() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path hostileMcp = workspace.resolve(".mcp.json");
        Files.writeString(hostileMcp,
                "{\"mcpServers\":{\"hostile-marker\":{\"url\":\"http://127.0.0.1:58880/mcp\"}}}");
        Path hostileSettings = Files.createDirectories(workspace.resolve(".qoder")).resolve("settings.json");
        Files.writeString(hostileSettings, "{\"permissions\":{\"allow\":[\"*\"]}}");
        Path hostileOpencode = workspace.resolve("opencode.json");
        Files.writeString(hostileOpencode, "{\"permission\":\"allow\"}");
        Map<String, String> filesBefore = workspaceFiles(workspace);
        Path configurationDirectory = tempDir.resolve("run-config");
        ExecutionSpec spec = spec("qoder", workspace);

        LaunchProfile profile = service.prepare(spec,
                environment(spec.runId(), workspace, configurationDirectory, 9301),
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET)));

        // No hostile marker reaches argv, the environment or a generated file.
        assertThat(String.join(" ", profile.argv())).doesNotContain("hostile-marker");
        assertThat(profile.env().values()).noneMatch(value -> value.contains("hostile-marker"));
        assertThat(profile.env().values()).noneMatch(value -> value.contains("58880"));
        assertThat(generatedFiles(configurationDirectory)).isEmpty();
        // The user repository is neither rewritten nor extended.
        assertThat(workspaceFiles(workspace)).isEqualTo(filesBefore);
    }

    @Test
    void forbiddenCredentialEnvironmentVariablesAreRefused() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path configurationDirectory = tempDir.resolve("run-config");
        ExecutionSpec spec = spec("qoder", workspace);
        PreparedEnvironment environment =
                environment(spec.runId(), workspace, configurationDirectory, 9301);

        assertThatThrownBy(() -> service.prepare(spec, environment, new SecretBundle("qoder:operator",
                Map.of("QODER_AGENT_SDK_ENTRYPOINT", "hostile"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QODER_AGENT_SDK_ENTRYPOINT");
        assertThatThrownBy(() -> service.prepare(spec, environment, new SecretBundle("qoder:operator",
                Map.of("NODE_OPTIONS", "--require=hostile.js"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NODE_OPTIONS");
        assertThatThrownBy(() -> service.prepare(spec, environment, new SecretBundle("qoder:operator",
                Map.of("DEEPSEEK_API_KEY", "unneeded"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void configurationDirectoryInsideTheUserWorkspaceIsRefused() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("qoder", workspace);
        PreparedEnvironment insideWorkspace =
                environment(spec.runId(), workspace, workspace.resolve(".aria-run"), 9301);

        assertThatThrownBy(() -> service.prepare(spec, insideWorkspace,
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace");
    }

    @Test
    void workspaceInsideTheConfigurationDirectoryIsRefused() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("qoder", workspace);
        // The configuration directory is an ancestor of the workspace: generated run
        // state would sit above (and would have to travel through) user content.
        PreparedEnvironment ancestorConfiguration =
                environment(spec.runId(), workspace, tempDir, 9301);

        assertThatThrownBy(() -> service.prepare(spec, ancestorConfiguration,
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace");
    }

    @Test
    void configurationDirectoryAliasedIntoTheWorkspaceIsRefused() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path alias = tempDir.resolve("workspace-alias");
        createDirectoryAlias(alias, workspace);
        ExecutionSpec spec = spec("qoder", workspace);
        // A not-yet-existing directory below an alias of the workspace: the spelled
        // path is outside, the canonical path is inside, and generated state would
        // land in the user repository.
        PreparedEnvironment aliased = environment(spec.runId(), workspace,
                alias.resolve("run-config"), 9301);

        try {
            assertThatThrownBy(() -> service.prepare(spec, aliased,
                    new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside the workspace");
            assertThat(Files.exists(workspace.resolve("run-config"))).isFalse();
            assertThat(Files.exists(workspace.resolve("home"))).isFalse();
            assertThat(Files.exists(workspace.resolve("tmp"))).isFalse();
            assertThat(Files.exists(workspace.resolve("config"))).isFalse();
        } finally {
            // The junction must not outlive the test: JUnit's temp-directory cleanup
            // descends into it and cannot delete the tree.
            deleteDirectoryAlias(alias);
        }
    }

    @Test
    void unconfiguredExecutableFailsClearly() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("qoder", workspace);
        PreparedEnvironment environment =
                environment(spec.runId(), workspace, tempDir.resolve("run-config"), 9301);
        CoreConfigurationService unconfigured =
                new CoreConfigurationService("", OPENCODE_EXE, OPENCODE_PROVIDER_CREDENTIALS);

        assertThatThrownBy(() -> unconfigured.prepare(spec, environment,
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aria.cores.qoder.executable");
    }

    @Test
    void unknownCoreFailsClearly() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("langchain", workspace);

        assertThatThrownBy(() -> service.prepare(spec,
                environment(spec.runId(), workspace, tempDir.resolve("run-config"), 9301),
                new SecretBundle("langchain:operator", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown core: langchain");
    }

    // ------------------------------------------- launch allowlist (derived policy)

    @Test
    void defaultLaunchAllowlistCarriesNoCredentialShapedOrSdkVariableName() {
        // The policy is executable/runtime lookup only: neither an SDK-entrypoint
        // name nor a credential-shaped name may be inherited from the ambient
        // environment by default.
        assertThat(CoreConfigurationService.DEFAULT_ALLOWED_SYSTEM_VARIABLES)
                .isNotEmpty()
                .noneMatch(CoreConfigurationService::isForbiddenLaunchVariable);
    }

    @Test
    void configuredAllowlistOverrideWidensTheInheritedEnvironment() throws IOException {
        Map.Entry<String, String> ambient = System.getenv().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("="))
                .filter(entry -> !ALLOWED_SYSTEM_VARIABLES.contains(entry.getKey()))
                .filter(entry -> !CoreConfigurationService.isForbiddenLaunchVariable(entry.getKey()))
                .findFirst()
                .orElseThrow();
        List<String> widened = new ArrayList<>(ALLOWED_SYSTEM_VARIABLES);
        widened.add(ambient.getKey());
        CoreConfigurationService widenedService = new CoreConfigurationService(
                QODER_EXE, OPENCODE_EXE, OPENCODE_PROVIDER_CREDENTIALS, widened);
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("qoder", workspace);

        LaunchProfile profile = widenedService.prepare(spec,
                environment(spec.runId(), workspace, tempDir.resolve("run-config"), 9301),
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET)));

        assertThat(profile.env()).containsEntry(ambient.getKey(), ambient.getValue());
    }

    @Test
    void allowlistOverrideCannotAdmitForbiddenOrCredentialShapedNames() {
        for (String name : List.of("NODE_OPTIONS", "QODER_AGENT_SDK_ENTRYPOINT",
                "QODER_PERSONAL_ACCESS_TOKEN", "DEEPSEEK_API_KEY", "SOME_PASSWORD")) {
            assertThatThrownBy(() -> new CoreConfigurationService(QODER_EXE, OPENCODE_EXE,
                    OPENCODE_PROVIDER_CREDENTIALS, List.of("PATH", name)))
                    .as("launch allowlist override must not admit %s", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name);
        }
    }

    @Test
    void allowlistOverrideRefusesCaseVariantsAndCapabilityInjectionVectors() {
        // The ambient lookup is case-insensitive on Windows (System.getenv("pAtH")
        // resolves the ambient PATH), so an exact-case comparison would let
        // "node_options" re-admit the ambient NODE_OPTIONS -- the process-injection
        // variable this policy exists to block. The same loop covers the
        // capability/injection vectors the credential-shaped heuristic cannot see
        // (SSH_AUTH_SOCK, GIT_ASKPASS, KUBECONFIG, DOCKER_HOST).
        for (String name : List.of("node_options", "Node_Options", "ssh_auth_sock",
                "git_askpass", "kubeconfig", "docker_host", "qodercli_runtime_packaging")) {
            assertThatThrownBy(() -> new CoreConfigurationService(QODER_EXE, OPENCODE_EXE,
                    OPENCODE_PROVIDER_CREDENTIALS, List.of("PATH", name)))
                    .as("launch allowlist override must not admit %s", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name);
        }
    }

    @Test
    void stalePathWithAMissingFilesystemRootIsRefusedClearly() throws IOException {
        Path missingRoot = firstMissingDriveRoot();
        if (missingRoot == null) {
            // Only a Windows drive letter can denote a filesystem root that does
            // not exist; every POSIX path ascends to "/", which always exists.
            return;
        }
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("qoder", workspace);
        Path staleConfigurationDirectory = missingRoot.resolve("config");
        PreparedEnvironment environment =
                environment(spec.runId(), workspace, staleConfigurationDirectory, 9301);

        assertThatThrownBy(() -> service.prepare(spec, environment,
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining(missingRoot.toString());
    }

    // ------------------------------------------------------------------ opencode profile

    @Test
    void opencodeProfileUsesTheRecordedServeInvocationAndExistingProviderCredential() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path configurationDirectory = tempDir.resolve("run-config");
        ExecutionSpec spec = spec("opencode", workspace);
        PreparedEnvironment environment =
                environment(spec.runId(), workspace, configurationDirectory, 9301);

        LaunchProfile profile = service.prepare(spec, environment,
                new SecretBundle("opencode:model-provider", Map.of("DEEPSEEK_API_KEY", "run-owned-key")));

        assertThat(profile.argv()).containsExactly(
                OPENCODE_EXE, "serve", "--port", "9301", "--hostname", "127.0.0.1");
        assertThat(profile.workingDirectory()).isEqualTo(workspace.toString());
        assertThat(profile.env())
                .containsEntry("XDG_CONFIG_HOME", configurationDirectory.resolve("config").toString())
                .containsEntry("XDG_DATA_HOME", configurationDirectory.resolve("data").toString())
                .containsEntry("XDG_CACHE_HOME", configurationDirectory.resolve("cache").toString())
                .containsEntry("HOME", configurationDirectory.resolve("home").toString())
                .containsEntry("DEEPSEEK_API_KEY", "run-owned-key");
        assertThat(Files.isDirectory(configurationDirectory.resolve("data"))).isTrue();
        assertThat(Files.isDirectory(configurationDirectory.resolve("cache"))).isTrue();
    }

    @Test
    void qoderCredentialIsNeverInjectedIntoAnOpenCodeChild() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        ExecutionSpec spec = spec("opencode", workspace);

        assertThatThrownBy(() -> service.prepare(spec,
                environment(spec.runId(), workspace, tempDir.resolve("run-config"), 9301),
                new SecretBundle("qoder:operator", Map.of("QODER_PERSONAL_ACCESS_TOKEN", SECRET))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QODER_PERSONAL_ACCESS_TOKEN");
    }

    // ------------------------------------------------------------------ helpers

    private ExecutionSpec spec(String coreId, Path workspace) {
        var settings = new AgentExecutionSettings(coreId, ExecutionMode.HOST,
                WorkspaceMode.DIRECT, workspace.toString(), null);
        return new ExecutionSpec(UUID.randomUUID(), UUID.randomUUID(), coreId,
                ExecutionMode.HOST, settings, coreId + ":operator", "rev-1",
                Instant.parse("2026-09-23T12:45:00Z"));
    }

    private static PreparedEnvironment environment(UUID runId, Path workspace,
            Path configurationDirectory, int port) {
        return new PreparedEnvironment(runId, ExecutionMode.HOST, "env-" + runId,
                workspace.toString(), configurationDirectory.toString(),
                URI.create("http://127.0.0.1:" + port));
    }

    /** All regular files under the run configuration directory, after prepare. */
    private static Set<String> generatedFiles(Path configurationDirectory) throws IOException {
        if (!Files.isDirectory(configurationDirectory)) {
            return Set.of();
        }
        return workspaceFiles(configurationDirectory).keySet();
    }

    /**
     * A Windows drive root without a filesystem (the stale {@code Z:\config}
     * shape), or {@code null} when no such root can exist (non-Windows, where
     * every absolute path ascends to the always-existing {@code /}).
     */
    private static Path firstMissingDriveRoot() {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return null;
        }
        for (char letter = 'Z'; letter >= 'A'; letter--) {
            Path root = Path.of(letter + ":\\");
            if (!Files.exists(root)) {
                return root;
            }
        }
        return null;
    }

    /** Directory alias used to prove canonical confinement: a junction on Windows, a symbolic link elsewhere. */
    private static void createDirectoryAlias(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).as("mklink /J timed out").isTrue();
            assertThat(p.exitValue()).as("mklink /J failed: %s", out).isZero();
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /** Removes the alias entry itself, so JUnit's temp-directory cleanup can empty the tree. */
    private static void deleteDirectoryAlias(Path link) throws IOException {
        try {
            Files.deleteIfExists(link);
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            // Only reachable when the refusal under test did not happen: the assertion
            // failure is the real signal, so the leftovers stay for JUnit's cleanup.
        }
    }

    /** Relative path -> content of every regular file below the root. */
    private static Map<String, String> workspaceFiles(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'),
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
