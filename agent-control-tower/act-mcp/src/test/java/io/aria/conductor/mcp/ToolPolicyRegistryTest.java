package io.aria.conductor.mcp;

import io.aria.conductor.common.security.ActorPrincipal;
import io.aria.conductor.mcp.tools.ToolPolicyRegistry;
import io.aria.conductor.mcp.tools.ToolPolicyRegistry.ToolPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reviewed tool policy (spec §6.1): the classification is explicit — never a
 * naming heuristic — every tool the embedded endpoint registers is covered, an
 * unclassified name stays GATED, and only explicitly classified read-only tools
 * may be allowed automatically.
 */
class ToolPolicyRegistryTest {

    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();

    private static final ActorPrincipal OPERATOR = ActorPrincipal.operator(null);
    private static final ActorPrincipal WORKER =
            ActorPrincipal.worker(java.util.UUID.fromString("00000000-0000-0000-0000-000000000601"),
                    Instant.parse("2026-09-22T12:10:00Z"));

    @Test
    void everyRegisteredMcpToolIsExplicitlyClassified() {
        assertThat(registry.classifiedToolNames())
                .containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES);
    }

    @Test
    void anUnknownToolNameStaysGated() {
        assertThat(registry.policyOf("not_a_registered_tool")).isEqualTo(ToolPolicy.GATED);
        assertThat(registry.policyOf(null)).isEqualTo(ToolPolicy.GATED);
        assertThat(registry.isAutomaticallyAllowed("not_a_registered_tool")).isFalse();
        assertThat(registry.policyOf("listaz_agent")).isEqualTo(ToolPolicy.GATED);
    }

    @Test
    void onlyExplicitlyClassifiedReadOnlyToolsAreAutomaticallyAllowed() {
        assertThat(registry.isAutomaticallyAllowed("list_runs")).isTrue();
        assertThat(registry.isAutomaticallyAllowed("get_dashboard_summary")).isTrue();
        assertThat(registry.isAutomaticallyAllowed("housekeeping_scan")).isTrue();
        assertThat(registry.isAutomaticallyAllowed("run_agent")).isFalse();
        assertThat(registry.isAutomaticallyAllowed("store_knowledge")).isFalse();
        assertThat(registry.isAutomaticallyAllowed("decide_approval")).isFalse();
        assertThat(registry.isAutomaticallyAllowed("not_a_registered_tool")).isFalse();
    }

    @Test
    void operatorOnlyClassificationIsExact() {
        assertThat(registry.classifiedToolNames().stream()
                .filter(name -> registry.policyOf(name) == ToolPolicy.OPERATOR_ONLY))
                .containsExactlyInAnyOrder("decide_approval", "retire_agent", "housekeeping_execute",
                        "create_llm_provider", "update_llm_provider", "delete_llm_provider",
                        "activate_llm_provider", "test_llm_provider");
    }

    @Test
    void aWorkerIsRefusedOnAnOperatorOnlyToolAndTheOperatorIsNot() {
        assertThatThrownBy(() -> registry.requireAuthority("decide_approval", WORKER))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Operator authority required");

        registry.requireAuthority("decide_approval", OPERATOR);
        assertThat(registry.requiresOperator("decide_approval")).isTrue();
    }

    @Test
    void readAndWriteToolsAcceptAWorkerAndAGatedToolRefuses() {
        registry.requireAuthority("list_runs", WORKER);
        registry.requireAuthority("run_agent", WORKER);
        registry.requireAuthority("run_agent", OPERATOR);

        assertThatThrownBy(() -> registry.requireAuthority("not_a_registered_tool", WORKER))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Tool 'not_a_registered_tool' is not classified by the reviewed tool policy; unknown tools stay gated");
    }
}
