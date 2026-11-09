package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.config.SystemConfigKeys;
import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.context.AssembledContext;
import io.aria.conductor.execution.context.ContextAssembler;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.CategoryReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.Exclusions;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingReceipt;
import io.aria.conductor.execution.housekeeping.HousekeepingModel.HousekeepingRequest;
import io.aria.conductor.execution.housekeeping.HousekeepingService;
import io.aria.conductor.memory.model.EntityStatus;
import io.aria.conductor.memory.model.FactStatus;
import io.aria.conductor.memory.model.MemoryEntity;
import io.aria.conductor.memory.model.MemoryFact;
import io.aria.conductor.memory.model.MemoryOrigin;
import io.aria.conductor.memory.repository.MemoryEntityRepository;
import io.aria.conductor.memory.repository.MemoryFactRepository;
import io.aria.conductor.memory.service.MemoryContextContributor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Lane I integration guard (plan "Lane I: Integration and architecture guard", spec §5 and
 * §8.5): the real application context must collect every context contributor — the memory
 * one included, as a Spring-scanned bean and not an absent optional — one chat turn must
 * write exactly one manifest row, an unseeded operator identity must still render its
 * documented defaults, and a run purge must take its manifests with it without touching
 * governed memory.
 *
 * <p>Named {@code *Test} on purpose: Surefire's tier excludes {@code *IntegrationTest}, so
 * this is the shape that the documented {@code mvn -pl act-app -am test} gate actually runs.
 * The profile is the repository's shared test H2 ({@code jdbc:h2:mem:act_test}, MODE=MySQL,
 * Flyway-migrated), the same database every other app-context test uses.
 */
class MemoryContextWiringTest extends BaseH2IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    List<ContextContributor> contributors;

    @Autowired
    ContextAssembler contextAssembler;

    @Autowired
    AgentLoopEngine agentLoopEngine;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    RunRepository runRepository;

    @Autowired
    ContextManifestRepository manifests;

    @Autowired
    HousekeepingService housekeepingService;

    @Autowired
    SystemConfigRepository systemConfigRepository;

    @Autowired
    MemoryEntityRepository memoryEntities;

    @Autowired
    MemoryFactRepository memoryFacts;

    @Test
    void theRealContextCollectsIdentityPolicyKnowledgeMemoryAndToolContributors() {
        Map<String, ContextContributor> byId = contributors.stream()
                .collect(Collectors.toMap(ContextContributor::id, Function.identity()));

        assertThat(byId)
                .as("the application context must inject every contributor, memory included")
                .containsKeys("identity", "policy.aria", "policy.agent", "knowledge", "memory", "tools");
        assertThat(byId.get("memory")).isInstanceOf(MemoryContextContributor.class);
        assertThat(context.getBeanNamesForType(MemoryContextContributor.class))
                .as("exactly one scanned memory contributor bean")
                .hasSize(1);
        assertThat(context.getBean(MemoryContextContributor.class))
                .as("the injected contributor is the real scanned bean, not a stand-in")
                .isSameAs(byId.get("memory"));
        assertThat(byId.get("identity").required()).as("identity is required for every agent").isTrue();
        assertThat(byId.get("policy.aria").required()).isTrue();
    }

    /**
     * One turn through the engine/assembler seam: the coordinated attempt assembles the
     * envelope once in {@code buildMessages} and records it before the core is launched.
     * The test environment has no sandbox endpoint, so the run reaches its terminal
     * refusal deterministically and the row can be read back.
     */
    @Test
    void oneChatTurnThroughTheEngineWritesExactlyOneManifestRow() {
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("lane-i-engine-agent").description("manifest wiring test")
                .agentType(AgentType.ADK).role("tester").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        Run run = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agent.getId()).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(50).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());

        agentLoopEngine.startRun(run.getId());

        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(found -> found.getStatus() == RunStatus.FAILED).orElse(false));

        List<ContextManifest> rows = manifests.findByRunIdOrderByTurnNumberAsc(run.getId());
        assertThat(rows).as("one manifest row per (run, turn)").hasSize(1);
        ContextManifest row = rows.get(0);
        assertThat(row.getRunId()).isEqualTo(run.getId());
        assertThat(row.getTurnNumber())
                .as("the prompt seed is trajectory turn 1, so the first assembly is turn 2")
                .isEqualTo(2);
        assertThat(row.getAdapter()).as("the non-streaming core task is a SYNC assembly").isEqualTo("SYNC");
        assertThat(row.getSectionsJson())
                .as("the envelope's required identity and the agent policy are recorded by id")
                .contains("\"id\":\"identity\"", "\"id\":\"policy.agent\"");
        assertThat(row.getSectionsJson())
                .as("the manifest stores provenance metadata, never the prompt text")
                .doesNotContain("do the work");
    }

    /**
     * A1's identity contributor reads {@code memory.operator.*}; none of the three keys is
     * seeded by a migration. The documented fallback (Operator / UTC / en) is the identity,
     * so no seeding is needed and no section goes missing.
     */
    @Test
    void identityRendersDocumentedDefaultsWhenOperatorKeysAreUnseeded() {
        assertThat(systemConfigRepository.findById(SystemConfigKeys.MEMORY_OPERATOR_NAME)).isEmpty();
        assertThat(systemConfigRepository.findById(SystemConfigKeys.MEMORY_OPERATOR_TIMEZONE)).isEmpty();
        assertThat(systemConfigRepository.findById(SystemConfigKeys.MEMORY_OPERATOR_LANGUAGE)).isEmpty();

        ContextRequest request = new ContextRequest(UUID.randomUUID(), null,
                AriaConstants.ARIA_AGENT_ID, ContextAdapter.CORE, 1, "hello", null,
                Sensitivity.INTERNAL, 24_000, null, null, List.of());
        AssembledContext assembled = contextAssembler.assemble(request, List.of());

        assertThat(assembled.systemText())
                .as("identity is required, so the fallback must render a real section")
                .contains("## Identity")
                .contains("name: Operator")
                .contains("timezone: UTC")
                .contains("language: en")
                .contains("## Policy");
    }

    /**
     * The V81 manifest is a run child with no database cascade. Housekeeping's run purge
     * must delete the manifest rows with the run; governed memory (entities, facts,
     * assertions) is never a housekeeping target.
     */
    @Test
    void purgingARunDeletesItsManifestsAndLeavesGovernedMemoryAlone() {
        Instant old = Instant.now().minus(30, ChronoUnit.HOURS);
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("lane-i-purge-agent").description("purge wiring test")
                .agentType(AgentType.ADK).role("tester").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        UUID agentId = agent.getId();
        Run stale = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.COMPLETED)
                .promptSeed("old turn").maxIterations(50).totalTokensUsed(0)
                .iterationCount(0).createdAt(old).updatedAt(old).build());
        manifests.saveAndFlush(ContextManifest.builder()
                .runId(stale.getId()).turnNumber(1).agentId(agentId)
                .adapter("CORE").sectionsJson("[]").droppedJson("[]").degradedJson("[]")
                .maxChars(24_000).estimatedTokens(0).createdAt(old).build());

        String key = "repo:lane-i-fixture-" + UUID.randomUUID();
        MemoryEntity entity = memoryEntities.saveAndFlush(MemoryEntity.builder()
                .kind("REPO").canonicalKey(key).displayName("lane-i fixture")
                .sensitivity(Sensitivity.INTERNAL).status(EntityStatus.ACTIVE)
                .origin(MemoryOrigin.OPERATOR).createdAt(Instant.now()).build());
        MemoryFact fact = memoryFacts.saveAndFlush(MemoryFact.builder()
                .entityId(entity.getId()).factKey("default_branch").valueText("main")
                .valueType("STRING").status(FactStatus.ACTIVE).origin(MemoryOrigin.OPERATOR)
                .sensitivity(Sensitivity.INTERNAL).validFrom(Instant.now())
                .createdAt(Instant.now()).build());

        HousekeepingReceipt receipt = housekeepingService.execute(
                new HousekeepingRequest(List.of("runs"), false, Exclusions.empty(), true));

        CategoryReceipt runs = receipt.categories().stream()
                .filter(category -> category.key().equals("runs")).findFirst().orElseThrow();
        assertThat(runs.cleared()).as("receipt=%s", receipt).isGreaterThanOrEqualTo(1);
        assertThat(runRepository.findById(stale.getId())).as("the run is purged").isEmpty();
        assertThat(manifests.findByRunIdOrderByTurnNumberAsc(stale.getId()))
                .as("the run's manifest rows go with it, so no orphan is left behind")
                .isEmpty();
        assertThat(memoryEntities.findById(entity.getId()))
                .as("governed memory is never a housekeeping target").isPresent();
        assertThat(memoryFacts.findById(fact.getId()))
                .as("governed memory is never a housekeeping target").isPresent();
    }
}
