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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The two approved live writes (spec X15: {@code start_agent_run}, {@code run_skill}).
 *
 * <p>Both reach this class only from {@link LiveToolExecutor#executeApproved}, which the
 * router calls after the operator's approval committed. That caller runs inside an
 * {@code AFTER_COMMIT} listener on the decision's thread, where the completed
 * transaction's resources are still bound: a write that joined it would never commit
 * (the same trap {@link LiveRunService#settle} documents). Every write here therefore
 * opens its own {@code REQUIRES_NEW} transaction through a {@link TransactionTemplate}
 * rather than a self-invoked annotated method, which the proxy would bypass.
 *
 * <p>{@code start_agent_run} is the engine's normal run path: {@link RunService#createRun}
 * persists the row and publishes {@code RunStartedEvent}, whose listener dispatches the
 * run after the commit. {@code run_skill} reuses the chat's skill activation: the enabled
 * SKILL-stage template becomes a system message and the run is started directly, exactly
 * as {@code AriaStreamService} does, because a run started through the event would lose
 * that per-turn context.
 *
 * <p>Nothing here logs a prompt or a skill template.
 */
@Slf4j
@Service
public class LiveWriteService {

    /** The Aria agent the skill turn runs as; the same id the chat surface uses. */
    private static final UUID ARIA_AGENT_ID = AriaConstants.ARIA_AGENT_ID;

    private final RunService runService;
    private final RunRepository runRepository;
    private final AgentRepository agentRepository;
    private final AgentLoopEngine agentLoopEngine;
    private final SkillContextProvider skillContextProvider;
    private final TransactionTemplate requiresNew;

    @Autowired
    public LiveWriteService(RunService runService,
                            RunRepository runRepository,
                            AgentRepository agentRepository,
                            AgentLoopEngine agentLoopEngine,
                            SkillContextProvider skillContextProvider,
                            PlatformTransactionManager transactionManager) {
        this.runService = runService;
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.agentLoopEngine = agentLoopEngine;
        this.skillContextProvider = skillContextProvider;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Starts a run for the requested agent through the engine's own create path (the
     * reviewed agent-health checks, the dispatch event, the audit trail).
     *
     * @param maxIterations an explicit cap, or null to use the service default
     */
    public RunResponse startAgentRun(UUID agentId, String prompt, Integer maxIterations) {
        CreateRunRequest.CreateRunRequestBuilder request = CreateRunRequest.builder()
                .agentId(agentId)
                .promptSeed(prompt);
        if (maxIterations != null) {
            request.maxIterations(maxIterations);
        }
        RunResponse created = requiresNew.execute(status -> runService.createRun(request.build()));
        log.debug("live approved run started: run={} agent={}", created.getId(), agentId);
        return created;
    }

    /**
     * Resolves the enabled skill, persists an Aria run whose first turn carries that
     * skill's template as the client-policy system message, and starts it. One approval,
     * one skill: this does not compose {@code assign_skill} with a generic run.
     *
     * @param prompt the operator's instruction, or null/blank to run the skill as-is
     */
    public RunResponse runSkill(String skillId, String prompt) {
        PreparedRun prepared = Objects.requireNonNull(
                requiresNew.execute(status -> prepareSkillRun(skillId, prompt)),
                "skill run transaction produced no row");
        // Outside the transaction: the engine reads the committed row, takes the admission
        // slot and only then launches the loop on its own thread.
        agentLoopEngine.startRun(prepared.runId(), List.of(LlmMessage.system(prepared.directive())));
        RunStatus status = runRepository.findStateById(prepared.runId())
                .map(RunRepository.RunStateView::getStatus)
                .orElse(RunStatus.PENDING);
        log.debug("live approved skill run started: run={} skill={} stage={}",
                prepared.runId(), skillId, prepared.stage());
        return RunResponse.builder()
                .id(prepared.runId())
                .agentId(ARIA_AGENT_ID)
                .status(status)
                .maxIterations(prepared.maxIterations())
                .build();
    }

    /** The run row and the system directive, carried out of the write transaction. */
    private PreparedRun prepareSkillRun(String skillId, String prompt) {
        SkillContext skill = skillContextProvider.getEnabledSkillsByIds(List.of(skillId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Skill " + skillId + " is unknown, disabled or not a SKILL"));
        // A SKILL row without a template has no instruction to inject. Interpolating the
        // null would put the literal text "null" into the model's system message and start
        // a run that cannot do the skill, so the approved write is refused instead.
        String template = skill.template();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("Skill " + skillId + " has no template");
        }
        Agent aria = agentRepository.findById(ARIA_AGENT_ID)
                .orElseThrow(() -> new IllegalStateException("Aria agent not found"));
        int maxIterations = AgentLoopEngine.parseMaxIterationsFromConfig(aria, 0);
        String seed = prompt == null || prompt.isBlank()
                ? "Run the skill: " + skill.name()
                : prompt;
        Run run = Run.builder()
                .agentId(ARIA_AGENT_ID)
                .promptSeed(seed)
                .maxIterations(maxIterations)
                .status(RunStatus.PENDING)
                .build();
        Run saved = runRepository.save(run);
        // The exact directive the chat surface injects for an active slash-command skill.
        String directive = "## Active Skill: " + skill.name() + "\n" + template;
        return new PreparedRun(saved.getId(), maxIterations, skill.stage(), directive);
    }

    private record PreparedRun(UUID runId, int maxIterations, String stage, String directive) {
    }
}
