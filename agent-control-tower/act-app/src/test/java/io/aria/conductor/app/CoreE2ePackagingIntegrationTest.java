package io.aria.conductor.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Packaged-boundary test of the deterministic core E2E distribution (Task 16).
 *
 * <p>Two artifacts are checked as they ship:
 * <ul>
 *   <li>the production {@code backend-jar} must stay fixture-free: no harness
 *       class, no test-support class and no mock peer may be packaged into it;</li>
 *   <li>the {@code backend-e2e-harness} ZIP must carry exactly the distribution
 *       the launcher runs on: {@code app/} (production classes and resources),
 *       {@code harness/} (the dedicated e2e harness classes plus the selected
 *       test-support classes and nothing else from the test tree; both class
 *       sets are pinned exactly, so a leaked test class or a stale staging entry
 *       fails the build instead of being shipped), {@code lib/} (runtime
 *       dependency jars), {@code peers/} (the committed mock protocol
 *       executables plus their scenario manifest) and {@code bridge/} (the built
 *       Qoder ACP bridge).</li>
 * </ul>
 *
 * <p>Both paths are supplied by the {@code core-e2e-harness} profile's Failsafe
 * system properties, which is also the profile that builds the ZIP; without the
 * profile the test fails on the missing properties instead of passing on absent
 * artifacts.
 */
class CoreE2ePackagingIntegrationTest {

    /** The production jar and the harness ZIP, both built by the same verify run. */
    private static Path propertyPath(String property) {
        String value = Objects.requireNonNull(System.getProperty(property),
                property + " must be provided by the core-e2e-harness profile's Failsafe system properties");
        Path path = Path.of(value);
        assertThat(Files.isRegularFile(path))
                .as("%s (%s) must exist; the packaged distribution was not built", property, path)
                .isTrue();
        return path;
    }

    @Test
    void productionJarCarriesNoHarnessOrFixtureEntries() throws IOException {
        Path productionJar = propertyPath("e2e.productionJar");
        try (var jar = new JarFile(productionJar.toFile())) {
            List<String> names = jar.stream().map(ZipEntry::getName).toList();
            // The exact production-boundary predicate of the task brief.
            assertThat(names)
                    .noneMatch(name -> name.contains("/e2e/")
                            || name.contains("act-test-support")
                            || name.contains("mock-qoder"));
            // It is the Spring Boot production application, not an empty or foreign jar.
            assertThat(names).contains(
                    "BOOT-INF/classes/io/aria/conductor/ActApplication.class",
                    "BOOT-INF/classes/application.yml",
                    "BOOT-INF/classes/application-h2.yml");
            // And it carries no other harness-owned asset.
            assertThat(names).noneMatch(name -> name.contains("mock-opencode")
                    || name.contains("peer-actions")
                    || name.contains("scenarios.json")
                    || name.contains("bridge/main.js")
                    || name.contains("CoreE2e"));
        }
    }

    @Test
    void harnessZipCarriesTheDeterministicDistribution() throws IOException {
        Path harnessZip = propertyPath("e2e.harnessZip");
        try (var zip = new ZipFile(harnessZip.toFile())) {
            List<String> names = zip.stream().map(ZipEntry::getName).toList();
            // Directory entries of the ZIP end with '/'; every content check below is exact
            // about the files that ship.
            List<String> files = names.stream().filter(name -> !name.endsWith("/")).toList();

            // The distribution has exactly five top-level trees plus the peer scenario manifest.
            assertThat(files).allMatch(name -> name.equals("scenarios.json")
                    || name.startsWith("app/") || name.startsWith("harness/")
                    || name.startsWith("lib/") || name.startsWith("peers/")
                    || name.startsWith("bridge/"));

            // app/: the production classpath (classes and resources of act-app).
            assertThat(files).contains(
                    "app/io/aria/conductor/ActApplication.class",
                    "app/application.yml",
                    "app/application-h2.yml",
                    "app/db/migration/V62__acp_permission_correlation.sql");
            assertThat(files).noneMatch(name -> name.startsWith("app/") && name.contains("e2e"));

            // harness/: exactly the dedicated e2e package plus the selected support classes.
            assertThat(files).contains(
                    "harness/io/aria/conductor/app/e2e/CoreE2eApplication.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eConfiguration.class",
                    "harness/io/aria/conductor/app/e2e/DeterministicLlmClient.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eSetup.class");
            List<String> harnessEntries = files.stream()
                    .filter(name -> name.startsWith("harness/"))
                    .toList();
            assertThat(harnessEntries)
                    .as("harness/ must only carry the e2e package and the selected support classes")
                    .allMatch(name -> name.startsWith("harness/io/aria/conductor/app/e2e/")
                            || name.startsWith("harness/io/aria/conductor/test/"));
            assertThat(harnessEntries).contains(
                    "harness/io/aria/conductor/test/MockLlmClient.class",
                    "harness/io/aria/conductor/test/TestDataBuilder.class");
            // Exact absence: act-app integration tests, harness-irrelevant bases and slice
            // helpers must not leak into the distribution.
            assertThat(harnessEntries).noneMatch(name -> name.contains("IntegrationTest")
                    || name.contains("NoopLlmTestConfig")
                    || name.contains("BaseH2IntegrationTest")
                    || name.contains("IntegrationTestBase")
                    || name.contains("DataJpaTestBase")
                    || name.contains("WebMvcTestBase")
                    || name.contains("JpaSliceConfig")
                    || name.contains("AssertionHelpers"));

            // Exact pin (fix round 1): the launcher package ships exactly these class
            // files. The descriptor lists the launcher classes individually instead of
            // globbing the package, so a future JUnit-bearing class in that package
            // cannot reach this JUnit-less classpath; this set makes any drift -- a
            // broadened descriptor include or a stale class under harness/ -- a build
            // failure instead of a silently augmented distribution.
            List<String> harnessLauncherEntries = harnessEntries.stream()
                    .filter(name -> name.startsWith("harness/io/aria/conductor/app/e2e/"))
                    .toList();
            assertThat(harnessLauncherEntries).containsExactlyInAnyOrder(
                    "harness/io/aria/conductor/app/e2e/CoreE2eApplication.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eConfiguration.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eConfiguration$CoreE2eSettings.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eConfiguration$SandboxTransport.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eSetup.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eSetup$SetupRefusal.class",
                    "harness/io/aria/conductor/app/e2e/DeterministicLlmClient.class",
                    // Task 19 peer-launch wiring.
                    "harness/io/aria/conductor/app/e2e/CoreE2eController.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eController$ScenarioSelection.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eScenarios.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eWorkerTokens.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eProcessBackend.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eProcessBackend$State.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eOpenCodeAdapter.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eOpenCodeAdapter$PeerSession.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eQoderAdapter.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eQoderAdapter$SuspendableSession.class",
                    // Task 19 fix round 1: the bounded authenticated endpoint
                    // readiness wait the adapters run before opening a session.
                    "harness/io/aria/conductor/app/e2e/CoreE2eEndpointReadiness.class",
                    "harness/io/aria/conductor/app/e2e/CoreE2eEndpointReadiness$Probe.class");

            // The exact pin above is a hand list, so on its own it cannot notice a
            // class the descriptor forgot: both lists would simply be missing it
            // (fix round 3 shipped the package without CoreE2eQoderAdapter's new
            // inner class that way, and the harness died at run time with
            // NoClassDefFoundError). Every built non-test class of the package must
            // therefore ship; a new type or inner class is a build failure until the
            // descriptor pins it.
            List<String> builtHarnessClasses;
            try (var harnessClassFiles = Files.list(
                    Path.of("target", "test-classes", "io", "aria", "conductor", "app", "e2e"))) {
                builtHarnessClasses = harnessClassFiles.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".class") && !name.contains("Test"))
                        .map(name -> "harness/io/aria/conductor/app/e2e/" + name)
                        .sorted()
                        .toList();
            }
            assertThat(builtHarnessClasses).isNotEmpty();
            assertThat(harnessLauncherEntries)
                    .as("every built non-test harness class ships in the distribution")
                    .containsAll(builtHarnessClasses);

            // Exact pin: the support classes are exactly the explicitly unpacked
            // framework-free helpers -- a stale or broadened unpack shows up here.
            List<String> harnessSupportEntries = harnessEntries.stream()
                    .filter(name -> name.startsWith("harness/io/aria/conductor/test/"))
                    .toList();
            assertThat(harnessSupportEntries).containsExactlyInAnyOrder(
                    "harness/io/aria/conductor/test/MockLlmClient.class",
                    "harness/io/aria/conductor/test/TestDataBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$AgentBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$AgentSessionBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$ApprovalBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$KnowledgeItemBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$LlmProviderBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$PackCredentialBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$PromptCallBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$RunBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$ScheduledJobBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$SystemConfigBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$ToolCallBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$ToolDefinitionBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$ToolPackBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$WorkflowChainBuilder.class",
                    "harness/io/aria/conductor/test/TestDataBuilder$WorkflowStepBuilder.class");

            // lib/: the runtime dependency jars (module jars and production third-party jars).
            assertThat(files).contains(
                    "lib/act-common-0.1.0-SNAPSHOT.jar",
                    "lib/act-execution-0.1.0-SNAPSHOT.jar",
                    "lib/spring-boot-3.4.5.jar",
                    "lib/h2-2.3.232.jar");
            List<String> libEntries = files.stream()
                    .filter(name -> name.startsWith("lib/"))
                    .toList();
            assertThat(libEntries).allMatch(name -> name.endsWith(".jar"));
            // Exact absence: test-scope libraries and the test-support jar itself.
            assertThat(libEntries).noneMatch(name -> name.contains("act-test-support")
                    || name.contains("junit")
                    || name.contains("assertj")
                    || name.contains("mockito")
                    || name.contains("wiremock")
                    || name.contains("archunit")
                    || name.contains("spring-boot-starter-test"));

            // peers/: the committed mock protocol executables and their manifest.
            assertThat(files).contains(
                    "peers/mock-qoder.mjs",
                    "peers/mock-opencode.mjs",
                    "peers/peer-actions.mjs",
                    "scenarios.json");
            assertThat(files).noneMatch(name -> name.startsWith("peers/") && name.endsWith(".test.mjs"));

            // bridge/: the built Qoder ACP bridge module tree.
            assertThat(files).contains(
                    "bridge/main.js",
                    "bridge/acp-client.js",
                    "bridge/events.js",
                    "bridge/permissions.js",
                    "bridge/server.js");
            assertThat(files).noneMatch(name -> name.startsWith("bridge/")
                    && !name.endsWith(".js"));
        }
    }
}
