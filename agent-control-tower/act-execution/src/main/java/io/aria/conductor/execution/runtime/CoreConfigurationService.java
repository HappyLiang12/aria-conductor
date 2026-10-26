package io.aria.conductor.execution.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared launch-environment policy of the run-owned cores: the
 * credential-shaped-name predicate, the names a launch allowlist may never
 * admit, and the refusal of an operator map that would set a variable the
 * launcher owns.
 *
 * <p>{@link #isCredentialShapedName(String)} is the one name rule the run-owned
 * core log uses as defence in depth, in addition to the values a
 * {@code LaunchProfile} declares secret, so the two sides cannot drift apart.
 * {@link #requireNoLauncherOwnedVariables(Map, String)} is called where an
 * operator's {@code opencode.sandbox-env} is merged into a launch environment
 * and again in each core adapter before it copies a profile environment into a
 * launch: overriding the run-owned {@code HOME}/TMP/{@code XDG_*} roots or the
 * run bearer is either evidence loss or a credential-smuggling vector.
 *
 * <p>The OS allowlist is a <em>derived</em> policy, not a captured one, and it
 * remains unverified against the real CLI: the configured override
 * ({@code aria.cores.launch-system-variables}, comma-separated names; it
 * replaces the derived default; blank means the default) is validated at
 * construction, and that validation is the list's whole role today. An override
 * may only name executable/runtime lookup variables: a name from the recorded
 * scrub list, {@code NODE_OPTIONS}, a capability or process-injection vector
 * ({@code SSH_AUTH_SOCK}, {@code GIT_ASKPASS}, {@code KUBECONFIG},
 * {@code DOCKER_HOST}) or any credential-shaped name
 * (TOKEN/SECRET/PASSWORD/CREDENTIAL/{@code _KEY}/APIKEY/{@code _AUTH}) is
 * refused loudly instead of being silently dropped. The refusal is
 * case-insensitive, because the ambient lookup it guards is: on Windows
 * {@code System.getenv("node_options")} resolves the ambient
 * {@code NODE_OPTIONS}, so an exact-case comparison would re-admit exactly the
 * variable this policy exists to block.
 *
 * <p>The allowlist is not the source of any launch environment. The core
 * adapters build the environments they launch -- {@code OpenCodeCoreAdapter}
 * merges the reviewed provider environment ({@code opencode.sandbox-env}) and
 * adds the run-owned XDG roots, {@code QoderCoreAdapter} launches the reviewed
 * bridge environment -- so the list validates the configuration surface, not
 * the child.
 */
@Service
public class CoreConfigurationService {

    /**
     * The default OS allowlist (DERIVED, not captured from Task 1): the minimum
     * executable/runtime lookup surface a Node/CLI child needs, with no HOME, no
     * APP_DATA, no credential-shaped name and nothing this process's operator has
     * personalized. Overridable via {@code aria.cores.launch-system-variables};
     * any override is still validated against {@link #isForbiddenLaunchVariable}.
     */
    public static final List<String> DEFAULT_ALLOWED_SYSTEM_VARIABLES = List.of(
            "PATH", "PATHEXT", "SystemRoot", "SystemDrive", "windir", "COMSPEC",
            "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS", "LANG", "LC_ALL", "TZ");

    /** The run-owned HOME root the launcher always sets (never the operator's). */
    public static final String HOME_VARIABLE = "HOME";
    /** The Windows spelling of the run-owned HOME root the launcher always sets. */
    public static final String USERPROFILE_VARIABLE = "USERPROFILE";
    /** The run-owned temporary root the launcher always sets. */
    public static final String TMP_VARIABLE = "TMP";
    /** The second spelling of the run-owned temporary root the launcher always sets. */
    public static final String TEMP_VARIABLE = "TEMP";
    /** The run-owned OpenCode config root the launcher always sets. */
    public static final String XDG_CONFIG_HOME_VARIABLE = "XDG_CONFIG_HOME";
    /** The run-owned OpenCode data root the launcher always sets. */
    public static final String XDG_DATA_HOME_VARIABLE = "XDG_DATA_HOME";
    /** The run-owned OpenCode cache root the launcher always sets. */
    public static final String XDG_CACHE_HOME_VARIABLE = "XDG_CACHE_HOME";
    /** The run-scoped platform-MCP bearer the OpenCode adapter always sets when wired. */
    public static final String ARIA_MCP_TOKEN_VARIABLE = "ARIA_MCP_TOKEN";

    /**
     * The names the launcher itself owns in a launch environment. An operator's
     * {@code opencode.sandbox-env} may not carry one of these: the launcher either
     * sets it to a run-owned root or withholds it on purpose, and a silent
     * override is how a credential placed under {@code HOME} reached the core log
     * verbatim. The collision is refused where the map is merged, never warned.
     */
    public static final Set<String> LAUNCHER_OWNED_ENVIRONMENT_NAMES = Set.of(
            HOME_VARIABLE, USERPROFILE_VARIABLE, TMP_VARIABLE, TEMP_VARIABLE,
            XDG_CONFIG_HOME_VARIABLE, XDG_DATA_HOME_VARIABLE, XDG_CACHE_HOME_VARIABLE,
            ARIA_MCP_TOKEN_VARIABLE);

    /**
     * SDK-entrypoint, process-injection and capability variable names the launch
     * allowlist may never admit: the recorded scrub list of the capability
     * evidence plus {@code NODE_OPTIONS} and the injection/capability vectors the
     * credential-shaped heuristic cannot see ({@code SSH_AUTH_SOCK},
     * {@code GIT_ASKPASS}, {@code KUBECONFIG}, {@code DOCKER_HOST}).
     */
    static final Set<String> FORBIDDEN_ENVIRONMENT_VARIABLES = Set.of(
            "QODERCLI_RUNTIME_PACKAGING",
            "QODER_AGENT_SDK_ENTRYPOINT",
            "QODER_AGENT_SDK_VERSION",
            "QODER_SDK_AUTH_PAYLOAD_FILE",
            "QODER_SESSION_TYPE",
            "QODER_WORKER_CWD",
            "QODER_WORKER_RUNTIME_ASSET_ROOT",
            "NODE_OPTIONS",
            "SSH_AUTH_SOCK",
            "GIT_ASKPASS",
            "KUBECONFIG",
            "DOCKER_HOST");

    /**
     * Production wiring: the allowlist is the derived default unless
     * {@code aria.cores.launch-system-variables} replaces it (comma-separated).
     * A malformed override fails the construction, so it fails startup.
     */
    @Autowired
    public CoreConfigurationService(
            @Value("${aria.cores.launch-system-variables:}") String launchSystemVariables) {
        this(parseAllowedSystemVariables(launchSystemVariables));
    }

    /** Test/override seam: an explicit OS allowlist, validated like the configured one. */
    public CoreConfigurationService(List<String> allowedSystemVariables) {
        validateAllowedSystemVariables(allowedSystemVariables);
    }

    // ------------------------------------------------------ launch environment policy

    /**
     * Parses the configured allowlist override: blank keeps the derived default,
     * otherwise the comma-separated names replace it (trimmed, blanks dropped,
     * duplicates collapsed). The result is validated by the constructor.
     */
    private static List<String> parseAllowedSystemVariables(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ALLOWED_SYSTEM_VARIABLES;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String candidate : configured.split(",")) {
            String name = candidate.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * True for a name no launch allowlist may ever admit: the recorded
     * SDK-entrypoint/process-injection scrub list, the capability vectors the
     * credential heuristic misses, and credential-shaped names.
     *
     * <p>The forbidden-list comparison is case-insensitive, because the ambient
     * lookup it guards is: on Windows {@code System.getenv("node_options")}
     * resolves the ambient {@code NODE_OPTIONS}, so an exact-case comparison
     * would re-admit exactly the variable this method exists to refuse.
     */
    static boolean isForbiddenLaunchVariable(String name) {
        if (name == null || name.isBlank() || isForbiddenEnvironmentVariable(name)) {
            return true;
        }
        return isCredentialShapedName(name);
    }

    /**
     * The credential-shaped name rule shared with the run-owned core log: a value
     * under one of these names is a credential whatever its length. It is defence
     * in depth -- the log's primary rule is by value -- but both sides must mean
     * the same shape, so there is one predicate and no second list to drift.
     */
    public static boolean isCredentialShapedName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")
                || upper.contains("CREDENTIAL") || upper.endsWith("_KEY") || upper.endsWith("APIKEY")
                // A provider "auth" key (DEEPSEEK_AUTH, *_AUTH) is a credential in this
                // product's configuration surface; the suffix is narrow so an author
                // name or an authorization header field is not swept in.
                || upper.endsWith("_AUTH");
    }

    /**
     * Refuses a map that would set a variable the launcher owns. Called at the
     * point an operator's {@code opencode.sandbox-env} is merged into a launch
     * environment -- where the configuration is merged, and again in each core
     * adapter before it copies a profile environment into a launch: a hard block,
     * because silently overriding the launcher's root or bearer is either
     * evidence loss or a credential-smuggling vector.
     *
     * @param environment the map about to be merged
     * @param source the operator-facing name of that map, quoted in the error
     * @throws IllegalStateException naming the variable and the launcher, or
     *         naming the source and the defect when the map carries a null key
     *         (a value without a name cannot be merged, and a later merge would
     *         fail without saying why)
     */
    public static void requireNoLauncherOwnedVariables(Map<String, String> environment,
            String source) {
        if (environment == null || environment.isEmpty()) {
            return;
        }
        Set<String> names = new TreeSet<>();
        for (String name : environment.keySet()) {
            if (name == null) {
                throw new IllegalStateException(source + " must not carry a null variable name: a"
                        + " value without a name cannot be merged into a launch environment, and a"
                        + " later merge would fail without naming this configuration error. Remove the"
                        + " null entry from " + source + ".");
            }
            names.add(name);
        }
        for (String name : names) {
            if (LAUNCHER_OWNED_ENVIRONMENT_NAMES.contains(name.toUpperCase(Locale.ROOT))) {
                throw new IllegalStateException(source + " must not set \"" + name
                        + "\": the launcher owns this variable (it sets the run-owned"
                        + " HOME/TMP/XDG roots or the run bearer itself), so an operator value"
                        + " here would silently override the launch environment. Remove " + name
                        + " from " + source + ".");
            }
        }
    }

    /** Case-insensitive membership: the ambient lookup is case-insensitive on Windows. */
    private static boolean isForbiddenEnvironmentVariable(String name) {
        return FORBIDDEN_ENVIRONMENT_VARIABLES.stream().anyMatch(name::equalsIgnoreCase);
    }

    /** Refuses an allowlist that tries to admit a forbidden name, loudly at construction. */
    private static List<String> validateAllowedSystemVariables(List<String> names) {
        List<String> refused = names.stream()
                .filter(CoreConfigurationService::isForbiddenLaunchVariable)
                .toList();
        if (!refused.isEmpty()) {
            throw new IllegalStateException("Launch environment allowlist"
                    + " (aria.cores.launch-system-variables) must not carry"
                    + " credential-shaped or SDK-entrypoint/process-injection names: " + refused);
        }
        return List.copyOf(names);
    }
}
