package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.git.GitTokenSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour tests for the run-owned launch environment of a HOST core (F10/F12/
 * F13/F14/F20): the effective PATH the core's shell resolves tools through, the
 * git credential injected from the resolved pack credential, the ceiling that
 * keeps a bare {@code git} in a run scratch inside the run, and the long-path
 * config a Windows clone needs. Every value is built, never inherited: the launch
 * clears the process environment, so what is not built here is absent in the core.
 */
class CoreLaunchEnvironmentTest {

    private static final String WINDOWS_SEPARATOR = ";";

    private static CoreLaunchEnvironment windows(String hostPath, List<String> extraDirectories,
            GitTokenSource gitTokens) {
        return CoreLaunchEnvironment.forHost(hostPath, WINDOWS_SEPARATOR, extraDirectories, gitTokens, true);
    }

    // -------------------------------------------------------------- effective PATH

    /**
     * F13/F10: the host PATH reaches the core's shell, and each tool directory is
     * rendered in the form that shell resolves. Git Bash splits PATH on ':', so a
     * native {@code C:/Program Files/GitHub CLI} entry is torn into {@code C} and
     * {@code /Program Files/GitHub CLI} and never resolves; the MSYS spelling
     * {@code /c/Program Files/GitHub CLI} does. Both input spellings are accepted.
     */
    @Test
    void theEffectivePathRendersWindowsToolDirectoriesInPosixFormForTheCoresShell() {
        String path = CoreLaunchEnvironment.effectivePath(
                "C:\\Windows\\system32;C:\\Program Files\\GitHub CLI;C:\\Users\\User\\tools\\apache-maven-3.9.6\\bin",
                WINDOWS_SEPARATOR,
                List.of("C:/Users/User/tools/apache-maven-3.9.6/bin", "/usr/bin"),
                CoreLaunchEnvironment.PathStyle.POSIX);

        assertThat(path.split(":")).containsExactly(
                "/c/Windows/system32",
                "/c/Program Files/GitHub CLI",
                "/c/Users/User/tools/apache-maven-3.9.6/bin",
                "/usr/bin");
    }

    /** The same builder renders the native Windows spelling, for a non-POSIX child. */
    @Test
    void theEffectivePathCanRenderNativeWindowsSpellings() {
        String path = CoreLaunchEnvironment.effectivePath(
                "C:\\Windows\\system32",
                WINDOWS_SEPARATOR,
                List.of("/c/Users/User/tools/apache-maven-3.9.6/bin"),
                CoreLaunchEnvironment.PathStyle.NATIVE, ";");

        assertThat(path.split(";")).containsExactly(
                "C:\\Windows\\system32",
                "C:\\Users\\User\\tools\\apache-maven-3.9.6\\bin");
    }

    /** Blank entries are dropped and one directory appears once, whatever its spelling. */
    @Test
    void theEffectivePathDropsBlanksAndDuplicates() {
        String path = CoreLaunchEnvironment.effectivePath(
                "C:\\bin;;C:/bin;   ;/usr/bin",
                WINDOWS_SEPARATOR,
                List.of("c:/BIN"),
                CoreLaunchEnvironment.PathStyle.POSIX);

        assertThat(path).isEqualTo("/c/bin:/usr/bin");
    }

    /** A non-Windows host keeps the POSIX ordering and the ':' separator it already had. */
    @Test
    void aPosixHostPathIsPassedThroughWithoutDriveTranslation() {
        String path = CoreLaunchEnvironment.effectivePath(
                "/usr/local/bin:/usr/bin", ":", List.of("/opt/tools/bin"),
                CoreLaunchEnvironment.PathStyle.NATIVE, ":");

        assertThat(path).isEqualTo("/usr/local/bin:/usr/bin:/opt/tools/bin");
    }

    // ------------------------------------------------------------- bridge environment

    /**
     * The bridge process receives the effective PATH, because the committed bridge
     * builds the CLI child's environment from its own PATH only (an explicit
     * allowlist). Delivering it here is what makes the tool directories visible in
     * the core's shell.
     */
    @Test
    void theBridgeEnvironmentCarriesTheEffectivePath() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32",
                List.of("C:/Program Files/GitHub CLI"), null);

        assertThat(environment.bridgeEnvironment())
                .containsEntry("PATH", "/c/Windows/system32:/c/Program Files/GitHub CLI");
    }

    // ----------------------------------------------------------- git credential (F14)

    /**
     * F14: the core receives the credential the backend resolved from the git pack,
     * so a stored-and-valid token is what its tools use. The launch environment
     * never forwards an ambient value: with no resolved credential, the variables
     * are absent rather than present-and-wrong.
     */
    @Test
    void theChildEnvironmentCarriesTheResolvedGitCredentialAsBothTokenNames() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32", List.of(),
                () -> "ghp_resolved_pack_credential");

        Map<String, String> child = environment.childEnvironment("C:\\runs\\scratch\\run-1");

        assertThat(child)
                .containsEntry("GH_TOKEN", "ghp_resolved_pack_credential")
                .containsEntry("GITHUB_TOKEN", "ghp_resolved_pack_credential");
        assertThat(environment.secretValues())
                .as("the value is declared a secret so the run-owned core log masks it")
                .containsExactly("ghp_resolved_pack_credential");
    }

    @Test
    void anUnresolvedGitCredentialAddsNoTokenVariable() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32", List.of(), () -> null);

        Map<String, String> child = environment.childEnvironment("C:\\runs\\scratch\\run-1");

        assertThat(child).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN");
        assertThat(environment.secretValues()).isEmpty();
    }

    // -------------------------------------------------------------- git isolation (F20)

    /**
     * F20: a bare {@code git} in a run scratch used to ascend into the operator's
     * checkout. The ceiling is the run's own parent, so git stops inside the run
     * workspace tree instead of discovering the operator repository above it.
     */
    @Test
    void theChildEnvironmentCeilingsGitAtTheRunWorkspaceParent() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32", List.of(), null);

        Map<String, String> child = environment.childEnvironment("C:\\runs\\scratch\\5b249b0f-run");

        assertThat(child).containsEntry("GIT_CEILING_DIRECTORIES", "/c/runs/scratch");
    }

    /** A worktree workspace still has its own {@code .git}: the ceiling stays above it. */
    @Test
    void theCeilingNeverPointsInsideTheWorkspaceItself() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32", List.of(), null);

        Map<String, String> child = environment.childEnvironment("C:\\runs\\worktrees\\run-1");

        assertThat(child).containsEntry("GIT_CEILING_DIRECTORIES", "/c/runs/worktrees");
        assertThat(child.get("GIT_CEILING_DIRECTORIES")).doesNotContain("run-1");
    }

    // ---------------------------------------------------------------- long paths (F12)

    /**
     * F12: a Windows clone fails with {@code Filename too long} unless git has
     * {@code core.longpaths=true}. Delivering it through the documented
     * {@code GIT_CONFIG_COUNT/KEY/VALUE} environment leaves the operator's own git
     * configuration untouched and applies to every git command of the run.
     */
    @Test
    void aWindowsLaunchEnablesLongPathsThroughTheEnvironment() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32", List.of(), null);

        Map<String, String> child = environment.childEnvironment("C:\\runs\\scratch\\run-1");

        assertThat(child)
                .containsEntry("GIT_CONFIG_COUNT", "1")
                .containsEntry("GIT_CONFIG_KEY_0", "core.longpaths")
                .containsEntry("GIT_CONFIG_VALUE_0", "true");
    }

    @Test
    void aPosixLaunchDoesNotSetTheWindowsLongPathConfig() {
        CoreLaunchEnvironment environment = CoreLaunchEnvironment.forHost(
                "/usr/local/bin:/usr/bin", ":", List.of(), null, false);

        Map<String, String> child = environment.childEnvironment("/runs/scratch/run-1");

        assertThat(child).doesNotContainKeys("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0");
        assertThat(child).containsEntry("GIT_CEILING_DIRECTORIES", "/runs/scratch");
    }

    /**
     * F13b (live 6dfb895c): the launcher clears the process environment and the
     * bridge's allowlist carries only PATH/SystemRoot. Without TEMP/TMP Java uses
     * {@code C:\WINDOWS} as {@code java.io.tmpdir} and Maven's surefire fork dies
     * with "Unable to create temporary directory C:\WINDOWS\surefire-User", so the
     * DEV and QA agents could not run the project's tests.
     */
    @Test
    void aWindowsLaunchCarriesThePlatformTempDirectoriesToTheChild() {
        CoreLaunchEnvironment environment = CoreLaunchEnvironment.forHost(
                "C:\\Windows\\system32", WINDOWS_SEPARATOR, List.of(), null, true,
                Map.of("TEMP", "C:\\Users\\User\\AppData\\Local\\Temp",
                        "TMP", "C:\\Users\\User\\AppData\\Local\\Temp"));

        Map<String, String> child = environment.childEnvironment("C:\\runs\\scratch\\run-1");

        assertThat(child)
                .containsEntry("TEMP", "C:\\Users\\User\\AppData\\Local\\Temp")
                .containsEntry("TMP", "C:\\Users\\User\\AppData\\Local\\Temp");
        assertThat(environment.bridgeEnvironment())
                .as("the temp directories belong to the CLI child, not the bridge")
                .doesNotContainKeys("TEMP", "TMP");
    }

    @Test
    void aPosixLaunchDoesNotCarryWindowsTempVariables() {
        CoreLaunchEnvironment environment = CoreLaunchEnvironment.forHost(
                "/usr/local/bin:/usr/bin", ":", List.of(), null, false,
                Map.of("TEMP", "/tmp", "TMP", "/tmp"));

        assertThat(environment.childEnvironment("/runs/scratch/run-1"))
                .doesNotContainKeys("TEMP", "TMP");
    }

    // ------------------------------------------------------- extra-path cross-lane seam

    /**
     * The cross-lane launch contract: the bridge reads
     * {@code ARIA_CORE_EXTRA_PATH} from ITS OWN environment and appends it to the
     * CLI child's PATH without dropping what it already has. Publishing it on the
     * child was inert (the bridge only reads its own process environment), so the
     * variable belongs to the bridge environment and must not reach the child.
     */
    @Test
    void configuredToolDirectoriesArePublishedOnTheBridgeProcessNotTheChild() {
        CoreLaunchEnvironment environment = windows("C:\\Windows\\system32",
                List.of("C:/Program Files/GitHub CLI"), null);

        assertThat(environment.bridgeEnvironment())
                .containsEntry("ARIA_CORE_EXTRA_PATH", "/c/Program Files/GitHub CLI")
                .containsEntry("PATH", "/c/Windows/system32:/c/Program Files/GitHub CLI");
        assertThat(environment.childEnvironment("C:\\runs\\scratch\\run-1"))
                .doesNotContainKey("ARIA_CORE_EXTRA_PATH");
    }

    /** The configured extra directories (F10/F13) are a comma-separated operator value. */
    @Test
    void configuredToolchainDirectoriesAreParsedAsACommaSeparatedList() {
        assertThat(CoreLaunchEnvironment.parseDirectories(
                " C:/Program Files/GitHub CLI , /usr/bin,,C:\\Users\\User\\tools "))
                .containsExactly("C:/Program Files/GitHub CLI", "/usr/bin", "C:\\Users\\User\\tools");
        assertThat(CoreLaunchEnvironment.parseDirectories("  ")).isEmpty();
        assertThat(CoreLaunchEnvironment.parseDirectories(null)).isEmpty();
    }
}
