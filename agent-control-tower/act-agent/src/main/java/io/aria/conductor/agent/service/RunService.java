package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.RunStartedEvent;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RunService {

    private static final Map<RunStatus, Set<RunStatus>> VALID_TRANSITIONS;

    static {
        Map<RunStatus, Set<RunStatus>> transitions = new EnumMap<>(RunStatus.class);
        transitions.put(RunStatus.PENDING, EnumSet.of(RunStatus.INITIALIZING, RunStatus.CANCELLED));
        transitions.put(RunStatus.INITIALIZING, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED));
        transitions.put(RunStatus.RUNNING, EnumSet.of(RunStatus.PAUSED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED));
        transitions.put(RunStatus.PAUSED, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED));
        transitions.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
        transitions.put(RunStatus.FAILED, EnumSet.noneOf(RunStatus.class));
        transitions.put(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private final RunRepository runRepository;
    private final AgentService agentService;
    private final ApplicationEventPublisher eventPublisher;

    public RunService(RunRepository runRepository,
                      AgentService agentService,
                      ApplicationEventPublisher eventPublisher) {
        this.runRepository = runRepository;
        this.agentService = agentService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RunResponse createRun(CreateRunRequest request) {
        Agent agent = agentService.findAgentOrThrow(request.getAgentId());

        if (agent.getHealthStatus() == HealthStatus.RETIRED) {
            throw new IllegalArgumentException("Cannot create run for retired agent: " + agent.getId());
        }
        if (agent.getHealthStatus() == HealthStatus.UNHEALTHY) {
            throw new IllegalArgumentException("Cannot create run for unhealthy agent: " + agent.getId());
        }

        log.info("Creating run for agent: agentId={}, prompt={}", request.getAgentId(),
                request.getPromptSeed().substring(0, Math.min(50, request.getPromptSeed().length())));

        Run run = Run.builder()
                .agentId(request.getAgentId())
                .promptSeed(request.getPromptSeed())
                .maxIterations(request.getMaxIterations() > 0 ? request.getMaxIterations() : 50)
                .status(RunStatus.PENDING)
                .build();

        Run saved = runRepository.save(run);
        log.info("Run created: id={}", saved.getId());

        eventPublisher.publishEvent(new RunStartedEvent(this, saved.getId(), saved.getAgentId()));

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRuns() {
        return runRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByAgent(UUID agentId) {
        return runRepository.findByAgentId(agentId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByStatus(RunStatus status) {
        return runRepository.findByStatus(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RunResponse> listRunsByAgentAndStatus(UUID agentId, RunStatus status) {
        return runRepository.findByAgentIdAndStatus(agentId, status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(UUID id) {
        Run run = findRunOrThrow(id);
        return toResponse(run);
    }

    @Transactional
    public RunResponse pauseRun(UUID id) {
        Run run = findRunOrThrow(id);
        validateTransition(run.getStatus(), RunStatus.PAUSED);
        log.info("Pausing run: id={}", id);
        run.setStatus(RunStatus.PAUSED);
        return toResponse(runRepository.save(run));
    }

    @Transactional
    public RunResponse resumeRun(UUID id) {
        return resumeRun(id, null);
    }

    @Transactional
    public RunResponse resumeRun(UUID id, String newInstruction) {
        Run run = findRunOrThrow(id);
        validateTransition(run.getStatus(), RunStatus.RUNNING);
        if (newInstruction != null && !newInstruction.isBlank()) {
            log.info("Updating run instruction on resume: id={}", id);
            run.setPromptSeed(newInstruction);
        }
        log.info("Resuming run: id={}", id);
        run.setStatus(RunStatus.RUNNING);
        return toResponse(runRepository.save(run));
    }

    @Transactional
    public RunResponse cancelRun(UUID id) {
        Run run = findRunOrThrow(id);
        validateTransition(run.getStatus(), RunStatus.CANCELLED);
        log.info("Cancelling run: id={}", id);
        run.setStatus(RunStatus.CANCELLED);
        run.setCompletedAt(Instant.now());
        Run saved = runRepository.save(run);

        eventPublisher.publishEvent(new RunCompletedEvent(this, saved.getId(), saved.getAgentId(), RunStatus.CANCELLED));

        return toResponse(saved);
    }

    @Transactional
    public RunResponse updateRunStatus(UUID id, RunStatus newStatus) {
        Run run = findRunOrThrow(id);
        validateTransition(run.getStatus(), newStatus);
        log.info("Updating run status: id={}, {} -> {}", id, run.getStatus(), newStatus);

        run.setStatus(newStatus);

        if (newStatus == RunStatus.COMPLETED || newStatus == RunStatus.FAILED || newStatus == RunStatus.CANCELLED) {
            run.setCompletedAt(Instant.now());
            eventPublisher.publishEvent(new RunCompletedEvent(this, run.getId(), run.getAgentId(), newStatus));
        }

        return toResponse(runRepository.save(run));
    }

    @Transactional(readOnly = true)
    public Map<RunStatus, Long> countByStatus() {
        Map<RunStatus, Long> counts = new EnumMap<>(RunStatus.class);
        for (RunStatus status : RunStatus.values()) {
            counts.put(status, runRepository.countByStatus(status));
        }
        return counts;
    }

    private Run findRunOrThrow(UUID id) {
        return runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Run", id));
    }

    private void validateTransition(RunStatus current, RunStatus target) {
        Set<RunStatus> allowed = VALID_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(RunStatus.class));
        if (!allowed.contains(target)) {
            throw new InvalidStateTransitionException("Run", current.name(), target.name());
        }
    }

    private RunResponse toResponse(Run run) {
        return RunResponse.builder()
                .id(run.getId())
                .agentId(run.getAgentId())
                .status(run.getStatus())
                .promptSeed(run.getPromptSeed())
                .maxIterations(run.getMaxIterations())
                .totalTokensUsed(run.getTotalTokensUsed())
                .iterationCount(run.getIterationCount())
                .errorMessage(run.getErrorMessage())
                .finalOutput(run.getFinalOutput())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
