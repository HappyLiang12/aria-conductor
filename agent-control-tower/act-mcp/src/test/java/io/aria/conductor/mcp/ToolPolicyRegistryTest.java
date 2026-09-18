package io.aria.conductor.mcp;

import io.aria.conductor.mcp.tools.McpTool;
import io.aria.conductor.mcp.tools.ToolPolicyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 ruling 7: the tool policy registry is an explicit, reviewed map with no
 * fallbacks by accident. This test reconciles it in BOTH directions with the
 * actual {@code @Tool} methods of every {@link McpTool} bean package and with
 * the curated {@link McpToolInventory}, so a new tool cannot appear without a
 * deliberate category decision and a removal cannot leave a stale policy.
 *
 * <p>The full 57-row table (category + citation) is the review artifact in
 * task-C4-report.md; here the pinned sets freeze it against silent edits.
 */
class ToolPolicyRegistryTest {

    private static final Set<String> WORKER_READ = Set.of(
            "list_knowledge", "query_knowledge", "list_kanban_items", "get_dod_status",
            "list_reports", "get_run", "get_agent");

    private static final Set<String> WORKER_WRITE = Set.of(
            "store_knowledge", "review_knowledge", "create_kanban_item", "update_kanban_item",
            "transition_kanban_item", "init_dod", "submit_dod_review", "generate_report");

    /** Denials the coordinator pinned explicitly (brief: "Pinned OPERATOR_ONLY denials"). */
    private static final Set<String> PINNED_OPERATOR_ONLY = Set.of(
            "decide_approval",
            "list_llm_providers", "get_llm_provider", "create_llm_provider", "update_llm_provider",
            "delete_llm_provider", "activate_llm_provider", "test_llm_provider",
            "create_agent", "update_agent", "retire_agent",
            "run_agent", "pause_run", "resume_run", "cancel_run",
            "housekeeping_scan", "housekeeping_execute",
            "toggle_skill", "assign_skill", "unassign_skill");

    private final ToolPolicyRegistry registry = new ToolPolicyRegistry();

    @Test
    void policyMap_coversExactlyTheCuratedInventory_bothDirections() {
        assertThat(registry.policies().keySet())
                .containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES);
        assertThat(registry.policies()).hasSize(57);
    }

    @Test
    void policyMap_coversExactlyTheLiveToolMethods_bothDirections() throws Exception {
        Set<String> scanned = scannedToolNames();

        assertThat(scanned).hasSize(57);
        assertThat(scanned).containsExactlyInAnyOrderElementsOf(McpToolInventory.EXPECTED_TOOL_NAMES);
        assertThat(registry.policies().keySet()).containsExactlyInAnyOrderElementsOf(scanned);
    }

    @Test
    void categories_areExactlyTheFrozenWorkerSets_andEverythingElseIsOperatorOnly() {
        Map<String, ToolPolicyRegistry.Category> categories = new TreeMap<>();
        registry.policies().forEach((name, policy) -> categories.put(name, policy.category()));

        assertThat(namesOf(categories, ToolPolicyRegistry.Category.WORKER_READ))
                .containsExactlyInAnyOrderElementsOf(WORKER_READ);
        assertThat(namesOf(categories, ToolPolicyRegistry.Category.WORKER_WRITE))
                .containsExactlyInAnyOrderElementsOf(WORKER_WRITE);
        assertThat(ToolPolicyRegistry.Category.values()).hasSize(3)
                .containsExactly(ToolPolicyRegistry.Category.WORKER_READ,
                        ToolPolicyRegistry.Category.WORKER_WRITE,
                        ToolPolicyRegistry.Category.OPERATOR_ONLY);
    }

    @Test
    void pinnedDenials_areOperatorOnly() {
        for (String pinned : PINNED_OPERATOR_ONLY) {
            assertThat(registry.lookup(pinned))
                    .as("pinned operator-only tool %s must have a policy", pinned)
                    .isPresent();
            assertThat(registry.lookup(pinned).orElseThrow().category())
                    .as("pinned operator-only tool %s", pinned)
                    .isEqualTo(ToolPolicyRegistry.Category.OPERATOR_ONLY);
        }
    }

    @Test
    void everyWorkerAllowedEntry_carriesAConcreteInRepoCitation() {
        registry.policies().forEach((name, policy) -> {
            if (policy.category() != ToolPolicyRegistry.Category.OPERATOR_ONLY) {
                assertThat(policy.citation())
                        .as("worker-allowed tool %s must cite a consumer at file:line", name)
                        .isNotBlank()
                        .matches(".*[A-Za-z0-9_.-]+\\.(java|sql|md|ts):\\d+.*");
            }
        });
        // Operator-only rows still carry a rationale, so the map is reviewable end to end.
        registry.policies().values().forEach(policy ->
                assertThat(policy.citation()).as("policy %s needs a rationale", policy.toolName()).isNotBlank());
    }

    @Test
    void scopeParams_declareTheInvocationArgument_andOnlyGetRunAndGetAgentCarryOne() throws Exception {
        Map<String, Method> methods = scannedToolMethods();

        assertThat(registry.lookup("get_run").orElseThrow())
                .satisfies(policy -> {
                    assertThat(policy.scopeKind()).isEqualTo(ToolPolicyRegistry.ScopeKind.RUN);
                    assertThat(policy.scopeParam()).isEqualTo("id");
                });
        assertThat(registry.lookup("get_agent").orElseThrow())
                .satisfies(policy -> {
                    assertThat(policy.scopeKind()).isEqualTo(ToolPolicyRegistry.ScopeKind.AGENT);
                    assertThat(policy.scopeParam()).isEqualTo("id");
                });

        Set<String> scoped = new TreeSet<>();
        registry.policies().forEach((name, policy) -> {
            if (policy.scopeParam() != null) {
                scoped.add(name);
                assertThat(policy.scopeKind())
                        .as("tool %s declares a scope param but no scope kind", name)
                        .isNotNull();
                Method method = methods.get(name);
                assertThat(method).as("scoped tool %s must be a real @Tool method", name).isNotNull();
                Set<String> parameterNames = new LinkedHashSet<>();
                for (Parameter parameter : method.getParameters()) {
                    parameterNames.add(parameter.getName());
                }
                assertThat(parameterNames)
                        .as("scope param %s of %s must be a real parameter", policy.scopeParam(), name)
                        .contains(policy.scopeParam());
            } else {
                assertThat(policy.scopeKind())
                        .as("tool %s declares a scope kind without a scope param", name)
                        .isNull();
            }
        });
        assertThat(scoped).containsExactlyInAnyOrder("get_run", "get_agent");
    }

    @Test
    void noCredentialChangingOrQuestionAnsweringToolExistsInActMcp() throws Exception {
        Set<String> names = scannedToolNames();

        assertThat(names)
                .as("no act-mcp tool may expose credential material (B7/B8 own the credential API)")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("credential"));
        assertThat(names)
                .as("no act-mcp tool may answer an approval question; decide_approval is operator-only")
                .noneMatch(name -> name.matches(".*(answer|respond|submit_approval|approve_|reject_).*"));

        // Anything whose name mentions approvals must be operator-only, including decide_approval.
        registry.policies().forEach((name, policy) -> {
            if (name.contains("approval")) {
                assertThat(policy.category())
                        .as("approval tool %s must be operator-only", name)
                        .isEqualTo(ToolPolicyRegistry.Category.OPERATOR_ONLY);
            }
        });
    }

    @Test
    void unknownTool_hasNoPolicy() {
        assertThat(registry.lookup("not_a_real_tool")).isEmpty();
        assertThat(registry.lookup(null)).isEmpty();
        assertThat(registry.lookup("")).isEmpty();
    }

    /** Live @Tool method names of every McpTool bean class in this module. */
    private static Set<String> scannedToolNames() throws Exception {
        return scannedToolMethods().keySet();
    }

    /** Live @Tool method name -> method, across the whole io.aria.conductor.mcp.tools package. */
    private static Map<String, Method> scannedToolMethods() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(McpTool.class));
        Map<String, Method> methods = new TreeMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("io.aria.conductor.mcp.tools")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    methods.put(tool.name(), method);
                }
            }
        }
        return methods;
    }

    private static Set<String> namesOf(Map<String, ToolPolicyRegistry.Category> categories,
                                       ToolPolicyRegistry.Category wanted) {
        Set<String> names = new TreeSet<>();
        categories.forEach((name, category) -> {
            if (category == wanted) {
                names.add(name);
            }
        });
        return names;
    }
}
