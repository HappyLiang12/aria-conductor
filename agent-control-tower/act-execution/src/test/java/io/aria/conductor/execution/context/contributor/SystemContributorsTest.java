package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.SystemConfigRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.model.SystemConfig;
import io.aria.conductor.common.config.SystemConfigKeys;
import io.aria.conductor.common.tool.ToolCatalogProvider;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The system contributors each own one section of the envelope (spec §8.1): a
 * fixed order, a typed id, and content the manifest can cite. Optional inputs
 * yield no section instead of an empty heading.
 */
class SystemContributorsTest {

    private static ContextRequest request() {
        return request(UUID.randomUUID(), "ship it", null, null);
    }

    private static ContextRequest request(UUID agentId, String goal, String workspaceRef, String clientPolicy) {
        return new ContextRequest(UUID.randomUUID(), "c", agentId, ContextAdapter.SYNC, 1, goal,
                null, Sensitivity.INTERNAL, 24_000, workspaceRef, clientPolicy, List.of());
    }

    private static Agent agent(UUID id, String role, String config) {
        return Agent.builder()
                .id(id)
                .name("worker")
                .role(role)
                .agentType(AgentType.ADK)
                .config(config)
                .healthStatus(HealthStatus.HEALTHY)
                .build();
    }

    // ---- identity ----

    @Test
    void identityDefaultsWhenConfigIsEmpty() {
        SystemConfigRepository config = mock(SystemConfigRepository.class);
        when(config.findById(anyString())).thenReturn(Optional.empty());

        List<ContextSection> sections = new IdentityContextContributor(config).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("identity");
            assertThat(section.content()).contains("Operator", "UTC", "en");
            assertThat(section.sensitivity()).isEqualTo(Sensitivity.INTERNAL);
        });
    }

    @Test
    void identityRendersTheConfiguredOperatorInNameTimezoneLanguageOrder() {
        SystemConfigRepository config = mock(SystemConfigRepository.class);
        when(config.findById(SystemConfigKeys.MEMORY_OPERATOR_NAME))
                .thenReturn(Optional.of(config(SystemConfigKeys.MEMORY_OPERATOR_NAME, "Kim")));
        when(config.findById(SystemConfigKeys.MEMORY_OPERATOR_TIMEZONE))
                .thenReturn(Optional.of(config(SystemConfigKeys.MEMORY_OPERATOR_TIMEZONE, "Asia/Taipei")));
        when(config.findById(SystemConfigKeys.MEMORY_OPERATOR_LANGUAGE))
                .thenReturn(Optional.of(config(SystemConfigKeys.MEMORY_OPERATOR_LANGUAGE, "zh-TW")));

        List<ContextSection> sections = new IdentityContextContributor(config).contribute(request());

        assertThat(sections).singleElement().satisfies(section ->
                assertThat(section.content()).isEqualTo(
                        "name: Kim\ntimezone: Asia/Taipei\nlanguage: zh-TW"));
    }

    @Test
    void identityFallsBackToDefaultsWhenTheConfigReadFails() {
        SystemConfigRepository config = mock(SystemConfigRepository.class);
        when(config.findById(anyString())).thenThrow(new IllegalStateException("config unavailable"));

        List<ContextSection> sections = new IdentityContextContributor(config).contribute(request());

        assertThat(sections).singleElement().satisfies(section ->
                assertThat(section.content()).isEqualTo(
                        "name: Operator\ntimezone: UTC\nlanguage: en"));
    }

    // ---- policy ----

    @Test
    void agentPolicyIsEmptyForAria() {
        AgentRepository agents = mock(AgentRepository.class);

        List<ContextSection> sections = new AgentPolicyContextContributor(agents)
                .contribute(request(AriaConstants.ARIA_AGENT_ID, "hi", null, null));

        assertThat(sections).isEmpty();
    }

    @Test
    void agentPolicyUsesTheConfiguredPrompt() {
        AgentRepository agents = mock(AgentRepository.class);
        UUID agentId = UUID.randomUUID();
        when(agents.findById(agentId))
                .thenReturn(Optional.of(agent(agentId, "analyst", "{\"systemPrompt\":\"be a BA\"}")));

        List<ContextSection> sections = new AgentPolicyContextContributor(agents)
                .contribute(request(agentId, "goal", null, null));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("policy.agent");
            assertThat(section.content()).isEqualTo("be a BA");
        });
    }

    @Test
    void agentPolicyFallsBackToTheRole() {
        AgentRepository agents = mock(AgentRepository.class);
        UUID agentId = UUID.randomUUID();
        when(agents.findById(agentId)).thenReturn(Optional.of(agent(agentId, "analyst", null)));

        List<ContextSection> sections = new AgentPolicyContextContributor(agents)
                .contribute(request(agentId, "goal", null, null));

        assertThat(sections).singleElement()
                .satisfies(section -> assertThat(section.content()).isEqualTo("analyst"));
    }

    @Test
    void clientPolicyIsEmptyWhenBlank() {
        List<ContextSection> sections = new ClientPolicyContextContributor()
                .contribute(request(UUID.randomUUID(), "goal", null, ""));

        assertThat(sections).isEmpty();
    }

    @Test
    void clientPolicyRendersTheRequestText() {
        List<ContextSection> sections = new ClientPolicyContextContributor()
                .contribute(request(UUID.randomUUID(), "goal", null, "## Active Skill: TDD"));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("policy.client");
            assertThat(section.content()).isEqualTo("## Active Skill: TDD");
        });
    }

    // ---- goal ----

    @Test
    void goalRendersTheCurrentRequestOnce() {
        List<ContextSection> sections = new GoalContextContributor()
                .contribute(request(UUID.randomUUID(), "ship it", null, null));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("goal");
            assertThat(section.content()).isEqualTo("ship it");
        });
    }

    // ---- skills ----

    @Test
    void skillsRenderTheEnabledSkillTemplates() {
        AgentRepository agents = mock(AgentRepository.class);
        AgentSkillResolver resolver = mock(AgentSkillResolver.class);
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId, "dev", null);
        when(agents.findById(agentId)).thenReturn(Optional.of(agent));
        when(resolver.resolveForAgent(agent))
                .thenReturn(List.of(new SkillContext("s1", "tdd", "", "test first", "SKILL")));

        List<ContextSection> sections = new SkillsContextContributor(agents, resolver)
                .contribute(request(agentId, "goal", null, null));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("skills");
            assertThat(section.content()).contains("tdd", "test first");
        });
    }

    @Test
    void skillsAreEmptyWhenTheResolverReturnsNone() {
        AgentRepository agents = mock(AgentRepository.class);
        AgentSkillResolver resolver = mock(AgentSkillResolver.class);
        UUID agentId = UUID.randomUUID();
        Agent agent = agent(agentId, "dev", null);
        when(agents.findById(agentId)).thenReturn(Optional.of(agent));
        when(resolver.resolveForAgent(agent)).thenReturn(List.of());

        List<ContextSection> sections = new SkillsContextContributor(agents, resolver)
                .contribute(request(agentId, "goal", null, null));

        assertThat(sections).isEmpty();
    }

    // ---- state ----

    @Test
    void stateRendersLiveCounts() {
        RunRepository runs = mock(RunRepository.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);
        when(runs.countByStatus(RunStatus.RUNNING)).thenReturn(2L);
        when(approvals.countByStatus(ApprovalStatus.PENDING)).thenReturn(1L);

        List<ContextSection> sections = new StateContextContributor(runs, approvals).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("state");
            assertThat(section.content()).isEqualTo("running runs: 2\npending approvals: 1");
        });
    }

    // ---- workspace ----

    @Test
    void workspaceIsEmptyWithoutAReference() {
        List<ContextSection> sections = new WorkspaceContextContributor()
                .contribute(request(UUID.randomUUID(), "goal", null, null));

        assertThat(sections).isEmpty();
    }

    @Test
    void workspaceRendersTheReference() {
        List<ContextSection> sections = new WorkspaceContextContributor()
                .contribute(request(UUID.randomUUID(), "goal", "repo@main", null));

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.id()).isEqualTo("workspace");
            assertThat(section.content()).contains("repo@main");
        });
    }

    // ---- tools ----

    @Test
    void toolsExcludeOperatorOnlyEntries() {
        ToolCatalogProvider catalog = () -> List.of(
                new ToolDescriptor("get_run", "Read one run", ToolPolicy.WORKER_READ, true),
                new ToolDescriptor("decide_approval", "Operator only", ToolPolicy.OPERATOR_ONLY, false));

        List<ContextSection> sections = new ToolCatalogContextContributor(catalog).contribute(request());

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.content()).contains("get_run");
            assertThat(section.content()).contains("(WORKER_READ): Read one run");
            assertThat(section.content()).doesNotContain("decide_approval");
        });
    }

    @Test
    void anEmptyCatalogProducesNoToolsSection() {
        ToolCatalogProvider catalog = List::of;

        List<ContextSection> sections = new ToolCatalogContextContributor(catalog).contribute(request());

        assertThat(sections).isEmpty();
    }

    private static SystemConfig config(String key, String value) {
        return SystemConfig.builder().configKey(key).configValue(value).build();
    }
}
