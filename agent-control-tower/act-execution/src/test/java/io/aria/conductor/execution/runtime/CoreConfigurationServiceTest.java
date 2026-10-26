package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour tests for the launch-environment policy of
 * {@link CoreConfigurationService}: the validated OS allowlist override (which
 * uses the credential-shaped-name predicate the run-owned core log shares) and
 * the launcher-owned collision guard. The launch profiles themselves are built,
 * and tested, by the core adapters.
 */
class CoreConfigurationServiceTest {

    // ------------------------------------------------------------------ brief step 1 guard

    @Test
    void launchObjectsNeverRevealTheirSecretEnvironmentInToString() {
        var bundle = new SecretBundle("qoder:test", java.util.Map.of("RUNTIME_SECRET", "synthetic-secret-42"));
        assertThat(bundle.toString()).isEqualTo("SecretBundle[redacted]");
        assertThat(bundle.environment().get("RUNTIME_SECRET")).isEqualTo("synthetic-secret-42");
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
    void allowlistOverrideCannotAdmitForbiddenOrCredentialShapedNames() {
        for (String name : List.of("NODE_OPTIONS", "QODER_AGENT_SDK_ENTRYPOINT",
                "QODER_PERSONAL_ACCESS_TOKEN", "DEEPSEEK_API_KEY", "SOME_PASSWORD",
                "DEEPSEEK_AUTH")) {
            assertThatThrownBy(() -> new CoreConfigurationService(List.of("PATH", name)))
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
            assertThatThrownBy(() -> new CoreConfigurationService(List.of("PATH", name)))
                    .as("launch allowlist override must not admit %s", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name);
        }
    }

    /**
     * The collision P2: {@code opencode.sandbox-env} is an operator map that
     * reaches the real HOST launch environment, so a key that names a variable the
     * launcher owns would silently override the run-owned root or the run bearer.
     * It is refused loudly where the map is merged, naming the variable -- a
     * silent override is how a credential under {@code HOME} was written verbatim.
     */
    @Test
    void anOpencodeSandboxEnvKeyTheLauncherOwnsIsRefusedAtTheMerge() {
        for (String name : List.of("HOME", "USERPROFILE", "TMP", "TEMP",
                "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "ARIA_MCP_TOKEN",
                // The ambient lookup is case-insensitive on Windows, so a lowercase or
                // mixed-case spelling overrides the launcher's own variable just as well.
                "home", "Home", "XDG_config_home", "aria_mcp_token")) {
            assertThatThrownBy(() -> CoreRuntimeConfiguration.openCodeModelProviderEnvironment(
                    Map.of(name, "synthetic-operator-value")))
                    .as("opencode.sandbox-env must not set %s", name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(name)
                    .hasMessageContaining("opencode.sandbox-env")
                    .hasMessageContaining("launcher owns");
        }
        assertThat(CoreRuntimeConfiguration.openCodeModelProviderEnvironment(
                Map.of("DEEPSEEK_API_KEY", "test-key")))
                .as("positive control: a non-colliding provider key is still merged")
                .containsEntry("DEEPSEEK_API_KEY", "test-key");
    }

    /**
     * A malformed operator map can carry a null key ({@code Map.of} cannot, an
     * operator-built map can). The collision guard is where the merge is refused,
     * so it must name the defect instead of failing inside its own sort with a
     * bare {@link NullPointerException} that says nothing about the configuration.
     */
    @Test
    void aNullVariableNameIsRefusedWithAnActionableMessage() {
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put(null, "synthetic-operator-value");

        assertThatThrownBy(() -> CoreConfigurationService.requireNoLauncherOwnedVariables(
                malformed, "opencode.sandbox-env"))
                .as("a null key must be reported as a malformed configuration, not as an NPE")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null variable name")
                .hasMessageContaining("opencode.sandbox-env");
    }

    /**
     * The production wiring validates the configured override at construction (so
     * a malformed one fails startup) and accepts a blank one.
     */
    @Test
    void theProductionConstructorAcceptsABlankOverrideAndRefusesAMalformedOne() {
        assertThat(new CoreConfigurationService("")).isNotNull();
        assertThatThrownBy(() -> new CoreConfigurationService("PATH,DEEPSEEK_AUTH"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_AUTH");
    }
}
