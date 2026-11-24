package io.aria.conductor.aria.live;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.service.RunService;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two approved live writes (spec X15). {@code start_agent_run} is the engine's own
 * create path; {@code run_skill} resolves an enabled skill, persists an Aria run and
 * applies the exact chat directive as the client-policy system message. Both writes reach
 * the repository through the service's own transaction boundary; the test's mocked
 * transaction manager keeps the boundary observable without a database.
 */
@ExtendWith(MockitoExtension.class)
class LiveWriteServiceTest {

    private static final String SKILL_ID = "skill-1";

    @Mock
    private RunService runService;
    @Mock
    private RunRepository runRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentLoopEngine agentLoopEngine;
    @Mock
    private SkillContextProvider skillContextProvider;
    @Mock
    private PlatformTransactionManager transactionManager;

    private LiveWriteService service;

    @BeforeEach
    void setUp() {
        service = new LiveWriteService(runService, runRepository, agentRepository,
                agentLoopEngine, skillContextProvider, transactionManager);
    }

    @Test
    void startAgentRunGoesThroughTheEngineCreatePathWithTheExplicitCap() {
        UUID agentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING).build());

        RunResponse response = service.startAgentRun(agentId, "ship it", 3);

        assertThat(response.getId()).isEqualTo(runId);
        ArgumentCaptor<CreateRunRequest> request = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(request.capture());
        assertThat(request.getValue().getAgentId()).isEqualTo(agentId);
        assertThat(request.getValue().getPromptSeed()).isEqualTo("ship it");
        assertThat(request.getValue().getMaxIterations()).isEqualTo(3);
    }

    @Test
    void startAgentRunLeavesTheServiceDefaultWhenNoCapWasGiven() {
        UUID agentId = UUID.randomUUID();
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(agentId).status(RunStatus.PENDING).build());

        service.startAgentRun(agentId, "ship it", null);

        ArgumentCaptor<CreateRunRequest> request = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(request.capture());
        assertThat(request.getValue().getMaxIterations()).isEqualTo(50);
    }

    @Test
    void runSkillSavesTheAriaRunAndStartsItWithTheChatsSkillDirective() {
        UUID runId = UUID.randomUUID();
        when(skillContextProvider.getEnabledSkillsByIds(List.of(SKILL_ID))).thenReturn(List.of(
                new SkillContext(SKILL_ID, "Deploy", "how to deploy", "TEMPLATE BODY", "SKILL")));
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(
                Agent.builder().id(AriaConstants.ARIA_AGENT_ID).name("Aria").config("{}").build()));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            Run run = invocation.getArgument(0);
            run.setId(runId);
            return run;
        });
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(state(RunStatus.RUNNING)));

        RunResponse response = service.runSkill(SKILL_ID, "deploy it");

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentId()).isEqualTo(AriaConstants.ARIA_AGENT_ID);
        assertThat(saved.getValue().getPromptSeed()).isEqualTo("deploy it");
        assertThat(saved.getValue().getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(response.getId()).isEqualTo(runId);
        assertThat(response.getStatus()).isEqualTo(RunStatus.RUNNING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> context = ArgumentCaptor.forClass(List.class);
        verify(agentLoopEngine).startRun(org.mockito.ArgumentMatchers.eq(runId), context.capture());
        assertThat(context.getValue()).singleElement().satisfies(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).isEqualTo("## Active Skill: Deploy\nTEMPLATE BODY");
        });
    }

    @Test
    void runSkillWithoutAPromptSeedsTheRunWithTheSkillName() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of(SKILL_ID))).thenReturn(List.of(
                new SkillContext(SKILL_ID, "Deploy", null, "TEMPLATE", "SKILL")));
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.of(
                Agent.builder().id(AriaConstants.ARIA_AGENT_ID).config("{}").build()));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            Run run = invocation.getArgument(0);
            run.setId(UUID.randomUUID());
            return run;
        });

        service.runSkill(SKILL_ID, " ");

        ArgumentCaptor<Run> saved = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(saved.capture());
        assertThat(saved.getValue().getPromptSeed()).isEqualTo("Run the skill: Deploy");
    }

    @Test
    void anUnknownOrDisabledSkillStartsNothing() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of("missing"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.runSkill("missing", "go"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        verifyNoInteractions(agentRepository, runRepository, agentLoopEngine);
    }

    @Test
    void aMissingAriaAgentStartsNothing() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of(SKILL_ID))).thenReturn(List.of(
                new SkillContext(SKILL_ID, "Deploy", null, "TEMPLATE", "SKILL")));
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runSkill(SKILL_ID, "go"))
                .isInstanceOf(IllegalStateException.class);
        verify(runRepository, never()).save(any());
        verifyNoInteractions(agentLoopEngine);
    }

    private static RunRepository.RunStateView state(RunStatus status) {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return null;
            }
        };
    }
}
