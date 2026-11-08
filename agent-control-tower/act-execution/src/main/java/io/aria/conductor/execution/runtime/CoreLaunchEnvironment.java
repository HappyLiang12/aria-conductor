package io.aria.conductor.execution.runtime;

import io.aria.conductor.execution.git.GitTokenSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The run-owned launch environment of a HOST core: the effective {@code PATH} the
 * core's shell resolves tools through (F10/F13), the git credential injected from
 * the resolved git-pack credential (F14), the {@code GIT_CEILING_DIRECTORIES}
 * isolation of a run scratch (F20) and the Windows long-path git config (F12).
 *
 * <p>Everything here is <em>built</em>, never inherited: the host launcher clears
 * the process environment before it applies a launch profile
 * ({@code OwnedProcessController.launch}), and the committed bridge builds its CLI
 * child environment from an explicit allowlist (PATH/SystemRoot plus declared
 * {@code --child-env} pairs). A value that is not produced here is therefore
 * absent in the core -- which is why an ambient, invalid {@code GH_TOKEN} cannot
 * reach it and why a missing tool directory is a real gap instead of a mystery.
 *
 * <p>PATH rendering follows the shell that resolves it. The core's Bash tool is a
 * POSIX shell even on Windows, and that shell splits PATH on {@code :}: a native
 * {@code C:/Program Files/GitHub CLI} entry tears into {@code C} and
 * {@code /Program Files/GitHub CLI} and never resolves, while the MSYS spelling
 * {@code /c/Program Files/GitHub CLI} does. A Windows launch therefore renders the
 * effective PATH in POSIX form; a POSIX launch keeps the native {@code :}
 * separated form it already had. Both spellings are accepted as input, and a
 * directory appears once.
 *
 * <p>The git credential is resolved on every launch through the single
 * {@link GitTokenSource} the pack wiring exposes (the same path
 * {@code GitBranchService} uses), so a credential stored after startup is what
 * the next run's core receives. It is delivered as both {@code GH_TOKEN} and
 * {@code GITHUB_TOKEN} -- the names the GitHub CLI and the documented prompts use
 * -- and declared a secret value of the launch so the run-owned core log masks it.
 */
public final class CoreLaunchEnvironment {

    /** The variable the child resolves executables through. */
    public static final String PATH_VARIABLE = "PATH";
    /** The GitHub CLI's token variable (the name the SDD prompts read). */
    public static final String GH_TOKEN_VARIABLE = "GH_TOKEN";
    /** The canonical GitHub token variable. */
    public static final String GITHUB_TOKEN_VARIABLE = "GITHUB_TOKEN";
    /** Git's repository-search ceiling (F20). */
    public static final String GIT_CEILING_DIRECTORIES_VARIABLE = "GIT_CEILING_DIRECTORIES";
    /** The documented git config-through-environment pair count (F12). */
    public static final String GIT_CONFIG_COUNT_VARIABLE = "GIT_CONFIG_COUNT";
    /** The documented git config key-through-environment name, entry 0. */
    public static final String GIT_CONFIG_KEY_0_VARIABLE = "GIT_CONFIG_KEY_0";
    /** The documented git config value-through-environment name, entry 0. */
    public static final String GIT_CONFIG_VALUE_0_VARIABLE = "GIT_CONFIG_VALUE_0";
    /** Cross-lane launch contract: an extra PATH entry the bridge appends to the CLI child. */
    public static final String ARIA_CORE_EXTRA_PATH_VARIABLE = "ARIA_CORE_EXTRA_PATH";

    private static final Pattern WINDOWS_DRIVE =
            Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");
    private static final Pattern MSYS_DRIVE =
            Pattern.compile("^/([A-Za-z])(?:/(.*))?$");

    /** How a directory is spelled in a PATH string. */
    public enum PathStyle {
        /** The host's native spelling ({@code C:\...} on Windows, {@code /...} elsewhere). */
        NATIVE,
        /** The POSIX spelling a Git Bash/MSYS shell resolves ({@code /c/...}). */
        POSIX
    }

    private final String hostPath;
    private final String hostPathSeparator;
    private final List<String> toolchainDirectories;
    private final GitTokenSource gitTokens;
    private final boolean windows;
    private final boolean enabled;
    /** Platform variables the CLI child needs but the bridge's allowlist cannot know (Windows TEMP/TMP). */
    private final Map<String, String> platformEnvironment;

    private CoreLaunchEnvironment(String hostPath, String hostPathSeparator,
            List<String> toolchainDirectories, GitTokenSource gitTokens, boolean windows,
            boolean enabled, Map<String, String> platformEnvironment) {
        this.hostPath = hostPath == null ? "" : hostPath;
        this.hostPathSeparator = hostPathSeparator == null || hostPathSeparator.isBlank()
                ? ":" : hostPathSeparator;
        this.toolchainDirectories = toolchainDirectories == null ? List.of() : List.copyOf(toolchainDirectories);
        this.gitTokens = gitTokens;
        this.windows = windows;
        this.enabled = enabled;
        this.platformEnvironment = platformEnvironment == null ? Map.of() : Map.copyOf(platformEnvironment);
    }

    /** A launch environment that adds nothing (a directly constructed adapter profile). */
    public static CoreLaunchEnvironment disabled() {
        return new CoreLaunchEnvironment("", ":", List.of(), null, false, false, Map.of());
    }

    /**
     * Parses the configured extra toolchain directories
     * ({@code aria.cores.toolchain-path}): comma-separated, trimmed, blanks dropped,
     * duplicates collapsed. A configured value may use either spelling of a
     * Windows directory; rendering happens later, per launch.
     */
    public static List<String> parseDirectories(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        Set<String> directories = new LinkedHashSet<>();
        for (String candidate : configured.split(",")) {
            String directory = candidate.trim();
            if (!directory.isEmpty()) {
                directories.add(directory);
            }
        }
        return List.copyOf(directories);
    }

    /** The environment of this process: its PATH, the platform separator and the host OS. */
    public static CoreLaunchEnvironment forCurrentProcess(List<String> toolchainDirectories,
            GitTokenSource gitTokens) {
        return forHost(System.getenv(PATH_VARIABLE), java.io.File.pathSeparator,
                toolchainDirectories, gitTokens, isWindows(), currentPlatformEnvironment());
    }

    /**
     * The platform variables a Windows CLI child needs and the bridge's allowlist
     * cannot derive: {@code TEMP}/{@code TMP}. Java without them falls back to
     * {@code C:\WINDOWS} as {@code java.io.tmpdir}, where Maven's surefire fork
     * cannot create its temp directory ("Unable to create temporary directory
     * C:\WINDOWS\surefire-User"), so an agent could not run the project's tests.
     */
    private static Map<String, String> currentPlatformEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : List.of("TEMP", "TMP")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                environment.put(name, value);
            }
        }
        return environment;
    }

    /** Explicit-input factory (test seam and non-default hosts). */
    public static CoreLaunchEnvironment forHost(String hostPath, String hostPathSeparator,
            List<String> toolchainDirectories, GitTokenSource gitTokens, boolean windows) {
        return forHost(hostPath, hostPathSeparator, toolchainDirectories, gitTokens, windows, Map.of());
    }

    /** Explicit-input factory including the platform variables a Windows child needs. */
    public static CoreLaunchEnvironment forHost(String hostPath, String hostPathSeparator,
            List<String> toolchainDirectories, GitTokenSource gitTokens, boolean windows,
            Map<String, String> platformEnvironment) {
        return new CoreLaunchEnvironment(hostPath, hostPathSeparator, toolchainDirectories, gitTokens,
                windows, true, platformEnvironment);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** The style the core's child shell resolves: POSIX on Windows, native elsewhere. */
    public PathStyle pathStyle() {
        return windows ? PathStyle.POSIX : PathStyle.NATIVE;
    }

    // ------------------------------------------------------------------ PATH building

    /**
     * The effective PATH: the host entries followed by the configured toolchain
     * directories, each rendered in the requested style, blanks dropped and one
     * directory listed once (case-insensitively where the spelling is a Windows
     * drive path, because the ambient lookup is case-insensitive there).
     */
    public static String effectivePath(String hostPath, String hostPathSeparator,
            List<String> extraDirectories, PathStyle style) {
        return effectivePath(hostPath, hostPathSeparator, extraDirectories, style, separatorOf(style));
    }

    /** The effective PATH with an explicit join separator (host-independent test seam). */
    public static String effectivePath(String hostPath, String hostPathSeparator,
            List<String> extraDirectories, PathStyle style, String joinSeparator) {
        String separator = hostPathSeparator == null || hostPathSeparator.isBlank() ? ":" : hostPathSeparator;
        List<String> entries = new ArrayList<>();
        if (hostPath != null) {
            for (String entry : hostPath.split(Pattern.quote(separator), -1)) {
                entries.add(entry);
            }
        }
        if (extraDirectories != null) {
            entries.addAll(extraDirectories);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> rendered = new ArrayList<>();
        for (String entry : entries) {
            String directory = renderDirectory(entry, style);
            if (directory == null) {
                continue;
            }
            String key = isCaseInsensitiveEntry(directory, style) ? directory.toLowerCase(Locale.ROOT) : directory;
            if (seen.add(key)) {
                rendered.add(directory);
            }
        }
        return String.join(joinSeparator, rendered);
    }

    /** Drive paths are case-insensitive on Windows whatever the shell spelling. */
    private static boolean isCaseInsensitiveEntry(String directory, PathStyle style) {
        return style == PathStyle.NATIVE || MSYS_DRIVE.matcher(directory).matches();
    }

    private static String separatorOf(PathStyle style) {
        return style == PathStyle.POSIX ? ":" : java.io.File.pathSeparator;
    }

    /**
     * One directory in the requested style; {@code null} for a blank entry. A
     * drive spelling converts to the MSYS form ({@code C:\a b} -> {@code /c/a b})
     * and back; an entry that is already in the target form is kept.
     */
    public static String renderDirectory(String directory, PathStyle style) {
        if (directory == null) {
            return null;
        }
        String trimmed = directory.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (style == PathStyle.POSIX) {
            Matcher drive = WINDOWS_DRIVE.matcher(trimmed);
            if (drive.matches()) {
                String rest = drive.group(2).replace('\\', '/');
                return "/" + drive.group(1).toLowerCase(Locale.ROOT) + "/" + rest;
            }
            return trimmed;
        }
        Matcher msys = MSYS_DRIVE.matcher(trimmed.replace('\\', '/'));
        if (msys.matches()) {
            String rest = msys.group(2) == null ? "" : msys.group(2).replace('/', '\\');
            return msys.group(1).toUpperCase(Locale.ROOT) + ":\\" + rest;
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ launch values

    /**
     * The bridge process's environment: the effective PATH, which the committed
     * bridge forwards to the CLI child as its base PATH, and the toolchain
     * directories the bridge prepends to that child PATH (F13). The bridge reads
     * {@code ARIA_CORE_EXTRA_PATH} from its own process and never forwards the
     * variable itself; publishing it on the child was inert.
     */
    public Map<String, String> bridgeEnvironment() {
        if (!enabled) {
            return Map.of();
        }
        String path = effectivePath(hostPath, hostPathSeparator, toolchainDirectories, pathStyle());
        Map<String, String> environment = new LinkedHashMap<>();
        if (!path.isBlank()) {
            environment.put(PATH_VARIABLE, path);
        }
        String extraPath = String.join(separatorOf(pathStyle()), renderedToolchainDirectories());
        if (!extraPath.isBlank()) {
            environment.put(ARIA_CORE_EXTRA_PATH_VARIABLE, extraPath);
        }
        return Map.copyOf(environment);
    }

    /**
     * The child (core) environment: the resolved git credential, the git search
     * ceiling of the run workspace and the Windows long-path config. A name is
     * present only when it carries a value: an absent token is absent, not
     * present-and-blank. The token is routed through a run-owned file by the
     * launcher, never through argv.
     */
    public Map<String, String> childEnvironment(String workingDirectory) {
        if (!enabled) {
            return Map.of();
        }
        Map<String, String> environment = new LinkedHashMap<>();
        String token = resolvedGitToken();
        if (token != null && !token.isBlank()) {
            environment.put(GH_TOKEN_VARIABLE, token);
            environment.put(GITHUB_TOKEN_VARIABLE, token);
        }
        String ceiling = gitCeiling(workingDirectory);
        if (ceiling != null) {
            environment.put(GIT_CEILING_DIRECTORIES_VARIABLE, ceiling);
        }
        if (windows) {
            // F12: applies to every git command of this launch, never to the
            // operator's own git configuration.
            environment.put(GIT_CONFIG_COUNT_VARIABLE, "1");
            environment.put(GIT_CONFIG_KEY_0_VARIABLE, "core.longpaths");
            environment.put(GIT_CONFIG_VALUE_0_VARIABLE, "true");
            // F13b: the launcher clears the environment, and the bridge's allowlist
            // carries only PATH/SystemRoot. Without TEMP/TMP Java uses C:\WINDOWS
            // as the temp directory and Maven's surefire fork cannot create it.
            environment.putAll(platformEnvironment);
        }
        return Map.copyOf(environment);
    }

    /** The values this launch delivers as secrets: the git credential, when one resolved. */
    public Set<String> secretValues() {
        String token = resolvedGitToken();
        return token == null || token.isBlank() ? Set.of() : Set.of(token);
    }

    private List<String> renderedToolchainDirectories() {
        List<String> rendered = new ArrayList<>();
        for (String directory : toolchainDirectories) {
            String value = renderDirectory(directory, pathStyle());
            if (value != null) {
                rendered.add(value);
            }
        }
        return rendered;
    }

    private String resolvedGitToken() {
        if (gitTokens == null) {
            return null;
        }
        try {
            return gitTokens.token();
        } catch (RuntimeException e) {
            // An unreadable stored credential is "no credential here": the core then
            // carries none instead of receiving a wrong ambient value, and the run
            // fails visibly at its first authenticated call. The value never logs.
            return null;
        }
    }

    /**
     * The git repository-search ceiling of the run workspace: its parent. A run
     * scratch is created below it, so a bare {@code git} in the scratch stops
     * inside the run's tree instead of ascending into the operator's checkout (F20).
     * A worktree keeps its own {@code .git}, found before the ceiling is reached.
     *
     * <p>The parent is derived from the spelled path, not through
     * {@link Path#toAbsolutePath()}: a POSIX-spelled working directory of a
     * non-Windows placement must not be re-rooted on the drive of the host that
     * merely builds the profile.
     */
    private String gitCeiling(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }
        String parent = parentDirectory(workingDirectory);
        return parent == null ? null : renderDirectory(parent, pathStyle());
    }

    /** The spelled parent of an absolute directory, accepting either separator. */
    public static String parentDirectory(String directory) {
        String normalized = directory.trim().replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        if (slash == 0) {
            return "/";
        }
        String parent = normalized.substring(0, slash);
        // A drive root keeps its separator: "C:" alone is drive-relative, not a directory.
        return parent.matches("^[A-Za-z]:$") ? parent + "/" : parent;
    }
}
