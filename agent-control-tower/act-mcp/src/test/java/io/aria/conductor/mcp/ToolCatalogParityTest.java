package io.aria.conductor.mcp;

import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.context.contributor.ToolCatalogContextContributor;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.mcp.catalog.ToolCatalog;
import io.aria.conductor.mcp.tools.DashboardTools;
import io.aria.conductor.mcp.tools.MemoryTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one tool catalog (spec P7): every registered MCP tool, the reviewed
 * policy, the one-line description and the auto-approve flag live in
 * {@link ToolCatalog}. The inventory, the policy registry and the
 * {@code aria.mcp.auto-approve-read-tools} default all derive from it, so an
 * added tool, a renamed tool or a swapped policy fails this test rather than
 * drifting the prompt.
 */
class ToolCatalogParityTest {

    private final ToolCatalog catalog = new ToolCatalog();

    @Test
    void inventoryIsExactlyTheCatalog() {
        assertThat(McpToolInventory.EXPECTED_TOOL_NAMES)
                .containsExactlyInAnyOrderElementsOf(catalog.names());
    }

    @Test
    void everyToolHasAReviewedPolicyAndTheAutoApproveSetIsReadOnly() {
        assertThat(catalog.tools()).allSatisfy(tool ->
                assertThat(tool.policy()).isNotEqualTo(ToolPolicy.GATED));
        assertThat(new McpProperties().getAutoApproveReadTools())
                .containsExactlyInAnyOrderElementsOf(catalog.autoApproveReadNames());
        assertThat(catalog.tools().stream()
                .filter(ToolDescriptor::autoApproveRead)
                .map(ToolDescriptor::policy))
                .allMatch(policy -> policy == ToolPolicy.WORKER_READ);
    }

    /**
     * Snapshot over every entry, not a sample: exchanging {@code WORKER_READ}
     * and {@code WORKER_WRITE} on any single tool changes this map and fails.
     */
    @Test
    void theFullNameToPolicyMapIsPinned() {
        Map<String, ToolPolicy> expected = new LinkedHashMap<>();
        // workflows
        expected.put("list_workflow_templates", ToolPolicy.WORKER_READ);
        expected.put("instantiate_workflow_template", ToolPolicy.WORKER_WRITE);
        expected.put("get_workflow", ToolPolicy.WORKER_READ);
        // knowledge
        expected.put("list_knowledge", ToolPolicy.WORKER_READ);
        expected.put("store_knowledge", ToolPolicy.WORKER_WRITE);
        expected.put("query_knowledge", ToolPolicy.WORKER_READ);
        expected.put("review_knowledge", ToolPolicy.WORKER_WRITE);
        expected.put("retire_knowledge", ToolPolicy.WORKER_WRITE);
        // memory
        expected.put("remember_fact", ToolPolicy.WORKER_WRITE);
        expected.put("recall_memory", ToolPolicy.WORKER_READ);
        expected.put("list_memory_entities", ToolPolicy.WORKER_READ);
        expected.put("get_memory_entity", ToolPolicy.WORKER_READ);
        expected.put("forget_fact", ToolPolicy.OPERATOR_ONLY);
        expected.put("list_memory_proposals", ToolPolicy.WORKER_READ);
        expected.put("decide_memory_proposal", ToolPolicy.OPERATOR_ONLY);
        expected.put("get_context_manifest", ToolPolicy.WORKER_READ);
        // approvals
        expected.put("list_approvals", ToolPolicy.WORKER_READ);
        expected.put("decide_approval", ToolPolicy.OPERATOR_ONLY);
        // agents
        expected.put("list_agents", ToolPolicy.WORKER_READ);
        expected.put("get_agent", ToolPolicy.WORKER_READ);
        expected.put("create_agent", ToolPolicy.WORKER_WRITE);
        expected.put("update_agent", ToolPolicy.WORKER_WRITE);
        expected.put("retire_agent", ToolPolicy.OPERATOR_ONLY);
        // runs
        expected.put("run_agent", ToolPolicy.WORKER_WRITE);
        expected.put("list_runs", ToolPolicy.WORKER_READ);
        expected.put("list_running_runs", ToolPolicy.WORKER_READ);
        expected.put("get_run", ToolPolicy.WORKER_READ);
        expected.put("pause_run", ToolPolicy.WORKER_WRITE);
        expected.put("resume_run", ToolPolicy.WORKER_WRITE);
        expected.put("cancel_run", ToolPolicy.WORKER_WRITE);
        // kanban
        expected.put("create_kanban_item", ToolPolicy.WORKER_WRITE);
        expected.put("list_kanban_items", ToolPolicy.WORKER_READ);
        expected.put("update_kanban_item", ToolPolicy.WORKER_WRITE);
        expected.put("transition_kanban_item", ToolPolicy.WORKER_WRITE);
        // dashboard
        expected.put("get_dashboard_summary", ToolPolicy.WORKER_READ);
        // definition of done
        expected.put("init_dod", ToolPolicy.WORKER_WRITE);
        expected.put("submit_dod_review", ToolPolicy.WORKER_WRITE);
        expected.put("get_dod_status", ToolPolicy.WORKER_READ);
        // reports
        expected.put("generate_report", ToolPolicy.WORKER_WRITE);
        expected.put("list_reports", ToolPolicy.WORKER_READ);
        expected.put("amend_report", ToolPolicy.WORKER_WRITE);
        // housekeeping / ops
        expected.put("housekeeping_scan", ToolPolicy.WORKER_READ);
        expected.put("housekeeping_execute", ToolPolicy.OPERATOR_ONLY);
        // skills
        expected.put("list_skills", ToolPolicy.WORKER_READ);
        expected.put("get_skill", ToolPolicy.WORKER_READ);
        expected.put("toggle_skill", ToolPolicy.WORKER_WRITE);
        expected.put("list_agent_skills", ToolPolicy.WORKER_READ);
        expected.put("assign_skill", ToolPolicy.WORKER_WRITE);
        expected.put("unassign_skill", ToolPolicy.WORKER_WRITE);
        // llm providers (credential / core-selection management)
        expected.put("list_llm_providers", ToolPolicy.WORKER_READ);
        expected.put("get_llm_provider", ToolPolicy.WORKER_READ);
        expected.put("create_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("update_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("delete_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("activate_llm_provider", ToolPolicy.OPERATOR_ONLY);
        expected.put("test_llm_provider", ToolPolicy.OPERATOR_ONLY);
        // aria assistant surfaces
        expected.put("list_notifications", ToolPolicy.WORKER_READ);
        expected.put("get_unread_notification_count", ToolPolicy.WORKER_READ);
        expected.put("mark_notification_read", ToolPolicy.WORKER_WRITE);
        expected.put("mark_all_notifications_read", ToolPolicy.WORKER_WRITE);
        expected.put("list_scheduled_jobs", ToolPolicy.WORKER_READ);
        expected.put("create_scheduled_job", ToolPolicy.WORKER_WRITE);
        expected.put("cancel_scheduled_job", ToolPolicy.WORKER_WRITE);
        expected.put("get_latest_conversation", ToolPolicy.WORKER_READ);
        expected.put("get_conversation_timeline", ToolPolicy.WORKER_READ);
        // the cores' own read-only web tools, listed explicitly for
        // auto-approval provenance by the 2026-10-04 operator decision (D5)
        expected.put("WebSearch", ToolPolicy.WORKER_READ);
        expected.put("WebFetch", ToolPolicy.WORKER_READ);

        Map<String, ToolPolicy> actual = catalog.tools().stream()
                .collect(Collectors.toMap(ToolDescriptor::name, ToolDescriptor::policy));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** Every catalog entry flagged for auto-approval is a read-only tool. */
    @Test
    void autoApproveReadImpliesWorkerRead() {
        assertThat(catalog.tools())
                .filteredOn(ToolDescriptor::autoApproveRead)
                .isNotEmpty()
                .allSatisfy(tool -> assertThat(tool.policy()).isEqualTo(ToolPolicy.WORKER_READ));
        assertThat(catalog.autoApproveReadNames())
                .containsExactlyInAnyOrderElementsOf(catalog.tools().stream()
                        .filter(ToolDescriptor::autoApproveRead)
                        .map(ToolDescriptor::name)
                        .toList());
    }

    /**
     * The configured default names only tools that exist in the catalog with
     * the reviewed read policy: a name that is not a WORKER_READ tool (a
     * mutating, operator-only or unclassified name) fails here.
     */
    @Test
    void everyConfiguredAutoApproveNameIsACatalogWorkerReadTool() {
        for (String name : new McpProperties().getAutoApproveReadTools()) {
            ToolDescriptor tool = catalog.find(name)
                    .orElseThrow(() -> new AssertionError(
                            "auto-approve entry '" + name + "' is not a catalog tool"));
            assertThat(tool.policy())
                    .as("auto-approve entry '%s'", name)
                    .isEqualTo(ToolPolicy.WORKER_READ);
        }
    }

    /**
     * Decision (2026-11-11 operator amendment, reversing the 2026-11 review): the memory read
     * tools are auto-approved for the Aria assistant's coordinated runs. Memory reads are
     * clamped at INTERNAL and every prompt still records which facts entered it, so the
     * operator keeps auditability without a per-read click. Writes and operator-only tools
     * are unchanged. Pinned so a later tidy-up cannot flip it back silently.
     */
    @Test
    void theMemoryReadToolsAreWorkerReadAndAutoApproved() {
        List<String> memoryReads = List.of("recall_memory", "list_memory_entities", "get_memory_entity",
                "list_memory_proposals", "get_context_manifest");

        assertThat(catalog.autoApproveReadNames()).containsAll(memoryReads);
        assertThat(catalog.tools())
                .filteredOn(tool -> memoryReads.contains(tool.name()))
                .hasSize(memoryReads.size())
                .allSatisfy(tool -> {
                    assertThat(tool.policy()).isEqualTo(ToolPolicy.WORKER_READ);
                    assertThat(tool.autoApproveRead()).isTrue();
                });
        assertThat(catalog.autoApproveReadNames())
                .doesNotContain("remember_fact", "forget_fact", "decide_memory_proposal");
    }

    @Test
    void everyDescriptorCarriesItsOneLineToolDescription() {
        assertThat(catalog.tools()).allSatisfy(tool -> {
            assertThat(tool.description()).as("description of '%s'", tool.name()).isNotBlank();
            assertThat(tool.description()).as("description of '%s'", tool.name()).doesNotContain("\n");
        });
    }

    /**
     * The memory surface is eight tools on top of the 59-descriptor catalog: 65
     * registered names, 67 descriptors including the two core-native web tools.
     */
    @Test
    void theCatalogCoversTheMemorySurface() {
        assertThat(catalog.tools()).hasSize(67);
        assertThat(catalog.names()).hasSize(65);
        assertThat(catalog.tools())
                .filteredOn(tool -> tool.policy() == ToolPolicy.OPERATOR_ONLY)
                .hasSize(10);
    }

    /**
     * The Aria prompt's tool section is generated from this catalog and excludes
     * OPERATOR_ONLY entries: a worker may never approve its own proposal, so
     * {@code decide_memory_proposal} and {@code forget_fact} must be absent while
     * the other six memory tools are visible.
     */
    @Test
    void theGeneratedToolSectionExcludesTheOperatorOnlyMemoryTools() {
        ContextRequest request = new ContextRequest(UUID.randomUUID(), "c", null, ContextAdapter.SYNC, 1,
                "ship it", null, Sensitivity.INTERNAL, 24_000, null, null, List.of());

        List<ContextSection> sections = new ToolCatalogContextContributor(catalog).contribute(request);

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.content())
                    .doesNotContain("decide_memory_proposal")
                    .doesNotContain("forget_fact")
                    .contains("remember_fact (WORKER_WRITE)")
                    .contains("recall_memory (WORKER_READ)")
                    .contains("list_memory_entities (WORKER_READ)")
                    .contains("get_memory_entity (WORKER_READ)")
                    .contains("list_memory_proposals (WORKER_READ)")
                    .contains("get_context_manifest (WORKER_READ)");
            // 67 descriptors minus the 10 operator-only entries.
            assertThat(section.content().lines()).hasSize(57);
            assertThat(section.content().length()).isLessThan(3_000);
            assertThat(section.truncated()).isFalse();
        });
    }

    /**
     * A catalog entry that advertises a tool without saying the platform refuses a worker is the
     * same dishonesty class this program removes. Ten operator-only tools refuse a run-scoped
     * worker credential with FORBIDDEN, so all ten descriptions must say so; dropping one sentence
     * from any entry fails here rather than silently widening what the prompt promises.
     */
    @Test
    void everyOperatorOnlyDescriptionStatesThatAWorkerIsRefused() {
        assertThat(catalog.tools())
                .filteredOn(tool -> tool.policy() == ToolPolicy.OPERATOR_ONLY)
                .hasSize(10)
                .allSatisfy(tool -> assertThat(tool.description())
                        .as("description of '%s'", tool.name())
                        .contains("Operator authority only:")
                        .contains("refused with FORBIDDEN"));
    }

    /**
     * The two entity reads refuse a PENDING view to a worker, so their descriptions must say that
     * PENDING is a review status with an operator-only listing/read rather than leaving the reader
     * with ACTIVE/MERGED/RETIRED as the whole status set. The detail read must also name where the
     * operator does look: the explicit {@code status=PENDING} detail filter.
     */
    @Test
    void theEntityReadDescriptionsStateThePendingOperatorGuard() {
        assertThat(catalog.find("list_memory_entities").orElseThrow().description())
                .contains("status=PENDING")
                .contains("operator authority only")
                .contains("refused with FORBIDDEN");
        assertThat(catalog.find("get_memory_entity").orElseThrow().description())
                .contains("PENDING entity is review material")
                .contains("this agent-facing detail read reports it as not found")
                .contains("the operator reviews it with the explicit status=PENDING detail filter on the Memory page");
    }

    /**
     * {@code remember_fact} now accepts two call shapes: an existing {@code entityId}, or
     * {@code kind} + {@code canonicalKey} for an unknown key. A description that omits the second
     * shape (or implies a worker write) hides the producer Task 14 landed: the call proposes, it does
     * not write.
     * <p>
     * T39 Item 2 (P2): the second shape used to promise "creates a new entity with this first fact
     * in the same transaction", which is true only for a new key. Read against
     * {@code MemoryCommandService.addFactByKey} / {@code createEntity}: an existing ACTIVE or
     * PENDING key attaches the fact to that entity (a PENDING entity takes only a worker's PENDING
     * proposal), a RETIRED or MERGED key is re-proposed to PENDING by a worker's call, and an
     * operator call on a decided key is refused as a conflict. Task 34 had already corrected the
     * Aria prompt to state all three key states, so the description now states them too instead of
     * contradicting the prompt that the model also reads.
     */
    @Test
    void rememberFactDescribesBothShapesAndTheProposalRatherThanWriteRule() {
        assertThat(catalog.find("remember_fact").orElseThrow().description())
                .contains("exactly one of two shapes")
                .contains("(entityId)")
                .contains("(kind, canonicalKey)")
                .as("the new-key case, now scoped to a new key")
                .contains("a new key creates the entity with this first fact in the same transaction")
                .as("the existing-key case the old wording denied")
                .contains("an existing ACTIVE or PENDING key adds the fact to that entity")
                .as("the decided-key case, which only a worker's call re-proposes")
                .contains("a RETIRED or MERGED key is re-proposed to PENDING by a worker's call")
                .as("and the operator call on a decided key, which is refused")
                .contains("an operator call on a decided key is refused as a conflict")
                .contains("PENDING extraction proposal that an operator must approve");
    }

    /**
     * The {@code remember_fact} wording debt (T14 review): the description promised the two
     * shapes and the proposal rule but never said that one operator decision promotes the entity
     * and its first fact together. The sentence is asserted on BOTH copies — the registered
     * {@code @Tool} annotation and the catalog mirror — because byte parity alone would let the
     * sentence be deleted from both sides at once.
     */
    @Test
    void rememberFactStatesThatOneEntityDecisionPromotesTheEntityAndItsFirstFact() {
        String sentence = "One operator decision on the entity proposal promotes the new entity "
                + "and its first fact together.";

        assertThat(catalog.find("remember_fact").orElseThrow().description())
                .as("catalog mirror")
                .contains(sentence);
        assertThat(registeredRememberFactDescription())
                .as("registered @Tool annotation")
                .contains(sentence);
    }

    /**
     * T29 Item 5 (P3) corrected the actor half of this clause; T34 Item 3 (P3) corrects the status
     * half. The entity branch of {@code MemoryProposalService.listProposals} excludes only a PENDING
     * entity row for a non-operator, so with {@code status=ACTIVE} or {@code status=RETIRED} a
     * worker does receive entity rows — "a worker read receives facts and relations only" was false
     * for two of the three statuses. The sentence now states both halves, and it is asserted on
     * BOTH copies: byte parity alone would let it be deleted from both sides at once. The
     * behavioural half is pinned in
     * {@code MemoryToolsTest#aWorkerListOfActiveProposalsKeepsTheEntityRow}.
     */
    @Test
    void listMemoryProposalsDescriptionStatesWhichCredentialSeesTheEntityRow() {
        String clause = "An entity row is listed for status ACTIVE or RETIRED to any credential; a PENDING "
                + "entity row, with its canonical key in factKey, is returned to an operator credential only.";

        assertThat(catalog.find("list_memory_proposals").orElseThrow().description())
                .as("catalog mirror")
                .contains(clause)
                .doesNotContain("a worker read receives facts and relations only");
        assertThat(registeredDescription("list_memory_proposals"))
                .as("registered @Tool annotation")
                .contains(clause)
                .doesNotContain("a worker read receives facts and relations only");
    }

    /**
     * T29 Item 6 (P3). {@code get_memory_entity} described only the not-found outcome, but the read
     * has a FORBIDDEN fallback for a PENDING row that reaches it without operator authority. Both
     * outcomes are stated, on both copies.
     */
    @Test
    void getMemoryEntityDescriptionStatesBothTheNotFoundAndTheForbiddenOutcome() {
        String clause = "Should a PENDING row still reach this read, an operator credential may see it "
                + "and any other credential is refused with FORBIDDEN.";

        assertThat(catalog.find("get_memory_entity").orElseThrow().description())
                .as("catalog mirror")
                .contains(clause);
        assertThat(registeredDescription("get_memory_entity"))
                .as("registered @Tool annotation")
                .contains(clause);
    }

    private static String registeredRememberFactDescription() {
        return registeredDescription("remember_fact");
    }

    private static String registeredDescription(String toolName) {
        for (Method method : MemoryTools.class.getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null && toolName.equals(annotation.name())) {
                return annotation.description();
            }
        }
        throw new AssertionError("MemoryTools has no @Tool '" + toolName + "'");
    }

    /**
     * The REST summary counts a parked (WAITING_INPUT) run as live work; this tool counts RUNNING
     * only. The descriptions must state that divergence instead of promising the two payloads are
     * the same.
     */
    @Test
    void theDashboardSummaryDescriptionsNameTheRunningRunsDivergence() {
        assertThat(catalog.find("get_dashboard_summary").orElseThrow().description())
                .contains("Same shape as GET /api/v1/dashboard/summary")
                .contains("runningRuns counts RUNNING only here")
                .contains("the REST summary also counts WAITING_INPUT");
    }

    /**
     * The registered tool description comes from the {@code @Tool} annotation and the catalog is a
     * hand-written second copy, so the two can drift. {@code McpEndpointIntegrationTest} catches a
     * drift at the failsafe tier; this pins the same byte equality for the tool classes whose
     * descriptions moved in this change, inside the unit tier.
     */
    @Test
    void theChangedToolDescriptionsAreByteEqualToTheirToolAnnotation() {
        assertAnnotationMatchesCatalog(DashboardTools.class);
        assertAnnotationMatchesCatalog(MemoryTools.class);
    }

    private void assertAnnotationMatchesCatalog(Class<?> toolClass) {
        int annotated = 0;
        for (Method method : toolClass.getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }
            annotated++;
            ToolDescriptor descriptor = catalog.find(annotation.name()).orElseThrow(
                    () -> new AssertionError("@Tool '" + annotation.name() + "' is not in the catalog"));
            assertThat(descriptor.description())
                    .as("catalog description of '%s' equals its @Tool annotation", annotation.name())
                    .isEqualTo(annotation.description());
        }
        assertThat(annotated)
                .as("annotated methods found on '%s'", toolClass.getSimpleName())
                .isGreaterThan(0);
    }

    /**
     * The catalog covers the registered MCP surface plus the cores' own
     * read-only web tools; only the registered names are the inventory.
     */
    @Test
    void onlyTheNativeReadOnlyToolsAreOutsideTheRegisteredNames() {
        assertThat(catalog.tools().stream()
                .map(ToolDescriptor::name)
                .filter(name -> !catalog.names().contains(name)))
                .containsExactlyInAnyOrder("WebSearch", "WebFetch");
        assertThat(catalog.names()).hasSize(McpToolInventory.EXPECTED_TOOL_NAMES.size());
    }
}
